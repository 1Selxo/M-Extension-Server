package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Converts unexpected interceptor failures to IOExceptions so OkHttp callers
 * can handle them as request failures instead of fatal runtime exceptions.
 */
class UncaughtExceptionInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        try {
            chain.proceed(chain.request())
        } catch (exception: Exception) {
            if (exception is IOException) {
                throw exception
            }
            throw IOException(exception)
        }
}
