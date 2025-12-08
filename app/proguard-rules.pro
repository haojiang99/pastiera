# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line number information for debugging stack traces
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Remove logging in release builds (removes Log.d, Log.v, Log.i calls)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int d(...);
    public static int w(...);
}

# Keep org.json classes (though they're in Android SDK, better safe than sorry)
-keep class org.json.** { *; }

# Keep BuildConfig for runtime checks if needed
-keep class it.neuralrad.coolwulf.BuildConfig { *; }

# Vosk speech recognition - keep all classes and native methods
-keep class org.vosk.** { *; }
-keepclassmembers class org.vosk.** { *; }

# JNA library (required by Vosk)
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# Ignore missing AWT classes (JNA references them but they're not on Android)
-dontwarn java.awt.**
-dontwarn com.sun.jna.platform.win32.**
-dontwarn com.sun.jna.platform.mac.**
-dontwarn com.sun.jna.platform.linux.**
-dontwarn com.sun.jna.platform.unix.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}