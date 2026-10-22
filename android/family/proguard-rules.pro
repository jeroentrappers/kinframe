# kotlinx.serialization rules for kinframe models come from :core's consumer rules.
# Logto's SDK parses OIDC responses with Gson and verifies tokens with jose4j.
-keep class io.logto.sdk.core.type.** { *; }
-keep class org.jose4j.** { *; }
-dontwarn org.jose4j.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
