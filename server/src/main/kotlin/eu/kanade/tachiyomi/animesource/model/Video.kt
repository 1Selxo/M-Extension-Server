package eu.kanade.tachiyomi.animesource.model

import android.net.Uri
import eu.kanade.tachiyomi.network.ProgressListener
import kotlinx.serialization.Serializable
import okhttp3.Headers
import rx.subjects.Subject

@Serializable
data class Track(
    val url: String,
    val lang: String,
)

data class Video(
    var videoUrl: String = "",
    val videoTitle: String = "",
    val resolution: Int? = null,
    val bitrate: Int? = null,
    val headers: Headers? = null,
    val preferred: Boolean = false,
    val subtitleTracks: List<Track> = emptyList(),
    val audioTracks: List<Track> = emptyList(),
    val timestamps: List<TimeStamp> = emptyList(),
    val mpvArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegStreamArgs: List<Pair<String, String>> = emptyList(),
    val ffmpegVideoArgs: List<Pair<String, String>> = emptyList(),
    val internalData: String = "",
    val initialized: Boolean = false,
) : ProgressListener {
    /** Compatibility aliases retained for extension-lib 14/15. */
    val quality: String get() = videoTitle
    val url: String get() = videoPageUrl
    val videoPageUrl: String get() = legacyVideoPageUrl
    private var legacyVideoPageUrl: String = ""

    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        headers: Headers? = null,
        subtitleTracks: List<Track> = emptyList(),
        audioTracks: List<Track> = emptyList(),
    ) : this(
        videoUrl = videoUrl ?: "null",
        videoTitle = quality,
        headers = headers,
        subtitleTracks = subtitleTracks,
        audioTracks = audioTracks,
    ) {
        legacyVideoPageUrl = url
    }

    fun copyForProxy(
        videoUrl: String,
        headers: Headers?,
        subtitleTracks: List<Track>,
        audioTracks: List<Track>,
    ): Video =
        copy(
            videoUrl = videoUrl,
            headers = headers,
            subtitleTracks = subtitleTracks,
            audioTracks = audioTracks,
        ).also { it.legacyVideoPageUrl = legacyVideoPageUrl }

    @Suppress("UNUSED_PARAMETER")
    constructor(
        url: String,
        quality: String,
        videoUrl: String?,
        uri: Uri? = null,
        headers: Headers? = null,
    ) : this(url, quality, videoUrl, headers)

    @Transient
    @Volatile
    var status: Int = 0
        set(value) {
            field = value
            statusSubject?.onNext(value)
            statusCallback?.invoke(this)
        }

    @Transient
    @Volatile
    var progress: Int = 0
        set(value) {
            progressSubject?.onNext(value)
            field = value
            statusCallback?.invoke(this)
        }

    @Transient
    @Volatile
    var totalBytesDownloaded: Long = 0L

    @Transient
    @Volatile
    var totalContentLength: Long = 0L

    @Transient
    @Volatile
    var bytesDownloaded: Long = 0L
        set(value) {
            totalBytesDownloaded +=
                if (value < field) {
                    value
                } else {
                    value - field
                }
            field = value
            statusCallback?.invoke(this)
        }

    @Transient
    private var statusSubject: Subject<Int, Int>? = null

    @Transient
    private var progressSubject: Subject<Int, Int>? = null

    @Transient
    private var statusCallback: ((Video) -> Unit)? = null

    override fun update(
        bytesRead: Long,
        contentLength: Long,
        done: Boolean,
    ) {
        bytesDownloaded = bytesRead
        if (contentLength > totalContentLength) {
            totalContentLength = contentLength
        }
        val newProgress =
            if (totalContentLength > 0) {
                (100 * totalBytesDownloaded / totalContentLength).toInt()
            } else {
                -1
            }
        if (progress != newProgress) progress = newProgress
    }

    fun setStatusSubject(subject: Subject<Int, Int>?) {
        this.statusSubject = subject
    }

    fun setProgressSubject(subject: Subject<Int, Int>?) {
        this.progressSubject = subject
    }

    fun setStatusCallback(f: ((Video) -> Unit)?) {
        statusCallback = f
    }

    companion object {
        const val MPV_ARGS_TAG = "ANIYOMI_MPV_ARGS"
        const val QUEUE = 0
        const val LOAD_VIDEO = 1
        const val DOWNLOAD_IMAGE = 2
        const val READY = 3
        const val ERROR = 4
    }
}
