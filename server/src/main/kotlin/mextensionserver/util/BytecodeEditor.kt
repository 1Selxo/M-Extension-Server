package mextensionserver.util

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import io.github.oshai.kotlinlogging.KotlinLogging
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.ArrayDeque
import kotlin.streams.asSequence

object BytecodeEditor {
    private val logger = KotlinLogging.logger {}

    /**
     * Replace some java class references inside a jar with new ones that behave like Androids
     *
     * @param jarFile The JarFile to replace class references in
     */
    fun fixAndroidClasses(jarFile: Path) {
        FileSystems.newFileSystem(jarFile, null as ClassLoader?)?.use {
            val classes =
                Files
                    .walk(it.getPath("/"))
                    .asSequence()
                    .filterNotNull()
                    .filterNot(Files::isDirectory)
                    .mapNotNull(::getClassBytes)
                    .toList()
            val hierarchy = ClassHierarchy(classes.map(Pair<Path, ByteArray>::second))
            classes
                .asSequence()
                .map { classFile -> transform(classFile, hierarchy) }
                .forEach(::write)
        }
    }

    /**
     * Get class bytes from a [Path]
     *
     * @param path The path entry to get the class bytes from
     *
     * @return [Pair] of the [Path] plus the class [ByteArray], or null if it's not a valid class
     */
    private fun getClassBytes(path: Path): Pair<Path, ByteArray>? {
        return try {
            if (path.toString().endsWith(".class")) {
                val bytes = Files.readAllBytes(path)
                if (bytes.size < 4) {
                    // Invalid class size
                    return null
                }
                val cafebabe =
                    String.format(
                        "%02X%02X%02X%02X",
                        bytes[0],
                        bytes[1],
                        bytes[2],
                        bytes[3],
                    )
                if (cafebabe.lowercase() != "cafebabe") {
                    // Corrupted class
                    return null
                }

                path to bytes
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error(e) { "Error loading class from Path: $path" }
            null
        }
    }

    /**
     * The path where replacement classes will reside
     */
    private const val REPLACEMENT_PATH = "xyz/nulldev/androidcompat/replace"

    /**
     * List of classes that will be replaced
     */
    private val classesToReplace =
        listOf(
            "java/text/SimpleDateFormat",
        )

    /**
     * Replace direct references to the class, used on places
     * that don't have any other text then the class
     *
     * @return [String] of class or null if [String] was null
     */
    private fun String?.replaceDirectly() =
        when (this) {
            null -> null
            in classesToReplace -> "$REPLACEMENT_PATH/$this"
            else -> this
        }

    /**
     * Replace references to the class, used in places that have
     * other text around the class references
     *
     * @return [String] with class references replaced, or null if [String] was null
     */
    private fun String?.replaceIndirectly(): String? {
        if (this == null) return null
        var classReference: String = this
        classesToReplace.forEach {
            classReference = classReference.replace(it, "$REPLACEMENT_PATH/$it")
        }
        return classReference
    }

    /**
     * Replace all references to certain classes inside the class file
     * with ones that behave more like Androids
     *
     * @param pair Class bytecode to load into ASM for ease of modification
     *
     * @return [ByteArray] with modified bytecode
     */
    private fun transform(
        pair: Pair<Path, ByteArray>,
        hierarchy: ClassHierarchy,
    ): Pair<Path, ByteArray> {
        // Read the class and prepare to modify it
        val cr = ClassReader(pair.second)
        // dex2jar output can have missing or stale StackMapTable entries. Rebuild
        // them using actual class hierarchy metadata. Returning Object for every
        // merge corrupts uninitialized constructor values in obfuscated classes.
        val cw =
            object : ClassWriter(cr, ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS) {
                override fun getCommonSuperClass(
                    type1: String,
                    type2: String,
                ): String = hierarchy.commonSuperClass(type1, type2)
            }
        val needsConstructor = hierarchy.needsSyntheticConstructor(cr.className)
        // Modify the class
        cr.accept(
            object : ClassVisitor(Opcodes.ASM9, cw) {
                // Modify field descriptor, for example
                // class MangaYes {
                //     val format = SimpleDateFormat("YYYY-MM-dd")
                // }
                override fun visitField(
                    access: Int,
                    name: String?,
                    desc: String?,
                    signature: String?,
                    cst: Any?,
                ): FieldVisitor? {
                    logger.trace { "CLass Field" to "${desc.replaceIndirectly()}: ${cst?.let { it::class.java.simpleName }}: $cst" }
                    return super.visitField(access, name, desc.replaceIndirectly(), signature, cst)
                }

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String?,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?,
                ) {
                    logger.trace { "Visiting $name: $signature: $superName" }
                    super.visit(version, access, name, signature, superName, interfaces)
                }

                // Modify method bytecode, for example
                // class MangaYes {
                //     fun fetchChapterList() {
                //         SimpleDateFormat("YYYY-MM-dd")
                //     }
                // }
                override fun visitMethod(
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    exceptions: Array<String?>?,
                ): MethodVisitor {
                    logger.trace { "Processing method $name: ${desc.replaceIndirectly()}: $signature" }
                    val mv: MethodVisitor? =
                        super.visitMethod(
                            access,
                            name,
                            desc.replaceIndirectly(),
                            signature,
                            exceptions,
                        )
                    return object : MethodVisitor(Opcodes.ASM9, mv) {
                        private val pendingConstructions = ArrayDeque<String>()

                        override fun visitLdcInsn(cst: Any?) {
                            logger.trace { "Ldc" to "${cst?.let { "${it::class.java.simpleName}: $it" }}" }
                            super.visitLdcInsn(cst)
                        }

                        // Replace method type, for example
                        // val format = DateFormat()
                        // fun fetchChapterList() {
                        //     if (format is SimpleDateFormat)
                        // }
                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String?,
                        ) {
                            val replacementType = type.replaceDirectly()
                            logger.trace {
                                "Type" to "$opcode: $replacementType"
                            }
                            if (opcode == Opcodes.NEW && replacementType != null) {
                                pendingConstructions.addLast(replacementType)
                            }
                            super.visitTypeInsn(
                                opcode,
                                replacementType,
                            )
                        }

                        // Replace method field, for example
                        // fun fetchChapterList() {
                        //     val format = SimpleDateFormat("YYYY-MM-dd")
                        // }
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                            itf: Boolean,
                        ) {
                            var replacementOwner = owner.replaceDirectly()
                            if (opcode == Opcodes.INVOKESPECIAL && name == "<init>" && pendingConstructions.isNotEmpty()) {
                                val constructedType = pendingConstructions.last()
                                if (replacementOwner == constructedType) {
                                    pendingConstructions.removeLast()
                                } else if (
                                    desc == "()V" &&
                                    hierarchy.needsSyntheticConstructor(constructedType) &&
                                    replacementOwner == hierarchy.superName(constructedType)
                                ) {
                                    // dex2jar can emit `new Child` followed by
                                    // `Object.<init>` and omit Child's trivial
                                    // constructor. That is legal in DEX but not
                                    // in JVM bytecode.
                                    replacementOwner = constructedType
                                    pendingConstructions.removeLast()
                                }
                            }
                            logger.trace {
                                "Method" to "$opcode: $replacementOwner: $name: ${desc.replaceIndirectly()}"
                            }
                            super.visitMethodInsn(
                                opcode,
                                replacementOwner,
                                name,
                                desc.replaceIndirectly(),
                                itf,
                            )
                        }

                        // Replace class field call from method, for example
                        // val format = SimpleDateFormat("YYYY-MM-dd")
                        // fun fetchChapterList() {
                        //     format.format(Date())
                        // }
                        override fun visitFieldInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            desc: String?,
                        ) {
                            logger.trace { "Field" to "$opcode: $owner: $name: ${desc.replaceIndirectly()}" }
                            super.visitFieldInsn(opcode, owner, name, desc.replaceIndirectly())
                        }

                        override fun visitInvokeDynamicInsn(
                            name: String?,
                            desc: String?,
                            bsm: Handle?,
                            vararg bsmArgs: Any?,
                        ) {
                            logger.trace { "InvokeDynamic" to "$name: $desc" }
                            super.visitInvokeDynamicInsn(name, desc, bsm, *bsmArgs)
                        }
                    }
                }

