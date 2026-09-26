plugins {
    id("java")
    id("idea")
    id("net.fabricmc.fabric-loom") version("1.15.4")
}

evaluationDependsOn(":common")

if (project.hasProperty("irisProbe")) {
    val probe = sourceSets.create("probe") {
        compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
    }
    val probeJar = tasks.register<Jar>("probeJar") {
        from(probe.output)
        archiveFileName.set("iris-native-probe.jar")
        destinationDirectory.set(layout.buildDirectory.dir("probe"))
    }
    val worldProbe = project.hasProperty("irisProbeWorld")
    val ultraProbe = project.hasProperty("irisProbeUltra")
    val ultraMotionProbe = project.hasProperty("irisProbeUltraMotion")
    val cameraProbe = project.hasProperty("irisProbeCamera")
    val playerShadowProbe = project.hasProperty("irisProbePlayerShadow")
    val enchantedProbe = project.hasProperty("irisProbeEnchanted")
    val portalProbe = project.hasProperty("irisProbePortal")
    val probeBackend = providers.gradleProperty("irisProbeBackend").orElse("vulkan").get()
    val probeDirectoryName = providers.gradleProperty("irisProbeRunDirectory").orNull ?: if (worldProbe && probeBackend == "opengl") "run-opengl-world-reference" else if (worldProbe) "run-vulkan-world-probe" else if (project.hasProperty("irisProbePack")) "run-vulkan-pack-audit" else "run-vulkan-probe"
    val runFolder = file(probeDirectoryName)
    val selectedProbePack = providers.gradleProperty("irisProbePack").orNull?.let { file(it) }
    if (portalProbe) {
        require(worldProbe && probeBackend == "vulkan" && selectedProbePack != null
                && providers.gradleProperty("irisProbeExpectedProfile").orNull in listOf("HIGH", "ULTRA")) {
            "irisProbePortal requires irisProbeWorld, Vulkan, an explicit irisProbePack and exact HIGH/ULTRA irisProbeExpectedProfile"
        }
        require(listOf("irisProbeInteractive", "irisProbeMotion", "irisProbeScenarios", "irisProbeQualityChange",
                "irisProbeHidden", "irisProbeCamera", "irisProbePlayerShadow", "irisProbeEnchanted", "irisProbeUltra").none { project.hasProperty(it) }) {
            "irisProbePortal is a separate visible fixture; supply the real profile sidecar, not the legacy irisProbeUltra mode"
        }
        require(providers.gradleProperty("irisProbeRunDirectory").orNull?.contains("portal", ignoreCase = true) == true) {
            "irisProbePortal requires a dedicated irisProbeRunDirectory containing 'portal'"
        }
    }
    if (ultraProbe) {
        require(worldProbe && probeBackend == "vulkan" && selectedProbePack != null) {
            "irisProbeUltra requires irisProbeWorld, the Vulkan backend, and an explicit irisProbePack"
        }
        require(!project.hasProperty("irisProbeInteractive") && !project.hasProperty("irisProbeMotion") && !project.hasProperty("irisProbeQualityChange") && !cameraProbe && !playerShadowProbe) {
            "irisProbeUltra is an isolated rendering/readback probe and cannot modify a manual, motion, or quality-change run"
        }
        require(providers.gradleProperty("irisProbeRunDirectory").orNull?.contains("ultra", ignoreCase = true) == true) {
            "irisProbeUltra requires a dedicated irisProbeRunDirectory containing 'ultra'"
        }
    }
    if (ultraMotionProbe) {
        require(ultraProbe && !project.hasProperty("irisProbeHidden")) {
            "irisProbeUltraMotion requires the visible isolated irisProbeUltra run and its exact Ultra option sidecar"
        }
    }
    if (enchantedProbe) {
        require(worldProbe && probeBackend == "vulkan" && selectedProbePack != null
                && !project.hasProperty("irisProbeInteractive") && !playerShadowProbe) {
            "irisProbeEnchanted requires an isolated Vulkan world probe with an explicit pack"
        }
        require(providers.gradleProperty("irisProbeRunDirectory").orNull?.contains("enchanted", ignoreCase = true) == true) {
            "irisProbeEnchanted requires a dedicated irisProbeRunDirectory containing 'enchanted'"
        }
    }
    if (cameraProbe) {
        require(worldProbe && selectedProbePack != null) {
            "irisProbeCamera requires irisProbeWorld and an explicit irisProbePack"
        }
        require(listOf("irisProbeInteractive", "irisProbeMotion", "irisProbeScenarios", "irisProbeQualityChange", "irisProbeHidden", "irisProbePlayerShadow").none { project.hasProperty(it) }) {
            "irisProbeCamera is a visible, isolated camera regression probe"
        }
        require(providers.gradleProperty("irisProbeRunDirectory").orNull?.contains("camera", ignoreCase = true) == true) {
            "irisProbeCamera requires a dedicated irisProbeRunDirectory containing 'camera'"
        }
    }
    if (playerShadowProbe) {
        require(worldProbe && probeBackend == "vulkan" && selectedProbePack != null) {
            "irisProbePlayerShadow requires irisProbeWorld, Vulkan, and an explicit irisProbePack"
        }
        require(listOf("irisProbeInteractive", "irisProbeMotion", "irisProbeScenarios", "irisProbeQualityChange", "irisProbeHidden", "irisProbeCamera").none { project.hasProperty(it) }) {
            "irisProbePlayerShadow is a visible, isolated third-person shadow probe"
        }
        require(providers.gradleProperty("irisProbeRunDirectory").orNull?.contains("player-shadow", ignoreCase = true) == true) {
            "irisProbePlayerShadow requires a dedicated irisProbeRunDirectory containing 'player-shadow'"
        }
    }
    val prepare = tasks.register("prepareNativeProbe") {
        dependsOn(probeJar)
        doLast {
            copy { from(probeJar); into(runFolder.resolve("mods")) }
            copy { from(rootDir.resolve("tests/shaderpacks/native-final-check")); into(runFolder.resolve("shaderpacks/native-final-check")) }
            if (selectedProbePack != null) copy { from(selectedProbePack); into(runFolder.resolve("shaderpacks")) }
            runFolder.resolve("config").mkdirs()
            val packName = selectedProbePack?.name ?: "native-final-check"
            if (ultraProbe) {
                // Iris reads this exact sidecar before constructing its first ShaderPack.
                runFolder.resolve("shaderpacks/$packName.txt").writeText("""
                    ANISOTROPIC_FILTER=8
                    CLOUD_QUALITY=3
                    COLORED_LIGHTING=512
                    DETAIL_QUALITY=3
                    LIGHTSHAFT_QUALI_DEFINE=3
                    SHADOW_QUALITY=3
                    WORLD_SPACE_REFLECTIONS=1
                    shadowDistance=256.0
                """.trimIndent() + "\n")
            }
            // Automated development probes acknowledge the disclosure explicitly; normal installs do not.
            runFolder.resolve("config/iris.properties").writeText("enableShaders=true\nshaderPack=$packName\ndisableUpdateMessage=true\nvulkanWarningVersion=1\nenableDebugOptions=${project.hasProperty("irisProbeDebugShaders")}\n")
            val optionsFile = runFolder.resolve("options.txt")
            if (project.hasProperty("irisProbeInteractive") && optionsFile.exists()) {
                val retained = optionsFile.readLines().filterNot { it.startsWith("preferredGraphicsBackend:") || it.startsWith("maxFps:") || it.startsWith("enableVsync:") }
                optionsFile.writeText((retained + listOf("preferredGraphicsBackend:\"$probeBackend\"", "maxFps:260", "enableVsync:false")).joinToString("\n") + "\n")
            } else optionsFile.writeText("preferredGraphicsBackend:\"$probeBackend\"\nrenderDistance:4\nmaxFps:${if (project.hasProperty("irisProbeInteractive")) 260 else 60}\nenableVsync:false\nfullscreen:false\nonboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\n")
            runFolder.resolve("native-probe-result.txt").delete()
        }
    }
    loom.runs.named("client") {
        runDir(probeDirectoryName)
        programArgs("--graphicsBackend", probeBackend.uppercase(), "--width", if (project.hasProperty("irisProbeInteractive")) "1280" else "960", "--height", if (project.hasProperty("irisProbeInteractive")) "720" else "540")
        val mode = if (worldProbe) "all" else if (selectedProbePack == null) "final" else "off"
        vmArgs("-Diris.vulkan.screenPassMode=$mode", "-Diris.vulkan.screenPassDrawMode=shaderpack",
                "-Diris.vulkan.probe.runDir=${runFolder.absolutePath}", "-Diris.vulkan.probe.backend=$probeBackend")
        if (worldProbe) vmArgs("-Diris.vulkan.worldDevelopment=${probeBackend == "vulkan"}", "-Diris.vulkan.probe.world=true", "-Diris.vulkan.dumpGbufferShaders=true")
        else if (selectedProbePack != null) vmArgs("-Diris.vulkan.probe.auditOnly=true", "-Diris.vulkan.probe.compileAudit=true")
        if (project.hasProperty("irisProbeScenarios")) vmArgs("-Diris.vulkan.probe.scenarios=true")
        providers.gradleProperty("irisProbeExpectedProfile").orNull?.let {
            vmArgs("-Diris.vulkan.probe.expectedProfile=$it")
        }
        if (project.hasProperty("irisProbeDebugShaders")) vmArgs("-Diris.vulkan.probe.shaderDumps=true")
        if (project.hasProperty("irisProbeMotion")) vmArgs("-Diris.vulkan.probe.motion=true")
        if (cameraProbe) vmArgs("-Diris.vulkan.probe.camera=true")
        if (playerShadowProbe) vmArgs("-Diris.vulkan.probe.playerShadow=true")
        if (enchantedProbe) vmArgs("-Diris.vulkan.probe.enchanted=true")
        if (portalProbe) vmArgs("-Diris.vulkan.probe.portal=true")
        if (project.hasProperty("irisProbeShadowSkipEntities")) vmArgs("-Diris.probe.shadowSkipEntities=true")
        if (cameraProbe && (project.hasProperty("irisProbeCameraRecordOnly") || project.hasProperty("irisProbeCameraLegacy"))) vmArgs("-Diris.vulkan.probe.camera.recordOnly=true")
        if (project.hasProperty("irisProbeHidden")) vmArgs("-Diris.vulkan.probe.hidden=true")
        if (project.hasProperty("irisProbeQualityChange")) vmArgs("-Diris.vulkan.probe.qualityChange=true")
        if (ultraProbe || project.hasProperty("irisProbeStorage")) vmArgs("-Diris.vulkan.storageDevelopment=true")
        if (ultraProbe) vmArgs("-Diris.vulkan.probe.ultra=true")
        if (ultraMotionProbe) vmArgs("-Diris.vulkan.probe.ultraMotion=true")
        if (project.hasProperty("irisProbeTrace")) vmArgs("-Diris.vulkan.probe.fxaaTrace=true", "-Diris.vulkan.probe.particleTrace=true", "-Diris.vulkan.dumpCustomScreenPassShaders=true")
        providers.gradleProperty("irisProbeStopAfter").orNull?.let {
            vmArgs("-Diris.vulkan.debugStopAfterPass=$it", "-Diris.vulkan.debugOutputTarget=${providers.gradleProperty("irisProbeOutputTarget").orElse("3").get()}")
        }
        if (project.hasProperty("irisProbeInteractive")) vmArgs("-Diris.vulkan.probe.interactive=true",
            "-Diris.vulkan.probe.openWorld=${providers.gradleProperty("irisProbeOpenWorld").get()}")
        providers.gradleProperty("irisProbeUser").orNull?.let { programArgs("--username", it) }
        providers.gradleProperty("irisProbeUuid").orNull?.let { programArgs("--uuid", it) }
    }
    tasks.named("runClient") { dependsOn(prepare) }
}

