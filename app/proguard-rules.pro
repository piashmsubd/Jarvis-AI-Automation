# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Gson
-keep class com.jarvis.ai.network.model.** { *; }
-keep class com.jarvis.ai.network.api.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep accessibility service
-keep class com.jarvis.ai.accessibility.JarvisAccessibilityService { *; }
-keep class com.jarvis.ai.service.JarvisNotificationListener { *; }

# Picovoice Porcupine
-keep class ai.picovoice.porcupine.** { *; }
-dontwarn ai.picovoice.**

# Keep wake word and boot receiver
-keep class com.jarvis.ai.voice.WakeWordService { *; }
-keep class com.jarvis.ai.service.BootReceiver { *; }

# WebView JS interface (if we add one later)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
