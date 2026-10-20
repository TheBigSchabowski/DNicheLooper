import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is configured from a file OUTSIDE the repo, so no key
// material or path can be committed by accident. Missing file = unsigned
// release build, which is the right behaviour on a machine that has no
// business signing (a fresh clone, CI, someone else's checkout).
val signingProps = Properties().apply {
    val f = File(System.getProperty("user.home"), "keystores/dnichelooper-signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigningConfig = signingProps.getProperty("storeFile") != null

android {
    namespace = "com.example.dnichelooper"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.dnichelooper"
        // AAudio (required for low-latency USB routing via Oboe) needs API 26+
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                // Oboe's prefab package links against the shared STL
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                val secret = File(signingProps.getProperty("passwordFile")).readText().trim()
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = secret
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = secret
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.oboe)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
