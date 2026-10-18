package eu.kanade.tachiyomi.extension.all.perffixture

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A Keiyoushi-style extension used only by the performance harness.
 *
 * It is compiled to dex and packaged as a real APK so the harness measures
 * the same dex2jar, bytecode-repair, class-loading and invocation paths as a
 * downloaded extension. Its upstream address comes from the `perf.upstream`
 * system property, which the harness points at a local fake server.
 */
class PerfFixture :
    ParsedHttpSource(),
    ConfigurableSource {
    override val name = "Perf Fixture"

    override val lang = "en"

    override val supportsLatest = true

    override val baseUrl: String = System.getProperty("perf.upstream") ?: "http://127.0.0.1:9"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)

    override fun headersBuilder() =
        super
            .headersBuilder()
            .add("Referer", "$baseUrl/")
            .add("X-Perf-Quality", preferences.getString(PREF_QUALITY, "high") ?: "high")

    // ============================== Popular ===============================

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/popular?page=$page", headers)

    override fun popularMangaSelector() = "div.item"

    override fun popularMangaFromElement(element: Element) = mangaFromElement(element)

    override fun popularMangaNextPageSelector() = "a.next"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest?page=$page", headers)

    override fun latestUpdatesSelector() = popularMangaSelector()

    override fun latestUpdatesFromElement(element: Element) = mangaFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // =============================== Search ===============================

    override fun searchMangaRequest(
        page: Int,
        query: String,
        filters: FilterList,
    ): Request {
        val url =
            "$baseUrl/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("page", page.toString())
        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> url.addQueryParameter("status", filter.toUriPart())
                is GenreFilter ->
                    filter.state.forEach { genre ->
                        when {
                            genre.isIncluded() -> url.addQueryParameter("genre", genre.id)
                            genre.isExcluded() -> url.addQueryParameter("exclude", genre.id)
                        }
                    }
                is AdultFilter -> if (filter.state) url.addQueryParameter("adult", "1")
                is AuthorFilter -> if (filter.state.isNotBlank()) url.addQueryParameter("author", filter.state)
                is OrderFilter ->
                    filter.state?.let { selection ->
                        url.addQueryParameter("sort", ORDERS[selection.index])
                        url.addQueryParameter("asc", selection.ascending.toString())
                    }
                else -> {}
            }
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()

    override fun searchMangaFromElement(element: Element) = mangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    private fun mangaFromElement(element: Element) =
        SManga.create().apply {
            val link = element.selectFirst("a")!!
            setUrlWithoutDomain(link.attr("abs:href"))
            title = element.selectFirst("h3")!!.text()
            thumbnail_url = element.selectFirst("img")?.attr("abs:data-src")
        }

    // =============================== Details ==============================

    override fun mangaDetailsParse(document: Document) =
        SManga.create().apply {
            val info = document.selectFirst("div.info")!!
            title = info.selectFirst("h1")!!.text()
            author = info.selectFirst("span.author")?.text()
            artist = info.selectFirst("span.artist")?.text()
            description = info.select("div.description p").joinToString("\n\n") { it.text() }
            genre = info.select("ul.genres li").joinToString { it.text() }
            status =
                when (info.selectFirst("span.status")?.text()) {
                    "Ongoing" -> SManga.ONGOING
                    "Completed" -> SManga.COMPLETED
                    else -> SManga.UNKNOWN
                }
            thumbnail_url = info.selectFirst("img")?.attr("abs:src")
        }

    // ============================== Chapters ==============================

    override fun chapterListSelector() = "ul.chapters li"

    override fun chapterFromElement(element: Element) =
        SChapter.create().apply {
            val link = element.selectFirst("a")!!
            setUrlWithoutDomain(link.attr("abs:href"))
            name = link.text()
            chapter_number = element.attr("data-number").toFloatOrNull() ?: -1f
            scanlator = element.selectFirst("span.group")?.text()
            date_upload =
                element.selectFirst("span.date")?.text()?.let { text ->
                    runCatching { dateFormat.parse(text)?.time }.getOrNull()
                } ?: 0L
        }

    // =============================== Pages ================================

    override fun pageListRequest(chapter: SChapter): Request = GET("$baseUrl/api${chapter.url}/pages", headers)

    override fun pageListParse(response: Response): List<Page> {
        // Generated @Serializable classes call interface default methods via
        // invokespecial, which the dex2jar path cannot translate yet; parse
        // the tree instead, as many extensions do.
        val pages = Json.parseToJsonElement(response.body.string()).jsonObject["pages"]!!.jsonArray
        return pages.mapIndexed { index, page -> Page(index, imageUrl = page.jsonObject["url"]!!.jsonPrimitive.content) }
    }

    override fun pageListParse(document: Document): List<Page> = throw UnsupportedOperationException()

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================== Filters ===============================

    override fun getFilterList() =
        FilterList(
            Filter.Header("Filters are applied together"),
            StatusFilter(),
            GenreFilter(GENRES.map { (label, id) -> Genre(label, id) }),
            AdultFilter(),
            AuthorFilter(),
            OrderFilter(),
        )

    private class StatusFilter : Filter.Select<String>("Status", arrayOf("Any", "Ongoing", "Completed")) {
        fun toUriPart() = values[state].lowercase()
    }

    private class Genre(
        name: String,
        val id: String,
    ) : Filter.TriState(name)

    private class GenreFilter(
        genres: List<Genre>,
    ) : Filter.Group<Genre>("Genres", genres)

    private class AdultFilter : Filter.CheckBox("Include adult titles")

    private class AuthorFilter : Filter.Text("Author")

    private class OrderFilter :
        Filter.Sort("Order by", ORDERS.map { it.replaceFirstChar(Char::uppercase) }.toTypedArray(), Selection(0, false))

    // ============================ Preferences =============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_QUALITY
                title = "Image quality"
                entries = arrayOf("High", "Medium", "Low")
                entryValues = arrayOf("high", "medium", "low")
                setDefaultValue("high")
                summary = "%s"
            }.also(screen::addPreference)
        EditTextPreference(screen.context)
            .apply {
                key = PREF_MIRROR
                title = "Mirror"
                summary = "Optional mirror host"
                setDefaultValue("")
            }.also(screen::addPreference)
        SwitchPreferenceCompat(screen.context)
            .apply {
                key = PREF_DATA_SAVER
                title = "Data saver"
                summary = "Request smaller images"
                setDefaultValue(false)
            }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY = "pref_quality"
        private const val PREF_MIRROR = "pref_mirror"
        private const val PREF_DATA_SAVER = "pref_data_saver"

        private val ORDERS = listOf("popular", "latest", "title", "rating")

        private val GENRES =
            listOf(
                "Action" to "action",
                "Adventure" to "adventure",
                "Comedy" to "comedy",
                "Drama" to "drama",
                "Fantasy" to "fantasy",
                "Horror" to "horror",
                "Mystery" to "mystery",
                "Romance" to "romance",
                "Sci-Fi" to "sci-fi",
                "Slice of Life" to "slice-of-life",
                "Sports" to "sports",
                "Thriller" to "thriller",
            )
    }
}
