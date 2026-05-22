# =============================================================================
# FundoCareer ProGuard / R8 Rules
# =============================================================================
# When minification is enabled (release build), R8 strips unused code.
# The rules below preserve classes needed by the WebView JavaScript bridges,
# Capacitor, Google Sign-In, and JSON serialization.
# =============================================================================


# ---------------------------------------------------------------------------
# CRITICAL: JavaScript Interface classes (invoked from WebView JS)
# ---------------------------------------------------------------------------
# Every @JavascriptInterface method must be preserved exactly as declared.
# Stripping these will break all native-to-web communication.

-keepclassmembers class com.fundocareer.app.AuthBridge { public *; }
-keepclassmembers class com.fundocareer.app.FileBridge { public *; }
-keepclassmembers class com.fundocareer.app.MicrophoneBridge { public *; }
-keepclassmembers class com.fundocareer.app.PaymentHandler$Bridge { public *; }

# Generic: keep any class with @JavascriptInterface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}


# ---------------------------------------------------------------------------
# Capacitor bridge and plugins
# ---------------------------------------------------------------------------
-keep class com.getcapacitor.** { *; }
-keep class com.fundocareer.app.** { *; }

-keep class * extends com.getcapacitor.Plugin { *; }
-keep class * extends com.getcapacitor.BridgeActivity { *; }


# ---------------------------------------------------------------------------
# Google Play Services (auth, sign-in)
# ---------------------------------------------------------------------------
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.gms.**


# ---------------------------------------------------------------------------
# JSON (org.json.*) - used throughout for bridge messages and analytics
# ---------------------------------------------------------------------------
-keep class org.json.** { *; }
-keepclassmembers class org.json.** { *; }


# ---------------------------------------------------------------------------
# Android WebView / Chrome Custom Tabs
# ---------------------------------------------------------------------------
-keep class android.webkit.** { *; }
-keep class androidx.browser.customtabs.** { *; }


# ---------------------------------------------------------------------------
# Material Components
# ---------------------------------------------------------------------------
-keep class com.google.android.material.** { *; }


# ---------------------------------------------------------------------------
# General Android platform
# ---------------------------------------------------------------------------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions, InnerClasses

# Keep all Activity subclasses
-keep class * extends android.app.Activity { *; }

# Keep all Application subclasses
-keep class * extends android.app.Application { *; }

# Keep View.onClick handlers
-keepclassmembers class * {
    void *(android.view.View);
}


# ---------------------------------------------------------------------------
# Debug info (optional but helpful for crash reports)
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
