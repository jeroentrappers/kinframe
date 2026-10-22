plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release version: CI passes -PversionName=0.2.0 -PversionCode=20099 derived from the git tag (docs/api/README.md §5.7:
// major*1_000_000 + minor*10_000 + patch*100 + 99, or + N for -beta.N). Local builds keep the defaults below.
val frameVersionName: String = providers.gradleProperty("versionName").getOrElse("0.1.0")
val frameVersionCode: Int = providers.gradleProperty("versionCode").map { it.toInt() }.getOrElse(1)

// Release signing from the environment or Gradle properties; without them release builds use the debug key, so
// local and pull-request builds still install on test frames. The keystore is never committed.
fun signingValue(name: String): String? =
    providers.environmentVariable(name).orElse(providers.gradleProperty(name)).orNull?.takeIf { it.isNotBlank() }
val releaseKeystore: String? = signingValue("KINFRAME_KEYSTORE_FILE")

android {
    namespace = "app.kinframe.frame"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.kinframe.frame"
        minSdk = 23
        targetSdk = 36
        versionCode = frameVersionCode
        versionName = frameVersionName
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        buildConfigField("String", "DEFAULT_SERVER", "\"https://kinframe.appmire.be\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("KINFRAME_KEYSTORE_PASSWORD")
                keyAlias = signingValue("KINFRAME_KEY_ALIAS")
                keyPassword = signingValue("KINFRAME_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the release key when configured (CI on tags); otherwise with the debug key so release
            // builds install on test frames (and give realistic performance, unlike debuggable builds).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        // App resources in Robolectric tests (strings for the weather screenshot tests).
        unitTests.isIncludeAndroidResources = true
    }
    packaging { resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.media3.exoplayer)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
