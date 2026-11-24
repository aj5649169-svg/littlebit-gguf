plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.littlebit.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.littlebit.chat"
        minSdk = 28          // matches -DANDROID_PLATFORM=android-28 in scripts/build-android.sh
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Native engine is arm64-only: KleidiAI kernels + LittleBit path are built for arm64-v8a.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3", "-fvisibility=hidden")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    // Keep in sync with .github/workflows/build-android-apk.yml (sdkmanager ndk;26.1.10909125).
    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Extract native libs on install: faster System.loadLibrary for the multi-MB engine.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
}
