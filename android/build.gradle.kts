// Root build file. Plugin versions live in gradle/libs.versions.toml.
// AGP 9 compiles Kotlin itself (built-in Kotlin); the kotlin-android entry only pins the Kotlin Gradle plugin version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
