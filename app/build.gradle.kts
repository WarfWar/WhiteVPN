plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.whitevpn.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.whitevpn.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.8.0"
    }
    buildFeatures {
        compose = true
    }
    sourceSets.getByName("main").java.srcDir(
        rootProject.file("third_party/l2tp/core/src/main/kotlin")
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
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
