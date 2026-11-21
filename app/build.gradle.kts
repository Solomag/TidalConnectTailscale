import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun props(name: String): Properties? = rootProject.file(name).takeIf { it.exists() }?.let { f ->
    Properties().apply { f.reader().use { r -> load(r) } }
}

// Debug builds only: a default PC for when the settings screen is empty.
// -PtsHosts=..., else tsHosts in ts.properties (not in git). Release builds ship none.
val tsHosts = (findProperty("tsHosts") as String?)?.takeIf { it.isNotBlank() && "ts.properties" !in it }
    ?: props("ts.properties")?.getProperty("tsHosts").orEmpty()

// Release signing: keystore.properties (not in git) = storeFile, storePassword, keyAlias, keyPassword.
val keystore = props("keystore.properties")

fun quoted(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.solomag.tidalconnectts"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.solomag.tidalconnectts"
        minSdk = 34 // registerServiceInfoCallback, NsdServiceInfo.setHostAddresses
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"
    }

    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = rootProject.file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "HOSTS", quoted(tsHosts))
        }
        release {
            buildConfigField("String", "HOSTS", quoted(""))
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // libxposed:service is published with newer Kotlin metadata than our 1.9 compiler.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

base.archivesName.set("TidalConnectTailscale-v${android.defaultConfig.versionName}")

dependencies {
    // Modern Xposed API: provided by the framework in the hooked process.
    compileOnly("io.github.libxposed:api:102.0.0")
    // Settings screen → the framework's remote preferences (101.0.0 matches targetApiVersion=101).
    implementation("io.github.libxposed:service:101.0.0")
}
