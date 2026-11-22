plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // namespace kaynak kod paketidir, aynı kalır; mağazadaki kimlik applicationId'dir
    namespace = "com.example.dynamicisland"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.whiteglow.omniisland"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        // ML Kit çeviri her işlemci mimarisi için ayrı yerel kütüphane taşır (APK ~115 MB oluyordu).
        // Modern telefonlar arm64-v8a kullanır; yalnızca onu paketlemek boyutu belirgin küçültür.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Sabit imza: GitHub Secrets doluysa kullanılır, yoksa debug anahtarına düşer
    val ksPath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Debug derlemede Compose çok yavaş çalışır; release'te R8 + baseline profile ile hızlanır
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    // Compose'un hazır baseline profile'ını kurulumda derletir (ilk açılış ve kaydırma akıcı olur)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Çeviri paneli: cihazda çalışan ML Kit çeviri (ücretsiz, API anahtarı yok; dil modeli ilk kullanımda iner)
    implementation("com.google.mlkit:translate:17.0.3")
    // Bildirim çevirisi: bildirimin dilini cihazda tanır (internet gerekmez)
    implementation("com.google.mlkit:language-id:17.0.5")
}
