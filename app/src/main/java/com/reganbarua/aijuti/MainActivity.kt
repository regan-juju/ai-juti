package com.reganbarua.aijuti

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * AI জুটি — তিনটি ট্যাব:
 *  0 = AI জুটি (অ্যাপের নিজস্ব পাতা, assets/index.html)
 *  1 = Claude  (claude.ai — নিজের সাবস্ক্রিপশন অ্যাকাউন্টে লগইন)
 *  2 = ChatGPT (chatgpt.com — নিজের সাবস্ক্রিপশন অ্যাকাউন্টে লগইন)
 *
 * লগইন অ্যাপের ভেতরেই কুকি হিসেবে থাকে, তাই একবার লগইন করলেই চলে।
 * অ্যাপ Claude/ChatGPT-এর পাতায় কিছু পড়ে না বা নিজে থেকে লেখে না;
 * প্রশ্ন ও উত্তর আদান-প্রদান ব্যবহারকারী নিজে কপি-পেস্ট করে করেন।
 */
class MainActivity : Activity() {

    private val home = "https://appassets.androidplatform.net/assets/index.html"
    private val sites = arrayOf(home, "https://claude.ai/new", "https://chatgpt.com/")
    private val tabNames = arrayOf("AI জুটি", "Claude", "ChatGPT")
    private val tabColors = intArrayOf(0xFF2F4BD1.toInt(), 0xFFC96442.toInt(), 0xFF10A37F.toInt())
    private val muted = 0xFF6B6B72.toInt()
    private val line = 0xFFE2DFD6.toInt()

    private lateinit var webs: Array<WebView>
    private val loaded = BooleanArray(3)
    private lateinit var tabViews: Array<TextView>
    private lateinit var progress: ProgressBar
    private lateinit var assetLoader: WebViewAssetLoader
    private var current = 0

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileReq = 41
    private val main = Handler(Looper.getMainLooper())

    // সাইট থেকে ফাইল নামানোর অনুরোধ এলে কিছুক্ষণের জন্য সংরক্ষণের অনুমতি থাকে
    private var siteSaveUntil = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = main.post { Toast.makeText(this, t, Toast.LENGTH_LONG).show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val frame = FrameLayout(this)
        webs = Array(3) { makeWeb(it) }
        webs.forEach { frame.addView(it, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        frame.addView(progress, FrameLayout.LayoutParams(MATCH_PARENT, dp(3)).apply { gravity = Gravity.TOP })
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        root.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
        }
        tabViews = Array(3) { i ->
            TextView(this).apply {
                text = tabNames[i]
                gravity = Gravity.CENTER
                textSize = 15f
                setPadding(0, dp(13), 0, dp(13))
                setOnClickListener { show(i) }
                setOnLongClickListener {
                    if (loaded[i]) {
                        webs[i].reload(); toast(tabNames[i] + " আবার লোড হচ্ছে…")
                    }
                    true
                }
            }
        }
        tabViews.forEach { bar.addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
        show(savedInstanceState?.getInt("tab") ?: 0)
    }

    private fun makeWeb(i: Int): WebView {
        val w = WebView(this)
        w.visibility = View.GONE
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            allowFileAccess = false
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)

        if (i == 0) {
            w.addJavascriptInterface(HomeBridge(), "AIJuti")
        } else {
            w.addJavascriptInterface(SiteSaver(), "AIJutiSave")
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (i == 0) assetLoader.shouldInterceptRequest(request.url) else null

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = (u.scheme ?: "").lowercase()
                if (scheme != "http" && scheme != "https") {
                    openExternal(u); return true
                }
                if (i == 0) {
                    // AI জুটির উত্তরের ভেতরের লিংক ফোনের ব্রাউজারে খুলবে
                    if (u.host == "appassets.androidplatform.net") return false
                    openExternal(u); return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (view === webs.getOrNull(current)) updateProgress(p)
            }

            override fun onShowFileChooser(
                view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val types = params.acceptTypes.orEmpty()
                    .flatMap { it.split(",") }.map { it.trim() }.filter { it.contains("/") }.distinct()
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (types.isNotEmpty()) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                    if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                return try {
                    startActivityForResult(Intent.createChooser(pick, "ফাইল বা ছবি বেছে নিন"), fileReq)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    cb.onReceiveValue(null)
                    toast("ফাইল বাছার কোনো অ্যাপ পাওয়া যায়নি")
                    false
                }
            }
        }

        if (i != 0) {
            w.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                when {
                    url.startsWith("blob:") -> {
                        siteSaveUntil = System.currentTimeMillis() + 15000
                        val js = "(function(){fetch(" + jsStr(url) + ").then(function(r){return r.blob();}).then(function(b){" +
                            "var f=new FileReader();f.onload=function(){AIJutiSave.save(" + jsStr(name) +
                            ",String(f.result).split(',')[1]||'',b.type||" + jsStr(mimeType ?: "") + ");};f.readAsDataURL(b);})" +
                            ".catch(function(){AIJutiSave.fail();});})();"
                        w.evaluateJavascript(js, null)
                    }
                    url.startsWith("data:") -> {
                        val b64 = url.substringAfter("base64,", "")
                        if (b64.isNotEmpty()) saveFile(name, b64, mimeType ?: "application/octet-stream")
                        else toast("এই ফাইলটি নামানো গেল না")
                    }
                    else -> openExternal(Uri.parse(url))
                }
            }
        }
        return w
    }

