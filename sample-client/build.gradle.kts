plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.caripc.sample.client"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.caripc.sample.client"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":ipc-sdk-ktx"))
    implementation(project(":climate-contract"))
    implementation(project(":display-contract"))
}
