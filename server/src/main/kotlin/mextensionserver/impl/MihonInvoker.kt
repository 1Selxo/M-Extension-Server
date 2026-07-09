package mextensionserver.impl

import android.app.Application
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import mextensionserver.model.AnimeData
import mextensionserver.model.AnimeResponse
import mextensionserver.model.ChapterData
import mextensionserver.model.DataBody
import mextensionserver.model.EpisodeData
import mextensionserver.model.JAnime
import mextensionserver.model.JFilterList
import mextensionserver.model.JManga
import mextensionserver.model.JPage
import mextensionserver.model.MangaData
import mextensionserver.model.MangaResponse
import mextensionserver.model.toJAnime
import mextensionserver.model.toJManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object MihonInvoker {
    private val logger = KotlinLogging.logger {}
    private const val BRIDGE_CONTEXT_KEY = "__mangatan_bridge_context__"
    private val context: Application
        get() = Injekt.get()

    fun invokeMethod(
        loadedExtension: MExtensionServerLoader.LoadedExtension,
        data: DataBody,
    ): Any {
        if (data.method == "sourcesManga" || data.method == "sourcesAnime") {
            return loadedExtension.sources.map(::sourceDescriptor)
        }

        val source = selectSource(loadedExtension.sources, data)

        preparePreferences(data, source)

        return when (data.method) {
            "headersManga" -> invokeHeadersManga(source as CatalogueSource)
            "filtersManga" -> invokeFiltersManga(source as CatalogueSource)
            "supportLatestManga" -> invokeSupportLatestManga(source as CatalogueSource)
            "getPopularManga" -> invokeGetPopularManga(source as CatalogueSource, data.page ?: 1)
            "getLatestManga" -> invokeGetLatestManga(source as CatalogueSource, data.page ?: 1)
            "getSearchManga" -> invokeGetSearchManga(source as CatalogueSource, data.page ?: 1, data.search ?: "", data.filterList)
            "getDetailsManga" -> invokeGetDetailsManga(source as CatalogueSource, data.mangaData)
            "getChapterList" -> invokeGetChapterList(source as CatalogueSource, data.mangaData)
            "getPageList" -> invokeGetPageList(source as CatalogueSource, data.chapterData)
            "preferencesManga" -> invokePreferencesManga(source as CatalogueSource)
            "setPreferenceManga" -> invokeSetPreferenceManga(source as CatalogueSource, data)
            "headersAnime" -> invokeHeadersAnime(source as AnimeCatalogueSource)
            "filtersAnime" -> invokeFiltersAnime(source as AnimeCatalogueSource)
            "supportLatestAnime" -> invokeSupportLatestAnime(source as AnimeCatalogueSource)
            "getPopularAnime" -> invokeGetPopularAnime(source as AnimeCatalogueSource, data.page ?: 1)
            "getLatestAnime" -> invokeGetLatestAnime(source as AnimeCatalogueSource, data.page ?: 1)
            "getSearchAnime" -> invokeGetSearchAnime(source as AnimeCatalogueSource, data.page ?: 1, data.search ?: "", data.filterList)
            "getDetailsAnime" -> invokeGetDetailsAnime(source as AnimeCatalogueSource, data.animeData)
            "getEpisodeList" -> invokeGetEpisodeList(source as AnimeCatalogueSource, data.animeData)
            "getVideoList" -> invokeGetVideoList(source as AnimeCatalogueSource, data.episodeData)
            "preferencesAnime" -> invokePreferencesAnime(source as AnimeCatalogueSource)
            "setPreferenceAnime" -> invokeSetPreferenceAnime(source as AnimeCatalogueSource, data)
            else -> throw IllegalArgumentException("Unknown method: ${data.method}")
        }
    }

    fun selectSource(
        loadedSources: List<Any>,
        data: DataBody,
    ): Any {
        if (loadedSources.isEmpty()) {
            throw IllegalArgumentException("No sources found in extension")
        }
        val requestedSourceId =
            bridgeContext(data)["sourceId"]?.toString()
                ?: return loadedSources.first()
        return loadedSources.firstOrNull { sourceId(it) == requestedSourceId }
            ?: throw IllegalArgumentException("Source $requestedSourceId was not found in extension")
    }

    private fun sourceId(source: Any): String =
        when (source) {
            is eu.kanade.tachiyomi.source.Source -> source.id.toString()
            is eu.kanade.tachiyomi.animesource.AnimeSource -> source.id.toString()
            else -> throw IllegalArgumentException("Unknown source type: ${source.javaClass}")
        }

    private fun sourceDescriptor(source: Any): Map<String, String> =
        when (source) {
            is eu.kanade.tachiyomi.source.Source ->
                mapOf(
                    "id" to source.id.toString(),
                    "name" to source.name,
                    "lang" to source.lang,
                    "baseUrl" to
                        runCatching {
                            source.javaClass
                                .getMethod("getBaseUrl")
                                .invoke(source)
                                ?.toString()
                                .orEmpty()
                        }.getOrDefault(""),
                )
            is eu.kanade.tachiyomi.animesource.AnimeSource ->
                mapOf(
                    "id" to source.id.toString(),
                    "name" to source.name,
                    "lang" to source.lang,
                    "baseUrl" to
                        runCatching {
                            source.javaClass
                                .getMethod("getBaseUrl")
                                .invoke(source)
                                ?.toString()
                                .orEmpty()
                        }.getOrDefault(""),
                )
            else -> throw IllegalArgumentException("Unknown source type: ${source.javaClass}")
        }

    private fun bridgeContext(data: DataBody): Map<String, Any> =
        data.preferences
            ?.firstOrNull { it["key"] == BRIDGE_CONTEXT_KEY }
            .orEmpty()

    fun preparePreferences(
        data: DataBody,
        source: Any,
    ) {
        val changedKey =
            if (data.method.startsWith("setPreference")) {
                bridgeContext(data)["changedPreferenceKey"]?.toString()
            } else {
                null
            }
        applyPreferences(data, source, skipPreferenceKey = changedKey)
    }

    private fun invokeHeadersManga(source: CatalogueSource): List<String> =
        if (source is HttpSource) {
            val headers =
                source.headers.toMultimap().flatMap { (name, values) ->
                    values.flatMap { value ->
                        listOf(name.replaceFirstChar { it.uppercase() }, value)
                    }
                }
            headers
        } else {
            emptyList()
        }

    private fun invokeFiltersManga(source: CatalogueSource): FilterList {
        val filterList = source.getFilterList()
        return filterList
    }

    private fun invokeSupportLatestManga(source: CatalogueSource): Boolean = source.supportsLatest

    private fun invokeGetPopularManga(
        source: CatalogueSource,
        page: Int,
    ): MangaResponse =
        runBlocking {
            val mangasPage = source.getPopularManga(page)
            MangaResponse(
                mangas = mangasPage.mangas.map { it.toJManga() },
                hasNextPage = mangasPage.hasNextPage,
            )
        }

    private fun invokeGetLatestManga(
        source: CatalogueSource,
        page: Int,
    ): MangaResponse =
        runBlocking {
            val mangasPage = source.getLatestUpdates(page)
            MangaResponse(
                mangas = mangasPage.mangas.map { it.toJManga() },
                hasNextPage = mangasPage.hasNextPage,
            )
        }

    private fun invokeGetSearchManga(
        source: CatalogueSource,
        page: Int,
        search: String,
        filterList: List<JFilterList>?,
    ): MangaResponse =
        runBlocking {
            val convertedFilters = filterList?.let { convertFilterList(source.getFilterList(), it) } ?: source.getFilterList()
            val mangasPage = source.getSearchManga(page, search, convertedFilters)
            MangaResponse(
                mangas = mangasPage.mangas.map { it.toJManga() },
                hasNextPage = mangasPage.hasNextPage,
            )
        }

    private fun invokeGetDetailsManga(
        source: CatalogueSource,
        mangaData: MangaData?,
    ): JManga {
        if (mangaData == null) {
            throw IllegalArgumentException("mangaData is required for getDetailsManga")
        }

        if (source !is HttpSource) {
            throw IllegalArgumentException("Source must be HttpSource for getDetailsManga")
        }

        val sManga =
            SManga.create().apply {
                url = mangaData.url ?: ""
                title = mangaData.title ?: ""
                artist = mangaData.artist
                author = mangaData.author
                description = mangaData.description
                genre = mangaData.genre
                status = mangaData.status ?: 0
                thumbnail_url = mangaData.thumbnail_url
                initialized = mangaData.initialized ?: false
            }

        return runBlocking {
            val detailedManga = source.getMangaDetails(sManga)
            detailedManga.toJManga()
        }
    }

    private fun invokeGetChapterList(
        source: CatalogueSource,
        mangaData: MangaData?,
    ): List<SChapter> {
        if (mangaData == null) {
            throw IllegalArgumentException("mangaData is required for getChapterList")
        }

        if (source !is HttpSource) {
            throw IllegalArgumentException("Source must be HttpSource for getChapterList")
        }

        val sManga =
            SManga.create().apply {
                url = mangaData.url ?: ""
                title = mangaData.title ?: ""
                artist = mangaData.artist
                author = mangaData.author
                description = mangaData.description
                genre = mangaData.genre
                status = mangaData.status ?: 0
                thumbnail_url = mangaData.thumbnail_url
                initialized = mangaData.initialized ?: false
            }

        return runBlocking {
            val chapters = source.getChapterList(sManga)
            chapters
        }
    }

    private fun invokeGetPageList(
        source: CatalogueSource,
        chapterData: ChapterData?,
    ): List<JPage> {
        if (chapterData == null) {
            throw IllegalArgumentException("chapterData is required for getPageList")
        }

        if (source !is HttpSource) {
            throw IllegalArgumentException("Source must be HttpSource for getPageList")
        }

        val sChapter =
            SChapter.create().apply {
                url = chapterData.url ?: ""
                name = chapterData.name ?: ""
                date_upload = chapterData.date_upload ?: 0L
                chapter_number = chapterData.chapter_number ?: 0f
                scanlator = chapterData.scanlator
            }

        return runBlocking {
            val pages = source.getPageList(sChapter)
            pages.map { page ->
                JPage(
                    index = page.index,
                    url = page.url,
                    imageUrl = source.imageRequest(page).url.toString(),
                )
            }
        }
    }

    private fun invokeHeadersAnime(source: AnimeCatalogueSource): List<String> =
        if (source is AnimeHttpSource) {
            val headers =
                source.headers.toMultimap().flatMap { (name, values) ->
                    values.flatMap { value ->
                        listOf(name.replaceFirstChar { it.uppercase() }, value)
                    }
                }
            headers
        } else {
            emptyList()
        }

    private fun invokeFiltersAnime(source: AnimeCatalogueSource): AnimeFilterList {
        val filterList = source.getFilterList()
        return filterList
    }

    private fun invokeSupportLatestAnime(source: AnimeCatalogueSource): Boolean = source.supportsLatest

    private fun invokeGetPopularAnime(
        source: AnimeCatalogueSource,
        page: Int,
    ): AnimeResponse =
        runBlocking {
            val animesPage = source.getPopularAnime(page)
            AnimeResponse(
                animes = animesPage.animes.map { it.toJAnime() },
                hasNextPage = animesPage.hasNextPage,
            )
        }

    private fun invokeGetLatestAnime(
        source: AnimeCatalogueSource,
        page: Int,
    ): AnimeResponse =
        runBlocking {
            val animesPage = source.getLatestUpdates(page)
            AnimeResponse(
                animes = animesPage.animes.map { it.toJAnime() },
                hasNextPage = animesPage.hasNextPage,
            )
        }

    private fun invokeGetSearchAnime(
        source: AnimeCatalogueSource,
        page: Int,
        search: String,
        filterList: List<JFilterList>?,
    ): AnimeResponse =
        runBlocking {
            val convertedFilters = filterList?.let { convertAnimeFilterList(source.getFilterList(), it) } ?: source.getFilterList()
            val animesPage = source.getSearchAnime(page, search, convertedFilters)
            AnimeResponse(
                animes = animesPage.animes.map { it.toJAnime() },
                hasNextPage = animesPage.hasNextPage,
            )
        }

    private fun invokeGetDetailsAnime(
        source: AnimeCatalogueSource,
        animeData: AnimeData?,
    ): JAnime {
        if (animeData == null) {
            throw IllegalArgumentException("animeData is required for getDetailsAnime")
        }

        if (source !is AnimeHttpSource) {
            throw IllegalArgumentException("Source must be AnimeHttpSource for getDetailsAnime")
        }

        val sAnime =
            SAnime.create().apply {
                url = animeData.url ?: ""
                title = animeData.title ?: ""
                artist = animeData.artist
                author = animeData.author
                description = animeData.description
                genre = animeData.genre
                status = animeData.status ?: 0
                thumbnail_url = animeData.thumbnail_url
                initialized = animeData.initialized ?: false
            }

        return runBlocking {
            val detailedAnime = source.getAnimeDetails(sAnime)
            detailedAnime.toJAnime()
        }
    }

    private fun invokeGetEpisodeList(
        source: AnimeCatalogueSource,
        animeData: AnimeData?,
    ): List<SEpisode> {
        if (animeData == null) {
            throw IllegalArgumentException("animeData is required for getEpisodeList")
        }

        if (source !is AnimeHttpSource) {
            throw IllegalArgumentException("Source must be AnimeHttpSource for getEpisodeList")
        }

        val sAnime =
            SAnime.create().apply {
                url = animeData.url ?: ""
                title = animeData.title ?: ""
                artist = animeData.artist
                author = animeData.author
                description = animeData.description
                genre = animeData.genre
                status = animeData.status ?: 0
                thumbnail_url = animeData.thumbnail_url
                initialized = animeData.initialized ?: false
            }

        return runBlocking {
            val episodes = source.getEpisodeList(sAnime)
            episodes
        }
    }

    private fun invokeGetVideoList(
        source: AnimeCatalogueSource,
        episodeData: EpisodeData?,
    ): List<Video> {
        if (episodeData == null) {
            throw IllegalArgumentException("episodeData is required for getVideoList")
        }

        if (source !is AnimeHttpSource) {
            throw IllegalArgumentException("Source must be AnimeHttpSource for getVideoList")
        }

        val sEpisode =
            SEpisode.create().apply {
                url = episodeData.url ?: ""
                name = episodeData.name ?: ""
                date_upload = episodeData.date_upload ?: 0L
                episode_number = episodeData.episode_number ?: 0f
                scanlator = episodeData.scanlator
            }

        return runBlocking {
            val videos = source.getVideoList(sEpisode)
            videos
        }
    }

    private fun invokePreferencesAnime(source: AnimeCatalogueSource): MutableList<Map<String, Any>> {
        val preferences = mutableListOf<Map<String, Any>>()
        if (source !is ConfigurableAnimeSource) {
            return preferences
        }

        val screen = PreferenceScreen(context)
        val sourceId = (source as eu.kanade.tachiyomi.animesource.AnimeSource).id
        val prefs = context.getSharedPreferences("source_$sourceId", 0)
        screen.setSharedPreferences(prefs)

        (source as ConfigurableAnimeSource).setupPreferenceScreen(screen)

        processPreferences(screen, preferences)

        return preferences
    }

    private fun invokePreferencesManga(source: CatalogueSource): MutableList<Map<String, Any>> {
        val preferences = mutableListOf<Map<String, Any>>()
        if (source !is eu.kanade.tachiyomi.source.ConfigurableSource) {
            return preferences
        }

        val screen = PreferenceScreen(context)
        val sourceId = (source as eu.kanade.tachiyomi.source.Source).id
        val prefs = context.getSharedPreferences("source_$sourceId", 0)
        screen.setSharedPreferences(prefs)

        (source as eu.kanade.tachiyomi.source.ConfigurableSource).setupPreferenceScreen(screen)

        processPreferences(screen, preferences)

        return preferences
    }

    private fun invokeSetPreferenceAnime(
        source: AnimeCatalogueSource,
        data: DataBody,
    ): MutableList<Map<String, Any>> {
        if (source !is ConfigurableAnimeSource) return mutableListOf()
        return invokeSetPreference(source.id, data, source::setupPreferenceScreen)
    }

    private fun invokeSetPreferenceManga(
        source: CatalogueSource,
        data: DataBody,
    ): MutableList<Map<String, Any>> {
        if (source !is eu.kanade.tachiyomi.source.ConfigurableSource) return mutableListOf()
        return invokeSetPreference(source.id, data, source::setupPreferenceScreen)
    }

    private fun invokeSetPreference(
        sourceId: Long,
        data: DataBody,
        setup: (PreferenceScreen) -> Unit,
    ): MutableList<Map<String, Any>> {
        val screen = PreferenceScreen(context)
        val prefs = context.getSharedPreferences("source_$sourceId", 0)
        screen.setSharedPreferences(prefs)
        setup(screen)

        val changedKey = bridgeContext(data)["changedPreferenceKey"]?.toString()
        val changedData = data.preferences?.firstOrNull { it["key"] == changedKey }
        val preference =
            changedKey?.let { key ->
                screen.preferences.firstOrNull { it.key == key }
            }
        val changedValue = changedData?.let(::preferenceValue)
        if (preference != null && changedValue != null) {
            val oldValue = preference.currentValue
            preference.saveNewValue(changedValue)
            if (!preference.callChangeListener(changedValue)) {
                preference.saveNewValue(oldValue)
            }
        }

        val preferences = mutableListOf<Map<String, Any>>()
        processPreferences(screen, preferences)
        return preferences
    }

    private fun preferenceValue(data: Map<String, Any>): Any? =
        when {
            data.containsKey("checkBoxPreference") ->
                (data["checkBoxPreference"] as? Map<*, *>)?.get("value")
            data.containsKey("switchPreferenceCompat") ->
                (data["switchPreferenceCompat"] as? Map<*, *>)?.get("value")
            data.containsKey("editTextPreference") ->
                (data["editTextPreference"] as? Map<*, *>)?.get("value")
            data.containsKey("listPreference") -> {
                val list = data["listPreference"] as? Map<*, *>
                val index = list?.get("valueIndex") as? Int
                val values = list?.get("entryValues") as? List<*>
                if (index != null && values != null && index in values.indices) values[index] else null
            }
            data.containsKey("multiSelectListPreference") ->
                ((data["multiSelectListPreference"] as? Map<*, *>)?.get("values") as? List<*>)?.toSet()
            else -> null
        }

    private fun processPreferences(
        screen: PreferenceScreen,
        preferences: MutableList<Map<String, Any>>,
    ) {
        for (i in 0 until screen.preferences.size) {
            val preference = screen.preferences[i]
            when (preference) {
                is CheckBoxPreference -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "checkBoxPreference" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "value" to preference.currentValue,
                                ),
                        ),
                    )
                }
                is SwitchPreferenceCompat -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "switchPreferenceCompat" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "value" to preference.currentValue,
                                ),
                        ),
                    )
                }
                is EditTextPreference -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "editTextPreference" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "value" to preference.currentValue,
                                    "text" to preference.currentValue,
                                    "dialogMessage" to preference.getDialogMessage(),
                                    "dialogTitle" to preference.getDialogTitle(),
                                ),
                        ),
                    )
                }
                is ListPreference -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "listPreference" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "valueIndex" to preference.findIndexOfValue(preference.currentValue.toString()),
                                    "entries" to preference.entries,
                                    "entryValues" to preference.entryValues,
                                ),
                        ),
                    )
                }
                is MultiSelectListPreference -> {
                    preferences.add(
                        mapOf(
                            "key" to preference.key,
                            "multiSelectListPreference" to
                                mapOf(
                                    "title" to preference.title,
                                    "summary" to (preference.summary ?: ""),
                                    "values" to preference.currentValue,
                                    "entries" to preference.entries,
                                    "entryValues" to preference.entryValues,
                                ),
                        ),
                    )
                }
            }
        }
    }

    fun applyPreferences(
        data: DataBody,
        source: Any,
        skipPreferenceKey: String? = null,
    ) {
        val preferences = data.preferences ?: return
        val sourceId =
            when (source) {
                is eu.kanade.tachiyomi.source.Source -> source.id
                is eu.kanade.tachiyomi.animesource.AnimeSource -> source.id
                else -> throw IllegalArgumentException("Unknown source type: ${source.javaClass}")
            }
        val prefs = context.getSharedPreferences("source_$sourceId", 0)
        val editor = prefs.edit()
        for (prefMap in preferences) {
            val rawKey = prefMap["key"] as? String ?: continue
            if (rawKey == skipPreferenceKey) continue
            // Java Preferences has 80 char limit for keys, hash long keys
            val key =
                if (rawKey.length > 80) {
                    val hash =
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(rawKey.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                            .take(64)
                    "hash_$hash"
                } else {
                    rawKey
                }
            when {
                prefMap.containsKey("checkBoxPreference") -> {
                    val checkBox = prefMap["checkBoxPreference"] as? Map<*, *>
                    val value = checkBox?.get("value") as? Boolean
                    if (value != null) {
                        editor.putBoolean(key, value)
                    }
                }
                prefMap.containsKey("switchPreferenceCompat") -> {
                    val switch = prefMap["switchPreferenceCompat"] as? Map<*, *>
                    val value = switch?.get("value") as? Boolean
                    if (value != null) {
                        editor.putBoolean(key, value)
                    }
                }
                prefMap.containsKey("editTextPreference") -> {
                    val editText = prefMap["editTextPreference"] as? Map<*, *>
                    val value = editText?.get("value") as? String
                    if (value != null) {
                        editor.putString(key, value)
                    }
                }
                prefMap.containsKey("listPreference") -> {
                    val list = prefMap["listPreference"] as? Map<*, *>
                    val valueIndex = list?.get("valueIndex") as? Int
                    val entryValues = list?.get("entryValues") as? List<String>
                    if (valueIndex != null && entryValues != null && valueIndex in entryValues.indices) {
                        editor.putString(key, entryValues[valueIndex])
                    }
                }
                prefMap.containsKey("multiSelectListPreference") -> {
                    val multiList = prefMap["multiSelectListPreference"] as? Map<*, *>
                    val values = multiList?.get("values") as? List<String>
                    if (values != null) {
                        editor.putStringSet(key, values.toSet())
                    }
                }
            }
        }
        editor.apply()
    }

    private fun convertFilterList(
        originalFilters: FilterList,
        jFilters: List<JFilterList>,
    ): FilterList {
        // Apply the filter values from jFilters to the original filters
        val convertedFilters =
            originalFilters.map { filter ->
                when (filter) {
                    is Filter.Select<*> -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null) {
                            val state = jFilter.stateInt
                            if (state != null) {
                                filter.state = state
                            }
                        }
                        filter
                    }
                    is Filter.CheckBox -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null &&
                            jFilter.stateList != null &&
                            jFilter.stateList.isNotEmpty() &&
                            jFilter.stateList[0].stateBoolean is Boolean
                        ) {
                            filter.state = jFilter.stateList[0].stateBoolean as Boolean
                        }
                        filter
                    }
                    is Filter.TriState -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null && jFilter.stateInt is Int) {
                            filter.state = jFilter.stateInt
                        }
                        filter
                    }
                    is Filter.Text -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null && jFilter.stateString is String) {
                            filter.state = jFilter.stateString
                        }
                        filter
                    }
                    is Filter.Group<*> -> {
                        val groupFilter = filter as Filter.Group<Filter<*>>
                        val jGroup = jFilters.find { it.name == groupFilter.name }
                        if (jGroup != null) {
                            val subJFilters = jGroup.stateList
                            for (state in groupFilter.state) {
                                val jSubFilter = subJFilters?.find { it.name == state.name }
                                val dataGroup = jSubFilter
                                if (dataGroup == null) {
                                    continue
                                }
                                if (state is Filter.CheckBox) {
                                    val checkBox = state
                                    checkBox.state = dataGroup.stateBoolean ?: false
                                }
                                if (state is Filter.TriState) {
                                    val checkBox = state
                                    checkBox.state = dataGroup.stateInt ?: 0
                                }
                                if (state is Filter.Select<*>) {
                                    val select = state
                                    select.state = dataGroup.stateInt ?: 0
                                }
                                if (state is Filter.Text) {
                                    val text = state
                                    text.state = dataGroup.name ?: ""
                                }
                            }
                        }
                        filter
                    }
                    is Filter.Sort -> {
                        val jSort = jFilters.find { it.name == filter.name }
                        if (jSort != null) {
                            val dataSort = jSort.stateSort
                            if (dataSort != null && dataSort.index != null && dataSort.ascending != null) {
                                filter.state = Filter.Sort.Selection(dataSort.index, dataSort.ascending)
                            }
                        }
                        filter
                    }
                    else -> filter
                }
            }
        return FilterList(convertedFilters)
    }

    private fun convertAnimeFilterList(
        originalFilters: AnimeFilterList,
        jFilters: List<JFilterList>,
    ): AnimeFilterList {
        // Apply the filter values from jFilters to the original filters
        val convertedFilters =
            originalFilters.map { filter ->
                when (filter) {
                    is AnimeFilter.Select<*> -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null) {
                            val state = jFilter.stateInt as? Int
                            if (state != null) {
                                filter.state = state
                            }
                        }
                        filter
                    }
                    is AnimeFilter.CheckBox -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null &&
                            jFilter.stateList != null &&
                            jFilter.stateList.isNotEmpty() &&
                            jFilter.stateList[0].stateBoolean is Boolean
                        ) {
                            filter.state = jFilter.stateList[0].stateBoolean as Boolean
                        }
                        filter
                    }
                    is AnimeFilter.TriState -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null && jFilter.stateInt is Int) {
                            filter.state = jFilter.stateInt as Int
                        }
                        filter
                    }
                    is AnimeFilter.Text -> {
                        val jFilter = jFilters.find { it.name == filter.name }
                        if (jFilter != null && jFilter.stateString is String) {
                            filter.state = jFilter.stateString
                        }
                        filter
                    }
                    is AnimeFilter.Group<*> -> {
                        val groupFilter = filter as AnimeFilter.Group<Filter<*>>
                        val jGroup = jFilters.find { it.name == groupFilter.name }
                        if (jGroup != null) {
                            val subJFilters = jGroup.stateList
                            for (state in groupFilter.state) {
                                val jSubFilter = subJFilters?.find { it.name == state.name }
                                val dataGroup = jSubFilter
                                if (dataGroup == null) {
                                    continue
                                }
                                if (state is AnimeFilter.CheckBox) {
                                    val checkBox = state
                                    checkBox.state = dataGroup.stateBoolean ?: false
                                }
                                if (state is AnimeFilter.TriState) {
                                    val checkBox = state
                                    checkBox.state = dataGroup.stateInt ?: 0
                                }
                                if (state is AnimeFilter.Select<*>) {
                                    val select = state
                                    select.state = dataGroup.stateInt ?: 0
                                }
                                if (state is AnimeFilter.Text) {
                                    val text = state
                                    text.state = dataGroup.name ?: ""
                                }
                            }
                        }
                        filter
                    }
                    is AnimeFilter.Sort -> {
                        val jSort = jFilters.find { it.name == filter.name }
                        if (jSort != null) {
                            val dataSort = jSort.stateSort
                            if (dataSort != null && dataSort.index != null && dataSort.ascending != null) {
                                filter.state =
                                    AnimeFilter.Sort
                                        .Selection(dataSort.index, dataSort.ascending)
                            }
                        }
                        filter
                    }
                    else -> filter
                }
            }
        return AnimeFilterList(convertedFilters)
    }
}
