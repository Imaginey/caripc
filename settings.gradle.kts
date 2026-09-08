pluginManagement {
    repositories {
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenLocal()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "CarIpc"

include(":ipc-contract-api")
include(":ipc-protocol")
include(":ipc-runtime")
include(":ipc-sdk")
include(":ipc-sdk-ktx")
include(":ipc-registry-app")
include(":climate-contract")
include(":display-contract")
include(":sample-server")
include(":sample-client")
