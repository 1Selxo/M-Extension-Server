package mextensionserver

import ca.gosyer.appdirs.AppDirs
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * Chooses where extension settings live.
 *
 * The settings code reads its root from [ROOT_DIR_PROPERTY] and otherwise
 * falls back to Suwayomi's "Tachidesk" data folder. With Suwayomi installed,
 * both apps then read and write the same per-source settings files. This
 * server keeps its own folder instead, and copies the old settings once so
 * extension logins survive the move.
 */
internal object DataDirectory {
    const val ROOT_DIR_PROPERTY = "suwayomi.tachidesk.config.server.rootDir"
    private const val SETTINGS = "settings"
    private val logger = KotlinLogging.logger {}

    fun configure(appDir: String?) {
        // An explicit property, for example from a test, wins.
        if (System.getProperty(ROOT_DIR_PROPERTY) != null) return
        val root = appDir ?: AppDirs { appName = "MExtensionServer" }.getUserDataDir()
        val legacyRoot = AppDirs { appName = "Tachidesk" }.getUserDataDir()
        try {
            copyLegacySettings(File(legacyRoot), File(root))
        } catch (error: Exception) {
            logger.warn(error) { "Could not copy extension settings from $legacyRoot" }
        }
        System.setProperty(ROOT_DIR_PROPERTY, root)
    }

    /** Copies [legacyRoot]'s settings into [root] unless [root] already has some. */
    internal fun copyLegacySettings(
        legacyRoot: File,
        root: File,
    ) {
        val source = File(legacyRoot, SETTINGS)
        val target = File(root, SETTINGS)
        if (target.exists() || !source.isDirectory) return
        if (legacyRoot.canonicalFile == root.canonicalFile) return
        // Copy beside the target and rename, so an interrupted copy is retried
        // on the next start instead of leaving a partial settings folder.
        val staging = File(root, "$SETTINGS.copying")
        staging.deleteRecursively()
        source.copyRecursively(staging)
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            error("Could not move copied settings into $target")
        }
        logger.info { "Copied extension settings from $source" }
    }
}
