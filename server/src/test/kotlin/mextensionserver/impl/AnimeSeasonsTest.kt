package mextensionserver.impl

import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.runBlocking
import mextensionserver.model.toJAnime
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnimeSeasonsTest {
    @AfterTest
    fun clear() = MihonVideoProxy.clear()

    @Test
    fun `seasons survive transport with source number and background`() {
        val anime =
            SAnime
                .create()
                .apply {
                    url = "/series"
                    title = "Series"
                    fetch_type = FetchType.Seasons
                    season_number = 2.5
                    background_url = "https://images.test/background"
                }.toJAnime()
        assertEquals("Seasons", anime.fetch_type)
        assertEquals(2.5, anime.season_number)
        assertEquals("https://images.test/background", anime.background_url)
    }

    @Test
    fun `lib16 video constructor and copy retain extension resolution data`() {
        val video =
            Video(
                videoTitle = "720p",
                internalData = "session",
                preferred = true,
                resolution = 720,
                bitrate = 2000,
                initialized = false,
            )
        val resolved = video.copy(videoUrl = "https://media.test/file", initialized = true)
        assertEquals("720p", resolved.quality)
        assertEquals("session", resolved.internalData)
        assertTrue(resolved.preferred)
        assertEquals(720, resolved.resolution)
        val legacy = Video("page", "legacy", "stream")
        assertEquals("legacy", legacy.quality)
        assertEquals("page", legacy.videoPageUrl)
        val copyDefault =
            Video::class.java.declaredMethods.single {
                it.name == "copy\$default"
            }
        assertEquals(17, copyDefault.parameterCount)
    }

    @Test
    fun `hoster detection leaves old sources on the episode API`() =
        runBlocking {
            val legacy = TestSource()
            assertFalse(AnimeVideoResolver.hasHosters(legacy))
            assertEquals("legacy", AnimeVideoResolver.resolve(legacy, SEpisode.create()).single().quality)
            val modern =
                object : TestSource() {
                    override suspend fun getHosterList(episode: SEpisode) =
                        listOf(
                            Hoster(
                                videoList =
                                    listOf(
                                        Video(videoTitle = "modern", initialized = true),
                                    ),
                            ),
                        )
                }
            assertTrue(AnimeVideoResolver.hasHosters(modern))
            assertEquals("modern", AnimeVideoResolver.resolve(modern, SEpisode.create()).single().quality)
        }

    @Test
    fun `opening one deferred quality resolves once and keeps headers and ranges`() {
        var resolutions = 0
        val requested = mutableListOf<Request>()
        val source =
            object : TestSource() {
                override val client =
                    OkHttpClient
                        .Builder()
                        .addInterceptor { chain ->
                            requested.add(chain.request())
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(206)
                                .message("Partial")
                                .body("video".toResponseBody())
                                .build()
                        }.build()

                override suspend fun resolveVideo(video: Video): Video {
                    resolutions++
                    return video.copy(
                        videoUrl = "https://media.test/file.mp4",
                        headers = Headers.headersOf("X-Session", "test"),
                        initialized = true,
                    )
                }
            }
        MihonVideoProxy.configure(45678)
        val first = MihonVideoProxy.proxy(source, Video(videoTitle = "720p"), deferResolution = true)
        MihonVideoProxy.proxy(source, Video(videoTitle = "1080p"), deferResolution = true)
        assertEquals(0, resolutions)
        val token = first.videoUrl!!.substringAfter("/video/")
        repeat(2) { MihonVideoProxy.fetch(token, "bytes=0-4")!!.stream.close() }
        assertEquals(1, resolutions)
        assertTrue(requested.all { it.header("X-Session") == "test" && it.header("Range") == "bytes=0-4" })
    }

    private open class TestSource : AnimeHttpSource() {
        override val baseUrl = "https://source.test"
        override val name = "Test"
        override val lang = "en"
        override val supportsLatest = false

        override fun headersBuilder(): Headers.Builder = Headers.Builder()

        override val client = OkHttpClient()

        override suspend fun getVideoList(episode: SEpisode) = listOf(Video("page", "legacy", "stream"))

        override fun popularAnimeRequest(page: Int): Request = error("Unused")

        override fun popularAnimeParse(response: Response): AnimesPage = error("Unused")

        override fun searchAnimeRequest(
            page: Int,
            query: String,
            filters: AnimeFilterList,
        ): Request = error("Unused")

        override fun searchAnimeParse(response: Response): AnimesPage = error("Unused")

        override fun latestUpdatesRequest(page: Int): Request = error("Unused")

        override fun latestUpdatesParse(response: Response): AnimesPage = error("Unused")

        override fun animeDetailsParse(response: Response): SAnime = error("Unused")

        override fun episodeListParse(response: Response): List<SEpisode> = error("Unused")

        override fun episodeVideoParse(response: Response): SEpisode = error("Unused")

        override fun videoListParse(response: Response): List<Video> = error("Unused")

        override fun videoUrlParse(response: Response): String = error("Unused")
    }
}
