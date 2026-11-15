plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.florian.teslamate"
    compileSdk = 37
    defaultConfig {
        applicationId = "fr.florian.teslamate"
        minSdk = 26
        targetSdk = 34
        // Fournis par les workflows Forge-Apk (numéro de run, tag sans le « v »)
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
        resValue("string", "app_name", "TeslaMate")
    }
    // Clé fixe pour que chaque release s'installe par-dessus la précédente
    signingConfigs {
        create("release") {
            System.getenv("KEYSTORE_FILE")?.let { storeFile = file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }
    buildTypes {
        // La debug s'installe à côté de la release : autre identifiant, autre nom
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug" + (System.getenv("GITHUB_RUN_NUMBER")?.let { ".$it" } ?: "")
            resValue("string", "app_name", "TeslaMate debug")
        }
        release { signingConfig = signingConfigs.getByName("release") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.VERSION_NAME, affiché dans les réglages
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20") // cartes OpenStreetMap, sans clé
}
