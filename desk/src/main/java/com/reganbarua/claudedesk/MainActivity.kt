package com.reganbarua.claudedesk

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File

/**
 * Claude ডেস্ক — শুধু claude.ai, কম্পিউটারের (ডেস্কটপ) সংস্করণে।
 * ব্যবহারকারী নিজের সাবস্ক্রিপশন অ্যাকাউন্টে একবার লগইন করেন; লগইন অ্যাপে থেকে যায়।
 * অ্যাপ claude.ai-এর পাতায় কিছু পড়ে না বা বদলায় না — এটা একটা হালকা ব্রাউজার মাত্র।
 */
class MainActivity : Activity() {

    private val home = "https://claude.ai/new"
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private val main = Handler(Looper.getMainLooper())

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileReq = 51
    private var pendingPermission: PermissionRequest? = null
    private val micReq = 52
    private var saveUntil = 0L
    private var lastBack = 0L

    // এই ঠিকানাগুলো অ্যাপের ভেতরেই খোলে (লগইন ও পেমেন্টসহ); বাকি লিংক ফোনের ব্রাউজারে
    private val insideHosts = listOf(
        "claude.ai", "claude.com", "anthropic.com", "claudeusercontent.com",
        "accounts.google.com", "google.com", "gstatic.com", "googleusercontent.com",
        "appleid.apple.com", "apple.com", "cloudflare.com", "stripe.com", "stripe.network",
        "hcaptcha.com", "recaptcha.net"
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = main.post { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() }

    private val desktopUA by lazy {
        val ua = try { WebSettings.getDefaultUserAgent(this) } catch (e: Exception) { "" }
        val chrome = Regex("Chrome/[\\d.]+").find(ua)?.value ?: "Chrome/124.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#F5F4EF"))
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, FrameLayout.LayoutParams(MATCH_PARENT, dp(3)).apply { gravity = Gravity.TOP })
        setContentView(root)

        setupWeb()

        val link = intent?.data
        when {
            savedInstanceState != null && web.restoreState(savedInstanceState) != null -> {}
            link != null && link.host == "claude.ai" -> web.loadUrl(link.toString())
            else -> web.loadUrl(home)
        }
    }

    private fun setupWeb() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            userAgentString = desktopUA          // কম্পিউটারের সংস্করণ
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)                  // চিমটি দিয়ে জুম
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false   // ভয়েস মোডের উত্তর শোনা যায়
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            offscreenPreRaster = true             // স্ক্রল মসৃণ রাখে
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        web.isVerticalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Saver(), "DeskSave")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = (u.scheme ?: "").lowercase()
                if (scheme != "http" && scheme != "https") { openExternal(u); return true }
                val host = (u.host ?: "").lowercase()
                val inside = insideHosts.any { host == it || host.endsWith(".$it") }
                if (inside) return false
                openExternal(u)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                progress.progress = p
                progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
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
                    false
                }
            }

            // ভয়েস মোড: শুধু মাইক্রোফোন, এবং ফোনের অনুমতি পেলে তবেই
            override fun onPermissionRequest(request: PermissionRequest) {
                main.post {
                    val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (!wantsMic) { request.deny(); return@post }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingPermission?.deny()
                        pendingPermission = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), micReq)
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingPermission === request) pendingPermission = null
            }
        }

        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            when {
                url.startsWith("blob:") -> {
                    saveUntil = System.currentTimeMillis() + 15000
                    val js = "(function(){fetch(" + jsStr(url) + ").then(function(r){return r.blob();}).then(function(b){" +
                        "var f=new FileReader();f.onload=function(){DeskSave.save(" + jsStr(name) +
                        ",String(f.result).split(',')[1]||'',b.type||" + jsStr(mimeType ?: "") + ");};f.readAsDataURL(b);})" +
                        ".catch(function(){DeskSave.fail();});})();"
                    web.evaluateJavascript(js, null)
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

    private fun jsStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'"

    private fun showOffline() {
        val html = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:sans-serif;background:#F5F4EF;color:#1d1d1f;display:flex;align-items:center;
            justify-content:center;height:90vh;margin:0;text-align:center;padding:0 24px}
            a{display:inline-block;margin-top:18px;background:#D97757;color:#fff;padding:12px 26px;border-radius:12px;
            text-decoration:none;font-size:17px}</style></head>
            <body><div><h2>ইন্টারনেট সংযোগ নেই</h2><p>সংযোগ চালু করে আবার চেষ্টা করুন।</p>
            <a href="$home">আবার চেষ্টা করুন</a></div></body></html>
        """.trimIndent()
        web.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
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

    /** ফাইল ফোনের Download/Claude Desk ফোল্ডারে সংরক্ষণ */
    private fun saveFile(name: String, b64: String, mime: String) {
        try {
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "claude-file" }
            val type = mime.ifBlank { "application/octet-stream" }
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, type)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Claude Desk")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("no uri")
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                toast("সংরক্ষিত: Download/Claude Desk/$safe")
            } else {
                val granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                val dir = if (granted)
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Claude Desk")
                else File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Claude Desk")
                dir.mkdirs()
                var f = File(dir, safe)
                var n = 1
                while (f.exists()) { f = File(dir, "(${n++}) $safe") }
                f.writeBytes(bytes)
                toast("সংরক্ষিত: " + f.absolutePath)
                if (!granted) main.post { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 53) }
            }
        } catch (e: Exception) {
            toast("ফাইল সংরক্ষণ করা গেল না")
        }
    }

    /** ব্যবহারকারী নিজে কোনো ফাইল নামাতে চাইলে শুধু সেটুকু সংরক্ষণ */
    inner class Saver {
        @JavascriptInterface
        fun save(name: String, b64: String, mime: String) {
            if (System.currentTimeMillis() > saveUntil) return
            saveUntil = 0
            if (b64.isEmpty()) toast("এই ফাইলটি নামানো গেল না") else saveFile(name, b64, mime)
        }

        @JavascriptInterface
        fun fail() {
            saveUntil = 0
            toast("এই ফাইলটি নামানো গেল না")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val link = intent.data
        if (link != null && link.host == "claude.ai") web.loadUrl(link.toString())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == micReq) {
            val req = pendingPermission
            pendingPermission = null
            if (req != null) {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                    req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                else {
                    req.deny(); toast("মাইক্রোফোনের অনুমতি ছাড়া ভয়েস চলবে না")
                }
            }
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
        if (web.canGoBack()) { web.goBack(); return }
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            lastBack = now
            toast("বন্ধ করতে আবার Back চাপুন")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
