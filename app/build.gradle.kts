plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.dynamicisland"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.dynamicisland"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }
    buildTypes {
        release {
            // Debug derlemede Compose çok yavaş çalışır; release'te R8 + baseline profile ile hızlanır
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Kendi anahtarın yok: GitHub'ın debug anahtarıyla imzalanır, telefona direkt kurulabilir
            signingConfig = signingConfigs.getByName("debug")
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
}
