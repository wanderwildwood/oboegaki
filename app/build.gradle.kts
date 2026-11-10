import java.security.MessageDigest
import java.util.Properties
import java.util.regex.Pattern

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries.android)
}

// The Whisper model goes into the APK as an asset. It is fetched by models/fetch.sh rather
// than committed, and checked here by hash as well: a build that quietly packed a different
// model, or half of one, would still say BUILD SUCCESSFUL. The same model Dream Log ships.
val modelName = "ggml-base.en-q5_1.bin"
val modelSha256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
val modelAssets = layout.buildDirectory.dir("generated/model-assets")

val stageModel by tasks.registering {
    val source = rootProject.file("models/$modelName")
    inputs.file(source).optional()
    outputs.dir(modelAssets)
    doLast {
        if (!source.isFile) {
            throw GradleException("models/$modelName is missing. Run models/fetch.sh first.")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != modelSha256) {
            throw GradleException("models/$modelName has sha256 $actual, expected $modelSha256.")
        }
        val out = modelAssets.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        source.copyTo(out.resolve("model.bin"))
    }
}

android {
    namespace = "com.wanderwildwood.oboegaki"
    compileSdk = 36
    // The build box has exactly this NDK, and CI installs the same one.
    ndkVersion = "24.0.8215888"

    defaultConfig {
        applicationId = "com.wanderwildwood.oboegaki"
        // The Kompakt runs Android 12 (API 31); nothing here needs anything newer, or even
        // that: Supernote's tablets run Android 11 (API 30), and it installs there too.
        minSdk = 30
        targetSdk = 31
        versionCode = 52
        versionName = "0.3.27"

        ndk {
            // The Kompakt is arm64. The emulator an x86_64 debug build is tried on is added
            // below, so a release carries one copy of the native code rather than two.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DGGML_OPENMP=OFF")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // A real keystore in signing/ signs every build type when it is present, so the
    // very first install is already release-signed and a later update can never hit
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE. It is gitignored, and there is no fallback:
    // a fresh clone builds an unsigned release APK, which will not install anywhere.
    val signingPropertiesFile = rootProject.file("signing/signing.properties")
    val realSigningConfig = if (signingPropertiesFile.isFile) {
        val signingProperties = Properties().apply {
            signingPropertiesFile.inputStream().use(::load)
        }
        signingConfigs.create("real") {
            storeFile = rootProject.file("signing/signing.keystore")
            storePassword = signingProperties.getProperty("STORE_PASSWORD")
            keyAlias = signingProperties.getProperty("KEY_ALIAS")
            keyPassword = signingProperties.getProperty("KEY_PASSWORD")
        }
    } else {
        null
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            ndk { abiFilters += "x86_64" }
            realSigningConfig?.let { signingConfig = it }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            realSigningConfig?.let { signingConfig = it }

            // AGP stamps the git revision into META-INF. The build box works from an rsync
            // with no .git and writes NO_SUPPORTED_VCS_FOUND there, while a CI runner writes
            // the real commit -- so with this on, the same version built in the two places
            // has different contents, and the published APK names a commit of his working
            // copy. Off, so neither happens.
            vcsInfo {
                include = false
            }
        }
    }

    // The model is read straight out of the APK by the native code, which needs it stored
    // rather than deflated. It barely compresses anyway.
    androidResources {
        noCompress += "bin"
    }

    lint {
        // Sideloaded onto a Kompakt, not going to Google Play, whose API-33 floor this
        // otherwise trips. Targeting the OS the device actually runs is deliberate.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // The About dialog shows the version it is actually running.
        buildConfig = true
    }

    // Robolectric reads the app's own strings for the layout tests.
    testOptions { unitTests.isIncludeAndroidResources = true }

    sourceSets {
        named("main") {
            kotlin.srcDir("src/main/kotlin")
            assets.srcDir(modelAssets)
        }
        named("test") { kotlin.srcDir("src/test/kotlin") }
    }
}

tasks.named("preBuild") { dependsOn(stageModel) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // Material3 1.4 no longer brings the icons its date picker draws; without them picking a
    // day crashed with NoClassDefFoundError (Icons$Filled).
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.mmd)

    // Nextcloud's files are reached over WebDAV, and WebDAV lists a folder with PROPFIND,
    // a method HttpURLConnection refuses to send.
    implementation(libs.okhttp)

    // Desktop Sync: the small web server inside the app (BSD-3-Clause).
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    testImplementation(libs.junit)
    // The real org.json, for the tests of what the page is sent: the Android one is a stub off the phone.
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Layout tests: a dialog measured at the Kompakt's sizes, upright and sideways, off the phone.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:${libs.versions.androidx.compose.get()}")
    debugImplementation("androidx.compose.ui:ui-test-manifest:${libs.versions.androidx.compose.get()}")
}

