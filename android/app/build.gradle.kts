plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.lorenzomp7.usageai"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lorenzomp7.usageai"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "2.2"
    }

    // Chiave di firma fissa nella repo: ogni build di GitHub Actions si installa sopra la precedente
    // senza dover disinstallare l'app. È una chiave di debug, non adatta al Play Store.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
