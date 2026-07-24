package mextensionserver.util

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BytecodeEditorTest {
    @Test
    fun `repairs dex2jar superclass allocations and frames`() {
        val jar = Files.createTempFile("mextension-bytecode-editor", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.writeClass(BASE_CLASS, baseClass())
                output.writeClass(DERIVED_CLASS, derivedClass())
                output.writeClass(FACTORY_CLASS, factoryClass())
            }

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val derivedClass = loader.loadClass(DERIVED_CLASS.replace('/', '.'))
                val factoryClass = loader.loadClass(FACTORY_CLASS.replace('/', '.'))
                val value = factoryClass.getMethod("create", Boolean::class.java).invoke(null, true)

                assertTrue(derivedClass.isInstance(value))
                assertEquals(true, derivedClass.getField("enabled").get(value))
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    private fun JarOutputStream.writeClass(
        name: String,
        bytes: ByteArray,
    ) {
        putNextEntry(JarEntry("$name.class"))
        write(bytes)
        closeEntry()
    }

    private fun baseClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER,
            BASE_CLASS,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitField(Opcodes.ACC_PUBLIC, "baseValue", "I", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun derivedClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            DERIVED_CLASS,
            null,
            BASE_CLASS,
            null,
        )
        writer.visitField(Opcodes.ACC_PUBLIC, "enabled", "Z", null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun factoryClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            FACTORY_CLASS,
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "create",
                "(Z)L$DERIVED_CLASS;",
                null,
                null,
            ).apply {
                val fallback = Label()
                val joined = Label()
                visitCode()

                // Shape emitted by dex2jar for DEX new-instance Derived followed
                // by invoke-direct Base.<init>: NEW incorrectly names Base.
                visitTypeInsn(Opcodes.NEW, BASE_CLASS)
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, BASE_CLASS, "<init>", "()V", false)
                visitVarInsn(Opcodes.ASTORE, 1)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitInsn(Opcodes.ICONST_1)
                visitFieldInsn(Opcodes.PUTFIELD, DERIVED_CLASS, "enabled", "Z")

                visitVarInsn(Opcodes.ILOAD, 0)
                visitJumpInsn(Opcodes.IFEQ, fallback)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitVarInsn(Opcodes.ASTORE, 2)
                visitJumpInsn(Opcodes.GOTO, joined)

                visitLabel(fallback)
                visitTypeInsn(Opcodes.NEW, BASE_CLASS)
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, BASE_CLASS, "<init>", "()V", false)
                visitVarInsn(Opcodes.ASTORE, 2)

                visitLabel(joined)
                visitVarInsn(Opcodes.ALOAD, 2)
                visitFieldInsn(Opcodes.GETFIELD, BASE_CLASS, "baseValue", "I")
                visitInsn(Opcodes.POP)
                visitVarInsn(Opcodes.ALOAD, 1)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(2, 3)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private companion object {
        const val BASE_CLASS = "mextensionserver/test/GeneratedBase"
        const val DERIVED_CLASS = "mextensionserver/test/GeneratedDerived"
        const val FACTORY_CLASS = "mextensionserver/test/GeneratedFactory"
    }
}
