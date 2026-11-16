import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Your Spotify app's Client ID, kept out of git in local.properties.
val spotifyClientId: String = Properties()
    .apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
    .getProperty("spotify.clientId")
    ?: throw GradleException("Add spotify.clientId=<your Spotify Client ID> to local.properties (see README).")

android {
    namespace = "com.peterhull.shelf"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.peterhull.shelf"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
    }

    buildTypes {
        release {
            // Signed with the debug key so the SHA-1 registered in the Spotify dashboard still matches.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Spotify's App Remote SDK isn't on Maven: put its .aar in app/libs (see README).
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("com.google.code.gson:gson:2.11.0") // required by the App Remote SDK

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
