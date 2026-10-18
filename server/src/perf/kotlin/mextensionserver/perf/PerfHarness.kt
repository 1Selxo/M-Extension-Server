package mextensionserver.perf

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.ceil

/*
 * Performance harness for the extension server. See docs/performance.md.
 *
 * This process is the client. It always runs on a normal JIT JVM, together
 * with the fake upstream site, so neither is slowed down by the mode under
 * test. The server runs in a child JVM, optionally with -Xint to approximate
 * the iOS OpenJDK Zero interpreter.
 */

private val mapper = jacksonObjectMapper()

private data class Options(
    val mode: String,
    val out: File,
    val iterations: Int,
    val warmup: Int,
    val latencyMs: Long,
    val startupRuns: Int,
    val installRuns: Int,
    val dump: File?,
    val jfr: Boolean,
    val only: Set<String>?,
    val serverJvmArgs: List<String>,
) {
    val interpreted get() = mode == "int"

    companion object {
        fun parse(args: Array<String>): Options {
            val values =
                args.associate { argument ->
                    val trimmed = argument.removePrefix("--")
                    trimmed.substringBefore('=') to trimmed.substringAfter('=', "true")
                }
            val mode = values["mode"] ?: "jit"
            require(mode == "jit" || mode == "int") { "--mode must be jit or int" }
            val interpreted = mode == "int"
            return Options(
                mode = mode,
                out = File(values["out"] ?: "server/build/perf"),
                iterations = values["iterations"]?.toInt() ?: if (interpreted) 10 else 40,
                warmup = values["warmup"]?.toInt() ?: if (interpreted) 3 else 15,
                latencyMs = values["latencyMs"]?.toLong() ?: 10,
                startupRuns = values["startupRuns"]?.toInt() ?: if (interpreted) 3 else 5,
                installRuns = values["installRuns"]?.toInt() ?: if (interpreted) 4 else 8,
                dump = values["dump"]?.takeIf(String::isNotBlank)?.let(::File),
                jfr = values["jfr"]?.toBoolean() ?: false,
                only =
                    values["only"]
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        ?.toSet(),
                // Extra server JVM flags separated by '|', e.g. "-Xlog:class+load".
                serverJvmArgs =
                    values["serverJvmArgs"]
                        ?.split('|')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        .orEmpty(),
            )
        }
    }
}

private class Stats(
    samplesNanos: List<Long>,
) {
    val samples = samplesNanos.map { it / 1_000_000.0 }.sorted()
    val median: Double get() = percentile(50.0)
    val p90: Double get() = percentile(90.0)
    val min: Double get() = samples.first()

    /** Nearest-rank percentile. */
    fun percentile(percent: Double): Double {
        val rank = ceil(percent / 100.0 * samples.size).toInt().coerceIn(1, samples.size)
        return samples[rank - 1]
    }
}

private class ServerProcess(
    options: Options,
    upstream: String,
    root: File,
    logName: String,
) : AutoCloseable {
    val process: Process
    val startedAt: Long = System.nanoTime()
    private val lines = LinkedBlockingQueue<String>()
    private val log = File(options.out, logName)

    init {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse("java")
        val command =
            buildList {
                add(java)
                if (options.interpreted) add("-Xint")
                if (options.jfr) {
                    // Deep stacks: conversion and class loading nest far below the request frame.
                    add("-XX:FlightRecorderOptions:stackdepth=1024")
                    add(
                        "-XX:StartFlightRecording=filename=${File(
                            options.out,
                            "server-${options.mode}.jfr",
                        ).path},settings=profile,jdk.ExecutionSample#period=1ms",
                    )
                }
                addAll(options.serverJvmArgs)
                add("-Dsuwayomi.tachidesk.config.server.rootDir=${root.path}")
                add("-Dperf.upstream=$upstream")
                add("-cp")
                add(System.getProperty("java.class.path"))
                add(PerfServerMain::class.java.name)
                add(root.path)
            }
        process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
        Thread({
            log.bufferedWriter().use { writer ->
                process.inputStream.bufferedReader().forEachLine { line ->
                    writer.appendLine(line)
                    writer.flush()
                    if (line.startsWith(PerfServerMain.PORT_PREFIX) || line == PerfServerMain.OK) lines.put(line)
                }
            }
        }, "server-output").apply {
            isDaemon = true
            start()
        }
    }

    fun awaitPort(): Int {
        val line = awaitLine { it.startsWith(PerfServerMain.PORT_PREFIX) }
        return line.removePrefix(PerfServerMain.PORT_PREFIX).trim().toInt()
    }

    fun reset() {
        process.outputStream.write("reset\n".toByteArray())
        process.outputStream.flush()
        awaitLine { it == PerfServerMain.OK }
    }

    private fun awaitLine(predicate: (String) -> Boolean): String {
        while (true) {
            val line =
                lines.poll(10, TimeUnit.MINUTES)
                    ?: error("Server did not respond; see ${log.path}")
            if (predicate(line)) return line
        }
    }

    override fun close() {
        runCatching {
            process.outputStream.write("quit\n".toByteArray())
            process.outputStream.flush()
        }
        if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}

