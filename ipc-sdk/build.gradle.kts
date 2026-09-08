plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.caripc.sdk"
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
}

dependencies {
    api(project(":ipc-runtime"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
