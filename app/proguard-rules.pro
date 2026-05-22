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

# Preserve line number information for debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# Hide the original source file name.
-renamesourcefileattribute SourceFile

# Suppress warnings for SLF4J (used by readability4j / essence)
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Keep Retrofit and Gson model classes
-keepattributes Signature
-keepattributes *Annotation*
-keep class xiangze.mmu.rssnewsreader.data.ai.** { *; }
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class androidx.room.Room
-keep class * extends androidx.room.migration.Migration
-dontwarn androidx.room.**

# Hilt
-keep class com.google.dagger.** { *; }
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.EntryPoint class *
-keep @dagger.hilt.components.SingletonComponent class *

# MediaPipe / Local LLM
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# RxJava
-dontwarn io.reactivex.rxjava3.**
-keep class io.reactivex.rxjava3.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase