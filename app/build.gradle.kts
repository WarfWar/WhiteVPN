plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.whitevpn.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.whitevpn.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.11.1"
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    // CI runners create different Android debug signing keys each build.
    // A separate test application ID prevents INSTALL_FAILED_UPDATE_INCOMPATIBLE
    // and leaves the user's existing WhiteVPN profiles untouched.
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
        }
    }
    buildFeatures { compose = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets.getByName("main").java.srcDir(
        rootProject.file("third_party/l2tp/core/src/main/kotlin")
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}

// Compile the MIT-licensed tun2socks engine from pinned, recursive git submodules.
// NDK output is generated during the build and must not be committed to Git.
val compileHev by tasks.registering(Exec::class) {
    group = "build"
    description = "Compile embedded hev-socks5-tunnel for Android ABIs"
    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        "$ndkDir/ndk-build.cmd" else "$ndkDir/ndk-build"
    workingDir = rootProject.projectDir
    args(
        "NDK_PROJECT_PATH=app/build/intermediates/hevNdk",
        "NDK_LIBS_OUT=app/src/main/jniLibs",
        "APP_BUILD_SCRIPT=third_party/hev-socks5-tunnel/Android.mk",
        "NDK_APPLICATION_MK=third_party/hev-socks5-tunnel/Application.mk",
        "APP_ABI=arm64-v8a armeabi-v7a x86_64"
    )
}
tasks.named("preBuild") { dependsOn(compileHev) }
