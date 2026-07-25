package mextensionserver.util

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.FileSystems
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class BytecodeEditorTest {
    @Test
    fun `repairs missing frames and dex constructors`() {
        val jar = Files.createTempFile("bytecode-editor-test", ".jar")
        Files.delete(jar)
        try {
            FileSystems.newFileSystem(
                URI.create("jar:${jar.toUri()}"),
                mapOf("create" to "true"),
            ).use { zip ->
                Files.write(zip.getPath("/a.class"), dexStyleFunction())
                Files.write(zip.getPath("/VerifierFixture.class"), verifierFixture())
            }

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val fixture = Class.forName("VerifierFixture", true, loader)
                val result = fixture.getMethod("create", Boolean::class.javaPrimitiveType).invoke(null, false)
                assertEquals("a", result.javaClass.name)
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    private fun dexStyleFunction(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "a",
            null,
            "java/lang/Object",
            arrayOf("kotlin/jvm/functions/Function2"),
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC,
                "invoke",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                null,
                null,
            ).apply {
                visitCode()
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 3)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun verifierFixture(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            "VerifierFixture",
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "create",
                "(Z)Ljava/lang/Object;",
                null,
                null,
            ).apply {
                val construct = org.objectweb.asm.Label()
                visitCode()
                visitVarInsn(Opcodes.ILOAD, 0)
                visitJumpInsn(Opcodes.IFEQ, construct)
                visitInsn(Opcodes.ACONST_NULL)
                visitInsn(Opcodes.ARETURN)
                visitLabel(construct)
                visitTypeInsn(Opcodes.NEW, "a")
                visitVarInsn(Opcodes.ASTORE, 1)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 2)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
