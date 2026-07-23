plugins {
    alias(libs.plugins.android.library)
}

val pocketIdeAbi = providers.gradleProperty("pocketide.abi").orNull
val supportedPocketIdeAbis = setOf("arm64-v8a", "x86_64")
require(pocketIdeAbi == null || pocketIdeAbi in supportedPocketIdeAbis) {
    "pocketide.abi must be one of: ${supportedPocketIdeAbis.joinToString()}"
}

android {
    namespace = "org.nehuatl.llamacpp"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += pocketIdeAbi?.let(::listOf) ?: supportedPocketIdeAbis
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
