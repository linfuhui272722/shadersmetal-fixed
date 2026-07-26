import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("java")
    id("net.fabricmc.fabric-loom") version("1.17.17")
    id("maven-publish")
}

val mavenGroup = property("maven_group") as String
val modVersion = property("mod_version") as String
val archivesBaseName = property("archives_base_name") as String
val minecraftVersion = property("minecraft_version") as String
val javaVersion = property("java_version") as String
val loaderVersion = property("loader_version") as String
val fabricVersion = property("fabric_version") as String

group = mavenGroup
version = modVersion
base.archivesName.set(archivesBaseName)

repositories {
    mavenCentral()
    maven { url = uri("https://maven.fabricmc.net/") }
    maven { url = uri("https://maven.caffeinemc.net/releases") }
}

loom {
    accessWidenerPath = file("src/main/resources/metallum.accesswidener")
    mods {
        register("metallum_shaders") {
            sourceSet("main")
        }
    }
    @Suppress("UnstableApiUsage")
    mixin {
        defaultRefmapName.set("metallum_shaders.refmap.json")
        useLegacyMixinAp = false
    }
}

dependencies {
    // 用 Loom 默认下载 MC 26.2（Mojang 官方映射，无混淆）
    minecraft("com.mojang:minecraft:${minecraftVersion}")
    implementation("net.fabricmc:fabric-loader:${loaderVersion}")
    implementation("net.fabricmc.fabric-api:fabric-api:${fabricVersion}")

    implementation(files("libs/metallum-1.0.1.jar"))
    compileOnly(files("libs/sodium-fabric-0.9.1+mc26.2.jar"))
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion.toInt())
        vendor = JvmVendorSpec.ADOPTIUM
    }
    withSourcesJar()
    sourceCompatibility = JavaVersion.toVersion(javaVersion.toInt())
    targetCompatibility = JavaVersion.toVersion(javaVersion.toInt())
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(project.property("java_version").toString().toInt())
}

tasks.withType<ProcessResources> {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to project.version))
    }
}

val nativeSrcDir = layout.projectDirectory.dir("src/main/cpp")
val nativeOutDir = layout.buildDirectory.dir("native")
val macosArmDylib = nativeOutDir.map { it.file("macos-arm64/libmetallum_shaders.dylib") }

val buildNativeArm64 by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile the Metal JNI shim for ARM64 (macOS only)."

    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }

    outputs.file(macosArmDylib)
    inputs.file(nativeSrcDir.file("metallum_shaders.cpp"))

    doFirst {
        val sdk = ProcessBuilder("xcrun", "--sdk", "macosx", "--show-sdk-path")
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().readText().trim()

        val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home")

        val outDir = nativeOutDir.get().asFile.resolve("macos-arm64")
        outDir.mkdirs()

        commandLine(
            "clang++",
            "-x", "objective-c++",
            "-std=c++17",
            "-stdlib=libc++",
            "-fobjc-arc",
            "-arch", "arm64",
            "-I", "$sdk/System/Library/Frameworks/Metal.framework/Headers",
            "-I", "$sdk/System/Library/Frameworks/Foundation.framework/Headers",
            "-I", "$javaHome/include",
            "-I", "$javaHome/include/darwin",
            "-F", "$sdk/System/Library/Frameworks",
            "-framework", "Metal",
            "-framework", "Foundation",
            "-shared",
            "-dynamiclib",
            "-o", macosArmDylib.get().asFile.absolutePath,
            nativeSrcDir.file("metallum_shaders.cpp").asFile.absolutePath
        )
    }
}

val buildNative by tasks.registering {
    group = "build"
    description = "Compile the Metal JNI shim (ARM64 only)."
    dependsOn(buildNativeArm64)
}

val copyNativeToResources by tasks.registering(Copy::class) {
    group = "build"
    dependsOn(buildNativeArm64)
    from(macosArmDylib)
    into(layout.projectDirectory.dir("src/main/resources/native/macos-arm64"))
}

tasks.named<Jar>("jar") {
    val dylibFile = file("${layout.buildDirectory.get().asFile}/native/macos-arm64/libmetallum_shaders.dylib")
    if (dylibFile.exists()) {
        from(dylibFile) {
            into("native/macos-arm64")
        }
    }
}