private class Client(
    val port: Int,
) {
    private val http =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(30))
            .build()

    data class Reply(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        val text: String get() = body.toString(Charsets.UTF_8)

        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    }

    fun get(path: String): Reply = send(request(path).GET().build())

    fun dalvik(body: String): Reply =
        send(
            request("/dalvik")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Perf Harness)")
                .header("Cookie", "session=perf; theme=dark")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )

    private fun request(path: String) =
        HttpRequest
            .newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofMinutes(10))
            // The Flutter client (dart:io) always accepts gzip.
            .header("Accept-Encoding", "gzip")

    private fun send(request: HttpRequest): Reply {
        val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        val headers = response.headers().map().mapKeys { it.key.lowercase() }
        val raw = response.body()
        val body =
            if (headers["content-encoding"]?.firstOrNull() == "gzip") {
                GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            } else {
                raw
            }
        return Reply(response.statusCode(), headers, body)
    }
}

private class Benchmark(
    val name: String,
    val call: () -> Client.Reply,
)

private data class Result(
    val name: String,
    val stats: Stats,
    val upstreamPerCall: Double,
    val latencyMs: Long,
) {
    val processingMedian get() = stats.median - upstreamPerCall * latencyMs
}

fun main(args: Array<String>) {
    val options = Options.parse(args)
    options.out.mkdirs()
    val apk =
        requireNotNull(Options::class.java.getResourceAsStream("/perf-extension.apk")) {
            "perf-extension.apk is missing; run ./gradlew :server:perfFixtureApk"
        }.use { it.readBytes() }
    val apkBase64 = Base64.getEncoder().encodeToString(apk)

    println(
        "Performance harness: mode=${options.mode}, latency=${options.latencyMs} ms, warmup=${options.warmup}, iterations=${options.iterations}",
    )
    FakeUpstream(options.latencyMs).use { upstream ->
        val startup = measureStartup(options, upstream, apkBase64)
        val root = freshRoot(options, "root-${options.mode}")
        ServerProcess(options, upstream.baseUrl, root, "server-${options.mode}.log").use { server ->
            val client = Client(server.awaitPort())
            check(client.get("/capabilities").status == 200)

            val install = measureInstall(options, server, client, apkBase64)
            val results = measureEndpoints(options, client, upstream, install.extensionId)
            report(options, startup, install, results)
        }
    }
}

private fun freshRoot(
    options: Options,
    name: String,
): File =
    File(options.out, name).apply {
        deleteRecursively()
        mkdirs()
    }

/** Cold-start timings, one sample per fresh server process. */
private class Startup(
    /** Process spawn to the first 200 from /capabilities. */
    val toFirstRequest: Stats,
    /** Process spawn to the first extension response (capabilities, then the APK request). */
    val toFirstExtensionResponse: Stats,
    /** The APK request alone, in the same cold processes. */
    val coldInstall: Stats,
)

