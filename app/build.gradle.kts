plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.telegram.messenger"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.telephoto.editor"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-O3 -std=c++17"
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("telephotoDebug") {
            storeFile = file("telephoto_debug.p12")
            storeType = "PKCS12"
            storePassword = "telephoto_debug_key"
            keyAlias = "telephotokey"
            keyPassword = "telephoto_debug_key"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("telephotoDebug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("telephotoDebug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}
