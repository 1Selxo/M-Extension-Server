package xyz.nulldev.androidcompat

/**
 * Toasts shown by extensions, waiting for the app to display them.
 *
 * Extensions report results such as a finished login only through a toast,
 * often from a background job after the request that started it returned.
 * The bridge hands queued toasts to the app on its next response.
 */
object ToastQueue {
    private const val MAX_QUEUED = 20
    private val queued = ArrayDeque<String>()

    @Synchronized
    fun add(text: String) {
        if (text.isBlank()) return
        queued.addLast(text)
        while (queued.size > MAX_QUEUED) queued.removeFirst()
    }

    @Synchronized
    fun drain(): List<String> = queued.toList().also { queued.clear() }
}
