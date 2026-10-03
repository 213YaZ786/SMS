import java.net.URI
import java.security.MessageDigest

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// whisper.cpp, the engine that writes voice messages out: see fetchWhisper below.
val whisperVersion = "1.9.4"
val whisperSha = "57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae"
val whisperRoot = layout.buildDirectory.dir("whisper").get().asFile
val whisperSrc = File(whisperRoot, "whisper.cpp-$whisperVersion")

android {
    namespace = "com.yaz.sms"
    // 37 because Compose compiles against it, and the phone app follows the
    // newest platform rules for calls, notifications and permissions.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yaz.sms"
        minSdk = 31
        targetSdk = 37
        versionCode = 23
        versionName = "0.13.1"
        // Voice messages written out on the phone: whisper.cpp, for 64-bit ARM phones.
        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a")
                arguments("-DWHISPER_SRC=${whisperSrc.absolutePath}", "-DANDROID_STL=c++_shared")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = providers.gradleProperty("sms.storeFile").orNull
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = providers.gradleProperty("sms.storePassword").orNull
                keyAlias = providers.gradleProperty("sms.keyAlias").orNull
                keyPassword = providers.gradleProperty("sms.keyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Only attached when the signing properties are supplied, so a
            // local build without a keystore still produces an unsigned APK.
            if (providers.gradleProperty("sms.storeFile").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // The chat engine is a program, run from where Android unpacks it.
        jniLibs.useLegacyPackaging = true
        // WebRTC's builds for x86 computers (emulators) stay out of the phone APK.
        jniLibs.excludes += setOf("lib/x86/libjingle_peerconnection_so.so", "lib/x86_64/libjingle_peerconnection_so.so")
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES"
        )
    }
}

dependencies {
    // The encrypted calls' voice: WebRTC's Android build by webrtc-sdk (MIT, LiveKit's community).
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    // Any emoji as a reaction: Android's own emoji picker (Apache-2.0).
    implementation("androidx.emoji2:emoji2-emojipicker:1.7.0")
    // A message of only emoji shows them big and moving: Lottie by Airbnb (Apache-2.0)
    // playing Google's Noto animated emoji (CC BY 4.0) kept in assets/emoji.
    implementation("com.airbnb.android:lottie-compose:6.7.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

// whisperVersion and its source's checksum are pinned at the top.
// The engine that writes voice messages out: whisper.cpp (MIT,
// github.com/ggml-org/whisper.cpp), its source fetched at the pinned
// release and checked against its SHA-256, then compiled here; the speech
// model itself is fetched by the app, only when the user asks for it.
val fetchWhisper by tasks.registering {
    val root = whisperRoot
    val src = whisperSrc
    val version = whisperVersion
    val sha = whisperSha
    inputs.property("version", version)
    outputs.dir(src)
    doLast {
        if (File(src, "CMakeLists.txt").exists()) return@doLast
        val bytes = URI("https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v$version.tar.gz").toURL().openStream().use { it.readBytes() }
        val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(got == sha) { "whisper.cpp: checksum does not match" }
        root.mkdirs()
        val archive = File(root, "whisper.tar.gz").apply { writeBytes(bytes) }
        val tar = ProcessBuilder("tar", "xzf", archive.path, "-C", root.path).inheritIO().start()
        check(tar.waitFor() == 0) { "whisper.cpp: could not unpack" }
        archive.delete()
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name.startsWith("generateJsonModel")) dependsOn(fetchWhisper)
}

// The engine of the rich chat: chatmail core's own Android build of
// deltachat-rpc-server (MPL-2.0, github.com/chatmail/core), fetched at
// its pinned version and checked against its SHA-256 before it is packed
// as a native library, so the repository holds no binary.
val chatmailVersion = "2.62.0"
val chatmailBinaries = mapOf(
    "arm64-v8a" to ("deltachat-rpc-server-arm64-v8a-android" to "4b215745f22c25661d8a3787345ef5b5f65d6d6747d7f40763c463e85ab61922"),
    "armeabi-v7a" to ("deltachat-rpc-server-armeabi-v7a-android" to "6120c9b87af69405632427385c7b8447ac177701c825290e2df53a89147d2e35")
)
val chatmailDir = layout.buildDirectory.dir("chatmail/jniLibs")
val fetchChatmail by tasks.registering {
    val out = chatmailDir
    val binaries = chatmailBinaries
    val version = chatmailVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        binaries.forEach { (abi, asset) ->
            val (name, sha) = asset
            val target = out.get().dir(abi).file("libchatmail.so").asFile
            if (target.exists() && sha256(target.readBytes()) == sha) return@forEach
            target.parentFile.mkdirs()
            val bytes = URI("https://github.com/chatmail/core/releases/download/v$version/$name").toURL().openStream().use { it.readBytes() }
            check(sha256(bytes) == sha) { "chatmail $abi: checksum does not match" }
            target.writeBytes(bytes)
        }
    }
}

android.sourceSets.getByName("main").jniLibs.srcDir(chatmailDir.get().asFile)
tasks.named("preBuild") { dependsOn(fetchChatmail) }
