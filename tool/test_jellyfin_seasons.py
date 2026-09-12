"""Exercise a released Jellyfin APK against a local fixture, without an account.

Usage: python tool/test_jellyfin_seasons.py --java JAVA --jar JAR --apk APK
The APK is supplied externally; no third-party binaries or credentials are saved
in the repository. Server logs are retained in a temporary directory on failure.
"""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def main():
    args = argparse.ArgumentParser()
    for name in ("java", "jar", "apk"):
        args.add_argument(f"--{name}", required=True)
    args.add_argument("--allow-known-transcode-bug", action="store_true",
                      help="Accept only Jellyfin 16.30 known TranscodingInfo JSON failure")
    args = args.parse_args()
    apk = Path(args.apk).read_bytes()
    print("APK SHA-256:", hashlib.sha256(apk).hexdigest(), flush=True)
    requests = []
    playback_requests = []
    media = {"Id": "media", "SupportsTranscoding": True,
             "SupportsDirectStream": True, "Bitrate": 1000000,
             "MediaStreams": [{"Index": 0, "Type": "Video", "BitRate": 1000000,
                               "SupportsExternalStream": False, "IsExternal": False}]}

    def item(kind, id, name, **fields):
        return {"Type": kind, "Id": id, "Name": name, "LocationType": "FileSystem",
                "ImageTags": {}, **fields}

    series = item("Series", "series", "Fixture series")
    season = item("Season", "season", "Season 1", SeriesId="series", IndexNumber=1)
    episode = item("Episode", "episode", "Episode 1", SeriesId="series", IndexNumber=1,
                   Overview="Fixture synopsis", MediaSources=[media])

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            path = urlsplit(self.path).path
            requests.append((self.command, self.path, dict(self.headers)))
            if path.endswith("/Seasons"):
                data = {"Items": [season], "TotalRecordCount": 1}
            elif path.endswith("/Episodes"):
                data = {"Items": [episode], "TotalRecordCount": 1}
            elif path.endswith("/series"):
                data = series
            elif path.endswith("/season"):
                data = season
            elif path.endswith("/episode"):
                data = episode
            elif path.endswith(".m3u8"):
                return self.respond(b"#EXTM3U\n#EXTINF:1,\nsegment.ts\n", "application/vnd.apple.mpegurl")
            elif path.endswith("segment.ts") or path.endswith("/stream"):
                return self.respond(b"fixture video", "video/mp2t")
            else:
                data = {"Items": [], "TotalRecordCount": 0}
            self.respond(json.dumps(data).encode(), "application/json")

        def do_POST(self):
            data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            playback_requests.append(data)
            self.respond(json.dumps({"MediaSources": [{**media, "TranscodingUrl": "/Videos/episode/master.m3u8"}],
                                     "PlaySessionId": "fixture-session"}).encode(), "application/json")

        def respond(self, body, content_type):
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    fixture = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
    threading.Thread(target=fixture.serve_forever, daemon=True).start()
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    directory = Path(tempfile.mkdtemp(prefix="jellyfin-seasons-test-"))
    log = (directory / "server.log").open("w", encoding="utf-8")
    process = subprocess.Popen([args.java, f"-Dsuwayomi.tachidesk.config.server.rootDir={directory / 'data'}",
                                "-jar", args.jar, str(port), str(directory / "data")],
                               stdout=log, stderr=subprocess.STDOUT,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    try:
        bridge = f"http://127.0.0.1:{port}"
        for _ in range(300):
            try:
                with urlopen(bridge + "/capabilities", timeout=1) as response:
                    capabilities = json.load(response)
                break
            except URLError:
                if process.poll() is not None:
                    raise RuntimeError(f"Bridge exited; see {directory}")
                time.sleep(0.1)
        else:
            raise RuntimeError(f"Bridge did not start; see {directory}")
        assert capabilities["animeSeasons"] and capabilities["animeHosters"]
        base = f"http://127.0.0.1:{fixture.server_port}"
        preferences = [{"key": key, "editTextPreference": {"value": value}}
                       for key, value in {"host_url": base, "api_key": "fixture-token", "user_id": "fixture-user"}.items()]
        preferences.append({"key": "preferred_meta_type", "switchPreferenceCompat": {"value": False}})
        handle = None

        def call(method, **fields):
            nonlocal handle
            data = {"method": method, "preferences": preferences, **fields}
            data.update({"extensionId": handle} if handle else {"data": base64.b64encode(apk).decode()})
            request = Request(bridge + "/dalvik", data=json.dumps(data).encode(), headers={"Content-Type": "application/json"})
            with urlopen(request, timeout=120) as response:
                handle = response.headers.get("X-Mangatan-Extension-Id", handle)
                result = json.load(response)
            if isinstance(result, dict) and "error" in result:
                raise RuntimeError(result)
            return result

        info = call("extensionInfo")
        assert info["versionName"] == "16.30", info
        parent = call("getDetailsAnime", animeData={"url": base + "/Users/fixture-user/Items/series#series"})
        assert parent["fetch_type"] == "Seasons", parent
        seasons = call("getSeasonList", animeData=parent)
        assert len(seasons) == 1 and seasons[0]["season_number"] == 1, seasons
        episodes = call("getEpisodeList", animeData=seasons[0])
        assert len(episodes) == 1 and episodes[0]["summary"] == "Fixture synopsis", episodes
        videos = call("getVideoList", episodeData=episodes[0])
        assert len(videos) > 1, videos
        initial = len(playback_requests)
        # Only source-session negotiation has occurred, not every transcode.
        assert initial == 1, playback_requests
        direct = next(v for v in videos if v["quality"].startswith("Source"))
        with urlopen(direct["videoUrl"], timeout=30) as response:
            assert b"fixture video" in response.read()
        transcode = next(v for v in reversed(videos) if not v["quality"].startswith("Source"))
        try:
            with urlopen(transcode["videoUrl"], timeout=30) as response:
                assert b"#EXTM3U" in response.read()
        except HTTPError as error:
            message = error.read().decode()
            if not (args.allow_known_transcode_bug and error.code == 500
                    and "TranscodingInfo" in message and "videoBitrate" in message
                    and "missing" in message):
                raise RuntimeError(message) from error
            assert len(playback_requests) == initial
            print("KNOWN UPSTREAM FAILURE: Jellyfin 16.30 TranscodingInfo JSON mismatch", flush=True)
        else:
            assert len(playback_requests) == initial + 1
            with urlopen(transcode["videoUrl"], timeout=30) as response:
                response.read()
            assert len(playback_requests) == initial + 1
            print("PASS: deferred transcode and cached resolution", flush=True)
        assert any("seasonId=season" in url for _, url, _ in requests)
        print("PASS: released APK details, seasons, episodes, hosters and direct playback", flush=True)
    finally:
        process.terminate()
        process.wait(timeout=20)
        fixture.shutdown()
        log.close()
        print("Test logs:", directory, flush=True)


if __name__ == "__main__":
    main()
