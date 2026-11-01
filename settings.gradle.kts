pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libsu 只发布在 JitPack（root 执行的事实标准库）
        maven("https://jitpack.io")
    }
}

rootProject.name = "HyperMount"
include(":app")
