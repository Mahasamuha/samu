import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Real endpoints live in the gitignored config.local.properties (see config.example.properties).
val localConfig = Properties().apply {
    val file = rootProject.file("config.local.properties")
    if (file.exists()) file.inputStream().use(::load)
}

/**
 * Copies the skills marked `delivery: app` from the repo's skills/ folder into the APK's assets
 * (plan §4b). Drafts (`delivery: server`) are served by the server instead.
 */
abstract class BundleSkills : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val skillsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        skillsDir.get().asFile.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.forEach { dir ->
            val file = File(dir, "SKILL.md").takeIf { it.isFile } ?: return@forEach
            val frontmatter = file.readText().substringAfter("---\n", "").substringBefore("\n---", "")
            val delivery = Regex("""(?m)^delivery:\s*(\S+)""").find(frontmatter)?.groupValues?.get(1)
            if (delivery == "app") file.copyTo(File(out, "skills/${dir.name}/SKILL.md"), overwrite = true)
        }
    }
}

val bundleSkills = tasks.register<BundleSkills>("bundleSkills") {
    skillsDir.set(layout.projectDirectory.dir("../skills"))
}

/**
 * Writes the local and hybrid entries of the repo's capabilities manifest into the APK's assets (plan
 * phase 3.5). Server-only entries are the MCP server's. Fails the build if a local tool the app registers
 * belongs to no local or hybrid capability, or if the manifest names a local tool the app doesn't have.
 */
abstract class BundleCapabilities : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifest: RegularFileProperty

    /** The app's Kotlin sources, scanned for the tools it registers. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        @Suppress("UNCHECKED_CAST")
        val entries = (JsonSlurper().parse(manifest.get().asFile) as Map<String, Any?>)["capabilities"] as List<Map<String, Any?>>

        // Every local tool declares itself as `ToolDefinition(name = "<tool>", ...)`.
        val toolPattern = Regex("""ToolDefinition\(\s*name\s*=\s*"([^"]+)"""")
        val appTools = sources.files.filter { it.extension == "kt" }
            .flatMap { file -> toolPattern.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()
        if (appTools.isEmpty()) {
            throw GradleException("Found no `ToolDefinition(name = \"...\")` in the app sources; the capability check can't run. Did the tool declaration style change?")
        }

        fun tools(entry: Map<String, Any?>) = (entry["tools"] as List<*>).map { @Suppress("UNCHECKED_CAST") (it as Map<String, Any?>) }
        // Tier from `where`: local only = local, remote only = server, anything else = hybrid.
        fun isServerOnly(entry: Map<String, Any?>) = tools(entry).all { it["where"] == "remote" }

        val bundled = entries.filterNot(::isServerOnly)
        val covered = bundled.flatMap { entry -> tools(entry).filter { it["where"] != "remote" }.map { it["name"] as String } }.toSet()

        val orphans = (appTools - covered).sorted()
        if (orphans.isNotEmpty()) {
            throw GradleException("Local tools in no local or hybrid capability in capabilities/capabilities.json: ${orphans.joinToString()}")
        }
        val unknown = (covered - appTools).sorted()
        if (unknown.isNotEmpty()) {
            throw GradleException("capabilities/capabilities.json lists local or hybrid tools the app doesn't register: ${unknown.joinToString()}")
        }

        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        File(out, "capabilities.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf("capabilities" to bundled))) + "\n")
    }
}

val bundleCapabilities = tasks.register<BundleCapabilities>("bundleCapabilities") {
    manifest.set(layout.projectDirectory.file("../capabilities/capabilities.json"))
    sources.from(fileTree("src/main/java") { include("**/*.kt") })
}

fun localConfigValue(key: String): String =
    localConfig.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: error("Missing $key in config.local.properties (copy config.example.properties and fill it in)")

/** Runs a git command and returns its trimmed stdout, or null if git fails (no tags, no repo). */
fun gitOutput(vararg args: String): String? {
    val result = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    return result.standardOutput.asText.get().trim().takeIf { result.result.get().exitValue == 0 && it.isNotEmpty() }
}

// Version comes from the vX.Y.Z tags CI creates on main; before the first tag, a dev placeholder.
fun gitVersionName(): String =
    gitOutput("describe", "--tags", "--match", "v[0-9]*", "--dirty")?.removePrefix("v")
        ?: gitOutput("rev-parse", "--short", "HEAD")?.let { "0.1.0-dev+$it" }
        ?: "0.1.0-dev"

fun gitVersionCode(): Int = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

android {
    namespace = "com.mahasamuha.assistant"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mahasamuha.assistant"
        minSdk = 37
        targetSdk = 37
        versionCode = gitVersionCode()
        versionName = gitVersionName()

        buildConfigField("String", "ISSUER_URL", "\"${localConfigValue("assistant.issuerUrl")}\"")
        buildConfigField("String", "CLIENT_ID", "\"${localConfigValue("assistant.clientId")}\"")
        buildConfigField("String", "GATEWAY_URLS", "\"${localConfigValue("assistant.gatewayUrls")}\"")
        // AppAuth's redirect receiver listens on this scheme: <applicationId>:/oauth2redirect
        manifestPlaceholders["appAuthRedirectScheme"] = "com.mahasamuha.assistant"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleSkills, BundleSkills::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(bundleCapabilities, BundleCapabilities::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.appauth)
    // Only for the one-tap "turn on location" dialog before navigating (plan §5a).
    implementation(libs.play.services.location)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
