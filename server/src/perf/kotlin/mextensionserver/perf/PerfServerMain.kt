package mextensionserver.perf

import mextensionserver.EmbeddedBridge
import mextensionserver.impl.MExtensionServerLoader
import mextensionserver.impl.MihonImageProxy
import mextensionserver.impl.MihonVideoProxy
import kotlin.system.exitProcess

/**
 * Server process started by [main] in PerfHarness.kt.
 *
 * It starts the bridge the way the iOS host does, through [EmbeddedBridge],
 * prints the port, then follows simple commands on stdin:
 * `reset` forgets loaded extensions so the next request converts the APK
 * again, and `quit` stops the process.
 */
object PerfServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val appDir = args[0]
        val port = EmbeddedBridge.start(0, appDir)
        println("$PORT_PREFIX$port")
        System.out.flush()
        val input = System.`in`.bufferedReader()
        while (true) {
            when (input.readLine()?.trim()) {
                null, "quit" -> {
                    EmbeddedBridge.stop()
                    exitProcess(0)
                }
                "reset" -> {
                    MExtensionServerLoader.cleanupTempFiles()
                    MihonImageProxy.configure(port)
                    MihonVideoProxy.configure(port)
                    System.gc()
                    println(OK)
                    System.out.flush()
                }
            }
        }
    }

    const val PORT_PREFIX = "PERF_PORT "
    const val OK = "PERF_OK"
}
