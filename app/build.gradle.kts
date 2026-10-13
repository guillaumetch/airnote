plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val stableKeystorePath = providers.environmentVariable("AIRNOTE_ANDROID_KEYSTORE_PATH").orNull
val stableKeystorePassword = providers.environmentVariable("AIRNOTE_ANDROID_KEYSTORE_PASSWORD").orNull
val stableKeyAlias = providers.environmentVariable("AIRNOTE_ANDROID_KEY_ALIAS").orNull
val stableKeyPassword = providers.environmentVariable("AIRNOTE_ANDROID_KEY_PASSWORD").orNull
val stableSigningValues = listOf(
    stableKeystorePath,
    stableKeystorePassword,
    stableKeyAlias,
    stableKeyPassword
)
val anyStableSigningConfigured = stableSigningValues.any { !it.isNullOrBlank() }
val allStableSigningConfigured = stableSigningValues.all { !it.isNullOrBlank() }

check(!anyStableSigningConfigured || allStableSigningConfigured) {
    "Stable AIRNOTE signing is partially configured. Set AIRNOTE_ANDROID_KEYSTORE_PATH, " +
        "AIRNOTE_ANDROID_KEYSTORE_PASSWORD, AIRNOTE_ANDROID_KEY_ALIAS, and AIRNOTE_ANDROID_KEY_PASSWORD together."
}

android {
    namespace = "com.airnote.hardwareprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.airnote.hardwareprobe"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val stableSigningConfig = if (allStableSigningConfigured) {
        signingConfigs.create("airnoteStable") {
            storeFile = file(requireNotNull(stableKeystorePath))
            storePassword = requireNotNull(stableKeystorePassword)
            keyAlias = requireNotNull(stableKeyAlias)
            keyPassword = requireNotNull(stableKeyPassword)
        }
    } else {
        null
    }

    buildTypes {
        getByName("debug") {
            stableSigningConfig?.let { signingConfig = it }
        }
        getByName("release") {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // Gate 09: native Seeker settlement. MWA keeps Solana private keys in the user's wallet.
    implementation("com.solanamobile:mobile-wallet-adapter-clientlib-ktx:2.0.7")
    implementation("com.solanamobile:web3-solana:0.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.85")
}
