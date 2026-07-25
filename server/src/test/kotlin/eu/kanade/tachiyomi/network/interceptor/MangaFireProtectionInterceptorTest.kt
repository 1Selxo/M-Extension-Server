package eu.kanade.tachiyomi.network.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MangaFireProtectionInterceptorTest {
    @Test
    fun `adds generated token to MangaFire API requests`() {
        val provider = FakeTokenProvider("generated-token")
        var seenRequest: Request? = null
        val client =
            clientWith(
                provider = provider,
                response = { request ->
                    seenRequest = request
                    response(request, 200, "{}")
                },
            )

        client
            .newCall(
                Request
                    .Builder()
                    .url("https://mangafire.to/api/titles?page=1&limit=50")
                    .build(),
            ).execute()
            .close()

        assertEquals("generated-token", seenRequest?.url?.queryParameter("vrf"))
        assertEquals("1", seenRequest?.url?.queryParameter("page"))
        assertEquals(1, provider.calls.size)
        assertFalse(provider.calls.single())
    }

    @Test
    fun `does not alter unrelated requests`() {
        val provider = FakeTokenProvider("unused")
        var seenRequest: Request? = null
        val client =
            clientWith(
                provider = provider,
                response = { request ->
                    seenRequest = request
                    response(request, 200, "{}")
                },
            )

        client
            .newCall(Request.Builder().url("https://example.com/api/titles?page=1").build())
            .execute()
            .close()

        assertEquals(null, seenRequest?.url?.queryParameter("vrf"))
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `refreshes site state and retries a rejected token`() {
        val provider = FakeTokenProvider("stale-token", "fresh-token")
        val attempts = AtomicInteger()
        val seenTokens = mutableListOf<String?>()
        val client =
            clientWith(
                provider = provider,
                response = { request ->
                    seenTokens += request.url.queryParameter("vrf")
                    if (attempts.getAndIncrement() == 0) {
                        response(request, 403, "{\"message\":\"Missing token.\"}")
                    } else {
                        response(request, 200, "{}")
                    }
                },
            )

        val result =
            client
                .newCall(Request.Builder().url("https://mangafire.to/api/titles?page=1").build())
                .execute()
        result.close()

        assertEquals(200, result.code)
        assertEquals(listOf<String?>("stale-token", "fresh-token"), seenTokens)
        assertEquals(listOf(false, true), provider.calls)
    }

    @Test
    fun `loads current protection script and caches generated token`() {
        val loadedUrls = mutableListOf<String>()
        var seenBootstrap: MangaFireBootstrap? = null
        val provider =
            MangaFireSiteTokenProvider(
                pageLoader = { url ->
                    loadedUrls += url.toString()
                    if (url.host == "mangafire.to") {
                        """
                        <script>window.__config = "current-config";</script>
                        <script>window.__build = "current-build";</script>
                        <link rel="modulepreload" href="https://cdn.example/build/polyfill-current.js">
                        """.trimIndent()
                    } else {
                        "current-protection-script"
                    }
                },
                sessionFactory = { bootstrap ->
                    seenBootstrap = bootstrap
                    object : MangaFireTokenSession {
                        override fun tokenFor(url: HttpUrl) = "current-token"

                        override fun close() = Unit
                    }
                },
            )
        val apiUrl = "https://mangafire.to/api/titles?page=1".toHttpUrl()

        val first = provider.tokenFor(apiUrl)
        val second = provider.tokenFor(apiUrl)

        assertEquals("current-token", first)
        assertEquals(first, second)
        assertEquals(
            listOf(
                "https://mangafire.to/",
                "https://cdn.example/build/polyfill-current.js",
            ),
            loadedUrls,
        )
        assertEquals("current-config", seenBootstrap?.config)
        assertEquals("current-build", seenBootstrap?.build)
        assertEquals("current-protection-script", seenBootstrap?.script)
    }

    private fun clientWith(
        provider: MangaFireTokenProvider,
        response: (Request) -> Response,
    ): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(MangaFireProtectionInterceptor(provider))
            .addInterceptor { chain -> response(chain.request()) }
            .build()

    private fun response(
        request: Request,
        code: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Forbidden")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private class FakeTokenProvider(
        private vararg val tokens: String,
    ) : MangaFireTokenProvider {
        val calls = mutableListOf<Boolean>()

        override fun tokenFor(
            url: HttpUrl,
            forceRefresh: Boolean,
        ): String {
            calls += forceRefresh
            return tokens[minOf(calls.lastIndex, tokens.lastIndex)]
        }
    }
}
