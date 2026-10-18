package mextensionserver.util

import java.net.URI
import java.net.URISyntaxException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shortcut in [urlWithoutDomain] must return exactly what Mihon's
 * `URI`-based implementation returns, for every input.
 */
class UrlWithoutDomainTest {
    // Verbatim copy of the implementation that HttpSource and AnimeHttpSource
    // used before, so a change to the production fallback is caught as well.
    private fun original(orig: String): String =
        try {
            val uri = URI(orig)
            var out = uri.path
            if (uri.query != null) {
                out += "?" + uri.query
            }
            if (uri.fragment != null) {
                out += "#" + uri.fragment
            }
            out
        } catch (e: URISyntaxException) {
            orig
        }

    private fun outcome(block: () -> String): String = runCatching(block).fold({ "ok:$it" }, { "throws:${it.javaClass.name}" })

    private fun assertSame(input: String) {
        assertEquals(outcome { original(input) }, outcome { urlWithoutDomain(input) }, "input: \"$input\"")
    }

    @Test
    fun `typical extension urls`() {
        listOf(
            "https://example.org/manga/one-piece",
            "https://example.org/chapter/12?page=3&lang=en",
            "https://example.org/chapter/12?page=3#top",
            "https://example.org",
            "https://example.org?x=1",
            "https://example.org#frag",
            "https://user:pw@example.org:8443/a/b;c=d/e@f:g",
            "http://127.0.0.1:9/chapter/300",
            "/manga/1",
            "/manga/1?x=y#z",
            "/",
            "/a?",
            "/a#",
            "/a?#",
            "https://example.org/a/../b/./c",
            "https://example.org/a%20b",
            "https://example.org/a b",
            "https://example.org/caf\u00e9",
            "https://example.org/a#b#c",
            "https://[::1]:8080/a",
            "https://[::1/a",
            "https://",
            "https:///a",
            "https://?x",
            "//example.org/a",
            "manga/1",
            "manga:1",
            "1http://example.org/a",
            "mailto:someone@example.org",
            "javascript:void(0)",
            "",
            "?",
            "#",
            "https://example.org/a|b",
            "https://example.org/a\\b",
            "https://ex ample.org/a",
            "https://example.org:abc/a",
            "https://999.999.999.999/a",
            "a+b.c-d://host/p",
            "HTTPS://EXAMPLE.ORG/P",
        ).forEach(::assertSame)
    }

    @Test
    fun `random inputs agree with URI`() {
        val prefixes =
            listOf(
                "",
                "/",
                "//",
                "https://",
                "http://",
                "HTTP://",
                "h:",
                "1a://",
                "a+b.c-d://",
                "https:///",
                "https://[::1]",
                "https://user@host:1",
                "mailto:",
                "?",
                "#",
                "a",
                "https://host",
            )
        val alphabet =
            "abcXYZ019-._~!$&'()*+,;=:@/?#%[] \"<>\\|^`{}\u00e9\u4e2d\u0000\t\n\ud83d\ude00\ud83d"
        val random = Random(20261018)
        var shortcuts = 0
        repeat(200_000) {
            val text =
                buildString {
                    append(prefixes[random.nextInt(prefixes.size)])
                    repeat(random.nextInt(0, 14)) {
                        // Mostly characters the shortcut accepts, to reach its branches.
                        val plain = random.nextInt(10) < 8
                        val pool = if (plain) "abcXYZ019-._~!$&'()*+,;=:@/?" else alphabet
                        append(pool[random.nextInt(pool.length)])
                    }
                }
            if (urlWithoutDomainShortcut(text) != null) shortcuts++
            assertSame(text)
        }
        assertTrue(shortcuts > 30_000, "shortcut taken for only $shortcuts inputs")
    }
}