    private fun jsStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"

    private fun updateProgress(p: Int) {
        progress.progress = p
        progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
    }

    private fun show(i: Int) {
        current = i
        webs.forEachIndexed { k, w -> w.visibility = if (k == i) View.VISIBLE else View.GONE }
        if (!loaded[i]) {
            loaded[i] = true
            webs[i].loadUrl(sites[i])
        }
        tabViews.forEachIndexed { k, t ->
            val on = k == i
            t.setTextColor(if (on) tabColors[k] else muted)
            t.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            t.background = if (on) indicator(tabColors[k]) else null
        }
        updateProgress(webs[i].progress)
    }

    private fun indicator(color: Int): LayerDrawable {
        val barShape = GradientDrawable().apply { setColor(color); cornerRadius = dp(2).toFloat() }
        val ld = LayerDrawable(arrayOf(barShape))
        ld.setLayerGravity(0, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        ld.setLayerSize(0, dp(44), dp(3))
        return ld
    }

    private fun openExternal(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            if (u.scheme == "intent") {
                try {
                    val it = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    val fb = it.getStringExtra("browser_fallback_url")
                    if (fb != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fb)))
                } catch (_: Exception) { toast("লিংকটি খোলা গেল না") }
            } else toast("লিংকটি খোলা গেল না")
        }
    }

    /** ফাইল ফোনের Download/AI Juti ফোল্ডারে সংরক্ষণ */
    private fun saveFile(name: String, b64: String, mime: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "ai-juti-file" }
            val type = mime.ifBlank { "application/octet-stream" }
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, type)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AI Juti")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("no uri")
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                toast("সংরক্ষিত: Download/AI Juti/$safe")
            } else {
                val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                val dir = if (granted)
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "AI Juti")
                else File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AI Juti")
                dir.mkdirs()
                var f = File(dir, safe)
                var n = 1
                while (f.exists()) { f = File(dir, "(${n++}) $safe") }
                f.writeBytes(bytes)
                toast("সংরক্ষিত: " + f.absolutePath)
                if (!granted) main.post { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 7) }
            }
        } catch (e: Exception) {
            toast("ফাইল সংরক্ষণ করা গেল না")
        }
    }

    /** AI জুটি পাতার জন্য সংযোগ (শুধু অ্যাপের নিজের পাতা ব্যবহার করে) */
    inner class HomeBridge {
        @JavascriptInterface
        fun open(which: String) = main.post { show(if (which == "gpt") 2 else 1) }

        @JavascriptInterface
        fun copy(text: String) = main.post {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AI জুটি", text))
        }

        @JavascriptInterface
        fun paste(): String {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            return clip.getItemAt(0).coerceToText(this@MainActivity)?.toString() ?: ""
        }

        @JavascriptInterface
        fun save(name: String, b64: String, mime: String) = saveFile(name, b64, mime)

        @JavascriptInterface
        fun version(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }
    }

    /** Claude/ChatGPT পাতা থেকে ব্যবহারকারী নিজে কোনো ফাইল নামাতে চাইলে শুধু সেটুকু সংরক্ষণ */
    inner class SiteSaver {
        @JavascriptInterface
        fun save(name: String, b64: String, mime: String) {
            if (System.currentTimeMillis() > siteSaveUntil) return
            siteSaveUntil = 0
            if (b64.isEmpty()) toast("এই ফাইলটি নামানো গেল না") else saveFile(name, b64, mime)
        }

        @JavascriptInterface
        fun fail() {
            siteSaveUntil = 0
            toast("এই ফাইলটি অ্যাপ থেকে নামানো গেল না। লেখাটি কপি করে নিন।")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == fileReq) {
            val cb = fileCallback
            fileCallback = null
            if (cb == null) return
            var result: Array<Uri>? = null
            if (resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                result = if (clip != null && clip.itemCount > 0) Array(clip.itemCount) { clip.getItemAt(it).uri }
                else data.data?.let { arrayOf(it) }
            }
            cb.onReceiveValue(result)
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = webs[current]
        when {
            w.canGoBack() -> w.goBack()
            current != 0 -> show(0)
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", current)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }
}
