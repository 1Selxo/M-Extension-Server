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

    @Test
    fun `repairs allocation from interface argument type`() {
        val jar = Files.createTempFile("mextension-bytecode-interface", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.writeClass(RUNNABLE_CLASS, interfaceImplementation(RUNNABLE_CLASS, "java/lang/Runnable", "run"))
                output.writeClass(
                    CLOSEABLE_CLASS,
                    interfaceImplementation(CLOSEABLE_CLASS, "java/lang/AutoCloseable", "close"),
                )
                output.writeClass(INTERFACE_FACTORY_CLASS, interfaceFactoryClass())
            }

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val runnableClass = loader.loadClass(RUNNABLE_CLASS.replace('/', '.'))
                val factoryClass = loader.loadClass(INTERFACE_FACTORY_CLASS.replace('/', '.'))
                val value = factoryClass.getMethod("create").invoke(null)

                assertTrue(runnableClass.isInstance(value))
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `repairs sole subclass allocation of abstract class`() {
        val jar = Files.createTempFile("mextension-bytecode-abstract", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.writeClass(ABSTRACT_CLASS, abstractClass())
                output.writeClass(CONCRETE_CLASS, concreteClass())
                output.writeClass(ABSTRACT_FACTORY_CLASS, abstractFactoryClass())
            }

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val concreteClass = loader.loadClass(CONCRETE_CLASS.replace('/', '.'))
                val factoryClass = loader.loadClass(ABSTRACT_FACTORY_CLASS.replace('/', '.'))
                val value = factoryClass.getMethod("create").invoke(null)

                assertTrue(concreteClass.isInstance(value))
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `repairs constructor calls that skip an abstract superclass`() {
        val jar = Files.createTempFile("mextension-bytecode-ancestor-constructor", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.writeClass(EMPTY_ABSTRACT_CLASS, emptyAbstractClass())
                output.writeClass(ANCESTOR_CALL_CLASS, ancestorCallClass())
            }

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val repaired = loader.loadClass(ANCESTOR_CALL_CLASS.replace('/', '.'))
                assertEquals(repaired, repaired.getConstructor().newInstance().javaClass)
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `repairs sole exception subclass allocation before throw`() {
        val jar = Files.createTempFile("mextension-bytecode-exception", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.writeClass(EXCEPTION_CLASS, exceptionClass())
                output.writeClass(EXCEPTION_FACTORY_CLASS, exceptionFactoryClass())
            }

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val exceptionClass = loader.loadClass(EXCEPTION_CLASS.replace('/', '.'))
                val factoryClass = loader.loadClass(EXCEPTION_FACTORY_CLASS.replace('/', '.'))
                val error = runCatching { factoryClass.getMethod("fail").invoke(null) }.exceptionOrNull()
                val directError = runCatching { factoryClass.getMethod("failDirect").invoke(null) }.exceptionOrNull()

                assertTrue(exceptionClass.isInstance(error?.cause))
                assertEquals("expected", error?.cause?.message)
                assertTrue(exceptionClass.isInstance(directError?.cause))
                assertEquals("direct", directError?.cause?.message)
            }
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    fun `repairs interface method constant pool references`() {
        val jar = Files.createTempFile("mextension-bytecode-interface-method", ".jar")
        try {
            JarOutputStream(Files.newOutputStream(jar)).use { output ->
                output.writeClass(SERIALIZER_INTERFACE, serializerInterface())
                output.writeClass(SERIALIZER_IMPLEMENTATION, serializerImplementation())
                output.writeClass(SERIALIZER_CALLER, serializerCaller())
            }

            BytecodeEditor.fixAndroidClasses(jar)

            URLClassLoader(arrayOf(jar.toUri().toURL()), javaClass.classLoader).use { loader ->
                val interfaceClass = loader.loadClass(SERIALIZER_INTERFACE.replace('/', '.'))
                val implementationClass = loader.loadClass(SERIALIZER_IMPLEMENTATION.replace('/', '.'))
                val callerClass = loader.loadClass(SERIALIZER_CALLER.replace('/', '.'))
                val implementation = implementationClass.getConstructor().newInstance()
                val value = callerClass.getMethod("call", interfaceClass).invoke(null, implementation)

                assertEquals("repaired", value)
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

    private fun interfaceImplementation(
        className: String,
        interfaceName: String,
        methodName: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            className,
            null,
            "java/lang/Object",
            arrayOf(interfaceName),
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun interfaceFactoryClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            INTERFACE_FACTORY_CLASS,
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitField(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "instance",
                "Ljava/lang/Runnable;",
                null,
                null,
            ).visitEnd()
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "accept",
                "(Ljava/lang/Runnable;)Ljava/lang/Runnable;",
                null,
                null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "create",
                "()Ljava/lang/Runnable;",
                null,
                null,
            ).apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "java/lang/Object")
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitVarInsn(Opcodes.ASTORE, 0)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitFieldInsn(
                    Opcodes.PUTSTATIC,
                    INTERFACE_FACTORY_CLASS,
                    "instance",
                    "Ljava/lang/Runnable;",
                )
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    INTERFACE_FACTORY_CLASS,
                    "accept",
                    "(Ljava/lang/Runnable;)Ljava/lang/Runnable;",
                    false,
                )
                visitInsn(Opcodes.ARETURN)
                visitMaxs(2, 1)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun abstractClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SUPER,
            ABSTRACT_CLASS,
            null,
            "java/lang/Object",
            null,
        )
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

    private fun emptyAbstractClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_SUPER,
            EMPTY_ABSTRACT_CLASS,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun ancestorCallClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            ANCESTOR_CALL_CLASS,
            null,
            EMPTY_ABSTRACT_CLASS,
            null,
        )
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

    private fun concreteClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            CONCRETE_CLASS,
            null,
            ABSTRACT_CLASS,
            null,
        )
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun abstractFactoryClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            ABSTRACT_FACTORY_CLASS,
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "create",
                "()L$ABSTRACT_CLASS;",
                null,
                null,
            ).apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, ABSTRACT_CLASS)
                visitInsn(Opcodes.DUP)
                visitMethodInsn(Opcodes.INVOKESPECIAL, ABSTRACT_CLASS, "<init>", "()V", false)
                visitInsn(Opcodes.ARETURN)
                visitMaxs(2, 0)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun exceptionClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            EXCEPTION_CLASS,
            null,
            "java/io/IOException",
            null,
        )
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun exceptionFactoryClass(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            EXCEPTION_FACTORY_CLASS,
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "fail",
                "()V",
                null,
                arrayOf("java/io/IOException"),
            ).apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, "java/io/IOException")
                visitInsn(Opcodes.DUP)
                visitLdcInsn("expected")
                visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "java/io/IOException",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false,
                )
                visitInsn(Opcodes.ATHROW)
                visitMaxs(3, 0)
                visitEnd()
            }
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "failDirect",
                "()V",
                null,
                arrayOf("java/io/IOException"),
            ).apply {
                visitCode()
                visitTypeInsn(Opcodes.NEW, EXCEPTION_CLASS)
                visitVarInsn(Opcodes.ASTORE, 0)
                visitVarInsn(Opcodes.ALOAD, 0)
                visitLdcInsn("direct")
                visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    "java/io/IOException",
                    "<init>",
                    "(Ljava/lang/String;)V",
                    false,
                )
                visitVarInsn(Opcodes.ALOAD, 0)
                visitInsn(Opcodes.ATHROW)
                visitMaxs(2, 1)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun serializerInterface(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            SERIALIZER_INTERFACE,
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
                "value",
                "()Ljava/lang/String;",
                null,
                null,
            ).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun serializerImplementation(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            SERIALIZER_IMPLEMENTATION,
            null,
            "java/lang/Object",
            arrayOf(SERIALIZER_INTERFACE),
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()Ljava/lang/String;", null, null).apply {
            visitCode()
            visitLdcInsn("repaired")
            visitInsn(Opcodes.ARETURN)
            visitMaxs(1, 1)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun serializerCaller(): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
            SERIALIZER_CALLER,
            null,
            "java/lang/Object",
            null,
        )
        writer
            .visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "call",
                "(L$SERIALIZER_INTERFACE;)Ljava/lang/String;",
                null,
                null,
            ).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                // Dex2jar occasionally emits invokeinterface with a Methodref
                // constant instead of the required InterfaceMethodref.
                visitMethodInsn(
                    Opcodes.INVOKEINTERFACE,
                    SERIALIZER_INTERFACE,
                    "value",
                    "()Ljava/lang/String;",
                    false,
                )
                visitInsn(Opcodes.ARETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private companion object {
        const val BASE_CLASS = "mextensionserver/test/GeneratedBase"
        const val DERIVED_CLASS = "mextensionserver/test/GeneratedDerived"
        const val FACTORY_CLASS = "mextensionserver/test/GeneratedFactory"
        const val RUNNABLE_CLASS = "mextensionserver/test/GeneratedRunnable"
        const val CLOSEABLE_CLASS = "mextensionserver/test/GeneratedCloseable"
        const val INTERFACE_FACTORY_CLASS = "mextensionserver/test/GeneratedInterfaceFactory"
        const val ABSTRACT_CLASS = "mextensionserver/test/GeneratedAbstract"
        const val CONCRETE_CLASS = "mextensionserver/test/GeneratedConcrete"
        const val ABSTRACT_FACTORY_CLASS = "mextensionserver/test/GeneratedAbstractFactory"
        const val EMPTY_ABSTRACT_CLASS = "mextensionserver/test/GeneratedEmptyAbstract"
        const val ANCESTOR_CALL_CLASS = "mextensionserver/test/GeneratedAncestorCall"
        const val EXCEPTION_CLASS = "mextensionserver/test/GeneratedException"
        const val EXCEPTION_FACTORY_CLASS = "mextensionserver/test/GeneratedExceptionFactory"
        const val SERIALIZER_INTERFACE = "mextensionserver/test/GeneratedSerializer"
        const val SERIALIZER_IMPLEMENTATION = "mextensionserver/test/GeneratedSerializerImpl"
        const val SERIALIZER_CALLER = "mextensionserver/test/GeneratedSerializerCaller"
    }
}
