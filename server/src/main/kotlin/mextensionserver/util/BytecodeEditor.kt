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
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
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
            Files.walk(it.getPath("/")).use { paths ->
                val classes =
                    paths
                        .asSequence()
                        .filterNotNull()
                        .filterNot(Files::isDirectory)
                        .mapNotNull(::getClassBytes)
                        .toList()
                val repairedClasses = repairMissingConstructors(classes)
                val hierarchy = ClassHierarchy(repairedClasses.map(Pair<Path, ByteArray>::second))
                repairedClasses
                    .asSequence()
                    .map { classBytes -> transform(classBytes, hierarchy) }
                    .forEach(::write)
            }
        }
    }

    /**
     * R8 can emit valid DEX classes without a JVM-style constructor and invoke
     * their direct superclass constructor after new-instance. Dex2jar then
     * incorrectly emits NEW for that superclass. Infer the intended subclass
     * from the following typed use, add the missing forwarding constructor,
     * and repair both instructions before JVM verification.
     */
    private fun repairMissingConstructors(classes: List<Pair<Path, ByteArray>>): List<Pair<Path, ByteArray>> {
        val parsed =
            classes.associate { (path, bytes) ->
                val node = ClassNode(Opcodes.ASM9)
                ClassReader(bytes).accept(node, 0)
                node.name to (path to node)
            }
        val missingConstructors =
            parsed.values
                .map(Pair<Path, ClassNode>::second)
                .filter {
                    it.superName != null &&
                        it.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE) == 0 &&
                        it.methods.none { method -> method.name == "<init>" }
                }.associateBy(ClassNode::name)
        val constructorsToAdd = mutableMapOf<String, MutableMap<String, String>>()

        parsed.values.forEach { (_, classNode) ->
            classNode.methods.forEach { method ->
                repairAllocations(method, missingConstructors, constructorsToAdd)
            }
        }
        constructorsToAdd.forEach { (className, constructors) ->
            val classNode = missingConstructors.getValue(className)
            constructors.forEach { (descriptor, superName) ->
                classNode.methods.add(forwardingConstructor(superName, descriptor))
            }
        }

        return parsed.values.map { (path, node) ->
            val writer = ClassWriter(0)
            node.accept(writer)
            path to writer.toByteArray()
        }
    }

    private fun repairAllocations(
        method: MethodNode,
        missingConstructors: Map<String, ClassNode>,
        constructorsToAdd: MutableMap<String, MutableMap<String, String>>,
    ) {
        val instructions = method.instructions.toArray()
        instructions.forEachIndexed { index, instruction ->
            val allocation = instruction as? TypeInsnNode ?: return@forEachIndexed
            if (allocation.opcode != Opcodes.NEW) return@forEachIndexed
            val constructorIndex =
                findMatchingConstructor(instructions, index, allocation.desc)
                    ?: return@forEachIndexed
            val constructor = instructions[constructorIndex] as MethodInsnNode
            val target =
                inferAllocatedClass(
                    instructions,
                    constructorIndex,
                    allocation.desc,
                    missingConstructors,
                ) ?: return@forEachIndexed

            logger.debug { "Repairing dex2jar allocation ${allocation.desc} -> $target in ${method.name}${method.desc}" }
            constructorsToAdd.getOrPut(target, ::mutableMapOf)[constructor.desc] = allocation.desc
            allocation.desc = target
            constructor.owner = target
        }
    }

    private fun findMatchingConstructor(
        instructions: Array<AbstractInsnNode>,
        allocationIndex: Int,
        allocatedClass: String,
    ): Int? {
        var nestedAllocations = 1
        for (index in allocationIndex + 1 until instructions.size) {
            val instruction = instructions[index]
            if (instruction is TypeInsnNode &&
                instruction.opcode == Opcodes.NEW &&
                instruction.desc == allocatedClass
            ) {
                nestedAllocations++
            } else if (instruction is MethodInsnNode &&
                instruction.opcode == Opcodes.INVOKESPECIAL &&
                instruction.name == "<init>" &&
                instruction.owner == allocatedClass
            ) {
                nestedAllocations--
                if (nestedAllocations == 0) return index
            }
        }
        return null
    }

    private fun inferAllocatedClass(
        instructions: Array<AbstractInsnNode>,
        constructorIndex: Int,
        allocatedSuperClass: String,
        missingConstructors: Map<String, ClassNode>,
    ): String? {
        val candidates = mutableSetOf<String>()
        val following =
            instructions
                .drop(constructorIndex + 1)
                .firstOrNull { it.opcode >= 0 }

        if (following is VarInsnNode && following.opcode == Opcodes.ASTORE) {
            val local = following.`var`
            val start = instructions.indexOf(following) + 1
            for (index in start until instructions.size) {
                val instruction = instructions[index]
                if (instruction is VarInsnNode &&
                    instruction.opcode == Opcodes.ASTORE &&
                    instruction.`var` == local
                ) {
                    break
                }
                if (instruction is VarInsnNode &&
                    instruction.opcode == Opcodes.ALOAD &&
                    instruction.`var` == local
                ) {
                    collectTypedUses(instructions, index + 1, local, candidates)
                }
            }
        } else {
            collectTypedUses(instructions, constructorIndex + 1, null, candidates)
        }

        return candidates
            .filter { missingConstructors[it]?.superName == allocatedSuperClass }
            .distinct()
            .singleOrNull()
    }

    private fun collectTypedUses(
        instructions: Array<AbstractInsnNode>,
        start: Int,
        local: Int?,
        candidates: MutableSet<String>,
    ) {
        var stackSize = 1
        for (index in start until minOf(instructions.size, start + 64)) {
            val instruction = instructions[index]
            if (instruction.opcode < 0) continue
            if (instruction is VarInsnNode &&
                local != null &&
                instruction.opcode == Opcodes.ALOAD &&
                instruction.`var` == local
            ) {
                break
            }
            var consumed = 0
            var produced = 0
            var preservesTrackedValue = false
            when (instruction) {
                is FieldInsnNode -> {
                    val fieldType = Type.getType(instruction.desc)
                    when (instruction.opcode) {
                        Opcodes.GETSTATIC -> produced = fieldType.size
                        Opcodes.PUTSTATIC -> {
                            consumed = fieldType.size
                            if (consumed == stackSize) {
                                fieldType.internalNameOrNull()?.let(candidates::add)
                            }
                        }
                        Opcodes.GETFIELD -> {
                            consumed = 1
                            produced = fieldType.size
                            if (consumed == stackSize) candidates.add(instruction.owner)
                        }
                        Opcodes.PUTFIELD -> {
                            consumed = 1 + fieldType.size
                            if (consumed == stackSize) candidates.add(instruction.owner)
                        }
                    }
                }
                is MethodInsnNode -> {
                    val arguments = Type.getArgumentTypes(instruction.desc)
                    consumed =
                        arguments.sumOf(Type::getSize) +
                        if (instruction.opcode == Opcodes.INVOKESTATIC) 0 else 1
                    produced = Type.getReturnType(instruction.desc).size
                    if (consumed == stackSize) {
                        if (instruction.opcode == Opcodes.INVOKESTATIC) {
                            arguments.firstOrNull()?.internalNameOrNull()?.let(candidates::add)
                        } else {
                            candidates.add(instruction.owner)
                        }
                    }
                }
                is VarInsnNode -> {
                    when (instruction.opcode) {
                        Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.ALOAD -> produced = 1
                        Opcodes.LLOAD, Opcodes.DLOAD -> produced = 2
                        Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.ASTORE -> consumed = 1
                        Opcodes.LSTORE, Opcodes.DSTORE -> consumed = 2
                        else -> break
                    }
                }
                is InsnNode -> {
                    when (instruction.opcode) {
                        in Opcodes.ACONST_NULL..Opcodes.DCONST_1 ->
                            produced =
                                if (instruction.opcode in Opcodes.LCONST_0..Opcodes.LCONST_1 ||
                                    instruction.opcode in Opcodes.DCONST_0..Opcodes.DCONST_1
                                ) {
                                    2
                                } else {
                                    1
                                }
                        Opcodes.POP -> consumed = 1
                        Opcodes.POP2 -> consumed = 2
                        Opcodes.DUP -> {
                            produced = 1
                            preservesTrackedValue = true
                        }
                        Opcodes.DUP2 -> {
                            produced = 2
                            preservesTrackedValue = true
                        }
                        in Opcodes.IRETURN..Opcodes.RETURN, Opcodes.ATHROW -> break
                        else -> break
                    }
                }
                is IntInsnNode -> {
                    when (instruction.opcode) {
                        Opcodes.BIPUSH, Opcodes.SIPUSH -> produced = 1
                        Opcodes.NEWARRAY -> {
                            consumed = 1
                            produced = 1
                        }
                        else -> break
                    }
                }
                is LdcInsnNode -> {
                    produced = if (instruction.cst is Long || instruction.cst is Double) 2 else 1
                }
                is TypeInsnNode -> {
                    when (instruction.opcode) {
                        Opcodes.NEW -> produced = 1
                        Opcodes.ANEWARRAY -> {
                            consumed = 1
                            produced = 1
                        }
                        Opcodes.CHECKCAST -> preservesTrackedValue = true
                        Opcodes.INSTANCEOF -> {
                            consumed = 1
                            produced = 1
                        }
                        else -> break
                    }
                }
                else -> break
            }
            val consumesTrackedValue = !preservesTrackedValue && consumed >= stackSize
            stackSize += produced - consumed
            if (consumesTrackedValue || stackSize <= 0) break
        }
    }

    private fun Type.internalNameOrNull(): String? =
        when (sort) {
            Type.OBJECT -> internalName
            Type.ARRAY -> descriptor
            else -> null
        }

    private fun forwardingConstructor(
        superName: String,
        descriptor: String,
    ): MethodNode {
        val constructor =
            MethodNode(
                Opcodes.ASM9,
                Opcodes.ACC_PUBLIC or Opcodes.ACC_SYNTHETIC,
                "<init>",
                descriptor,
                null,
                null,
            )
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        var local = 1
        Type.getArgumentTypes(descriptor).forEach { argument ->
            constructor.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), local)
            local += argument.size
        }
        constructor.visitMethodInsn(
            Opcodes.INVOKESPECIAL,
            superName,
            "<init>",
            descriptor,
            false,
        )
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, local)
        constructor.visitEnd()
        return constructor
    }

    private data class ClassInfo(
        val superName: String?,
        val interfaces: List<String>,
        val isInterface: Boolean,
    )

    /**
     * Resolves extension classes without loading them. ASM needs their real
     * hierarchy while computing frames; returning Object for every merge can
     * turn a valid obfuscated local variable into an invalid Object value.
     */
    private class ClassHierarchy(
        classBytes: Iterable<ByteArray>,
    ) {
        private val classes: MutableMap<String, ClassInfo> =
            classBytes
                .map(::classInfo)
                .toMap(mutableMapOf())

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
            if (resolve(type1)?.isInterface == true || resolve(type2)?.isInterface == true) {
                return OBJECT
            }

            var candidate = resolve(type1)?.superName
            while (candidate != null) {
                if (isAssignableFrom(candidate, type2)) return candidate
                candidate = resolve(candidate)?.superName
            }
            return OBJECT
        }

        private fun commonArrayType(
            type1: String,
            type2: String,
        ): String {
            if (!type1.startsWith("[") || !type2.startsWith("[")) return OBJECT
            val component1 = type1.substring(1)
            val component2 = type2.substring(1)
            if (!component1.isReferenceDescriptor() || !component2.isReferenceDescriptor()) {
                return OBJECT
            }
            return "[" + commonReferenceDescriptor(component1, component2)
        }

        private fun commonReferenceDescriptor(
            type1: String,
            type2: String,
        ): String {
            if (type1.startsWith("[") || type2.startsWith("[")) {
                return if (type1.startsWith("[") && type2.startsWith("[")) {
                    commonArrayType(type1, type2)
                } else {
                    "L$OBJECT;"
                }
            }
            return "L${commonSuperClass(type1.removeSurrounding("L", ";"), type2.removeSurrounding("L", ";"))};"
        }

        private fun isAssignableFrom(
            target: String,
            source: String,
            visited: MutableSet<String> = mutableSetOf(),
        ): Boolean {
            if (target == source || target == OBJECT) return true
            if (!visited.add(source)) return false
            val sourceInfo = resolve(source) ?: return false
            return sourceInfo.interfaces.any { isAssignableFrom(target, it, visited) } ||
                sourceInfo.superName?.let { isAssignableFrom(target, it, visited) } == true
        }

        private fun resolve(name: String): ClassInfo? {
            classes[name]?.let { return it }
            val resource = "$name.class"
            val bytes =
                BytecodeEditor::class.java.classLoader
                    ?.getResourceAsStream(resource)
                    ?.use { it.readBytes() }
                    ?: ClassLoader.getSystemResourceAsStream(resource)?.use { it.readBytes() }
                    ?: return null
            return classInfo(bytes).second.also { classes[name] = it }
        }

        companion object {
            private const val OBJECT = "java/lang/Object"

            private fun String.isReferenceDescriptor(): Boolean = startsWith("L") || startsWith("[")

            private fun classInfo(bytes: ByteArray): Pair<String, ClassInfo> {
                val reader = ClassReader(bytes)
                return reader.className to
                    ClassInfo(
                        superName = reader.superName,
                        interfaces = reader.interfaces.toList(),
                        isInterface = reader.access and Opcodes.ACC_INTERFACE != 0,
                    )
            }
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
        // dex2jar can emit stale stack-map frames for obfuscated Kotlin default
        // methods. Recompute them while rewriting Android class references so
        // the resulting JAR passes normal JVM bytecode verification.
        val cw =
            object : ClassWriter(cr, COMPUTE_FRAMES or COMPUTE_MAXS) {
                override fun getCommonSuperClass(
                    type1: String,
                    type2: String,
                ): String = hierarchy.commonSuperClass(type1, type2)
            }
        // Modify the class
        cr.accept(
            object : ClassVisitor(Opcodes.ASM5, cw) {
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
                    return object : MethodVisitor(Opcodes.ASM5, mv) {
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
                            logger.trace {
                                "Type" to "$opcode: ${type.replaceDirectly()}"
                            }
                            super.visitTypeInsn(
                                opcode,
                                type.replaceDirectly(),
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
                            logger.trace {
                                "Method" to "$opcode: ${owner.replaceDirectly()}: $name: ${desc.replaceIndirectly()}"
                            }
                            super.visitMethodInsn(
                                opcode,
                                owner.replaceDirectly(),
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
            },
            ClassReader.EXPAND_FRAMES,
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
}
