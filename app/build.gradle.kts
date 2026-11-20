import java.text.SimpleDateFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mob.lee.fastair"
        minSdk = 24
        targetSdk = 37
        versionCode = 11
        versionName = "1.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        resourceConfigurations += listOf("en", "zh-rCN", "zh-rHK", "zh-rTW", "pt-rBR", "ru-rRU")
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    namespace = "com.mob.lee.fastair"
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            val name = "${variant.applicationId.get()}_${output.versionName.get()}_${
                SimpleDateFormat("yyyy_MM_dd_HH_mm").format(System.currentTimeMillis())
            }.apk"
            output.outputFileName.set(name)
        }
    }
}

tasks.register<Delete>("clearAssets") {
    val assetsDir = file("src/main/assets")
    delete(
        fileTree(assetsDir).matching {
            exclude("about.html", "help.html")
        },
        file("$assetsDir/app")
    )
}

tasks.register<Exec>("frontEnd") {
    dependsOn("clearAssets")
    workingDir("$rootDir/FastSvelte")
    val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
    commandLine(npm, "run", "build")
}

tasks.register<Copy>("toAssets") {
    val assetsDir = file("src/main/assets")
    dependsOn("frontEnd")
    from("$rootDir/FastSvelte/build")
    into(assetsDir)
    doLast {
        File(assetsDir, "_app").renameTo(File(assetsDir, "app"))
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.viewpager2)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