private fun measureStartup(
    options: Options,
    upstream: FakeUpstream,
    apkBase64: String,
): Startup? {
    if (options.startupRuns <= 0 || options.only?.contains("startup") == false) return null
    val body = installBody(apkBase64)
    val samples =
        (1..options.startupRuns).map { run ->
            val root = freshRoot(options, "startup-${options.mode}")
            ServerProcess(options, upstream.baseUrl, root, "startup-${options.mode}-$run.log").use { server ->
                val client = Client(server.awaitPort())
                val reply = client.get("/capabilities")
                val toCapabilities = System.nanoTime() - server.startedAt
                check(reply.status == 200) { "capabilities failed during startup run $run" }
                val installStart = System.nanoTime()
                val install = client.dalvik(body)
                val end = System.nanoTime()
                check(install.status == 200) { "install failed during startup run $run: ${install.status}" }
                Triple(toCapabilities, end - server.startedAt, end - installStart)
            }
        }
    return Startup(
        toFirstRequest = Stats(samples.map { it.first }),
        toFirstExtensionResponse = Stats(samples.map { it.second }),
        coldInstall = Stats(samples.map { it.third }),
    )
}

private fun installBody(apkBase64: String): String =
    mapper.writeValueAsString(mapOf("method" to "extensionInfo", "preferences" to emptyList<Any>(), "data" to apkBase64))

private class Install(
    /** The very first request: APK conversion plus first use of every server path. */
    val firstMs: Double,
    /** Later conversions of the same APK after the loaded instance was dropped. */
    val later: Stats?,
    val extensionId: String,
)

/** Measures the first request carrying the APK, which converts and loads it. */
private fun measureInstall(
    options: Options,
    server: ServerProcess,
    client: Client,
    apkBase64: String,
): Install {
    val body = installBody(apkBase64)
    var extensionId = ""
    val runs = options.installRuns.coerceAtLeast(1)
    val samples =
        (0 until runs).map { run ->
            if (run > 0) server.reset()
            val start = System.nanoTime()
            val reply = client.dalvik(body)
            val elapsed = System.nanoTime() - start
            check(reply.status == 200) { "install failed: ${reply.status} ${reply.text}" }
            extensionId = requireNotNull(reply.header("X-Mangatan-Extension-Id"))
            dump(options, "install-extensionInfo", reply)
            elapsed
        }
    return Install(
        firstMs = samples.first() / 1_000_000.0,
        later = samples.drop(1).takeIf { it.isNotEmpty() }?.let(::Stats),
        extensionId = extensionId,
    )
}

