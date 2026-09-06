# Consumer ProGuard/R8 rules applied to apps that depend on trace-android.
#
# trace-android hands kotlinx.serialization the internal @Serializable record
# types from trace-core (via SessionRecorder). Keep their generated serializers
# so release builds with R8 still produce valid .trace.jsonl files.

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Keep the kotlinx-serialization runtime hooks.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep trace-core's serializable record model and its synthetic serializers.
-keep,includedescriptorclasses class trace.core.**$$serializer { *; }
-keepclassmembers class trace.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class trace.core.** { *; }
