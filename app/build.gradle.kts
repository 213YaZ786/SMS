import java.net.URI
import java.security.MessageDigest

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so no kotlin-android here.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "com.yaz.sms"
    // 37 because Compose compiles against it, and the phone app follows the
    // newest platform rules for calls, notifications and permissions.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.yaz.sms"
        minSdk = 31
        targetSdk = 37
        versionCode = 58
        versionName = "0.16.6"
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
        aidl = true // Translator's service interface (kit translate), https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/BuildFeatures#aidl()
    }

    // Screens drawn on the JVM by Robolectric and compared by Roborazzi (its README's setup).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
                it.maxHeapSize = "4096m"
                // Robolectric on JDK 17 and later (https://robolectric.org/getting-started/).
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"
                )
            }
        }
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
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    // Any emoji as a reaction: Android's own emoji picker (Apache-2.0).
    implementation("androidx.emoji2:emoji2-emojipicker:1.7.0")
    // A message of only emoji shows them big and moving: Lottie by Airbnb (Apache-2.0)
    // playing Google's Noto animated emoji (CC BY 4.0) kept in assets/emoji.
    implementation("com.airbnb.android:lottie-compose:6.7.1")
    // Numbers shown as the country writes them, offline: Google's libphonenumber (Apache-2.0),
    // with its own data, no network.
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.42")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.media3.exoplayer)
    // Films too big for the carrier's MMS size are transcoded to fit (MmsVideo).
    implementation("androidx.media3:media3-transformer:${libs.versions.media3.get()}")
    // GIF frames, decoded only inside the isolated DecoderService.
    implementation(libs.glide.gifdecoder)
    // QR codes of the invite to chat without a number (Apache 2.0).
    implementation("com.google.zxing:core:3.5.4")
    // Installs assets/dexopt/baseline.prof (from baseline-prof.txt) at the first launch after each update,
    // so the app is compiled even when it does not come from Play (Google, Apache-2.0,
    // https://github.com/androidx/androidx/tree/androidx-main/profileinstaller/profileinstaller).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Guava comes in transitively at 33.3.1-android (GHSA-xxph-c9ww-hj94): forced to the fixed release
    // with a dependency constraint (https://docs.gradle.org/current/userguide/dependency_constraints.html).
    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("GHSA-xxph-c9ww-hj94")
        }
    }

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The engine of the rich chat: chatmail core's own Android build of
// deltachat-rpc-server (MPL-2.0, github.com/chatmail/core), fetched at
// its pinned version and checked against its SHA-256 before it is packed
// as a native library, so the repository holds no binary.
// Built by this repository's .github/workflows/engine.yml from core 2.65.0's sources with the dependencies
// whose known flaws have a compatible fix moved to it (tokio, event-listener, quinn-proto): its release
// engine-2.65.0-p1, the patched Cargo.lock beside the binaries.
val chatmailVersion = "2.65.0"
val chatmailRelease = "https://github.com/213YaZ786/SMS/releases/download/engine-2.65.0-p1"
val chatmailBinaries = mapOf(
    "arm64-v8a" to ("deltachat-rpc-server-arm64-v8a-android" to "1b687c306eb9d6650fea4491971030ee8d80bf876d76ba787ebe63d1837b1c78"),
    "armeabi-v7a" to ("deltachat-rpc-server-armeabi-v7a-android" to "87d4d2388af289d6244cca298aea1bc6a5d5ca54b6524570f307feb1fa70f147")
)
val chatmailDir = layout.buildDirectory.dir("chatmail/jniLibs")
val fetchChatmail by tasks.registering {
    val out = chatmailDir
    val binaries = chatmailBinaries
    val version = chatmailVersion
    val release = chatmailRelease
    inputs.property("version", version)
    inputs.property("release", release)
    outputs.dir(out)
    doLast {
        fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        binaries.forEach { (abi, asset) ->
            val (name, sha) = asset
            val target = out.get().dir(abi).file("libchatmail.so").asFile
            if (target.exists() && sha256(target.readBytes()) == sha) return@forEach
            target.parentFile.mkdirs()
            val bytes = URI("$release/$name").toURL().openStream().use { it.readBytes() }
            check(sha256(bytes) == sha) { "chatmail $abi: checksum does not match" }
            target.writeBytes(bytes)
        }
    }
}

android.sourceSets.getByName("main").jniLibs.srcDir(chatmailDir.get().asFile)
tasks.named("preBuild") { dependsOn(fetchChatmail) }
