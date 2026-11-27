import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper
import org.objectweb.asm.commons.SimpleRemapper
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

buildscript {
    repositories { maven("https://maven-central.storage-download.googleapis.com/maven2/") }
    dependencies { classpath("org.ow2.asm:asm-commons:9.10.1") }
}

val skikoRemapped: Attribute<Boolean> = Attribute.of("org.polyfrost.compose.skiko-remapped", Boolean::class.javaObjectType)

val skikoRemapping = mapOf(
    "androidx/compose/ui/graphics/SkiaGraphicsContext" to "org/polyfrost/compose/skiko/SkiaGraphicsContext",
    "androidx/compose/ui/text/platform/FontLoader" to "org/polyfrost/compose/skiko/FontLoader",
)

val skikoUnreachable = setOf(
    "androidx/compose/ui/graphics/DesktopImageConverters_desktopKt",
    "androidx/compose/ui/graphics/Rects_skikoKt",
    "androidx/compose/ui/graphics/SkiaBackedCanvas_skikoKt",
    "androidx/compose/ui/graphics/SkiaBackedPaint_skikoKt",
    "androidx/compose/ui/graphics/SkiaImageAsset_skikoKt",
    "androidx/compose/ui/platform/ComposeUiSkikoRuntime_nonAndroidKt",
)

extra["skikoRemappedAttribute"] = skikoRemapped
extra["skikoRemapping"] = skikoRemapping
extra["skikoUnreachable"] = skikoUnreachable

abstract class RemapSkikoReferences : TransformAction<RemapSkikoReferences.Parameters> {
    interface Parameters : TransformParameters {
        @get:Input
        val mapping: MapProperty<String, String>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val file = input.get().asFile
        val mapping = parameters.mapping.get()
        val needles = mapping.keys.flatMap { listOf(it, it.replace('/', '.')) }.map { it.toByteArray() }
        val rewritten = HashMap<String, ByteArray>()
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".class")) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (needles.none { bytes.contains(it) }) continue
                val remapped = remap(bytes, mapping)
                val left = needles.firstOrNull { remapped.contains(it) }
                check(left == null) {
                    "${file.name}!/${entry.name} still references ${String(left!!)} after the Skiko remap. " +
                        "It is probably a string constant or reflection the remapper cannot rewrite."
                }
                rewritten[entry.name] = remapped
            }
        }
        if (rewritten.isEmpty()) {
            outputs.file(input)
            return
        }
        val output = outputs.file(file.name)
        ZipFile(file).use { zip ->
            ZipOutputStream(output.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    val copy = ZipEntry(entry.name)
                    copy.time = entry.time
                    out.putNextEntry(copy)
                    val bytes = rewritten[entry.name]
                    if (bytes != null) out.write(bytes) else zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
    }

    private fun remap(bytes: ByteArray, mapping: Map<String, String>): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(0)
        reader.accept(KotlinMetadataRemapper(ClassRemapper(writer, SimpleRemapper(Opcodes.ASM9, mapping)), mapping), 0)
        return writer.toByteArray()
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        val last = size - needle.size
        outer@ for (i in 0..last) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private class KotlinMetadataRemapper(next: ClassVisitor, private val mapping: Map<String, String>) : ClassVisitor(Opcodes.ASM9, next) {
        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            val visitor = super.visitAnnotation(descriptor, visible)
            if (descriptor != "Lkotlin/Metadata;" || visitor == null) return visitor
            return object : AnnotationVisitor(Opcodes.ASM9, visitor) {
                override fun visitArray(name: String?): AnnotationVisitor? {
                    val array = super.visitArray(name)
                    if (name != "d2" || array == null) return array
                    return object : AnnotationVisitor(Opcodes.ASM9, array) {
                        override fun visit(name: String?, value: Any?) {
                            super.visit(name, if (value is String) remapString(value) else value)
                        }
                    }
                }
            }
        }

        private fun remapString(value: String): String {
            mapping[value]?.let { return it }
            var result = value
            for ((from, to) in mapping) result = result.replace("L$from;", "L$to;")
            return result
        }
    }
}

dependencies {
    attributesSchema { attribute(skikoRemapped) }
    artifactTypes.maybeCreate(ArtifactTypeDefinition.JAR_TYPE).attributes.attribute(skikoRemapped, false)
    registerTransform(RemapSkikoReferences::class) {
        from.attribute(skikoRemapped, false).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
        to.attribute(skikoRemapped, true).attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
        parameters { mapping.set(skikoRemapping) }
    }
}

configurations.matching { it.name == "runtimeClasspath" || it.name == "testRuntimeClasspath" }.configureEach {
    attributes.attribute(skikoRemapped, true)
}

