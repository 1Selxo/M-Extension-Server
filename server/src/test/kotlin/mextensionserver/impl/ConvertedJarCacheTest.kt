package mextensionserver.impl

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConvertedJarCacheTest {
    private val root = Files.createTempDirectory("converted-jars-").toFile()

    private fun target() = File.createTempFile("extension-", ".jar")

    @Test
    fun `a relaunched server reuses the conversion of the same APK`() {
        var conversions = 0
        val convert = { jar: File ->
            conversions++
            jar.writeText("converted")
        }

        ConvertedJarCache(root, "r1").materialize("abc", target(), convert)
        val relaunched = target()
        ConvertedJarCache(root, "r1").materialize("abc", relaunched, convert)

        assertEquals(1, conversions)
        assertEquals("converted", relaunched.readText())
    }

    @Test
    fun `another server revision or APK converts again`() {
        var conversions = 0
        val convert = { jar: File ->
            conversions++
            jar.writeText("converted $conversions")
        }

        ConvertedJarCache(root, "r1").materialize("abc", target(), convert)
        ConvertedJarCache(root, "r1").materialize("def", target(), convert)
        ConvertedJarCache(root, "r2").materialize("abc", target(), convert)

        assertEquals(3, conversions)
        assertFalse(File(root, "extension-jars/r1-java${System.getProperty("java.specification.version")}").exists())
    }

    @Test
    fun `a failed conversion keeps nothing`() {
        val cache = ConvertedJarCache(root, "r1")
        runCatching { cache.materialize("abc", target()) { error("dex2jar failed") } }
        var conversions = 0
        cache.materialize("abc", target()) { conversions++ }

        assertEquals(1, conversions)
    }
}
