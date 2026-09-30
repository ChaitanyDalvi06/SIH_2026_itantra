# Keep JNI Native method names
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Room entities
-keep class com.itantra.edgespeech.persistence.** { *; }

# Keep Binary Protocol classes
-keep class com.itantra.edgespeech.protocol.** { *; }
