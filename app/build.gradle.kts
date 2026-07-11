import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

val extFirebaseApiKey: String = System.getenv("FIREBASE_API_KEY")
    ?: localProperties.getProperty("firebase.api.key")
    ?: "AIzaSyFakeApiKey"

val extFirebaseAppId: String = System.getenv("FIREBASE_APP_ID")
    ?: localProperties.getProperty("firebase.app.id")
    ?: "1:fake:web:appid"

val extFirebaseDbUrl: String = System.getenv("FIREBASE_DB_URL")
    ?: localProperties.getProperty("firebase.db.url")
    ?: "https://fake-db.firebaseio.com"

android {
    namespace = "com.example.securesolver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.securesolver"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "FIREBASE_API_KEY", "\"$extFirebaseApiKey\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"$extFirebaseAppId\"")
        buildConfigField("String", "FIREBASE_DB_URL", "\"$extFirebaseDbUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // CameraX
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Ktor
    implementation(libs.ktor.network)
    implementation(libs.ktor.io)

    // ZXing
    implementation(libs.zxing.core)

    // WebRTC & Firebase
    implementation(libs.webrtc)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.database)
}

