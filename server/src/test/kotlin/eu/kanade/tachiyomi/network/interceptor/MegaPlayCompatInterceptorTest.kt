package eu.kanade.tachiyomi.network.interceptor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MegaPlayCompatInterceptorTest {
    @Test
    fun `decodes observed episode 347 HD-1 response`() {
        val token =
            "wdeBruh3qqn_i5wUNnyaPW3GxFWAz0PzUtHz-gGMUfU13l4KCpNpXaRBh4DFe8C0qMx0r2JkHKBc8vsCsGj2KhSExK00CFZa" +
                "evGHy4ijrZcGTveVKDzCQEc8xIYN5KS54cBaqlfvWU20htiihKPqEO3tz2_Qz-tU7J7JFSsgurY"
        val json = jacksonObjectMapper().readTree(MegaPlayCompatInterceptor.adaptSources("""{"enc":"$token"}"""))
        assertEquals(
            "https://megap.akirax.buzz/f899139df5e1059396431415e770c6dd/298c2f0ab0db1a8aa56bf6135cafa0bf/master.m3u8",
            json["sources"]["file"].asText(),
        )
    }

    private fun encode(text: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec("i?LMTAx0Q6,:}50U".toByteArray().copyOf(32), "AES"),
            IvParameterSpec("W0;27ToaUpl_P%'c".toByteArray()),
        )
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(text.toByteArray()))
    }

    @Test
    fun `encrypted response gains sources without losing subtitles or markers`() {
        val token = encode("""{"file":"https://media.example/master.m3u8"}""")
        val result = MegaPlayCompatInterceptor.adaptSources("""{"enc":"$token","tracks":[{"label":"English"}],"intro":{"end":171}}""")
        val json = jacksonObjectMapper().readTree(result)
        assertEquals("https://media.example/master.m3u8", json["sources"]["file"].asText())
        assertEquals("English", json["tracks"][0]["label"].asText())
        assertEquals(171, json["intro"]["end"].asInt())
    }

    @Test
    fun `plaintext responses are unchanged`() {
        val text = """{"sources":"https://media.example/video.m3u8","enc":"invalid"}"""
        assertEquals(text, MegaPlayCompatInterceptor.adaptSources(text))
        assertEquals("{}", MegaPlayCompatInterceptor.adaptSources("{}"))
    }

    @Test
    fun `invalid ciphertext and non URL payload fail explicitly`() {
        assertFails { MegaPlayCompatInterceptor.adaptSources("""{"enc":"invalid"}""") }
        val token = encode("""{"file":"file:///private/data"}""")
        assertFails { MegaPlayCompatInterceptor.adaptSources("""{"enc":"$token"}""") }
    }

    @Test
    fun `playlist handles absolute relative and quoted tokens preserving ordinary lines`() {
        val url = "https://media.example/one.ts?x=1&y=2"
        val token = encode(url)
        val text =
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"/segment/$token\"\n" +
                "#EXTINF:5,\nhttps://proxy.example/segment/$token\n/segment/$token\nplain.ts\n"
        assertEquals(
            "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"$url\"\n#EXTINF:5,\n$url\n$url\nplain.ts\n",
            MegaPlayCompatInterceptor.adaptPlaylist(text),
        )
        assertEquals("not a playlist", MegaPlayCompatInterceptor.adaptPlaylist("not a playlist"))
    }

    @Test
    fun `server selector propagates only for matching host and does not override explicit selection`() {
        val request =
            Request
                .Builder()
                .url("https://megaplay.buzz/stream/getSources?id=1&id=1")
                .header("Referer", "https://megaplay.buzz/stream/s-2/1/sub?s=tcdn")
                .build()
        val result = MegaPlayCompatInterceptor.adaptRequest(request)
        assertEquals("tcdn", result.url.queryParameter("s"))
        assertEquals(listOf("1", "1"), result.url.queryParameterValues("id"))
        val explicit = request.newBuilder().url("https://megaplay.buzz/stream/getSources?s=bcdn").build()
        assertSame(explicit, MegaPlayCompatInterceptor.adaptRequest(explicit))
        val unrelated = request.newBuilder().url("https://example.com/stream/getSources").build()
        assertSame(unrelated, MegaPlayCompatInterceptor.adaptRequest(unrelated))
    }

    @Test
    fun `interceptor adapts metadata but leaves unrelated responses untouched`() {
        val body = """{"enc":"${encode("""{"file":"https://media.example/master.m3u8"}""")}"}"""
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor(MegaPlayCompatInterceptor())
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Length", body.length.toString())
                        .body(body.toResponseBody())
                        .build()
                }.build()
        client.newCall(Request.Builder().url("https://megaplay.buzz/stream/getSources").build()).execute().use {
            assertTrue(it.body!!.string().contains("sources"))
            assertEquals(null, it.header("Content-Length"))
        }
        client.newCall(Request.Builder().url("https://other.example/stream/getSources").build()).execute().use {
            assertEquals(body, it.body!!.string())
        }
    }
}
