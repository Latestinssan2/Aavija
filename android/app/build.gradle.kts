// The app module build file — Android app that advertises Aavija over Bonjour.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aartiq.aavija"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aartiq.aavija"
        minSdk = 26          // Android 8.0 — matches the plan
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // BouncyCastle ships three jars (bcprov/bcutil/bcpkix) that each contain
        // the same OSGi manifest file — Android's merger refuses duplicate paths,
        // so drop the duplicates and keep only our own resources.
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/*.SF"
            excludes += "META-INF/*.DSA"
            excludes += "META-INF/*.RSA"
        }
    }
}

dependencies {
    // Minimal Compose stack — enough for one screen with text + status.
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // BouncyCastle: used ONLY to mint a self-signed X.509 certificate for the
    // TLS server (Phase 3a). We don't register it as a JCA provider — we just
    // use its ASN.1 builders — which avoids the usual Android class conflicts.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // Phase 5: gesture detection. CameraX supplies frames; MediaPipe's
    // HandLandmarker turns each frame into 21 hand points, and HandGestureMachine
    // (pure Kotlin, JVM-tested) decides GRAB/RELEASE.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // JUnit for the protocol tests (TLS handshake, header parsing, checksum,
    // PIN lockout...). These run on the JVM, not the phone.
    testImplementation("junit:junit:4.13.2")

    // Real org.json for JVM unit tests. On Android the platform provides
    // org.json, but the unit-test stub throws "not mocked" — this artifact
    // shadows it so the pairing protocol parser is testable off-device.
    testImplementation("org.json:json:20240303")
}
