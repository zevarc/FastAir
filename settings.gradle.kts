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
        // Locally published ZevHub SDK (./gradlew :zevhub:publishToMavenLocal).
        mavenLocal()
    }
}

include(":app")
