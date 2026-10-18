import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jmailen.gradle.kotlinter.tasks.FormatTask
import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.io.BufferedReader

val iosRuntime = providers.gradleProperty("iosRuntime").map(String::toBoolean).getOrElse(false)

plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.shadowjar)
    alias(libs.plugins.buildconfig)
}

dependencies {
    implementation(libs.bundles.okhttp)
    implementation(libs.bundles.asm)
    implementation(libs.icu4j.charset)
    implementation(libs.quickjs4j)
    implementation(libs.newpipe.extractor)
    implementation(libs.protobuf.javalite)
    implementation(libs.appdirs)
    if (iosRuntime) {
        runtimeOnly(libs.slf4jsimple)
    }
    testImplementation(kotlin("test"))

    // AndroidCompat
    implementation(project(":AndroidCompat"))
    implementation(project(":AndroidCompat:Config"))
}

@Suppress("PropertyName")
val MainClass = "mextensionserver.MainKt"

application {
    mainClass.set(MainClass)
}

sourceSets {
    main {
        resources {
            srcDir("src/main/resources")
        }
    }
}

// Performance harness (see docs/performance.md). These source sets are never
// part of `build`, `test` or the shadow JAR; only the perf* tasks use them.
val perfExtension by sourceSets.creating
val perf by sourceSets.creating

configurations[perfExtension.compileOnlyConfigurationName].extendsFrom(configurations.implementation.get())
configurations[perfExtension.compileClasspathConfigurationName].attributes {
    attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
}
configurations[perf.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[perf.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

kotlin.target.compilations.getByName("perfExtension").associateWith(kotlin.target.compilations.getByName("main"))
kotlin.target.compilations.getByName("perf").associateWith(kotlin.target.compilations.getByName("main"))

// should be bumped with each stable release
val m_extension_serverVersion = "v1.0.7.0"

// counts commit count on master
val m_extension_serverRevision = runCatching {
    System.getenv("ProductRevision") ?: Runtime
        .getRuntime()
        .exec(arrayOf("git", "rev-list", "HEAD", "--count"))
        .let { process ->
            process.waitFor()
            val output = process.inputStream.use {
                it.bufferedReader().use(BufferedReader::readText)
            }
            process.destroy()
            "r" + output.trim()
        }
}.getOrDefault("r0")

val String.wrapped get() = """"$this""""

buildConfig {
    className("BuildConfig")
    packageName("mextensionserver")

    useKotlinOutput()

    buildConfigField("String", "NAME", rootProject.name.wrapped)
    buildConfigField("String", "VERSION", m_extension_serverVersion.wrapped)
    buildConfigField("String", "REVISION", m_extension_serverRevision.wrapped)
}

tasks {
    shadowJar {
        manifest {
            attributes(
                mapOf(
                    "Main-Class" to MainClass,
                    "Implementation-Title" to rootProject.name,
                    "Implementation-Vendor" to "Mangayomi",
                    "Specification-Version" to m_extension_serverVersion,
                    "Implementation-Version" to m_extension_serverRevision
                )
            )
        }
        archiveBaseName.set(rootProject.name)
        archiveVersion.set(m_extension_serverVersion)
        archiveClassifier.set(m_extension_serverRevision)
        if (iosRuntime) {
            dependencies {
                exclude(dependency("ch.qos.logback:logback-classic:.*"))
                exclude(dependency("ch.qos.logback:logback-core:.*"))
            }
            exclude(
                "AndroidManifest.xml",
                "resources.arsc",
                "res/**",
                "font/**",
                "native/**",
                "jni/**",
                "dev/datlag/kcef/**",
                "org/cef/**",
                "org/jogamp/**",
                "com/jogamp/**",
                "jogamp/**",
                "com/sun/jna/**",
            )
            mergeServiceFiles()
        }
    }

    withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.set(
                listOf(
                    "-opt-in=kotlin.RequiresOptIn",
                    "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                    "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
                    "-opt-in=kotlin.io.path.ExperimentalPathApi",
                )
            )
        }
    }

    test {
        useJUnit()
    }

    withType<ShadowJar> {
        destinationDirectory.set(File("$rootDir/server/build"))
        dependsOn("formatKotlin", "lintKotlin")
    }

    named("run") {
        dependsOn("formatKotlin", "lintKotlin")
    }

    withType<LintTask> {
        source(files("src/kotlin"))
    }

    withType<FormatTask> {
        source(files("src/kotlin"))
    }

    withType<ProcessResources> {
        duplicatesStrategy = DuplicatesStrategy.WARN
    }

    // Real extensions target an old JVM; d8 then dexes the fixture like one.
    named<KotlinCompile>("compilePerfExtensionKotlin") {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
    named<JavaCompile>("compilePerfExtensionJava") {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    // Rebuilds the committed fixture APK. Needs an Android SDK (d8, aapt2).
    register<Exec>("perfFixtureApk") {
        group = "performance"
        description = "Rebuilds src/perf/resources/perf-extension.apk from src/perfExtension."
        dependsOn("perfExtensionClasses")
        val classesDirs = perfExtension.output.classesDirs
        val libraries = perfExtension.compileClasspath
        val output = layout.projectDirectory.file("src/perf/resources/perf-extension.apk")
        val manifest = layout.projectDirectory.file("src/perfExtension/AndroidManifest.xml")
        val script = rootProject.layout.projectDirectory.file("tool/perf/build_fixture_apk.sh")
        doFirst {
            args(
                manifest.asFile.path,
                output.asFile.path,
                classesDirs.files.filter { it.exists() }.joinToString(File.pathSeparator),
                libraries.files.joinToString(File.pathSeparator),
            )
        }
        executable(script.asFile.path)
    }

    // Runs the harness: `./gradlew :server:perf` (both modes), or perfJit /
    // perfInt. Options: -Pperf.iterations, -Pperf.warmup, -Pperf.latencyMs,
    // -Pperf.startupRuns, -Pperf.installRuns, -Pperf.dump=<dir>, -Pperf.jfr=true,
    // -Pperf.only=<comma-separated endpoint names>, -Pperf.serverJvmArgs=<flags separated by |>.
    fun registerPerf(
        name: String,
        mode: String,
    ) = register<JavaExec>(name) {
        group = "performance"
        description = "Measures server endpoints with the server JVM in $mode mode."
        // Run from the shadow JAR, as the desktop and iOS builds do (pass
        // -PiosRuntime=true for the iOS variant). Class and resource lookup
        // costs depend on this layout.
        dependsOn(shadowJar, "perfClasses")
        classpath = files(shadowJar.flatMap { it.archiveFile }) + perf.output
        mainClass.set("mextensionserver.perf.PerfHarnessKt")
        workingDir = rootProject.projectDir
        val options =
            listOf("iterations", "warmup", "latencyMs", "startupRuns", "installRuns", "dump", "jfr", "only", "serverJvmArgs")
                .mapNotNull { key -> providers.gradleProperty("perf.$key").orNull?.let { "--$key=$it" } }
        args(listOf("--mode=$mode", "--out=${layout.buildDirectory.dir("perf").get().asFile.path}") + options)
        outputs.upToDateWhen { false }
    }
    val perfJit = registerPerf("perfJit", "jit")
    val perfInt = registerPerf("perfInt", "int")
    perfInt { mustRunAfter(perfJit) }
    register("perf") {
        group = "performance"
        description = "Runs the performance harness in JIT and interpreter-only (-Xint) modes."
        dependsOn(perfJit, perfInt)
    }
}
