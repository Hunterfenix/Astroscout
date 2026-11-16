# kotlinx.serialization: mantiene i serializer generati per i DTO.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-keepclassmembers @kotlinx.serialization.Serializable class com.astroscout.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.astroscout.**$$serializer { *; }

# Retrofit: le interfacce di servizio sono usate via reflection (Proxy).
-keep,allowobfuscation interface com.astroscout.data.remote.api.** { *; }
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-dontwarn retrofit2.**

# OkHttp / MapLibre
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.maplibre.**
