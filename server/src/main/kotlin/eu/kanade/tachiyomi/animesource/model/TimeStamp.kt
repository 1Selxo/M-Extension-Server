package eu.kanade.tachiyomi.animesource.model

import kotlinx.serialization.Serializable

@Serializable
enum class ChapterType { Opening, Ending, Recap, MixedOp, Other }

@Serializable
data class TimeStamp(
    val start: Double,
    val end: Double,
    val name: String,
    val type: ChapterType = ChapterType.Other,
)
