package eu.kanade.tachiyomi.network.interceptor

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import io.roastedroot.quickjs4j.core.Engine
import io.roastedroot.quickjs4j.core.Runner
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal interface MangaFireTokenProvider {
    fun tokenFor(
        url: HttpUrl,
        forceRefresh: Boolean = false,
    ): String?
}

internal class MangaFireProtectionInterceptor(
    private val tokenProvider: MangaFireTokenProvider = MangaFireSiteTokenProvider(),
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!isProtectedMangaFireRequest(original)) {
            return chain.proceed(original)
        }

        val firstToken = runCatching { tokenProvider.tokenFor(original.url) }.getOrNull()
        val firstResponse = chain.proceed(original.withMangaFireToken(firstToken))
        if (!firstResponse.isMissingMangaFireToken()) {
            return firstResponse
        }

        val refreshedToken =
            runCatching {
                tokenProvider.tokenFor(original.url, forceRefresh = true)
            }.getOrNull()
        if (refreshedToken.isNullOrEmpty() || refreshedToken == firstToken) {
            return firstResponse
        }

        firstResponse.close()
        return chain.proceed(original.withMangaFireToken(refreshedToken))
    }

    private fun isProtectedMangaFireRequest(request: Request): Boolean =
        request.method == "GET" &&
            request.url.host.equals(MANGAFIRE_HOST, ignoreCase = true) &&
            request.url.encodedPath.startsWith("/api/")

    private fun Request.withMangaFireToken(token: String?): Request {
        if (token.isNullOrEmpty()) return this
        val protectedUrl =
            url
                .newBuilder()
                .setQueryParameter(MANGAFIRE_TOKEN_PARAMETER, token)
                .build()
        return newBuilder().url(protectedUrl).build()
    }

    private fun Response.isMissingMangaFireToken(): Boolean =
        code == 403 &&
            peekBody(512).string().contains("Missing token", ignoreCase = true)
}

internal class MangaFireSiteTokenProvider(
    private val pageLoader: (HttpUrl) -> String = ::loadMangaFirePage,
    private val sessionFactory: (MangaFireBootstrap) -> MangaFireTokenSession = ::QuickJsMangaFireTokenSession,
) : MangaFireTokenProvider {
    private val logger = KotlinLogging.logger {}
    private var session: MangaFireTokenSession? = null
    private val tokenCache =
        object : LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_CACHED_TOKENS
        }

    @Synchronized
    override fun tokenFor(
        url: HttpUrl,
        forceRefresh: Boolean,
    ): String? {
        if (forceRefresh) clearSession()

        val key =
            url
                .newBuilder()
                .removeAllQueryParameters(MANGAFIRE_TOKEN_PARAMETER)
                .build()
                .toString()
        tokenCache[key]?.let { return it }

        return runCatching {
            val activeSession = session ?: createSession().also { session = it }
            activeSession.tokenFor(url)?.also { tokenCache[key] = it }
        }.onFailure { error ->
            logger.warn(error) { "Unable to generate MangaFire API token" }
            clearSession()
        }.getOrNull()
    }

    private fun createSession(): MangaFireTokenSession {
        val homepageUrl = MANGAFIRE_HOMEPAGE.toHttpUrl()
        val homepage = pageLoader(homepageUrl)
        val config =
            MANGAFIRE_CONFIG_PATTERN
                .find(homepage)
                ?.groupValues
                ?.get(2)
                ?: throw IOException("MangaFire configuration was not found")
        val build =
            MANGAFIRE_BUILD_PATTERN
                .find(homepage)
                ?.groupValues
                ?.get(2)
                .orEmpty()
        val scriptLocation =
            MANGAFIRE_SCRIPT_PATTERN
                .find(homepage)
                ?.groupValues
                ?.get(2)
                ?: throw IOException("MangaFire protection script was not found")
        val scriptUrl =
            homepageUrl.resolve(scriptLocation)
                ?: throw IOException("Invalid MangaFire protection script URL")

        return sessionFactory(
            MangaFireBootstrap(
                config = config,
                build = build,
                script = pageLoader(scriptUrl),
            ),
        )
    }

    private fun clearSession() {
        tokenCache.clear()
        session?.close()
        session = null
    }
}

internal data class MangaFireBootstrap(
    val config: String,
    val build: String,
    val script: String,
)

internal interface MangaFireTokenSession : AutoCloseable {
    fun tokenFor(url: HttpUrl): String?
}

