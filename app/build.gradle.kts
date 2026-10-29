import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The AppGallery Connect plugin needs agconnect-services.json, so it is only applied once you add it.
if (file("agconnect-services.json").exists()) {
    apply(plugin = "com.huawei.agconnect")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.petaldrive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.petaldrive" // change to your own; it must match the app you register in AppGallery Connect
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "PETAL_API_KEY", "\"${localProps.getProperty("PETAL_API_KEY", "")}\"")
        buildConfigField("String", "PETAL_STYLE_ID", "\"${localProps.getProperty("PETAL_STYLE_ID", "")}\"")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.huawei.hms:maps:6.11.2.301")
    implementation("com.huawei.hms:maps-basic:6.11.2.301")
}
