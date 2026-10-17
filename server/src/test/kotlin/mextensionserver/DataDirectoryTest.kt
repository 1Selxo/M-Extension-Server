package mextensionserver

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DataDirectoryTest {
    @Test
    fun `legacy settings are copied once and never overwrite newer ones`() {
        val base = createTempDirectory("data-directory").toFile()
        val legacy = base.resolve("Tachidesk").apply { resolve("settings").mkdirs() }
        legacy.resolve("settings/source_1.xml").writeText("legacy")
        val root = base.resolve("MExtensionServer")

        DataDirectory.copyLegacySettings(legacy, root)
        assertEquals("legacy", root.resolve("settings/source_1.xml").readText())
        assertFalse(root.resolve("settings.copying").exists())

        root.resolve("settings/source_1.xml").writeText("newer")
        legacy.resolve("settings/source_1.xml").writeText("changed later")
        DataDirectory.copyLegacySettings(legacy, root)
        assertEquals("newer", root.resolve("settings/source_1.xml").readText())

        base.deleteRecursively()
    }
}
