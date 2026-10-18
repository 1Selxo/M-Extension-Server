package mextensionserver.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import fi.iki.elonen.NanoHTTPD
import mextensionserver.model.DataBody
import mextensionserver.model.JChapter
import mextensionserver.model.JManga
import mextensionserver.model.JPage
import mextensionserver.model.MangaResponse
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Equivalence tests for the JSON fast paths: they must produce exactly the
 * bytes the handlers produced with NanoHTTPD's string response and a fresh
 * mapper per request.
 */
class JsonResponsesTest {
    private val samples =
        listOf(
            "",
            "{}",
            """{"mangas":[{"url":"/manga/1?x=y","title":"A & B"}],"hasNextPage":true}""",
            "question marks ??? and ? inside",
            "Latin-1 café ÿ",
            "CJK 中文日本語",
            "emoji 😀 pair",
            "lone high \ud83d here",
            "lone low \ude00 here",
            "only lone surrogates \ud800\udbff",
            "?\ud800?",
            "control \u0000\u0001\u007f",
            "x".repeat(70_000) + "é",
        )

    @Test
    fun `fixed-length JSON responses match NanoHTTPD on the wire`() {
        val statuses =
            listOf(
                NanoHTTPD.Response.Status.OK,
                NanoHTTPD.Response.Status.CONFLICT,
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
            )
        for (status in statuses) {
            for (text in samples) {
                val expected = NanoHTTPD.newFixedLengthResponse(status, "application/json", text)
                val actual = JsonResponses.fixedLength(status, "application/json", text)
                assertEquals(expected.mimeType, actual.mimeType, "content type for ${text.take(40)}")
                assertEquals(expected.status, actual.status)
                for (gzip in listOf(false, true)) {
                    assertEquals(
                        wire(expected, gzip),
                        wire(actual, gzip),
                        "wire bytes for ${text.take(40)} (gzip=$gzip)",
                    )
                }
            }
        }
    }

    @Test
    fun `ascii detection matches the US-ASCII encoder`() {
        val encoder = Charsets.US_ASCII.newEncoder()
        val texts = samples + (0..0xFFFF).map { it.toChar().toString() }
        for (text in texts) {
            assertEquals(
                encoder.canEncode(text),
                JsonResponses.isAscii(text, text.toByteArray(Charsets.UTF_8)),
                "for U+%04X".format(text.firstOrNull()?.code ?: 0),
            )
        }
    }

    @Test
    fun `shared mapper writes and reads exactly like a fresh mapper`() {
        val values: List<Any?> =
            listOf(
                MangaResponse(
                    mangas =
                        listOf(
                            JManga("/m/1", "Title é", null, "Author", "Line\nbreak \"quoted\"", "A, B", 1, "http://x/y.jpg", true),
                        ),
                    hasNextPage = false,
                ),
                listOf(JChapter("/c/1", "Chapter 1", 1_700_000_000_000, 1.5f, null)),
                listOf(JPage(0, "", "http://127.0.0.1:1/image/token")),
                FilterList(
                    Filter.Header("Header"),
                    object : Filter.Select<String>("Status", arrayOf("Any", "Ongoing"), 1) {},
                    object : Filter.CheckBox("Adult", true) {},
                    object : Filter.TriState("Genre", 2) {},
                    object : Filter.Text("Author", "x") {},
                    object : Filter.Sort("Order", arrayOf("A", "B"), Selection(1, true)) {},
                ),
                listOf(mapOf("key" to "k", "listPreference" to mapOf("title" to "T", "entries" to arrayOf("a", "b"), "valueIndex" to 1))),
                mapOf("error" to "Extension handle x is no longer loaded; resend the APK", "code" to 409),
                listOf("Toast ✓"),
                true,
                "plain string",
                null,
            )
        // Twice, so the second pass runs against warm shared caches.
        repeat(2) {
            for (value in values) {
                assertEquals(jacksonObjectMapper().writeValueAsString(value), JsonResponses.mapper.writeValueAsString(value))
            }
        }

        val request =
            """
            {"method":"getSearchManga","page":2,"search":"hero","extensionId":"abc",
             "filterList":[{"name":"Status","type":"SelectFilter","stateInt":1},
               {"name":"Genres","type":"GroupFilter","stateList":[{"name":"Action","type":"TriState","stateInt":1}]},
               {"name":"Order","type":"SortFilter","stateSort":{"index":2,"ascending":true}}],
             "mangaData":{"url":"/m/1","title":null,"artist":null,"author":null,"description":null,
               "genre":null,"status":1,"thumbnail_url":null,"initialized":false},
             "preferences":[{"key":"pref","listPreference":{"valueIndex":1,"entryValues":["a","b"]}},
               {"key":"__mangatan_bridge_context__","sourceId":"123"}]}
            """.trimIndent()
        repeat(2) {
            assertEquals(
                jacksonObjectMapper().readValue(request, DataBody::class.java),
                JsonResponses.mapper.readValue(request, DataBody::class.java),
            )
        }
    }

    private fun wire(
        response: NanoHTTPD.Response,
        gzip: Boolean,
    ): String {
        response.setGzipEncoding(gzip)
        val output = ByteArrayOutputStream()
        // Response.send is protected; it writes the status line, headers and body.
        NanoHTTPD.Response::class.java
            .getDeclaredMethod("send", java.io.OutputStream::class.java)
            .apply { isAccessible = true }
            .invoke(response, output)
        // The Date header is the only time-dependent line.
        return output
            .toByteArray()
            .toString(Charsets.ISO_8859_1)
            .replace(Regex("(?m)^Date: .*\r\n"), "")
    }
}