private fun measureEndpoints(
    options: Options,
    client: Client,
    upstream: FakeUpstream,
    extensionId: String,
): List<Result> {
    fun body(
        method: String,
        preferences: List<Any>,
        extra: Map<String, Any?> = emptyMap(),
    ): String =
        mapper.writeValueAsString(
            linkedMapOf<String, Any?>("method" to method).apply {
                putAll(extra)
                put("preferences", preferences)
                put("extensionId", extensionId)
            },
        )

    // Discover the source and its preferences the way the app does.
    val sources = mapper.readTree(client.dalvik(body("sourcesManga", emptyList())).text)
    val sourceId = sources[0]["id"].asText()
    val bridgeContext = mapOf("key" to "__mangatan_bridge_context__", "sourceId" to sourceId)
    val storedPreferences =
        mapper.readValue(
            client.dalvik(body("preferencesManga", listOf(bridgeContext))).text,
            List::class.java,
        )
    val preferences: List<Any> = storedPreferences.filterNotNull() + bridgeContext

    val filterList =
        listOf(
            mapOf("name" to "Status", "type" to "SelectFilter", "stateInt" to 1),
            mapOf(
                "name" to "Genres",
                "type" to "GroupFilter",
                "stateList" to
                    listOf(
                        mapOf("name" to "Action", "type" to "TriStateFilter", "stateInt" to 1),
                        mapOf("name" to "Horror", "type" to "TriStateFilter", "stateInt" to 2),
                    ),
            ),
            mapOf("name" to "Include adult titles", "type" to "CheckBoxFilter", "stateList" to listOf(mapOf("stateBoolean" to true))),
            mapOf("name" to "Author", "type" to "TextFilter", "stateString" to "Author Name"),
            mapOf("name" to "Order by", "type" to "SortFilter", "stateSort" to mapOf("index" to 2, "ascending" to true)),
        )
    val mangaData = mapOf("url" to "/manga/popular-1-0")
    val chapterData = mapOf("url" to "/chapter/popular-1-0-150", "name" to "Chapter 150")

    val popularBody = body("getPopularManga", preferences, mapOf("page" to 1, "search" to ""))
    val pageListBody = body("getPageList", preferences, mapOf("chapterData" to chapterData))

    // Proxy tokens for the image benchmarks.
    val posterPaths =
        mapper
            .readTree(client.dalvik(popularBody).text)["mangas"]
            .map { URI(it["thumbnail_url"].asText()).path }
    val pagePaths =
        mapper
            .readTree(client.dalvik(pageListBody).text)
            .map { URI(it["imageUrl"].asText()).path }
    var posterIndex = 0
    var pageIndex = 0

    val benchmarks =
        listOf(
            Benchmark("health") { client.get("/") },
            Benchmark("capabilities") { client.get("/capabilities") },
            Benchmark("extensionInfo") { client.dalvik(body("extensionInfo", preferences)) },
            Benchmark("sourcesManga") { client.dalvik(body("sourcesManga", preferences)) },
            Benchmark("headersManga") { client.dalvik(body("headersManga", preferences)) },
            Benchmark("filtersManga") { client.dalvik(body("filtersManga", preferences)) },
            Benchmark("supportLatestManga") { client.dalvik(body("supportLatestManga", preferences)) },
            Benchmark("preferencesManga") { client.dalvik(body("preferencesManga", preferences)) },
            Benchmark("getPopularManga") { client.dalvik(popularBody) },
            Benchmark("getLatestManga") { client.dalvik(body("getLatestManga", preferences, mapOf("page" to 2, "search" to ""))) },
            Benchmark("getSearchManga") {
                client.dalvik(body("getSearchManga", preferences, mapOf("page" to 1, "search" to "hero", "filterList" to filterList)))
            },
            Benchmark("getDetailsManga") { client.dalvik(body("getDetailsManga", preferences, mapOf("mangaData" to mangaData))) },
            Benchmark("getChapterList") { client.dalvik(body("getChapterList", preferences, mapOf("mangaData" to mangaData))) },
            Benchmark("getPageList") { client.dalvik(pageListBody) },
            Benchmark("imagePage") { client.get(pagePaths[pageIndex++ % pagePaths.size]) },
            Benchmark("imagePoster") { client.get(posterPaths[posterIndex++ % posterPaths.size]) },
        ).filter { options.only == null || it.name in options.only }

    return benchmarks.map { benchmark ->
        repeat(options.warmup) { checkOk(benchmark.name, benchmark.call()) }
        val upstreamBefore = upstream.requestCount
        var last: Client.Reply? = null
        val samples =
            (1..options.iterations).map {
                val start = System.nanoTime()
                val reply = benchmark.call()
                val elapsed = System.nanoTime() - start
                checkOk(benchmark.name, reply)
                last = reply
                elapsed
            }
        val upstreamCalls = (upstream.requestCount - upstreamBefore).toDouble() / options.iterations
        last?.let { dump(options, benchmark.name, it) }
        Result(benchmark.name, Stats(samples), upstreamCalls, options.latencyMs).also {
            println(
                "  %-20s median %9.2f ms  p90 %9.2f ms  upstream/call %.1f".format(
                    it.name,
                    it.stats.median,
                    it.stats.p90,
                    it.upstreamPerCall,
                ),
            )
        }
    }
}

private fun checkOk(
    name: String,
    reply: Client.Reply,
) = check(reply.status == 200) { "$name failed with HTTP ${reply.status}: ${reply.text.take(500)}" }

/**
 * Writes a normalized copy of a response for byte-level equivalence checks
 * between two builds. Proxy tokens are random, so they are renumbered by
 * first appearance; the loopback port is replaced too.
 */
