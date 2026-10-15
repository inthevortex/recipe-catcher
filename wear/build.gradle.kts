import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.eu.angsumanray.recipecatcher.wear"
    compileSdk = libs.versions.compileSdk.get().toInt()

    val tagVersionName = System.getenv("VERSION_NAME")
        ?: System.getenv("GITHUB_REF_NAME")?.removePrefix("v")
        ?: "1.0.0"
    val runVersionCode = System.getenv("VERSION_CODE")
        ?: System.getenv("GITHUB_RUN_NUMBER")
        ?: "1"

    defaultConfig {
        applicationId = "org.eu.angsumanray.recipecatcher"
        minSdk = libs.versions.wearMinSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = runVersionCode.toIntOrNull() ?: 1
        versionName = tagVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreBase64 = System.getenv("KEYSTORE_BASE64")
            val keystorePath = System.getenv("KEYSTORE_PATH") ?: System.getenv("RELEASE_KEYSTORE_PATH")
            val storePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS") ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("RELEASE_KEY_PASSWORD")

            if (!keystoreBase64.isNullOrEmpty()) {
                val keystoreFile = File(layout.buildDirectory.asFile.get(), "release.keystore")
                keystoreFile.parentFile?.mkdirs()
                keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                storeFile = keystoreFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            } else if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
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
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Wear Compose
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.navigation)

    // Wear Tiles & ProtoLayout
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.tiles.material)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material)
    implementation(libs.androidx.wear.protolayout.expression)

    // Wear Watchface Complications
    implementation(libs.androidx.wear.watchface.complications.data.source)

    // Play Services Wearable
    implementation(libs.play.services.wearable)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
