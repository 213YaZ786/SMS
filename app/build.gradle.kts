import java.net.URI
import java.security.MessageDigest

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sms.app"
    // 37 because Compose compiles against it, and the phone app follows the
    // newest platform rules for calls, notifications and permissions.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sms.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 14
        versionName = "0.9.0"
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
