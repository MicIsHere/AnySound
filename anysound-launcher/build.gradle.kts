plugins { java }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.compileJava { options.encoding = "UTF-8" }
tasks.compileTestJava { options.encoding = "UTF-8" }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.jar {
    archiveFileName.set("anysound-launcher.jar")
    manifest { attributes("Main-Class" to "tech.origin.launch.Main") }
    from(listOf("LICENSE")) { into("META-INF/anysound-launcher") }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    systemProperty("anysound.testLauncher", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
