package mextensionserver

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import android.os.Looper
import eu.kanade.tachiyomi.App
import io.github.oshai.kotlinlogging.KotlinLogging
import mextensionserver.controller.MExtensionServerController
import mextensionserver.ui.ServerWindow
import org.kodein.di.DI
import org.kodein.di.conf.global
import xyz.nulldev.androidcompat.AndroidCompat
import xyz.nulldev.androidcompat.AndroidCompatInitializer
import xyz.nulldev.ts.config.ConfigKodeinModule
import java.net.CookieHandler
import java.net.CookieManager
import javax.swing.SwingUtilities

private val logger = KotlinLogging.logger {}
private val androidCompat by lazy { AndroidCompat() }

@Suppress("BlockingMethodInNonBlockingContext")
fun main(args: Array<String>) {
    val useUI = "--ui" in args
    val filteredArgs = args.filter { it != "--ui" }
    val port = filteredArgs.getOrNull(0)?.toIntOrNull() ?: 0
    val appDir = filteredArgs.getOrNull(1)

    if (useUI) {
        // Must be set before any AWT/Swing initialisation
        System.setProperty("apple.awt.application.name", "MExtension Server")
        System.setProperty("apple.laf.useScreenMenuBar", "true")
    }

    CookieHandler.setDefault(CookieManager())
    initApplication(appDir)

    if (useUI) {
        // Show the Swing window; the AWT event-dispatch thread (non-daemon)
        // keeps the JVM alive until the window is closed.
        SwingUtilities.invokeLater { ServerWindow().show() }
    } else {
        val controller = MExtensionServerController()
        controller.start(port)
        Runtime.getRuntime().addShutdownHook(Thread { controller.stop() })
        // Keep running
        while (controller.isRunning()) {
            Thread.sleep(1000)
        }
    }
}

private fun initApplication(appDir: String?) {
    logger.info("Running MExtensionServer ${BuildConfig.VERSION} revision ${BuildConfig.REVISION}")

    // Initialize the main Looper for the application thread.
    // This is required for extensions that use Dispatchers.Main from coroutines,
    // which attempts to resolve the Android main handler via Looper.getMainLooper().
    // Without this, extensions that use `withContext(Dispatchers.Main)` will fail.
    try {
        Looper.prepareMainLooper()
    } catch (e: IllegalStateException) {
        // If main looper is already prepared, continue without error
        logger.debug { "Main Looper already initialized: ${e.message}" }
    }

    // Set custom app directory if provided
    appDir?.let { System.setProperty("ts.server.rootDir", it) }

    // Load config API
    DI.global.addImport(ConfigKodeinModule().create())
    // Load Android compatibility dependencies
    AndroidCompatInitializer().init()
    // start app
    androidCompat.startApp(App())
}