val MINECRAFT_VERSION: String by rootProject.extra
val PARCHMENT_VERSION: String? by rootProject.extra
val FABRIC_LOADER_VERSION: String by rootProject.extra
val FABRIC_API_VERSION: String by rootProject.extra
val SODIUM_DEPENDENCY_FABRIC: Any by rootProject.extra
val MOD_VERSION: String by rootProject.extra

repositories {
    mavenLocal()
    maven("https://maven.caffeinemc.net/releases")
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

base {
    archivesName.set("iris-vulkan-experimental")
}

dependencies {
    minecraft("com.mojang:minecraft:${MINECRAFT_VERSION}")

    implementation("net.fabricmc:fabric-loader:$FABRIC_LOADER_VERSION")

    fun addEmbeddedFabricModule(name: String) {
        val module = fabricApi.module(name, FABRIC_API_VERSION)
        implementation(module)
        include(module)
    }

    fun implementAndInclude(name: String) {
        implementation(name)
        include(name)
    }

    // Complete runtime module closure; include() does not nest transitive modules.
    // Fabric Loader deduplicates matching modules also supplied by Sodium/Fabric API.
    addEmbeddedFabricModule("fabric-api-base")
    addEmbeddedFabricModule("fabric-key-mapping-api-v1")
    addEmbeddedFabricModule("fabric-block-getter-api-v2")
    addEmbeddedFabricModule("fabric-rendering-fluids-v1")
    addEmbeddedFabricModule("fabric-resource-loader-v0")
    addEmbeddedFabricModule("fabric-resource-loader-v1")
    addEmbeddedFabricModule("fabric-lifecycle-events-v1")
    addEmbeddedFabricModule("fabric-renderer-api-v1")
    addEmbeddedFabricModule("fabric-rendering-v1")
    addEmbeddedFabricModule("fabric-transitive-access-wideners-v1")

    implementation(SODIUM_DEPENDENCY_FABRIC)
    implementAndInclude("org.antlr:antlr4-runtime:4.13.1")
    implementAndInclude("io.github.douira:glsl-transformer:3.0.0-pre3")
    implementAndInclude("org.anarres:jcpp:1.4.14")

    implementation(project(":common"))
    implementation(project(path = ":common", configuration = "vendoredJar"))
    implementation(project(path = ":common", configuration = "apiJar"))
    compileOnly(project(path = ":common", configuration = "headersJar"))

    compileOnly(files(rootDir.resolve("DHApi.jar")))
}

tasks.named("compileTestJava").configure {
    enabled = false
}

tasks.named("test").configure {
    enabled = false
}
tasks.named("check") { dependsOn(":common:verifyNativeTargetIndices") }

loom {
    if (project(":common").file("src/main/resources/iris.accesswidener").exists())
        accessWidenerPath.set(project(":common").file("src/main/resources/iris.accesswidener"))

    @Suppress("UnstableApiUsage")
    mixin {
        defaultRefmapName.set("iris-fabric.refmap.json")
        useLegacyMixinAp = false
    }

    runs {
        named("client") {
            client()
            configName = "Fabric Client"
            ideConfigGenerated(true)
            runDir("run")
           // vmArgs("-Dmixin.debug.export=true")
           // vmArg("-XX:+AllowEnhancedClassRedefinition")
        }
    }
}

tasks {
    processResources {
        from(project.project(":common").sourceSets.main.get().resources)
        inputs.property("version", project.version)

        filesMatching("fabric.mod.json") {
            expand(mapOf("version" to project.version))
        }
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        from(zipTree(project.project(":common").tasks.jar.get().archiveFile))

        manifest.attributes["Main-Class"] = "net.irisshaders.iris.LaunchWarn"
    }

    jar.get().destinationDirectory = rootDir.resolve("build").resolve("libs")
}

// Apply after the default development run above so it cannot reset the probe directory.
if (project.hasProperty("irisProbe")) {
    loom.runs.named("client") {
        runDir(providers.gradleProperty("irisProbeRunDirectory").orNull ?: if (project.hasProperty("irisProbeWorld") && providers.gradleProperty("irisProbeBackend").orNull == "opengl") "run-opengl-world-reference" else if (project.hasProperty("irisProbeWorld")) "run-vulkan-world-probe" else if (project.hasProperty("irisProbePack")) "run-vulkan-pack-audit" else "run-vulkan-probe")
        vmArgs(if (project.hasProperty("irisProbeInteractive")) "-Xmx6G" else "-Xmx2G")
    }
}
