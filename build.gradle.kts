plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("org.jetbrains.compose") version "1.10.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
}

group = "io.anysound"
version = "0.1.0"
kotlin { jvmToolchain(21) }

val launcher by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

val isWindows = System.getProperty("os.name").startsWith("Windows")
val isMac = System.getProperty("os.name").contains("Mac")
val isArm = System.getProperty("os.arch") in listOf("aarch64", "arm64")
// JVM bytecode is portable; JNI libraries must match the machine that will run it.
val targetPlatform = providers.gradleProperty("targetPlatform").getOrElse("host")
require(targetPlatform in setOf("host", "windows-x64")) { "targetPlatform 支持 host 或 windows-x64" }
val targetsWindows = targetPlatform == "windows-x64" || isWindows
val sherpaPlatform = when {
    targetsWindows -> "win-x64"
    isMac -> if (isArm) "osx-aarch64" else "osx-x64"
    else -> if (isArm) "linux-aarch64" else "linux-x64"
}

dependencies {
    launcher(project(":anysound-launcher"))
    implementation(if (targetsWindows) "org.jetbrains.compose.desktop:desktop-jvm-windows-x64:1.10.3" else compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material:1.10.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:v1.13.5")
    runtimeOnly("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-native-lib-$sherpaPlatform:v1.13.5")
    implementation("com.alibaba:dashscope-sdk-java:2.23.1")
    implementation("org.lwjgl:lwjgl:3.3.6")
    implementation("org.lwjgl:lwjgl-openvr:3.3.6")
    implementation("org.lwjgl:lwjgl-glfw:3.3.6")
    implementation("org.lwjgl:lwjgl-opengl:3.3.6")
    if (targetsWindows) {
        runtimeOnly("org.lwjgl:lwjgl:3.3.6:natives-windows")
        runtimeOnly("org.lwjgl:lwjgl-openvr:3.3.6:natives-windows")
        runtimeOnly("org.lwjgl:lwjgl-glfw:3.3.6:natives-windows")
        runtimeOnly("org.lwjgl:lwjgl-opengl:3.3.6:natives-windows")
    }
    runtimeOnly("org.slf4j:slf4j-nop:1.7.36")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Exercise the OpenVR ABI shim through real JNI without a SteamVR runtime.
    if (!targetsWindows) {
        val hostNatives = if (isMac) "macos" else "linux"
        testRuntimeOnly("org.lwjgl:lwjgl:3.3.6:natives-$hostNatives${if (isArm) "-arm64" else ""}")
    }
}

tasks.test {
    dependsOn(":anysound-launcher:test")
    useJUnitPlatform()
    providers.gradleProperty("modelSmokeDir").orNull?.let { systemProperty("anysound.testModel", file(it).absolutePath) }
    providers.gradleProperty("refinementSmokeDir").orNull?.let { systemProperty("anysound.testRefinementModel", file(it).absolutePath) }
    providers.gradleProperty("gpuOverlaySmoke").orNull?.let { systemProperty("anysound.testGpuOverlay", it) }
}

tasks.jar { archiveFileName.set("anysound-main.jar") }

val launcherResources = layout.buildDirectory.dir("launcher-resources")
val prepareLauncherResources by tasks.registering(Sync::class) {
    into(launcherResources)
    into("common") {
        from(tasks.jar)
        into("libs") {
            from(configurations.runtimeClasspath)
            // Preserve separate artifact directories, as in Perlica's release layout.
            eachFile { path = "common/libs/${file.nameWithoutExtension}-${file.parentFile.name}/$name" }
            duplicatesStrategy = DuplicatesStrategy.FAIL
        }
        into("licenses/launcher") { from("anysound-launcher/LICENSE", "anysound-launcher/NOTICE.md") }
    }
}

tasks.register<Sync>("prepareLauncher") {
    group = "distribution"
    description = "Prepare AnySound's standalone Launcher, main JAR and dependencies"
    dependsOn(prepareLauncherResources)
    from(launcher)
    from(launcherResources.map { it.dir("common") })
    into(layout.buildDirectory.dir("launcher"))
}

tasks.matching { it.name == "prepareAppResources" || it.name == "prepareReleaseAppResources" }.configureEach {
    dependsOn(prepareLauncherResources)
}

tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    doFirst {
        check(targetPlatform == "host" || (isWindows && !isArm)) {
            "跨平台只能生成 Launcher 目录：请用 prepareLauncher -PtargetPlatform=windows-x64；含 Java 的 Windows 程序包须在 Windows x64 构建"
        }
    }
}

compose.desktop {
    application {
        disableDefaultConfiguration()
        fromFiles(launcher)
        mainJar.set(layout.file(providers.provider { launcher.singleFile }))
        mainClass = "tech.origin.launch.Main"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
        providers.gradleProperty("appDataDir").orNull?.let { jvmArgs += "-Danysound.dataDir=${file(it).absolutePath}" }
        nativeDistributions {
            appResourcesRootDir.set(launcherResources)
            packageName = "AnySound"
            packageVersion = "0.1.0"
            description = "为 VRChat 提供键盘与语音转文字聊天"
            vendor = "AnySound"
            macOS {
                bundleID = "io.anysound.desktop"
                // macOS jpackage requires a positive major version, even for dev previews.
                packageVersion = "1.0.0"
            }
            // JNI and SDKs use modules that static jdeps analysis cannot reliably discover.
            includeAllModules = true
        }
    }
}

tasks.register<Zip>("portableZip") {
    group = "distribution"
    description = "Build the Windows x64 portable app with its Java runtime"
    dependsOn("createDistributable")
    doFirst { check(isWindows && !isArm) { "请在 Windows x64 / JDK 21 上生成便携包" } }
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    from("README.md")
    from("THIRD_PARTY_NOTICES.md")
    from("anysound-launcher") { include("LICENSE", "NOTICE.md"); into("anysound-launcher") }
    from("docs") { into("docs") }
    archiveFileName.set("AnySound-${project.version}-windows-x64.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

tasks.register<Zip>("launcherZip") {
    group = "distribution"
    description = "Build the Windows x64 Launcher ZIP (requires an installed Java 21 x64 runtime)"
    dependsOn("prepareLauncher")
    doFirst { check(targetsWindows) { "请在 Windows x64 构建，或添加 -PtargetPlatform=windows-x64" } }
    from(layout.buildDirectory.dir("launcher")) { into("AnySound") }
    from("README.md", "THIRD_PARTY_NOTICES.md")
    from("anysound-launcher/LICENSE") { into("anysound-launcher") }
    from("docs") { into("docs") }
    archiveFileName.set("AnySound-${project.version}-windows-x64-launcher.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
}

tasks.wrapper { gradleVersion = "9.3.0"; distributionType = Wrapper.DistributionType.BIN }
