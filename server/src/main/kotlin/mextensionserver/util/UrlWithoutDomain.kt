package mextensionserver.util

import java.net.URI
import java.net.URISyntaxException

/**
 * Mihon's `getUrlWithoutDomain`: the path, query and fragment of [orig] as
 * `java.net.URI` reports them, or [orig] itself if it is not a valid URI.
 *
 * Extensions call it for every manga, chapter, anime and episode they
 * parse. Building a `URI` dominates that call on the iOS interpreter, so
 * common, unambiguous inputs take a shortcut that returns exactly what the
 * `URI` path returns (see UrlWithoutDomainTest); everything else still goes
 * through `URI`.
 */
internal fun urlWithoutDomain(orig: String): String = urlWithoutDomainShortcut(orig) ?: urlWithoutDomainByUri(orig)

/** The original implementation, kept for every input the shortcut declines. */
internal fun urlWithoutDomainByUri(orig: String): String =
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

/**
 * Handles `/path...` and `scheme://authority/path...` when every character is
 * one that `URI` accepts unescaped everywhere (so decoding changes nothing)
 * and there is at most one `#`. For those inputs `URI` cannot fail, and its
 * path, query and fragment, joined as above, are exactly the text after the
 * authority. Returns null for anything else.
 */
internal fun urlWithoutDomainShortcut(orig: String): String? {
    val length = orig.length
    var hashes = 0
    for (index in 0 until length) {
        val char = orig[index]
        if (char == '#') {
            hashes++
        } else if (char.code >= 128 || !PLAIN[char.code]) {
            return null
        }
    }
    if (hashes > 1 || length == 0) return null

    if (orig[0] == '/') {
        // A relative reference; "//" would start an authority instead.
        return if (length == 1 || orig[1] != '/') orig else null
    }

    // scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ), then "://".
    if (!orig[0].isAsciiLetter()) return null
    var index = 1
    while (index < length) {
        val char = orig[index]
        if (char.isAsciiLetter() || char in '0'..'9' || char == '+' || char == '-' || char == '.') {
            index++
        } else {
            break
        }
    }
    if (!orig.startsWith("://", index)) return null
    val authorityStart = index + 3
    var authorityEnd = authorityStart
    while (authorityEnd < length) {
        val char = orig[authorityEnd]
        if (char == '/' || char == '?' || char == '#') break
        authorityEnd++
    }
    // An empty authority has special cases in URI; leave it to URI.
    if (authorityEnd == authorityStart) return null
    return orig.substring(authorityEnd)
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

/**
 * Characters that `URI` accepts unescaped in a registry authority, a path, a
 * query and a fragment alike, plus the delimiters `/` and `?`. Excludes `%`
 * (escapes are decoded), `[` and `]` (IPv6 literals) and everything illegal.
 */
private val PLAIN =
    BooleanArray(128).also { table ->
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~!$&'()*+,;=:@/?"
        for (char in chars) table[char.code] = true
    }
