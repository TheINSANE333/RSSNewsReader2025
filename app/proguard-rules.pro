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