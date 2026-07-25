package mextensionserver.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BytecodeEditorTest {
    @Test
    fun `ignores classes outside the Android replacement scope`() {
        val unrelatedClassBytes =
            byteArrayOf(
                0xca.toByte(),
                0xfe.toByte(),
                0xba.toByte(),
                0xbe.toByte(),
            ) + "eu/kanade/tachiyomi/extension/ExtensionGenerated".toByteArray()

        assertFalse(BytecodeEditor.requiresAndroidClassReplacement(unrelatedClassBytes))
    }

    @Test
    fun `detects classes that reference a replacement type`() {
        val classBytes = "Ljava/text/SimpleDateFormat;".toByteArray()

        assertTrue(BytecodeEditor.requiresAndroidClassReplacement(classBytes))
    }
}
