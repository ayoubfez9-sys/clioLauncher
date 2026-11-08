import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.ayoub.cliolauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.ayoub.cliolauncher"
        minSdk = 26
        // 34 volontairement : evite le mode "bord a bord" force d'Android 15
        targetSdk = 34
        versionCode = 10
        versionName = "0.7-details"
    }

    // Une seule cle de signature, identique sur Android Studio et sur GitHub :
    // les mises a jour s'installent par-dessus sans desinstaller le launcher.
    signingConfigs {
        create("clio") {
            storeFile = file("clio.jks")
            storePassword = "cliolauncher"
            keyAlias = "clio"
            keyPassword = "cliolauncher"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("clio")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("clio")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// Telecharge la police Rajdhani (licence OFL) au moment de la compilation.
// Si le telechargement echoue, l'appli utilise une police systeme a la place.
val downloadFonts by tasks.registering {
    val dir = file("src/main/assets/fonts")
    doLast {
        dir.mkdirs()
        listOf("Regular", "Medium", "SemiBold", "Bold").forEach { w ->
            val f = File(dir, "Rajdhani-$w.ttf")
            if (!f.exists() || f.length() < 1000) {
                try {
                    URI("https://github.com/google/fonts/raw/main/ofl/rajdhani/Rajdhani-$w.ttf")
                        .toURL().openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                } catch (e: Exception) {
                    f.delete()
                    logger.warn("Police Rajdhani-$w non telechargee : ${e.message}")
                }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadFonts) }