private fun dump(
    options: Options,
    name: String,
    reply: Client.Reply,
) {
    val directory = options.dump ?: return
    directory.mkdirs()
    val headers =
        reply.headers
            .filterKeys { it != "date" && it != "content-length" && it != "transfer-encoding" }
            .toSortedMap()
            .map { (key, values) -> "$key: ${values.joinToString(", ")}" }
    val contentType = reply.header("content-type").orEmpty()
    val body =
        if (contentType.startsWith("image/") || contentType.startsWith("application/octet-stream")) {
            "sha256=" + MessageDigest.getInstance("SHA-256").digest(reply.body).joinToString("") { "%02x".format(it) } +
                " bytes=${reply.body.size}"
        } else {
            normalize(reply.text)
        }
    File(directory, "$name.txt").writeText(
        buildString {
            appendLine("status: ${reply.status}")
            headers.forEach(::appendLine)
            appendLine()
            append(body)
        },
    )
}

private fun normalize(text: String): String {
    val tokens = linkedMapOf<String, Int>()
    return Regex("""http://127\.0\.0\.1:\d+/(image|video)/([0-9a-f-]{36})""")
        .replace(text) { match ->
            val index = tokens.getOrPut(match.groupValues[2]) { tokens.size }
            "http://127.0.0.1:<port>/${match.groupValues[1]}/<token-$index>"
        }.replace(Regex("""http://127\.0\.0\.1:\d+"""), "http://127.0.0.1:<port>")
}

private fun report(
    options: Options,
    startup: Startup?,
    install: Install,
    results: List<Result>,
) {
    val json: ObjectNode = mapper.createObjectNode()
    json.put("mode", options.mode)
    json.put("java", System.getProperty("java.version"))
    json.put("latencyMs", options.latencyMs)
    json.put("warmup", options.warmup)
    json.put("iterations", options.iterations)

    fun ObjectNode.putStats(stats: Stats) {
        put("medianMs", stats.median)
        put("p90Ms", stats.p90)
        put("minMs", stats.min)
        putArray("samplesMs").also { array -> stats.samples.forEach(array::add) }
    }
    startup?.let {
        json.putObject("startupToFirstRequest").putStats(it.toFirstRequest)
        json.putObject("startupToFirstExtensionResponse").putStats(it.toFirstExtensionResponse)
        json.putObject("coldInstall").putStats(it.coldInstall)
    }
    json.putObject("install").apply {
        put("firstMs", install.firstMs)
        install.later?.let { putObject("later").putStats(it) }
    }
    val endpoints: ArrayNode = json.putArray("endpoints")
    results.forEach { result ->
        endpoints.addObject().apply {
            put("name", result.name)
            putStats(result.stats)
            put("upstreamPerCall", result.upstreamPerCall)
            put("processingMedianMs", result.processingMedian)
        }
    }
    val file = File(options.out, "results-${options.mode}.json")
    mapper.writerWithDefaultPrettyPrinter().writeValue(file, json as JsonNode)

    println()
    println("| Benchmark (${options.mode}) | median ms | p90 ms | upstream calls | processing ms |")
    println("|---|---:|---:|---:|---:|")
    startup?.let { cold ->
        listOf(
            "startup to first request" to cold.toFirstRequest,
            "startup to first extension response" to cold.toFirstExtensionResponse,
            "install in a cold process" to cold.coldInstall,
        ).forEach { (label, stats) ->
            println("| %s | %.1f | %.1f | 0 | %.1f |".format(label, stats.median, stats.p90, stats.median))
        }
    }
    println("| install, first request (single sample) | %.1f | | 0 | %.1f |".format(install.firstMs, install.firstMs))
    install.later?.let {
        println("| install, later conversions | %.1f | %.1f | 0 | %.1f |".format(it.median, it.p90, it.median))
    }
    results.forEach {
        println(
            "| %s | %.2f | %.2f | %.1f | %.2f |".format(it.name, it.stats.median, it.stats.p90, it.upstreamPerCall, it.processingMedian),
        )
    }
    println()
    println("Results written to ${file.path}")
}