// The Licenses page lists what the build actually packs: this plugin walks each variant's runtime
// dependencies and writes res/raw/aboutlibraries.json (one entry per artifact, with its POM's
// license). What Gradle cannot see (fonts, data sets, native code, desugaring) and license texts
// of our own choosing are in config/libraries and config/licenses. Offline: the build fetches
// nothing; the full texts come from res/raw and config/licenses. The app reads the file with
// shared-licences' LicencesJson.kt (no AboutLibraries runtime library).
aboutLibraries {
    offlineMode.set(true)
    collect {
        configPath.set(rootProject.file("config"))
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
    export {
        excludeFields.addAll("funding")
    }
    library {
        mergePlatformArtifacts.set(true)
        // Only what the release APK carries: these come in through debugImplementation alone
        // (measured: the debug list minus the release list). material-icons-core is a real
        // release dependency here, so it stays.
        exclusionPatterns.addAll(
            listOf(
                "androidx.compose.ui:ui-tooling", "androidx.compose.ui:ui-tooling-data",
                "androidx.compose.material:material", "androidx.compose.ui:ui-test-manifest",
            ).map { Pattern.compile("^" + Regex.escape(it) + "$") },
        )
    }
}

// The build fails when a bundled library has no license, or a license with no full text to show
// (neither its own text from config/licenses nor a family text in res/raw). Mudita review, 4 Nov 2026.
val licenceRawTexts: List<String> = file("src/main/res/raw").listFiles().orEmpty().map { it.name.lowercase() }
tasks.matching { it.name.startsWith("prepareLibraryDefinitions") }.configureEach {
    val raw = licenceRawTexts
    inputs.property("licenceRawTexts", raw) // so the check reruns when a text is added or removed
    doLast {
        fun family(n: String): String? {
            val l = n.lowercase()
            return when {
                "classpath" in l -> "classpath"; "lgpl" in l || "lesser general" in l -> "lgpl"
                "gpl" in l || "general public licen" in l -> "gpl"; "unicode" in l -> "unicode"; "apache" in l -> "apache"
                "mpl" in l || "mozilla" in l -> "mpl"; "mit" in l.split(Regex("[^a-z]+")) -> "mit"; "bsd" in l -> "bsd"
                "ofl" in l || "open font" in l -> "ofl"; "cc-by" in l || "cc by" in l || "cc0" in l || "public domain" in l -> "none"
                else -> null
            }
        }
        val bad = mutableListOf<String>()
        outputs.files.asFileTree.matching { include("**/aboutlibraries.json") }.forEach { f ->
            @Suppress("UNCHECKED_CAST")
            val root = groovy.json.JsonSlurper().parse(f) as MutableMap<String, Any?>
            val lics = (root["licenses"] as? Map<String, Map<String, Any?>>).orEmpty()
            // A library's own license file (config/licenses, with its copyright line) stands in for
            // the bare family entry its POM names: the plugin merges config entries in, it never
            // takes one out, so kotpass would otherwise list "MIT" twice, once with no text.
            var changed = false
            (root["libraries"] as? List<MutableMap<String, Any?>>).orEmpty().forEach { lib ->
                val keys = (lib["licenses"] as? List<String>).orEmpty()
                fun fam(k: String) = family((lics[k]?.get("spdxId") ?: lics[k]?.get("name") ?: k).toString())
                fun hasText(k: String) = !(lics[k]?.get("content") as? String).isNullOrBlank()
                val own = keys.filter { hasText(it) }.mapNotNull { fam(it) }.toSet()
                val kept = keys.filterNot { !hasText(it) && fam(it) in own }
                if (kept != keys) { lib["licenses"] = kept; changed = true }
            }
            if (changed) f.writeText(groovy.json.JsonOutput.toJson(root))
            (root["libraries"] as? List<Map<String, Any?>>).orEmpty().forEach { lib ->
                val keys = (lib["licenses"] as? List<String>).orEmpty()
                if (keys.isEmpty()) bad += "${lib["uniqueId"]}: no license"
                keys.forEach { k ->
                    val e = lics[k].orEmpty()
                    if ((e["content"] as? String).isNullOrBlank()) {
                        val fam = family((e["spdxId"] ?: e["name"] ?: k).toString())
                        if (fam == null || (fam != "none" && raw.none { fam in it }))
                            bad += "${lib["uniqueId"]}: no text for ${e["name"] ?: k}"
                    }
                }
            }
        }
        if (bad.isNotEmpty()) throw GradleException("Licenses page: add the text in config/licenses or res/raw: " + bad.distinct().joinToString("; "))
    }
}
