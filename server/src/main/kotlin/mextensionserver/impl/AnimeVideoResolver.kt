package mextensionserver.impl

import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import kotlinx.coroutines.CancellationException

internal object AnimeVideoResolver {
    // Match Anikku's implementation detection, including inherited extension
    // implementations. A failed HTTP request is not evidence of an older API.
    fun hasHosters(source: AnimeHttpSource): Boolean {
        var type: Class<*>? = source.javaClass
        while (type != null && type != AnimeHttpSource::class.java && type != ParsedAnimeHttpSource::class.java) {
            if (type.declaredMethods.any { it.name in setOf("getHosterList", "hosterListRequest", "hosterListParse") }) return true
            type = type.superclass
        }
        return false
    }

    suspend fun resolve(
        source: AnimeHttpSource,
        episode: SEpisode,
    ): List<Video> {
        if (!hasHosters(source)) return source.getVideoList(episode)
        val hosters = source.run { getHosterList(episode).sortHosters() }
        val videos = mutableListOf<Video>()
        var failure: Exception? = null
        for (hoster in hosters) {
            try {
                videos.addAll(source.run { (hoster.videoList ?: getVideoList(hoster)).sortVideos() })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
        }
        if (videos.isEmpty() && failure != null) throw failure
        return videos.sortedByDescending { it.preferred }
    }
}