abstract class VerifySkikoRemap : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bundled: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val skikoUi: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val targets: ConfigurableFileCollection

    @get:Input
    abstract val mapping: MapProperty<String, String>

    @get:Input
    abstract val unreachable: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    private class Shape(val superName: String?, val interfaces: List<String>, val members: Set<String>)

    @TaskAction
    fun verify() {
        val mapping = mapping.get()
        val targetNames = mapping.values.toSet()
        val unreachable = unreachable.get()
        val skikoClasses = HashSet<String>()
        skikoUi.forEach { jar -> forEachClass(jar) { name, _ -> skikoClasses += name.removeSuffix(".class") } }
        val targetShapes = HashMap<String, Shape>()
        targets.forEach { jar -> forEachClass(jar) { _, bytes -> readShape(bytes, targetShapes) } }
        val pool = HashMap<String, Shape>(targetShapes)
        val unmapped = sortedMapOf<String, MutableSet<String>>()
        val leftovers = sortedSetOf<String>()
        val memberRefs = sortedSetOf<String>()
        val seenTargets = HashSet<String>()
        val seenUnreachable = sortedSetOf<String>()
        bundled.forEach { jar ->
            forEachClass(jar) { entry, bytes ->
                readShape(bytes, pool)
                val recorder = object : Remapper(Opcodes.ASM9) {
                    override fun map(internalName: String): String {
                        when (internalName) {
                            in mapping -> leftovers += "${jar.name}!/$entry -> $internalName"
                            in targetNames -> seenTargets += internalName
                            in unreachable -> seenUnreachable += internalName
                            in skikoClasses -> unmapped.getOrPut(internalName) { sortedSetOf() } += "${jar.name}!/$entry"
                        }
                        return internalName
                    }

                    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
                        if (owner in targetNames) memberRefs += "$owner $name:$descriptor"
                        return name
                    }

                    override fun mapFieldName(owner: String, name: String, descriptor: String): String {
                        if (owner in targetNames) memberRefs += "$owner $name:$descriptor"
                        return name
                    }
                }
                ClassReader(bytes).accept(ClassRemapper(ClassWriter(0), recorder), 0)
            }
        }

        val problems = ArrayList<String>()
        leftovers.forEach { problems += "Not remapped: $it" }
        unmapped.forEach { (name, users) ->
            problems += "$name comes from the excluded ui-skiko jar and has no target. Used by ${users.take(3)}. " +
                "Add a stand-in to core and a mapping entry, or list it as unreachable in skiko-remap.gradle.kts."
        }
        for (target in targetNames) {
            when {
                target !in targetShapes -> problems += "Remap target $target has no class in core."
                target !in seenTargets -> problems += "Remap target $target is no longer referenced by the bundle; drop its mapping and stand-in."
            }
        }
        for (ref in memberRefs) {
            val (owner, member) = ref.split(' ', limit = 2)
            if (owner in targetShapes && !resolves(pool, owner, member)) problems += "Core's $owner lacks $member, which the bundle calls."
        }
        check(problems.isEmpty()) { "Skiko remap check failed:\n" + problems.joinToString("\n") { "  $it" } }
        report.get().asFile.writeText(
            (memberRefs.map { "uses $it" } + seenUnreachable.map { "unreachable $it" }).joinToString("\n", postfix = "\n"),
        )
    }

    private fun resolves(pool: Map<String, Shape>, owner: String, member: String): Boolean {
        val shape = pool[owner] ?: return !member.startsWith("<init>:")
        if (member in shape.members) return true
        if (member.startsWith("<init>:")) return false
        return (listOfNotNull(shape.superName) + shape.interfaces).any { resolves(pool, it, member) }
    }

    private fun readShape(bytes: ByteArray, into: MutableMap<String, Shape>) {
        var name = ""
        var superName: String? = null
        var interfaces = emptyList<String>()
        val members = HashSet<String>()
        ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(version: Int, access: Int, className: String, signature: String?, superClass: String?, superInterfaces: Array<out String>?) {
                name = className
                superName = superClass
                interfaces = superInterfaces?.toList().orEmpty()
            }

            override fun visitMethod(access: Int, methodName: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                members += "$methodName:$descriptor"
                return null
            }

            override fun visitField(access: Int, fieldName: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                members += "$fieldName:$descriptor"
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        into.putIfAbsent(name, Shape(superName, interfaces, members))
    }

    private fun forEachClass(jar: File, block: (String, ByteArray) -> Unit) {
        ZipFile(jar).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/") || entry.name.endsWith("module-info.class")) continue
                block(entry.name, zip.getInputStream(entry).use { it.readBytes() })
            }
        }
    }
}

extra["registerSkikoRemapCheck"] = { bundled: Configuration, targets: Any ->
    val skikoUiJars = configurations.create("skikoUi") {
        isCanBeConsumed = false
        isTransitive = false
    }
    val skikoTargetJars = configurations.create("skikoTargets") {
        isCanBeConsumed = false
        isTransitive = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        }
    }
    dependencies.add(skikoUiJars.name, "org.jetbrains.compose.ui:ui-skiko-desktop:${property("deps.compose")}")
    dependencies.add(skikoTargetJars.name, targets)
    tasks.register<VerifySkikoRemap>("verifySkikoRemap") {
        group = "verification"
        description = "Checks that the bundled Compose jars only reach ui-skiko classes through core's stand-ins."
        this.bundled.from(bundled)
        skikoUi.from(skikoUiJars)
        this.targets.from(skikoTargetJars)
        mapping.set(skikoRemapping)
        unreachable.set(skikoUnreachable)
        report = layout.buildDirectory.file("$name/report.txt")
    }
}
