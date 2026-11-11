import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Machine-local settings that are never committed (local.properties is git-ignored): the Google OAuth client ID and where the
// release signing key lives.
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }

// Release signing: local.properties names a properties file outside the repository
// (sharemate.release.keystoreProperties=C:/.../keystore.properties) holding storeFile, storePassword, keyAlias and keyPassword.
// Without it `assembleRelease` still builds, unsigned, so a fresh checkout never needs the key.
val releaseSigning: Properties? = localProps.getProperty("sharemate.release.keystoreProperties")?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }?.takeIf { it.exists() }?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "ai.sharemate.app"
    compileSdk = 35
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.sharemate.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 22
        versionName = "2.12.0"
        // Google sign-in (phone): the OAuth Web client ID of the company's Google Cloud project, from local.properties
        // (sharemate.googleWebClientId=...apps.googleusercontent.com) or -Psharemate.googleWebClientId. Empty = sign-in disabled.
        val googleClientId = (project.findProperty("sharemate.googleWebClientId") as String?) ?: localProps.getProperty("sharemate.googleWebClientId", "")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleClientId\"")
    }

    signingConfigs {
        releaseSigning?.let { signing ->
            create("release") {
                storeFile = rootProject.file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // Not minified: the Pleos Vehicle SDK and the JSON codecs are used as shipped, with no keep rules to maintain.
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    // Sign in with Google on the phone through Credential Manager.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; the Gleo parameter parser needs the real implementation.
    testImplementation("org.json:json:20260814")
}
