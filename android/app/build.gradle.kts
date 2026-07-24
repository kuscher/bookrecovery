plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.google.chrome.recovery"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.google.chrome.recovery"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Pinned past the BOM's 1.4.0: every Material 3 Expressive API (wavy progress,
    // LoadingIndicator, MaterialExpressiveTheme, MaterialShapes) lives on the
    // 1.5.0-alpha track — Expressive was removed from the 1.4 line at beta01.
    implementation("androidx.compose.material3:material3:1.5.0-alpha23")
    // material3 stopped depending on material-icons transitively in newer BOMs;
    // the icons artifacts are frozen at 1.7.x and are no longer BOM-managed.
    implementation("androidx.compose.material:material-icons-core:1.7.8")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Adaptive layouts (window size classes + list-detail pane scaffold).
    // material3-adaptive-navigation-suite is deliberately absent: it exists for
    // apps with top-level destinations (bar/rail/drawer); a linear wizard has none.
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.2.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.2.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // JSON Parsing (Gson or Kotlinx Serialization, let's use Gson for simplicity)
    implementation("com.google.code.gson:gson:2.14.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
