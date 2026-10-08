import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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
        // The Kompakt runs Android 12 (API 31); nothing here needs anything newer.
        minSdk = 31
        targetSdk = 31
        versionCode = 10
        versionName = "0.2.0"

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
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.mmd)

    // Nextcloud's files are reached over WebDAV, and WebDAV lists a folder with PROPFIND,
    // a method HttpURLConnection refuses to send.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
