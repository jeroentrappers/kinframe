# Room and kotlinx.serialization ship their own rules; core adds rules for kinframe models.
-keep class app.kinframe.frame.data.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
