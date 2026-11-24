import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase settings are read straight from app/google-services.json (download it from the
// Firebase console). No Google Services plugin is needed, and the app still builds without
// the file: sync is then simply turned off.
@Suppress("UNCHECKED_CAST")
val firebase: Map<String, String> = run {
    val f = file("google-services.json")
    if (!f.exists()) return@run emptyMap()
    val json = JsonSlurper().parse(f) as Map<String, Any?>
    val project = json["project_info"] as Map<String, Any?>
    val clients = json["client"] as List<Map<String, Any?>>
    fun info(c: Map<String, Any?>) = c["client_info"] as Map<String, Any?>
    val client = clients.firstOrNull {
        (info(it)["android_client_info"] as Map<String, Any?>)["package_name"] == "com.stickynotes.app"
    } ?: clients.first()
    mapOf(
        "apiKey" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String),
        "appId" to (info(client)["mobilesdk_app_id"] as String),
        "projectId" to (project["project_id"] as String),
    )
}

android {
    namespace = "com.stickynotes.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stickynotes.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebase["apiKey"] ?: ""}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebase["appId"] ?: ""}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebase["projectId"] ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so a release APK can be side-loaded without extra setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
