# Keep line numbers for crash/telemetry stacks.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Protobuf (generated + reflective accessors used by AA / bike frames).
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}

# Conscrypt / TLS (AA self-mode).
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# MapLibre / OkHttp / jmDNS — reflection & native JNI.
-keep class org.maplibre.** { *; }
-keep class com.mapbox.** { *; }
-dontwarn org.maplibre.**
-dontwarn com.mapbox.**
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# ML Kit barcode / CameraX.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class androidx.camera.** { *; }

# OpenCfMoto entry points + BuildConfig.
-keep class dev.zanderp.opencfmoto.MainActivity { *; }
-keep class dev.zanderp.opencfmoto.** { *; }
