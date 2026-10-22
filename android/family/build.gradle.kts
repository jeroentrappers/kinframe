plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val familyVersionName: String = providers.gradleProperty("versionName").getOrElse("0.1.0")
val familyVersionCode: Int = providers.gradleProperty("versionCode").map { it.toInt() }.getOrElse(1)

// Push through Firebase Cloud Messaging is optional and off by default: UnifiedPush needs no Google services.
// Store builds pass -Pkinframe.fcm=true and supply their own Firebase options as string resources
// (google_app_id, gcm_defaultSenderId, google_api_key, project_id); no google-services.json lives in this repo.
val fcmEnabled: Boolean = providers.gradleProperty("kinframe.fcm").map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "app.kinframe.family"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.kinframe.family"
        minSdk = 26
        targetSdk = 36
        versionCode = familyVersionCode
        versionName = familyVersionName
        buildConfigField("String", "DEFAULT_SERVER", "\"https://kinframe.appmire.be\"")
        buildConfigField("boolean", "FCM_ENABLED", fcmEnabled.toString())
        // Logto's redirect receiver: we replace its filter with our own (app.kinframe:/callback, see the manifest).
        manifestPlaceholders["logtoRedirectScheme"] = "app.kinframe"
        manifestPlaceholders["fcmEnabled"] = fcmEnabled.toString()
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += if (fcmEnabled) "src/fcm/java" else "src/nofcm/java"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
    lint {
        // Translations for the other 34 languages arrive separately; English and Dutch are complete.
        disable += "MissingTranslation"
    }
    packaging { resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)
    implementation(libs.logto.android)
    // UnifiedPush depends on the plain-Java tink, EncryptedSharedPreferences on tink-android (a superset that also
    // has the Android Keystore integration). Use tink-android of the same version for both.
    implementation(libs.unifiedpush.connector) { exclude(group = "com.google.crypto.tink", module = "tink") }
    implementation(libs.tink.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    if (fcmEnabled) implementation(libs.firebase.messaging)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
