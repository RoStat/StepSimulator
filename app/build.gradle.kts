// Module applicatif : StepSimulator v3 (Kotlin + Health Connect).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rostat.stepsimulator"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rostat.stepsimulator"
        minSdk = 28          // Health Connect (app Play Store) exige Android 9 minimum
        targetSdk = 36
        versionCode = 3
        versionName = "3.0"
    }

    // Clé de debug versionnée dans le dépôt (app/debug.keystore, mot de passe standard "android").
    // Objectif : chaque APK produit par la CI est signé avec la MÊME clé, donc une nouvelle
    // version s'installe par-dessus l'ancienne sans désinstaller (sinon : "App non installée").
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Bibliothèque officielle Health Connect (StepsRecord, permissions, insertion)
    implementation("androidx.health.connect:connect-client:1.1.0")
    // ComponentActivity + registerForActivityResult (demande de permissions)
    implementation("androidx.activity:activity-ktx:1.10.1")
    // NotificationCompat, ContextCompat
    implementation("androidx.core:core-ktx:1.16.0")
    // lifecycleScope (coroutines liées à l'écran)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    // Coroutines (l'API Health Connect est asynchrone : fonctions "suspend")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