                override fun visitEnd() {
                    if (needsConstructor) {
                        visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)?.apply {
                            visitCode()
                            visitVarInsn(Opcodes.ALOAD, 0)
                            visitMethodInsn(
                                Opcodes.INVOKESPECIAL,
                                hierarchy.superName(cr.className),
                                "<init>",
                                "()V",
                                false,
                            )
                            visitInsn(Opcodes.RETURN)
                            visitMaxs(0, 0)
                            visitEnd()
                        }
                    }
                    super.visitEnd()
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        return pair.first to cw.toByteArray()
    }

    private fun write(pair: Pair<Path, ByteArray>) {
        Files.write(
            pair.first,
            pair.second,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private data class ClassInfo(
        val superName: String?,
        val interfaces: List<String>,
        val isInterface: Boolean,
        val hasConstructor: Boolean,
    )

    /**
     * Resolves hierarchy information from class bytes instead of loading classes.
     * Loading dex2jar output would itself trigger verification before it is fixed.
     */
    private class ClassHierarchy(
        classBytes: List<ByteArray>,
    ) {
        private val classes = mutableMapOf<String, ClassInfo?>()
        private val classLoader = BytecodeEditor::class.java.classLoader

        init {
            classBytes.forEach { bytes ->
                val reader = ClassReader(bytes)
                classes[reader.className] = reader.toClassInfo()
            }
        }

        fun commonSuperClass(
            type1: String,
            type2: String,
        ): String {
            if (type1 == type2) return type1
            if (type1.startsWith("[") || type2.startsWith("[")) {
                return commonArrayType(type1, type2)
            }
            if (isAssignableFrom(type1, type2)) return type1
            if (isAssignableFrom(type2, type1)) return type2
            if (classInfo(type1)?.isInterface == true || classInfo(type2)?.isInterface == true) {
                return OBJECT
            }

            var current = classInfo(type1)?.superName
            while (current != null) {
                if (isAssignableFrom(current, type2)) return current
                current = classInfo(current)?.superName
            }
            return OBJECT
        }

        fun superName(name: String): String? = classInfo(name)?.superName

        fun needsSyntheticConstructor(name: String): Boolean {
            val info = classInfo(name) ?: return false
            return !info.isInterface && !info.hasConstructor && info.superName == OBJECT
        }

        private fun commonArrayType(
            type1: String,
            type2: String,
        ): String {
            if (!type1.startsWith("[") || !type2.startsWith("[")) return OBJECT
            if (type1 == type2) return type1

            val element1 = type1.removePrefix("[")
            val element2 = type2.removePrefix("[")
            if (!element1.startsWith("L") || !element2.startsWith("L")) return OBJECT

            val common =
                commonSuperClass(
                    element1.removeSurrounding("L", ";"),
                    element2.removeSurrounding("L", ";"),
                )
            return "[L$common;"
        }

        private fun isAssignableFrom(
            target: String,
            source: String,
            visited: MutableSet<String> = mutableSetOf(),
        ): Boolean {
            if (target == source || target == OBJECT) return true
            if (!visited.add(source)) return false
            val sourceInfo = classInfo(source) ?: return false
            return sourceInfo.superName?.let { isAssignableFrom(target, it, visited) } == true ||
                sourceInfo.interfaces.any { isAssignableFrom(target, it, visited) }
        }

        private fun classInfo(name: String): ClassInfo? {
            if (classes.containsKey(name)) return classes[name]
            val info =
                classLoader
                    .getResourceAsStream("$name.class")
                    ?.use { ClassReader(it).toClassInfo() }
            classes[name] = info
            return info
        }

        private fun ClassReader.toClassInfo(): ClassInfo {
            var hasConstructor = false
            accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitMethod(
                        access: Int,
                        name: String?,
                        descriptor: String?,
                        signature: String?,
                        exceptions: Array<out String>?,
                    ): MethodVisitor? {
                        if (name == "<init>") hasConstructor = true
                        return null
                    }
                },
                ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
            )
            return ClassInfo(
                superName = superName,
                interfaces = interfaces.toList(),
                isInterface = access and Opcodes.ACC_INTERFACE != 0,
                hasConstructor = hasConstructor,
            )
        }

        private companion object {
            const val OBJECT = "java/lang/Object"
        }
    }
}
