# JavaScript থেকে ডাকা ফাংশনগুলো যেন মুছে না যায়
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
