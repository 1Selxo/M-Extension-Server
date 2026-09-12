# Anikku extension-lib 16 compatibility

Reference: [Anikku v0.2.0](https://github.com/komikku-app/anikku/releases/tag/v0.2.0)
and [Jellyfin season conversion](https://github.com/yuzono/anime-extensions/commit/e2616cdeebd090031aeb2c503a133ebf43a509e8).

`/capabilities` advertises `animeSeasons` and `animeHosters`. `getSeasonList`
accepts `animeData` and returns anime rows with `fetch_type` (`Seasons` or
`Episodes`), `season_number`, and `background_url`. Episode request/response
rows carry filler, summary and preview metadata. Older episode APIs remain.

Hoster detection follows Anikku's class-hierarchy implementation check. Hoster
and video sorting remain extension-owned; preferred videos come first. A failed
hoster does not discard working alternatives. The bridge returns its ordinary
video JSON to Flutter. Uninitialized videos receive opaque loopback URLs;
opening one invokes `resolveVideo`, caches that resolution and retains the
extension's headers, range requests and existing HLS rewriting. It does not
start a transcode for every offered quality. Android player commands and
extension timestamps are ABI-compatible data, not executable Flutter settings.

## Verification

With JDK 21, run `./gradlew :server:test :server:shadowJar`. The Kotlin tests
cover old APIs, the new constructor/copy ABI, metadata and deferred resolution
with headers and ranges. Build the embedded variant separately with
`-PiosRuntime=true`; both variants use the same output filename.

The released Jellyfin 16.30 APK can be tested without a Jellyfin account:

```sh
python tool/test_jellyfin_seasons.py --java /path/to/java \
  --jar /path/to/desktop.jar --apk /path/to/jellyfin-16.30.apk \
  --allow-known-transcode-bug
```

APK URL: https://raw.githubusercontent.com/yuzono/anime-repo/repo/apk/aniyomi-all.jellyfin-v16.30.apk

SHA-256: `1aee6a1526129cb9d68bca70e6464c040b59e9897bd48dada11634f33556f8b3`.

The fixture verifies series details, seasons, episode summaries, hosters and
direct playback. The test defaults to requiring transcoding too. Version 16.30
has a separate source bug: `TranscodingInfo.toJsonString()` uses the injected
JSON instance, while `resolveVideo` decodes it using Jellyfin's PascalCase
strategy. Anikku's injected JSON uses the same ordinary field naming as this
bridge. The opt-in flag accepts only that precise observed error and prints it
separately; other failures still fail the test. No source-specific JSON rewrite
is installed in the bridge. The synthetic deferred-resolution test checks the
successful transcode contract independently.

[Extension JSON helpers](https://github.com/yuzono/anime-extensions/blob/master/core/src/main/kotlin/keiyoushi/utils/Json.kt)
and [Jellyfin implementation](https://github.com/yuzono/anime-extensions/blob/master/src/all/jellyfin/src/eu/kanade/tachiyomi/animeextension/all/jellyfin/Jellyfin.kt)
show the differing serialization calls.

Branch workflow runs upload an iOS JAR artifact without publishing a release.
After merge, the runtime workflow uses a new immutable `ios-runtime-v10` tag.
A physical iPhone remains necessary to validate signing and the embedded VM.
