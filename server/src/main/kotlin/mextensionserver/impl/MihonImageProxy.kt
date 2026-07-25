package mextensionserver.impl

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

/**
 * Keeps image requests inside the extension's OkHttp client.
 *
 * Some extensions use interceptors to turn a synthetic page URL into image
 * bytes. Returning that URL to the caller bypasses the interceptor, so page
 * entries are registered here and exposed through a loopback URL instead.
 */
internal object MihonImageProxy {
    private const val MAX_ENTRIES = 4096

    data class ImageData(
        val bytes: ByteArray,
        val contentType: String,
    )

    private sealed interface Entry {
        val key: String
    }

    private data class PageEntry(
        override val key: String,
        val source: HttpSource,
        val page: Page,
    ) : Entry

    private data class PosterEntry(
        override val key: String,
        val source: HttpSource,
        val title: String,
        val originalUrl: String,
        val resolver: (OkHttpClient, String) -> String?,
        var originalFailed: Boolean = false,
        var resolvedUrl: String? = null,
    ) : Entry

    private val lock = Any()
    private val entriesByToken = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val tokensByKey = mutableMapOf<String, String>()

    @Volatile
    private var port: Int = 0

    fun configure(port: Int) {
        require(port > 0) { "Image proxy port must be positive" }
        this.port = port
    }

    fun register(
        source: HttpSource,
        page: Page,
    ): String? {
        val currentPort = port
        if (currentPort <= 0) return null

        val key =
            buildString {
                append(System.identityHashCode(source))
                append('\u0000')
                append(page.index)
                append('\u0000')
                append(page.url)
                append('\u0000')
                append(page.imageUrl.orEmpty())
            }
        val token =
            synchronized(lock) {
                tokensByKey[key]?.let { existingToken ->
                    entriesByToken[existingToken] = PageEntry(key, source, page)
                    return@synchronized existingToken
                }

                while (entriesByToken.size >= MAX_ENTRIES) {
                    val iterator = entriesByToken.entries.iterator()
                    val eldest = iterator.next()
                    iterator.remove()
                    tokensByKey.remove(eldest.value.key)
                }

                UUID.randomUUID().toString().also { newToken ->
                    entriesByToken[newToken] = PageEntry(key, source, page)
                    tokensByKey[key] = newToken
                }
            }

        return proxyUrl(currentPort, token)
    }

    internal fun registerPoster(
        source: HttpSource,
        title: String,
        url: String,
        resolver: (OkHttpClient, String) -> String? = KlRawPosterResolver::resolve,
    ): String? {
        val currentPort = port
        if (currentPort <= 0) return null

        val key =
            buildString {
                append(System.identityHashCode(source))
                append("\u0000poster\u0000")
                append(title)
                append('\u0000')
                append(url)
            }
        val token =
            synchronized(lock) {
                tokensByKey[key]?.let { existingToken ->
                    entriesByToken[existingToken] =
                        PosterEntry(
                            key = key,
                            source = source,
                            title = title,
                            originalUrl = url,
                            resolver = resolver,
                        )
                    return@synchronized existingToken
                }

                while (entriesByToken.size >= MAX_ENTRIES) {
                    val iterator = entriesByToken.entries.iterator()
                    val eldest = iterator.next()
                    iterator.remove()
                    tokensByKey.remove(eldest.value.key)
                }

                UUID.randomUUID().toString().also { newToken ->
                    entriesByToken[newToken] =
                        PosterEntry(
                            key = key,
                            source = source,
                            title = title,
                            originalUrl = url,
                            resolver = resolver,
                        )
                    tokensByKey[key] = newToken
                }
            }

        return proxyUrl(currentPort, token)
    }

    fun fetch(token: String): ImageData? {
        val entry = synchronized(lock) { entriesByToken[token] } ?: return null
        return when (entry) {
            is PageEntry -> fetchPage(entry)
            is PosterEntry -> fetchPoster(entry)
        }
    }

    private fun fetchPage(entry: PageEntry): ImageData {
        if (entry.page.imageUrl == null) {
            entry.page.imageUrl =
                entry.source
                    .fetchImageUrl(entry.page)
                    .toBlocking()
                    .single()
        }
        return entry.source.fetchImage(entry.page).toBlocking().single().use { response ->
            val body = requireNotNull(response.body) { "Extension returned an empty image response" }
            ImageData(
                bytes = body.bytes(),
                contentType = body.contentType()?.toString() ?: "application/octet-stream",
            )
        }
    }

    private fun fetchPoster(entry: PosterEntry): ImageData? {
        val shouldTryOriginal = synchronized(lock) { !entry.originalFailed }
        if (shouldTryOriginal) {
            fetchUrl(entry.source, entry.originalUrl)?.let { return it }
            synchronized(lock) { entry.originalFailed = true }
        }

        val resolvedUrl =
            synchronized(lock) { entry.resolvedUrl }
                ?: entry.resolver(entry.source.client, entry.title)?.also { resolved ->
                    synchronized(lock) { entry.resolvedUrl = resolved }
                }
                ?: return null
        return fetchUrl(entry.source, resolvedUrl)
    }

    private fun fetchUrl(
        source: HttpSource,
        url: String,
    ): ImageData? =
        runCatching {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .headers(runCatching { source.headers }.getOrDefault(Headers.Builder().build()))
                    .build()
            source.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                val contentType = body.contentType()?.toString() ?: "application/octet-stream"
                if (!contentType.startsWith("image/")) return@use null
                ImageData(
                    bytes = body.bytes(),
                    contentType = contentType,
                )
            }
        }.getOrNull()

    private fun proxyUrl(
        currentPort: Int,
        token: String,
    ): String = "http://127.0.0.1:$currentPort/image/$token"

    fun clear() {
        synchronized(lock) {
            entriesByToken.clear()
            tokensByKey.clear()
        }
        port = 0
    }
}
