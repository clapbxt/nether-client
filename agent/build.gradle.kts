import java.io.ByteArrayOutputStream

// Builds the in-game client into agent/build/ (the folder crates/core/build.rs embeds into the launcher):
//   nether-agent.jar       the agent: bootstrap, overlay, modules, fonts, icons, name tables, art
//   nether-agent-boot.jar  Hook + Tiny, which must sit on the bootstrap class path (Boot-Class-Path)
//   schema.json            every module and setting, for the launcher's Client page
//
// Output is Java 8 bytecode, one jar for 1.8.9 (LWJGL 2), 1.13+ (LWJGL 3 GLFW) and 26.3+ (SDL3). Each platform's
// classes compile against only that platform's LWJGL, as three source sets:
//   main    everything else, against LWJGL 3 (GLFW, OpenGL)
//   legacy  PlatLwjgl2, GlCompat, against LWJGL 2
//   sdl     PlatSdl, against LWJGL's SDL3 bindings
// ASM is bundled RELOCATED to dev.nether.shaded.asm (tools/Relocate.java) so it never clashes with Fabric's own.
// Versions are pinned in gradle/libs.versions.toml.
//
// Run: agent\gradlew.bat build   (or agent\build.ps1, which calls it)

plugins { java }

val legacyFiles = listOf("**/PlatLwjgl2.java", "**/GlCompat.java")
val sdlFiles = listOf("**/PlatSdl.java")
// Boot-class-path classes: Hook must be visible to every loader (incl. Fabric Knot); Tiny (Legacy Fabric 1.8.9
// names) is shared by the agent and the overlay.
val bootClasses = listOf("dev/nether/agent/Hook*.class", "dev/nether/agent/Tiny*.class")

fun lib(dep: Provider<MinimalExternalModuleDependency>) = dependencies.create(dep.get()).also { (it as ModuleDependency).isTransitive = false }
fun compileJars(vararg deps: Provider<MinimalExternalModuleDependency>) =
    configurations.detachedConfiguration(*deps.map { lib(it) }.toTypedArray())

val lwjgl3 = compileJars(libs.lwjgl3.core, libs.lwjgl3.glfw, libs.lwjgl3.opengl)
val lwjgl2 = compileJars(libs.lwjgl2)
val lwjglSdl = compileJars(libs.lwjglSdl.core, libs.lwjglSdl.sdl)
val asmCore = compileJars(libs.asm.core)

sourceSets {
    main { java { setSrcDirs(listOf("src")); exclude(legacyFiles + sdlFiles) }; resources.setSrcDirs(emptyList<String>()) }
    create("legacy") { java { setSrcDirs(listOf("src")); include(legacyFiles) }; resources.setSrcDirs(emptyList<String>()) }
    create("sdl") { java { setSrcDirs(listOf("src")); include(sdlFiles) }; resources.setSrcDirs(emptyList<String>()) }
    create("tools") { java { setSrcDirs(listOf("tools")); include("**/Relocate.java") }; resources.setSrcDirs(emptyList<String>()) }
}

dependencies {
    "toolsImplementation"(libs.asm.core)
    "toolsImplementation"(libs.asm.commons)
}

// org.objectweb.asm.* -> dev.nether.shaded.asm.* (only ASM core: that is all the agent uses)
val relocatedAsm = layout.buildDirectory.dir("gradle/shaded-asm")
val relocateAsm by tasks.registering(JavaExec::class) {
    description = "Copies ASM into the agent as dev.nether.shaded.asm"
    classpath = sourceSets["tools"].runtimeClasspath
    mainClass.set("Relocate")
    inputs.files(asmCore)
    outputs.dir(relocatedAsm)
    doFirst {
        val dir = relocatedAsm.get().asFile
        dir.deleteRecursively(); dir.mkdirs()
        args = listOf(dir.absolutePath) + asmCore.files.map { it.absolutePath }
    }
}
val shaded = files(relocatedAsm).builtBy(relocateAsm)
val mainClasses = sourceSets.main.get().output.classesDirs

tasks.named<JavaCompile>("compileJava") { classpath = shaded + lwjgl3 }
tasks.named<JavaCompile>("compileLegacyJava") { classpath = mainClasses + shaded + lwjgl2 }
tasks.named<JavaCompile>("compileSdlJava") { classpath = mainClasses + shaded + lwjglSdl }
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (name != "compileToolsJava") {
        options.release.set(8)
        options.compilerArgs.add("-Xlint:-options")
    }
}

val allClasses = files(mainClasses, sourceSets["legacy"].output.classesDirs, sourceSets["sdl"].output.classesDirs)

// The overlay's class list: the agent defines these inside the game's LWJGL class loader (Agent.installOverlay).
val ovList = layout.buildDirectory.file("gradle/ov.list")
val writeOvList by tasks.registering {
    description = "Lists the overlay classes the agent defines in the game's class loader"
    inputs.files(allClasses)
    outputs.file(ovList)
    doLast {
        // every class under dev/nether/ov, sub-packages included (platform, game, framework, ui, menu, features.*)
        val names = allClasses.files.flatMap { root ->
            root.resolve("dev/nether/ov").walkTopDown().filter { it.isFile && it.name.endsWith(".class") }
                .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.') }.toList()
        }.distinct().sorted()
        ovList.get().asFile.writeText(names.joinToString("\r\n", postfix = "\r\n"), Charsets.US_ASCII)
    }
}

// Module/settings schema for the launcher's Client page (one line of UTF-8 JSON, no BOM)
val schemaJson = layout.buildDirectory.file("schema.json")
val schema by tasks.registering(JavaExec::class) {
    description = "Dumps every module and setting to schema.json"
    classpath = mainClasses + shaded + lwjgl3
    mainClass.set("dev.nether.ov.Schema")
    outputs.file(schemaJson)
    val out = ByteArrayOutputStream()
    standardOutput = out
    doLast {
        val text = out.toString(Charsets.UTF_8.name()).replace(Regex("\r?\n"), "")
        check(text.isNotBlank()) { "schema dump was empty" }
        schemaJson.get().asFile.writeText(text, Charsets.UTF_8)
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    // fixed entry dates and order: an unchanged agent gives byte-identical jars, so the launcher doesn't rebuild
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.named<Jar>("jar") { enabled = false }

val bootJar by tasks.registering(Jar::class) {
    description = "Builds nether-agent-boot.jar (Boot-Class-Path classes)"
    destinationDirectory.set(layout.buildDirectory)
    archiveFileName.set("nether-agent-boot.jar")
    from(allClasses) { include(bootClasses) }
}

val agentJar by tasks.registering(Jar::class) {
    description = "Builds nether-agent.jar"
    destinationDirectory.set(layout.buildDirectory)
    archiveFileName.set("nether-agent.jar")
    from(allClasses) { exclude(bootClasses) }
    from(shaded)
    from(writeOvList)
    // fonts, name tables, brand art, line icons (read by ov/ModIcons), title panoramas
    for (dir in listOf("fonts", "names", "brand", "icons", "pano")) from("res/$dir") { into(dir) }
    manifest.attributes(
        "Premain-Class" to "dev.nether.agent.Agent",
        "Boot-Class-Path" to "nether-agent-boot.jar",
        "Can-Set-Native-Method-Prefix" to "true",
    )
}

tasks.named("assemble") { dependsOn(agentJar, bootJar, schema) }
