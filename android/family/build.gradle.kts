plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val familyVersionName: String = providers.gradleProperty("versionName").getOrElse("0.1.0")
val familyVersionCode: Int = providers.gradleProperty("versionCode").map { it.toInt() }.getOrElse(1)

// Crash reports (docs/spec/crash-reporting.md): only builds given -Pkinframe.sentryDsn=<DSN> (release tooling) report.
val sentryDsn: String = providers.gradleProperty("kinframe.sentryDsn").getOrElse("").trim()

// Push through Firebase Cloud Messaging is optional and off by default: UnifiedPush needs no Google services.
// Store builds pass -Pkinframe.fcm=true and supply their own Firebase options as string resources
// (google_app_id, gcm_defaultSenderId, google_api_key, project_id); no google-services.json lives in this repo.
val fcmEnabled: Boolean = providers.gradleProperty("kinframe.fcm").map { it.toBoolean() }.getOrElse(false)
// The Firebase options can also come from a google-services.json outside the repo: KINFRAME_GOOGLE_SERVICES=<path>.
@Suppress("UNCHECKED_CAST")
val firebaseOptions: Map<String, String> = System.getenv("KINFRAME_GOOGLE_SERVICES")?.takeIf { fcmEnabled }?.let { path ->
    val json = groovy.json.JsonSlurper().parse(file(path)) as Map<String, Any?>
    val project = json["project_info"] as Map<String, Any?>
    val client = (json["client"] as List<Map<String, Any?>>).first {
        ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "app.kinframe.family"
    }
    mapOf(
        "google_app_id" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String,
        "gcm_defaultSenderId" to project["project_number"] as String,
        "project_id" to project["project_id"] as String,
        "google_api_key" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String),
        "google_storage_bucket" to (project["storage_bucket"] as String? ?: ""),
    )
}.orEmpty()

// Release signing for Play (upload key): from the environment, e.g. ~/.config/play/kinframe-signing.env sourced by
// fastlane. Without it, release builds fall back to the debug key (local testing only; Play rejects those).
val uploadKeystore: String? = System.getenv("KINFRAME_UPLOAD_KEYSTORE")

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
        buildConfigField("String", "SENTRY_DSN", "\"\"")
        buildConfigField("boolean", "FCM_ENABLED", fcmEnabled.toString())
        // Logto's redirect receiver: we replace its filter with our own (app.kinframe:/callback, see the manifest).
        manifestPlaceholders["logtoRedirectScheme"] = "app.kinframe"
        manifestPlaceholders["fcmEnabled"] = fcmEnabled.toString()
        firebaseOptions.forEach { (k, v) -> resValue("string", k, v) }
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += if (fcmEnabled) "src/fcm/java" else "src/nofcm/java"
        }
    }

    signingConfigs {
        if (uploadKeystore != null) create("upload") {
            storeFile = file(uploadKeystore)
            storePassword = System.getenv("KINFRAME_UPLOAD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KINFRAME_UPLOAD_KEY_ALIAS")
            keyPassword = System.getenv("KINFRAME_UPLOAD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // Only release builds report crashes, so tests run in the same build as a release stay quiet.
            buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
        resValues = true
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

androidComponents {
    // Minified builds carry their R8 mapping's ProGuard UUID for GlitchTip (buildSrc/SentryProguardUuid.kt).
    onVariants(selector().all()) { variant ->
        if (!variant.isMinifyEnabled) return@onVariants
        val uuid = tasks.register<SentryProguardUuid>("${variant.name}SentryProguardUuid") {
            mapping.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.OBFUSCATION_MAPPING_FILE))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(uuid, SentryProguardUuid::output)
    }
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
    // QR codes of feed invite links.
    implementation(libs.zxing.core)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    if (fcmEnabled) implementation(libs.firebase.messaging)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    // SmokeTest: composes the real app root and taps through its tabs.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
