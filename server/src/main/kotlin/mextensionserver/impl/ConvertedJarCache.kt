package mextensionserver.impl

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Keeps converted extension JARs between server launches.
 *
 * Converting an APK (dex2jar, bytecode repair, asset copying) is most of what
 * loading an extension costs, and the server loses every loaded extension
 * when it stops. A client that refreshes its installed extensions on each
 * launch therefore paid for converting all of them again every time: about
 * 30 seconds for 59 APKs. The result depends only on the APK and on this
 * server's code and Java runtime, so it is kept under a key made of all
 * three.
 */
internal class ConvertedJarCache(
    root: File,
    revision: String,
    javaVersion: String = System.getProperty("java.specification.version") ?: "unknown",
    private val maxAgeMillis: Long = TimeUnit.DAYS.toMillis(30),
) {
    private val logger = KotlinLogging.logger {}
    private val parent = File(root, "extension-jars")
    private val directory = File(parent, "$revision-java$javaVersion".replace(Regex("[^A-Za-z0-9._-]"), "_"))

    init {
        try {
            directory.mkdirs()
            // Another revision's JARs are never read again.
            parent.listFiles()?.filter { it != directory }?.forEach { it.deleteRecursively() }
            val oldest = System.currentTimeMillis() - maxAgeMillis
            directory.listFiles()?.filter { it.lastModified() < oldest }?.forEach { it.delete() }
        } catch (error: Exception) {
            logger.warn(error) { "Could not tidy the converted extension cache in $parent" }
        }
    }

    /**
     * Writes the JAR for the APK with digest [sha256] to [target], from the
     * cache when it holds one, otherwise by running [convert] on [target] and
     * keeping a copy.
     */
    fun materialize(
        sha256: String,
        target: File,
        convert: (File) -> Unit,
    ) {
        val cached = File(directory, "$sha256.jar")
        if (cached.isFile) {
            try {
                Files.copy(cached.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                cached.setLastModified(System.currentTimeMillis())
                return
            } catch (error: Exception) {
                logger.warn(error) { "Converting again: could not read $cached" }
                cached.delete()
            }
        }
        convert(target)
        try {
            // Copied beside the entry and renamed, so a half-written JAR is
            // never mistaken for a converted one.
            val partial = File(directory, "$sha256.${UUID.randomUUID()}.partial")
            Files.copy(target.toPath(), partial.toPath())
            if (!partial.renameTo(cached)) partial.delete()
        } catch (error: Exception) {
            logger.warn(error) { "Could not keep the converted JAR for $sha256" }
        }
    }
}
