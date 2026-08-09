plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.newfuel.fuelalert"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.newfuel.fuelalert"
        // 29 (Android 10), not lower: this app's core behavior depends on
        // ACCESS_BACKGROUND_LOCATION, which only exists as a distinct,
        // separately-granted permission from API 29 onward - below that,
        // foreground location permission already implied background access,
        // which is a different (and today, unavailable) permission model to
        // build against. Building for 29+ only means one permission model to
        // reason about, not two.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            // 10.0.2.2 is the Android emulator's alias for the host
            // machine's localhost - matches tools/mock_backend.py run
            // locally during development (see PRD.md ss6 / PROGRESS.md
            // milestone 1). Real device testing against a real backend
            // needs an actual reachable URL here once hosting (PRD ss6,
            // still an open dependency) is decided - not before.
            buildConfigField("String", "BACKEND_BASE_URL", "\"http://10.0.2.2:8765\"")
        }
        release {
            isMinifyEnabled = false
            // Deliberately left unset until real backend hosting (PRD ss6)
            // exists - shipping a placeholder here would look configured
            // but silently fail on install, which is worse than failing to
            // compile the release build until it's actually decided.
            buildConfigField("String", "BACKEND_BASE_URL", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // PriceFetcher (backend/PriceFetcher.kt) - both confirmed reachable
    // on Maven Central (not Google-Maven-only, unlike androidx.*), which
    // is how PriceFetcher.kt could actually be compile-checked this
    // session - see mobile/PROGRESS.md milestone 2.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    // Local JVM unit tests run against Gradle's android.jar stub, which
    // throws "not mocked" for every android.* class it provides -
    // including org.json.*, since that package ships inside android.jar
    // even though it's really the standalone org.json library underneath.
    // This pulls in the real implementation so tests that exercise
    // PriceFetcher's JSON parsing (PriceFetcherTest.kt) run against real
    // org.json code instead of throwing on first use - the standard,
    // well-known workaround for this specific Android testing gotcha.
    testImplementation("org.json:json:20240303")
}
