plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.caripc.protocol"
    compileSdk = 33

    defaultConfig {
        minSdk = 30
        targetSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":ipc-contract-api"))
    testImplementation(kotlin("test"))
}
