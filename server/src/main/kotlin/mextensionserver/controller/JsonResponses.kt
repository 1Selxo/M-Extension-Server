package mextensionserver.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

/**
 * JSON plumbing shared by the request handlers.
 *
 * Both members exist for speed only; their output is byte-for-byte what the
 * handlers produced before (see JsonResponsesTest).
 */
internal object JsonResponses {
    /**
     * One mapper for every request. A mapper is thread-safe once configured
     * and keeps its (de)serializer caches; building one per request repeated
     * Kotlin-reflection introspection of every type on every call, which is
     * very slow on the iOS interpreter.
     */
    val mapper: ObjectMapper = jacksonObjectMapper()

    /**
     * Same response as `NanoHTTPD.newFixedLengthResponse(status, mimeType, text)`
     * for a [mimeType] without a charset parameter.
     *
     * NanoHTTPD checks the whole text with a US-ASCII `CharsetEncoder` before
     * encoding it a second time. That check runs character by character
     * through a `CharBuffer` and dominated small responses under `-Xint`.
     * This encodes once and derives the same answer: the text is US-ASCII
     * when its UTF-8 form has one byte per character and no character was
     * replaced (an unpaired surrogate encodes to `?`). As in NanoHTTPD, a
     * non-ASCII text is sent as UTF-8 and its content type gains
     * `; charset=UTF-8`.
     */
    fun fixedLength(
        status: NanoHTTPD.Response.IStatus,
        mimeType: String,
        text: String,
    ): NanoHTTPD.Response {
        require(!mimeType.contains("charset", ignoreCase = true)) { "mimeType must not carry a charset" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val contentType = if (isAscii(text, bytes)) mimeType else "$mimeType; charset=UTF-8"
        return NanoHTTPD.newFixedLengthResponse(status, contentType, ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    internal fun isAscii(
        text: String,
        utf8: ByteArray,
    ): Boolean {
        if (utf8.size != text.length) return false
        // One byte per character: every character is ASCII except ones the
        // encoder replaced with '?'.
        for (index in utf8.indices) {
            val byte = utf8[index]
            if (byte < 0) return false
            if (byte == QUESTION_MARK && text[index] != '?') return false
        }
        return true
    }

    private const val QUESTION_MARK = '?'.code.toByte()
}
