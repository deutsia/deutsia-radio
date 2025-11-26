# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line number information for debugging stack traces in production
-keepattributes SourceFile,LineNumberTable

# Remove debug and verbose logging in release builds for performance
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Keep error and warning logs for crash reporting
# (Log.e, Log.w, and Log.wtf are kept)

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep OkHttp platform classes for SOCKS proxy support
-dontwarn okhttp3.internal.platform.**
-keep class okhttp3.internal.platform.** { *; }

# Keep ExoPlayer classes for audio streaming
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Room database classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static ** Companion;
}

# Keep Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.** {
    volatile <fields>;
}

# Keep Coil image loader
-keep class coil.** { *; }
-dontwarn coil.**