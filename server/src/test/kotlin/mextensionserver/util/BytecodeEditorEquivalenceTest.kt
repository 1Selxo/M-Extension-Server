package mextensionserver.util

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the exact output of [BytecodeEditor.fixAndroidClasses] for a fixed
 * input: the raw dex2jar translation of the performance-harness fixture APK
 * (src/test/resources/perf-fixture-dex2jar.jar).
 *
 * The input is committed because dex2jar's own register allocation varies
 * between runs, so converting the APK is not byte-deterministic even without
 * any change. [EXPECTED] was recorded with BytecodeEditor as it was before
 * the performance work; every entry, its order and its bytes must stay the
 * same. ZIP timestamps are excluded because the writer uses the current time.
 */
class BytecodeEditorEquivalenceTest {
    @Test
    fun `bytecode repair output is unchanged`() {
        // Twice: the second pass runs with warm process-wide caches.
        repeat(2) { round ->
            assertEquals(EXPECTED, repairFixture(), "repair round ${round + 1}")
        }
    }

    private fun repairFixture(): String {
        val directory = Files.createTempDirectory("bytecode-equivalence").toFile()
        try {
            val jar = File(directory, "extension.jar")
            requireNotNull(javaClass.getResourceAsStream(INPUT)).use { input ->
                jar.outputStream().use(input::copyTo)
            }
            BytecodeEditor.fixAndroidClasses(jar.toPath())
            return digestEntries(jar)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun digestEntries(jar: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ZipInputStream(jar.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val bytes = if (entry.isDirectory) byteArrayOf() else input.readBytes()
                digest.update("${entry.name}\u0000${bytes.size}\u0000".toByteArray())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val INPUT = "/perf-fixture-dex2jar.jar"

        const val EXPECTED = "810c22e2f43e340dec5c883102ae81680a6aedf37067a5670d28a18bdadcaa37"
    }
}
