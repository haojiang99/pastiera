plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// File per salvare il build number incrementale
val buildPropertiesFile = file("build.properties")

// Task per incrementare il build number
tasks.register("incrementBuildNumber") {
    doLast {
        val buildNumber = if (buildPropertiesFile.exists()) {
            val props = Properties()
            buildPropertiesFile.inputStream().use { props.load(it) }
            (props.getProperty("buildNumber", "0").toIntOrNull() ?: 0) + 1
        } else {
            1
        }
        
        // Ottieni data/ora corrente
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.ITALIAN)
        val buildDate = dateFormat.format(Date())
        
        // Salva nel file
        val props = Properties()
        props.setProperty("buildNumber", buildNumber.toString())
        props.setProperty("buildDate", buildDate)
        buildPropertiesFile.outputStream().use { props.store(it, "Build number and date") }
        
        println("Build number incremented to: $buildNumber - $buildDate")
    }
}

android {
    namespace = "it.neuralrad.coolwulf"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.neuralrad.coolwulf"
        minSdk = 23
        targetSdk = 36
        versionCode = 18
        versionName = "0.77"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only include arm64-v8a to reduce APK size (for Titan 2 and modern devices)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        
        // Leggi build number e data dal file
        val buildNumber = if (buildPropertiesFile.exists()) {
            val props = Properties()
            buildPropertiesFile.inputStream().use { props.load(it) }
            props.getProperty("buildNumber", "0").toIntOrNull() ?: 0
        } else {
            0
        }
        
        val buildDate = if (buildPropertiesFile.exists()) {
            val props = Properties()
            buildPropertiesFile.inputStream().use { props.load(it) }
            props.getProperty("buildDate", "")
        } else {
            ""
        }
        
        // Aggiungi a BuildConfig
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore.properties")
            if (keystorePropertiesFile.exists()) {
                val keystoreProperties = Properties()
                keystoreProperties.load(keystorePropertiesFile.inputStream())
                storeFile = file("../pastiera-release-key.jks")
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            } else {
                // Fallback for CI/CD or local builds without the file
                // Uses environment variables if available
                storeFile = file("../pastiera-release-key.jks")
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "pastiera"
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // Disable lint for release to avoid file lock issues
            isDebuggable = false
        }
    }
    
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Exclude duplicate Kotlin wrapper classes from sherpa-onnx AAR
    // We use our own updated wrapper classes that match the JNI code
    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Esegui incrementBuildNumber prima di preBuild
    tasks.named("preBuild").configure {
        dependsOn("incrementBuildNumber")
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    // RecyclerView per performance ottimali nella griglia emoji
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Emoji2 per supporto emoji future-proof
    implementation("androidx.emoji2:emoji2:1.4.0")
    implementation("androidx.emoji2:emoji2-views:1.4.0")
    implementation("androidx.emoji2:emoji2-views-helper:1.4.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // Vosk offline speech recognition for Mandarin Chinese
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")

    // Apache Commons Compress for tar.bz2 extraction (Sherpa-ONNX models)
    implementation("org.apache.commons:commons-compress:1.26.0")

    // Sherpa-ONNX offline speech recognition (better accuracy for Chinese)
    // Native libraries extracted from official AAR, Kotlin wrappers in our source
    // The .so files should be in app/src/main/jniLibs/arm64-v8a/
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
