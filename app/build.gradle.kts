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
        versionCode = 15
        versionName = "0.6.4"

        // Pixel 9 is arm64. Restricting ABIs keeps the APK ~4x smaller, since the
        // sherpa-onnx AAR ships a full onnxruntime .so per architecture.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // The APK filename is the one filename a user actually sees — it is what
    // lands in Downloads when they fetch a release. Gradle's default,
    // "app-release.apk", says nothing and collides with every other Android
    // project's default. Name it after the app and stamp the version in, so a
    // downloaded file is still identifiable months later and two versions never
    // silently overwrite each other.
    //
    // Named for the app, not the package: users read this, and the package is
    // still "scribe" for upgrade-compatibility reasons they never see.
    base.archivesName = "meeting-transcript"

    applicationVariants.all {
        outputs.all {
            // "meeting-transcript-0.6.1.apk", and "…-0.6.1-debug.apk" for debug.
            // The build type is only worth spelling out when it is not the one
            // people download, so release carries no suffix.
            //
            // Built from defaultConfig.versionName rather than the variant's,
            // because the debug variant's already ends in "-debug" and would
            // otherwise stutter into "0.6.1-debug-debug.apk".
            val base = defaultConfig.versionName
            val suffix = if (buildType.name == "release") "" else "-${buildType.name}"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "meeting-transcript-$base$suffix.apk"
        }
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
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // tar.bz2 extraction for downloaded model bundles
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
}
