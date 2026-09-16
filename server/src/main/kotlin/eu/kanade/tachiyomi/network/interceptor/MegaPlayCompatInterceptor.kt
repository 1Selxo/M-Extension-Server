package eu.kanade.tachiyomi.network.interceptor

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Adapts MegaPlay's public newclient.min.js v4.7 wire format for older APKs.
 * Does not alter extension request signing, preferences, or unrelated hosts.
 */
internal class MegaPlayCompatInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(adaptRequest(chain.request()))
        val request = response.request
        val api = request.url.host == HOST && request.url.encodedPath in API_PATHS
        val playlist =
            request.header("Referer")?.toHttpUrlOrNull()?.host == HOST &&
                request.url.encodedPath.endsWith(".m3u8", ignoreCase = true)
        if (!response.isSuccessful || (!api && !playlist)) return response
        val body = response.body ?: return response
        // Bound buffering to small metadata documents, never video bodies.
        val peek = response.peekBody(MAX_BYTES + 1).bytes()
        if (peek.size > MAX_BYTES) return response
        val original = peek.toString(Charsets.UTF_8)
        val adapted =
            try {
                if (api) adaptSources(original) else adaptPlaylist(original)
            } catch (e: Exception) {
                response.close()
                throw IOException("MegaPlay stream metadata could not be decoded", e)
            }
        if (adapted == original) return response
        val contentType = body.contentType()
        body.close()
        return response
            .newBuilder()
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .body(adapted.toResponseBody(contentType))
            .build()
    }

    companion object {
        private const val HOST = "megaplay.buzz"
        private const val MAX_BYTES = 2L * 1024 * 1024
        private val API_PATHS = setOf("/stream/getSources", "/stream/getSourcesNew")
        private val mapper = jacksonObjectMapper()
        private val segment = Regex("""(?:https?://[^\s"<>]+)?/segment/([A-Za-z0-9_-]+)""")

        internal fun adaptRequest(request: Request): Request {
            if (request.url.host != HOST ||
                request.url.encodedPath !in API_PATHS ||
                request.url.queryParameter("s") != null
            ) {
                return request
            }
            val referer = request.header("Referer")?.toHttpUrlOrNull() ?: return request
            if (referer.host != HOST) return request
            val server =
                referer.queryParameter("s")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
                    ?: return request
            // Match GetSourcesRewrite in the public player: HD-1's ?s=tcdn
            // must survive the extension's embed-page -> API transition.
            return request
                .newBuilder()
                .url(
                    request.url
                        .newBuilder()
                        .addQueryParameter("s", server)
                        .build(),
                ).build()
        }

        internal fun adaptSources(text: String): String {
            val root = mapper.readTree(text) as? ObjectNode ?: return text
            if (root.hasNonNull("sources") || !root.path("enc").isTextual) return text
            val sources = mapper.readTree(decode(root.path("enc").asText()))
            require(sources.isObject && sources.path("file").asText().toHttpUrlOrNull() != null) {
                "Invalid MegaPlay source URL"
            }
            root.set<ObjectNode>("sources", sources)
            return mapper.writeValueAsString(root)
        }

        internal fun adaptPlaylist(text: String): String {
            if (!text.trimStart().startsWith("#EXTM3U")) return text
            return segment.replace(text) { match ->
                decode(match.groupValues[1]).also {
                    require(it.toHttpUrlOrNull() != null && !it.contains('\n') && !it.contains('\r') && !it.contains('"')) {
                        "Invalid MegaPlay segment URL"
                    }
                }
            }
        }

        private fun decode(token: String): String {
            // These public client constants encode URLs, not encrypted video/DRM.
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec("i?LMTAx0Q6,:}50U".toByteArray().copyOf(32), "AES"),
                IvParameterSpec("W0;27ToaUpl_P%'c".toByteArray()),
            )
            return cipher.doFinal(Base64.getUrlDecoder().decode(token)).toString(Charsets.UTF_8)
        }
    }
}
