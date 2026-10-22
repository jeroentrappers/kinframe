# kotlinx.serialization: keep generated serializers of kinframe models.
-keepclassmembers @kotlinx.serialization.Serializable class app.kinframe.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.kinframe.**$$serializer { *; }
