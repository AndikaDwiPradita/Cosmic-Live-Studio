# Cosmic Live Studio ProGuard Rules

# Keep MediaCodec and MediaProjection classes
-keep class android.media.** { *; }
-keep class android.media.projection.** { *; }
-keep class android.hardware.display.** { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class com.google.gson.** { *; }

# Keep ZXing (QR code)
-keep class com.google.zxing.** { *; }
-keepclassmembers class com.google.zxing.** { *; }
-keep class com.journeyapps.zxing.** { *; }
-keepclassmembers class com.journeyapps.zxing.** { *; }

# Keep our app classes
-keep class com.cosmic.livestudio.** { *; }
-keepclassmembers class com.cosmic.livestudio.** { *; }

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Suppress warnings for common libraries
-dontwarn kotlinx.coroutines.**
-dontwarn com.google.gson.**
-dontwarn android.media.**
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.zxing.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep serialization for data classes
-keepclassmembers class * {
    @kotlin.metadata.KotlinClass <fields>;
}