internal class QuickJsMangaFireTokenSession(
    bootstrap: MangaFireBootstrap,
) : MangaFireTokenSession {
    private val objectMapper = jacksonObjectMapper()
    private val runner = Runner.builder().withEngine(Engine.builder().build()).build()

    init {
        try {
            runner.compileAndExec(bootstrapScript(bootstrap))
        } catch (error: Throwable) {
            runner.close()
            throw error
        }
    }

    override fun tokenFor(url: HttpUrl): String? {
        val parameters = linkedMapOf<String, Any>()
        for (name in url.queryParameterNames) {
            if (name == MANGAFIRE_TOKEN_PARAMETER) continue
            val values = url.queryParameterValues(name).map { it.orEmpty() }
            parameters[name] = if (values.size == 1) values.first() else values
        }

        val pathJson = objectMapper.writeValueAsString(url.encodedPath)
        val parametersJson = objectMapper.writeValueAsString(parameters)
        runner.compileAndExec(
            "console.log('$MANGAFIRE_RESULT_MARKER' + " +
                "JSON.stringify(globalThis.__mangatanMangaFireToken($pathJson, $parametersJson)));",
        )
        val encodedResult =
            runner
                .stdout()
                .lineSequence()
                .lastOrNull { it.startsWith(MANGAFIRE_RESULT_MARKER) }
                ?.removePrefix(MANGAFIRE_RESULT_MARKER)
                ?: throw IOException("MangaFire token script returned no result: ${runner.stderr()}")
        return objectMapper.readValue(encodedResult, String::class.java).ifEmpty { null }
    }

    override fun close() {
        runner.close()
    }

    private fun bootstrapScript(bootstrap: MangaFireBootstrap): String {
        val configJson = objectMapper.writeValueAsString(bootstrap.config)
        val buildJson = objectMapper.writeValueAsString(bootstrap.build)
        val siteScript = bootstrap.script.replace(MANGAFIRE_EXPORT_PATTERN, "")
        return """
            globalThis.window = globalThis;
            globalThis.navigator = { appCodeName: "Mozilla", userAgent: "Mozilla/5.0", language: "en-US", languages: ["en-US"] };
            globalThis.location = { origin: "https://mangafire.to", host: "mangafire.to", hostname: "mangafire.to", href: "https://mangafire.to/", protocol: "https:", pathname: "/", reload: function(){}, replace: function(){} };
            const __mangatanBase64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
            globalThis.btoa = function(input) {
              let output = "";
              for (let i = 0; i < input.length; i += 3) {
                const a = input.charCodeAt(i) & 255;
                const b = i + 1 < input.length ? input.charCodeAt(i + 1) & 255 : 0;
                const c = i + 2 < input.length ? input.charCodeAt(i + 2) & 255 : 0;
                output += __mangatanBase64Chars[a >> 2] + __mangatanBase64Chars[((a & 3) << 4) | (b >> 4)] + (i + 1 < input.length ? __mangatanBase64Chars[((b & 15) << 2) | (c >> 6)] : "=") + (i + 2 < input.length ? __mangatanBase64Chars[c & 63] : "=");
              }
              return output;
            };
            globalThis.atob = function(input) {
              input = input.replace(/[^A-Za-z0-9+\/=]/g, "");
              let output = "", bits = 0, value = 0;
              for (let i = 0; i < input.length && input[i] !== "="; i++) {
                const index = __mangatanBase64Chars.indexOf(input[i]);
                if (index < 0) continue;
                value = (value << 6) | index;
                bits += 6;
                if (bits >= 8) {
                  bits -= 8;
                  output += String.fromCharCode((value >> bits) & 255);
                }
              }
              return output;
            };
            globalThis.setTimeout = function(){ return 0; };
            globalThis.clearTimeout = function(){};
            globalThis.setInterval = function(){ return 0; };
            globalThis.clearInterval = function(){};
            globalThis.addEventListener = function(){};
            globalThis.removeEventListener = function(){};
            globalThis.localStorage = { getItem: function(){ return null; }, setItem: function(){}, removeItem: function(){}, clear: function(){} };
            globalThis.sessionStorage = globalThis.localStorage;
            const __mangatanElement = { style: {}, appendChild: function(){}, remove: function(){}, setAttribute: function(){}, addEventListener: function(){}, contentWindow: {} };
            globalThis.document = {
              cookie: "", location: globalThis.location, referrer: "https://mangafire.to/", domain: "mangafire.to", readyState: "complete",
              body: __mangatanElement, head: __mangatanElement, documentElement: __mangatanElement,
              createElement: function(){ return __mangatanElement; }, querySelector: function(){ return null; }, querySelectorAll: function(){ return []; },
              addEventListener: function(){}, removeEventListener: function(){}, getElementById: function(){ return null; }
            };
            window.__config = $configJson;
            window.__build = $buildJson;
            $siteScript
            globalThis.__mangatanMangaFireToken = function(path, parameters) {
              return globalThis.getProtectionToken(path, parameters) || "";
            };
            """.trimIndent()
    }
}

private fun loadMangaFirePage(url: HttpUrl): String {
    val request =
        Request
            .Builder()
            .url(url)
            .header("User-Agent", MANGAFIRE_BROWSER_USER_AGENT)
            .build()
    return MANGAFIRE_BOOTSTRAP_CLIENT.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code} while loading $url")
        }
        response.body.string()
    }
}

private const val MANGAFIRE_HOST = "mangafire.to"
private const val MANGAFIRE_HOMEPAGE = "https://mangafire.to/"
private const val MANGAFIRE_TOKEN_PARAMETER = "vrf"
private const val MANGAFIRE_RESULT_MARKER = "__MANGATAN_MANGAFIRE_TOKEN__"
private const val MAX_CACHED_TOKENS = 128
private const val MANGAFIRE_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

private val MANGAFIRE_BOOTSTRAP_CLIENT =
    OkHttpClient
        .Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
private val MANGAFIRE_CONFIG_PATTERN = Regex("""window\.__config\s*=\s*(["'])(.*?)\1""")
private val MANGAFIRE_BUILD_PATTERN = Regex("""window\.__build\s*=\s*(["'])(.*?)\1""")
private val MANGAFIRE_SCRIPT_PATTERN =
    Regex("""(?:src|href)\s*=\s*(["'])([^"']*polyfill-[^"']*\.js[^"']*)\1""")
private val MANGAFIRE_EXPORT_PATTERN = Regex("""export\{[^}]+};?\s*$""")
