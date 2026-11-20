import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// tools/prepare.py schreibt diese Datei aus apps/<app>.json (Paketname, Version)
val gen = Properties().apply {
    val f = file("generated.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "de.kasparkk.appshell"
    compileSdk = 35

    defaultConfig {
        applicationId = gen.getProperty("package", "de.kasparkk.appshell")
        minSdk = 26
        targetSdk = 35
        versionCode = run
        versionName = "1.0.$run"
        manifestPlaceholders["fileAuthority"] = "${applicationId}.files"
    }

    // Fester Schluessel im Repo: Updates installieren sich ueber die alte Version
    signingConfigs {
        create("fixed") {
            storeFile = file("shell.keystore")
            storePassword = "appshell"
            keyAlias = "shell"
            keyPassword = "appshell"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
