plugins {
    id("com.android.application")
}

// Firebase is optional. Add app/google-services.json from the Firebase console to enable push.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "org.sigil.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.sigil.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 14
        versionName = "2.0.1"
        buildConfigField("String", "API_BASE", "\"https://sigil.adnand.com\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpg-jdk18on:1.78.1")
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
