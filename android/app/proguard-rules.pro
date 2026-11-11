# Methods the pages call through MulticuentaBridge (fallback for WebViews without message listeners)
-keepclassmembers class com.multicuenta.app.** {
    @android.webkit.JavascriptInterface <methods>;
}
