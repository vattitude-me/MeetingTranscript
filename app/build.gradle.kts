import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "me.vattitude.scribe"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.vattitude.scribe"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        // Pixel 9 is arm64. Restricting ABIs keeps the APK ~4x smaller, since the
        // sherpa-onnx AAR ships a full onnxruntime .so per architecture.
        ndk { abiFilters += listOf("arm64-v8a") }
    }


    // Release signing reads keystore.properties, which is NOT committed. Without it
    // the release build still assembles, just unsigned — see README > Signing.
    signingConfigs {
        create("release") {
            val props = Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) {
                f.inputStream().use { props.load(it) }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (rootProject.file("keystore.properties").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    // Fetched by scripts/setup.sh — see README. Not committed (48 MB).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // tar.bz2 extraction for downloaded model bundles
    implementation("org.apache.commons:commons-compress:1.27.1")
}
