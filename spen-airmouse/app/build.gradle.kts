plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.webfuturo.airmouse"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.webfuturo.airmouse"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        // Il build debug e' firmato con la debug keystore generata da Gradle,
        // quindi l'APK risultante e' installabile direttamente sul telefono.
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            // Senza keystore di release l'APK release non e' installabile.
            // Per un uso personale usa sempre il build debug.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // Nessuna dipendenza AndroidX: l'app usa solo il framework Android.
    //
    // L'SDK Samsung Pen Remote NON e' una dipendenza di compilazione.
    // Viene raggiunto a runtime per reflection (vedi input/SPenInputSource.kt),
    // cosi' il progetto compila anche senza AAR e l'app non va in crash sui
    // dispositivi dove l'SDK non c'e'.
    //
    // Se metti penremote.aar dentro app/libs/, viene impacchettato
    // automaticamente e la sorgente S Pen si attiva da sola.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
}
