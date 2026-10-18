package mextensionserver.perf

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic local stand-in for a manga website.
 *
 * Every response waits [latencyMs] before it is sent, so upstream time is a
 * known constant that can be subtracted from a measured request. Responses
 * are marked `no-store`, so OkHttp's cache never hides an upstream call.
 */
class FakeUpstream(
    private val latencyMs: Long,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 64)
    private val requests = AtomicInteger()

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    /** Number of upstream requests served so far. */
    val requestCount: Int
        get() = requests.get()

    init {
        server.executor = Executors.newFixedThreadPool(8) { runnable -> Thread(runnable, "fake-upstream").apply { isDaemon = true } }
        server.createContext("/") { exchange -> exchange.use(::handle) }
        server.start()
    }

    private fun handle(exchange: HttpExchange) {
        requests.incrementAndGet()
        if (latencyMs > 0) Thread.sleep(latencyMs)
        val path = exchange.requestURI.path
        val query = parseQuery(exchange.requestURI.rawQuery)
        when {
            path == "/popular" -> html(exchange, catalogue("popular", query.page(), CATALOGUE_SIZE))
            path == "/latest" -> html(exchange, catalogue("latest", query.page(), CATALOGUE_SIZE))
            path == "/search" -> html(exchange, catalogue("search-${query["q"].orEmpty()}", query.page(), SEARCH_SIZE))
            path.startsWith("/manga/") -> html(exchange, details(path.removePrefix("/manga/")))
            path.startsWith("/api/chapter/") && path.endsWith("/pages") ->
                send(exchange, 200, "application/json", pageList(path.removePrefix("/api/chapter/").removeSuffix("/pages")))
            path.startsWith("/img/") -> send(exchange, 200, "image/jpeg", PAGE_IMAGE)
            path.startsWith("/cover/") -> send(exchange, 200, "image/jpeg", COVER_IMAGE)
            else -> send(exchange, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun Map<String, String>.page() = this["page"]?.toIntOrNull() ?: 1

    private fun catalogue(
        kind: String,
        page: Int,
        size: Int,
    ): String =
        buildString {
            append("<!DOCTYPE html><html><head><title>Catalogue</title></head><body><div class=\"list\">")
            repeat(size) { index ->
                val id = "$kind-$page-$index"
                append("<div class=\"item\"><a href=\"/manga/")
                append(id)
                append("\"><img data-src=\"/cover/")
                append(id)
                append(".jpg\" alt=\"cover\"><h3>Title ")
                append(id)
                append(" &amp; friends</h3></a><span class=\"meta\">Chapter ")
                append(index * 3)
                append("</span></div>")
            }
            append("</div>")
            if (page < LAST_PAGE) append("<a class=\"next\" href=\"?page=${page + 1}\">Next</a>")
            append("</body></html>")
        }

    private fun details(id: String): String =
        buildString {
            append("<!DOCTYPE html><html><body><div class=\"info\"><img src=\"/cover/")
            append(id)
            append(".jpg\"><h1>Title ")
            append(id)
            append("</h1><span class=\"author\">Author Name</span><span class=\"artist\">Artist Name</span>")
            append("<span class=\"status\">Ongoing</span><ul class=\"genres\">")
            listOf("Action", "Comedy", "Drama", "Fantasy", "Mystery").forEach { append("<li>").append(it).append("</li>") }
            append("</ul><div class=\"description\">")
            repeat(4) { paragraph ->
                append("<p>Paragraph ")
                append(paragraph)
                append(" of a longer synopsis with \"quotes\", unicode éè中文 and an ampersand &amp; more text.</p>")
            }
            append("</div></div><ul class=\"chapters\">")
            for (number in CHAPTER_COUNT downTo 1) {
                append("<li data-number=\"")
                append(number)
                append("\"><a href=\"/chapter/")
                append(id)
                append('-')
                append(number)
                append("\">Chapter ")
                append(number)
                append("</a><span class=\"group\">Scan Group</span><span class=\"date\">")
                append(MONTHS[number % 12])
                append(' ')
                append((number % 28 + 1).toString().padStart(2, '0'))
                append(", 20")
                append(10 + number % 15)
                append("</span></li>")
            }
            append("</ul></body></html>")
        }

    private fun pageList(chapter: String): ByteArray =
        buildString {
            append("{\"chapter\":\"")
            append(chapter)
            append("\",\"pages\":[")
            repeat(PAGE_COUNT) { index ->
                if (index > 0) append(',')
                append("{\"url\":\"")
                append(baseUrl)
                append("/img/")
                append(chapter)
                append('/')
                append(index)
                append(".jpg\",\"width\":800,\"height\":1200}")
            }
            append("]}")
        }.toByteArray()

    private fun html(
        exchange: HttpExchange,
        body: String,
    ) = send(exchange, 200, "text/html; charset=utf-8", body.toByteArray())

    private fun send(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: ByteArray,
    ) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw
            ?.split('&')
            ?.filter(String::isNotEmpty)
            ?.associate { part ->
                val name = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
            }.orEmpty()

    override fun close() {
        server.stop(0)
    }

    companion object {
        const val CATALOGUE_SIZE = 48
        const val SEARCH_SIZE = 24
        const val LAST_PAGE = 5
        const val CHAPTER_COUNT = 300
        const val PAGE_COUNT = 30
        private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

        /** Deterministic pseudo-image bytes; the content is never decoded. */
        private fun imageBytes(size: Int): ByteArray {
            var state = 0x2545F491
            return ByteArray(size) { index ->
                if (index < 3) {
                    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())[index]
                } else {
                    state = state xor (state shl 13)
                    state = state xor (state ushr 17)
                    state = state xor (state shl 5)
                    state.toByte()
                }
            }
        }

        val PAGE_IMAGE = imageBytes(300 * 1024)
        val COVER_IMAGE = imageBytes(40 * 1024)
    }
}
