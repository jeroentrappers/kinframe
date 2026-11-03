# Room and kotlinx.serialization ship their own rules; core adds rules for kinframe models.
-keep class app.kinframe.frame.data.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# QuickJS (plugin host): the native side calls back into the binding's classes by name; its own consumer rules keep
# only a few of them.
-keep class com.dokar.quickjs.** { *; }
