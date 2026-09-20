# EduIntels WebView wrapper uses only Android framework APIs.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
