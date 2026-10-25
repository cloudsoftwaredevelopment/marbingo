plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ph.marbingo.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testOptions.targetSdk = 35
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.isIncludeAndroidResources = true
}

// No Compose here: networking, DTOs, money and receipt formatting stay pure so their
// tests run on the plain JVM — the only kind of test this headless server can run.
dependencies {
    implementation(libs.kotlinx.coroutines.android)
    api(libs.retrofit)
    implementation(libs.retrofit.moshi)
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    api(libs.moshi)
    implementation(libs.moshi.kotlin)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
