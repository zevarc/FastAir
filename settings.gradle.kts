pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // ZevHub SDK, built by JitPack from the zevarc/zevhub tag.
        maven { url = uri("https://jitpack.io") }
    }
}

include(":app")
