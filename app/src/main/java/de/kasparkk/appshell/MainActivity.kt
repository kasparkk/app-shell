package de.kasparkk.appshell

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.provider.MediaStore
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File

/**
 * Huelle um eine Web-App: laedt die Live-URL und ergaenzt, was die WebView nicht kann
 * (Datei-Auswahl mit Kamera, Downloads, Teilen, Drucken, Berechtigungen, Fenster).
 * Alles App-Spezifische steht in assets/config.json.
 */
@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var cfg: Cfg
    private lateinit var shellJs: String
    private var docStart = false

    private lateinit var stack: FrameLayout
    private val webs = ArrayList<WebView>()
    private lateinit var offline: LinearLayout
    private lateinit var offlineText: TextView
    private lateinit var bar: LinearLayout
    private lateinit var barText: TextView
    private var barUri: Uri? = null
    private var barMime: String = "*/*"
    private val hideBar = Runnable { bar.visibility = View.GONE }

    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var fileParams: WebChromeClient.FileChooserParams? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null
    private var pendingPerm: PermissionRequest? = null

    companion object {
        const val REQ_FILE = 1
        const val REQ_CAM_FILE = 2
        const val REQ_MEDIA = 3
    }

    // ---------- Aufbau ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Cfg.load(this)
        shellJs = assets.open("shell.js").bufferedReader().use { it.readText() }
        docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val light = ColorUtils.calculateLuminance(cfg.theme) > 0.5
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }

        // root traegt die Themenfarbe hinter Status- und Navigationsleiste,
        // content sitzt per Insets-Padding dazwischen (targetSdk 35 = immer edge-to-edge)
        val root = FrameLayout(this)
        root.setBackgroundColor(cfg.theme)
        val content = FrameLayout(this)
        content.setBackgroundColor(cfg.bg)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val s = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(s.left, s.top, s.right, maxOf(s.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        stack = FrameLayout(this)
        content.addView(stack, FrameLayout.LayoutParams(-1, -1))
        offline = buildOffline()
        content.addView(offline, FrameLayout.LayoutParams(-1, -1))
        bar = buildBar()
        content.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(10)) })
        setContentView(root)

        val main = newWeb()
        pushWeb(main)
        if (savedInstanceState == null || main.restoreState(savedInstanceState) == null) main.loadUrl(startUrl(intent))
    }

    private fun startUrl(i: Intent?): String {
        val d = i?.data
        return if (d != null && (d.scheme == "https" || d.scheme == "http") && cfg.isInternal(d.host)) d.toString() else cfg.url
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webs.firstOrNull()?.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        webs.forEach { it.onPause() }
    }

    override fun onResume() {
        super.onResume()
        webs.forEach { it.onResume() }
    }

    override fun onDestroy() {
        webs.forEach { it.destroy() }
        webs.clear()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val top = webs.lastOrNull() ?: return super.onBackPressed()
        when {
            bar.visibility == View.VISIBLE -> bar.visibility = View.GONE
            top.canGoBack() -> top.goBack()
            webs.size > 1 -> closeWeb(top)
            else -> super.onBackPressed()
        }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildOffline(): LinearLayout {
        val fg = if (ColorUtils.calculateLuminance(cfg.bg) > 0.5) Color.parseColor("#2C2C2A") else Color.parseColor("#F1EFE8")
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        l.setBackgroundColor(cfg.bg)
        l.setPadding(dp(32), dp(32), dp(32), dp(32))
        l.isClickable = true
        val title = TextView(this).apply {
            text = "Keine Verbindung"
            setTextColor(fg); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        offlineText = TextView(this).apply {
            setTextColor(fg); alpha = 0.75f; textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(20))
        }
        val btn = Button(this).apply {
            text = "Neu laden"
            setOnClickListener { reload() }
        }
        l.addView(title); l.addView(offlineText); l.addView(btn)
        l.visibility = View.GONE
        return l
    }

    private fun buildBar(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        l.setPadding(dp(16), dp(6), dp(6), dp(6))
        l.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#2C2C2A")); cornerRadius = dp(10).toFloat()
        }
        l.elevation = dp(6).toFloat()
        barText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; maxLines = 2
        }
        l.addView(barText, LinearLayout.LayoutParams(0, -2, 1f))
        fun action(label: String, f: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = label; setTextColor(Color.parseColor("#9FE1CB")); isAllCaps = false; textSize = 14f
            setOnClickListener { f() }
        }
        l.addView(action("Öffnen") { barUri?.let { openUri(it, barMime) } })
        l.addView(action("Teilen") { barUri?.let { shareUris(listOf(it), barMime, "", "") } })
        l.visibility = View.GONE
        return l
    }

    private fun showBar(text: String, uri: Uri, mime: String) {
        barText.text = text
        barUri = uri
        barMime = mime
        bar.visibility = View.VISIBLE
        h.removeCallbacks(hideBar)
        h.postDelayed(hideBar, 8000)
    }

    private fun reload() {
        offline.visibility = View.GONE
        val w = webs.firstOrNull() ?: return
        val u = w.url
        if (u.isNullOrEmpty() || u == "about:blank") w.loadUrl(cfg.url) else w.reload()
    }

    // ---------- WebViews ----------

    private fun newWeb(): WebView {
        val w = WebView(this)
        w.setBackgroundColor(cfg.bg)
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            userAgentString = "$userAgentString AppShell/1"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(w), "AndroidShell")
        if (docStart) WebViewCompat.addDocumentStartJavaScript(w, shellJs, setOf("*"))
        w.webViewClient = Client()
        w.webChromeClient = Chrome()
        w.setDownloadListener { url, ua, cd, mime, _ -> download(w, url, ua, cd, mime) }
        return w
    }

    private fun pushWeb(w: WebView) {
        stack.addView(w, FrameLayout.LayoutParams(-1, -1))
        webs.add(w)
    }

    private fun closeWeb(w: WebView) {
        if (webs.size <= 1 || w == webs.first()) return
        webs.remove(w)
        stack.removeView(w)
        w.destroy()
    }

    private fun isChild(w: WebView) = webs.size > 1 && w != webs.first()

    /** true = WebView soll die URL nicht selbst laden */
    private fun route(v: WebView, u: Uri): Boolean {
        when (u.scheme?.lowercase()) {
            "http", "https" -> {
                if (cfg.isInternal(u.host)) return false
                openExternal(u)
                if (isChild(v) && (v.url.isNullOrEmpty() || v.url == "about:blank")) h.post { closeWeb(v) }
                return true
            }
            "blob", "data" -> {
                v.evaluateJavascript("window.__shellSave&&__shellSave(${JSONObject.quote(u.toString())},'',true)", null)
                return true
            }
            "about", "javascript" -> return false
            "intent" -> {
                try {
                    val i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                    i.addCategory(Intent.CATEGORY_BROWSABLE)
                    i.component = null
                    i.selector = null
                    try { startActivity(i) } catch (e: ActivityNotFoundException) {
                        i.getStringExtra("browser_fallback_url")?.let { openExternal(Uri.parse(it)) }
                    }
                } catch (_: Exception) {}
                return true
            }
            else -> { openExternal(u); return true }
        }
    }

    private fun openExternal(u: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
            toast("Keine App für diesen Link")
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return route(view, request.url)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            // Neue Fenster (window.open) laufen nicht immer durch shouldOverrideUrlLoading
            if (url != null && isChild(view)) {
                val u = Uri.parse(url)
                if ((u.scheme == "http" || u.scheme == "https") && !cfg.isInternal(u.host)) {
                    view.stopLoading()
                    openExternal(u)
                    h.post { closeWeb(view) }
                }
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!docStart) view.evaluateJavascript(shellJs, null)
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && view == webs.firstOrNull()) {
                offlineText.text = "${cfg.name} konnte nicht geladen werden.\n(${error.description})"
                offline.visibility = View.VISIBLE
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val child = newWeb()
            pushWeb(child)
            (resultMsg.obj as WebView.WebViewTransport).webView = child
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            closeWeb(window)
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: WebChromeClient.FileChooserParams): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = filePathCallback
            fileParams = fileChooserParams
            if (wantsImage(fileChooserParams) && !has(Manifest.permission.CAMERA)) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAM_FILE)
            } else {
                launchChooser()
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                if (!cfg.isInternal(request.origin.host)) { request.deny(); return@runOnUiThread }
                val res = request.resources.filter {
                    it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (res.isEmpty()) { request.deny(); return@runOnUiThread }
                val missing = permsFor(res).filter { !has(it) }
                if (missing.isEmpty()) request.grant(res.toTypedArray())
                else {
                    pendingPerm?.deny()
                    pendingPerm = request
                    requestPermissions(missing.toTypedArray(), REQ_MEDIA)
                }
            }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
            callback.invoke(origin, false, false)
        }
    }

    // ---------- Berechtigungen ----------

    private fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun permsFor(res: List<String>) = res.map {
        if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_CAM_FILE -> launchChooser()
            REQ_MEDIA -> {
                val r = pendingPerm ?: return
                pendingPerm = null
                val ok = r.resources.filter {
                    (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE && has(Manifest.permission.CAMERA)) ||
                        (it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && has(Manifest.permission.RECORD_AUDIO))
                }
                if (ok.isEmpty()) r.deny() else r.grant(ok.toTypedArray())
            }
        }
    }

    // ---------- Datei-Auswahl ----------

    private fun acceptMimes(p: WebChromeClient.FileChooserParams): List<String> =
        p.acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.map { t ->
            if (t.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.substring(1)) ?: "*/*" else t
        }.distinct()

    private fun wantsImage(p: WebChromeClient.FileChooserParams): Boolean {
        val m = acceptMimes(p)
        return m.isEmpty() || m.any { it.startsWith("image/") || it == "*/*" }
    }

    private fun cameraIntent(): Intent? {
        if (!has(Manifest.permission.CAMERA)) return null
        return try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
            val f = File(dir, "foto_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            cameraFile = f
            cameraUri = uri
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { null }
    }

    private fun launchChooser() {
        val p = fileParams ?: return
        cameraFile = null
        cameraUri = null
        val cam = if (wantsImage(p)) cameraIntent() else null
        try {
            if (p.isCaptureEnabled && cam != null) {
                startActivityForResult(cam, REQ_FILE)
                return
            }
            val mimes = acceptMimes(p).filter { it != "*/*" }
            val get = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
            get.type = if (mimes.size == 1) mimes[0] else "*/*"
            if (mimes.size > 1) get.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            if (p.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) get.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            val chooser = Intent.createChooser(get, "Datei wählen")
            if (cam != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cam))
            startActivityForResult(chooser, REQ_FILE)
        } catch (_: Exception) {
            fileCb?.onReceiveValue(null)
            fileCb = null
            toast("Keine App zur Dateiauswahl gefunden")
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        var res: Array<Uri>? = null
        if (resultCode == RESULT_OK) {
            val clip = data?.clipData
            val picked: Array<Uri>? = when {
                data?.data != null -> arrayOf(data!!.data!!)
                clip != null && clip.itemCount > 0 && clip.getItemAt(0).uri != cameraUri ->
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                else -> null
            }
            val cf = cameraFile
            res = picked ?: if (cf != null && cf.exists() && cf.length() > 0) arrayOf(cameraUri!!) else null
        }
        fileCb?.onReceiveValue(res)
        fileCb = null
    }

    // ---------- Downloads, Oeffnen, Teilen ----------

    private fun safeName(n: String): String =
        n.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(120).ifBlank { "download" }

    private fun mimeOf(name: String, given: String?): String {
        if (!given.isNullOrBlank() && given != "application/octet-stream") return given
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: (given ?: "application/octet-stream")
    }

    private fun saveBytes(name: String, mime: String, bytes: ByteArray): Uri {
        val n = safeName(name)
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, n)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("Downloads nicht beschreibbar")
            contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(u, v, null, null)
            return u
        }
        val dir = File(getExternalFilesDir(null), Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        var f = File(dir, n)
        var i = 1
        while (f.exists()) { f = File(dir, n.substringBeforeLast('.') + " ($i)." + n.substringAfterLast('.', "bin")); i++ }
        f.writeBytes(bytes)
        return FileProvider.getUriForFile(this, "$packageName.files", f)
    }

    private fun download(w: WebView, url: String, ua: String?, cd: String?, mime: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            w.evaluateJavascript("window.__shellSave&&__shellSave(${JSONObject.quote(url)},'',true)", null)
            return
        }
        downloadHttp(url, ua, URLUtil.guessFileName(url, cd, mime), mime)
    }

    private fun downloadHttp(url: String, ua: String?, name: String, mime: String?) {
        try {
            val n = safeName(name)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeOf(n, mime))
                .setTitle(n)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            if (!ua.isNullOrEmpty()) req.addRequestHeader("User-Agent", ua)
            if (Build.VERSION.SDK_INT >= 29) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, n)
            else req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, n)
            getSystemService(DownloadManager::class.java).enqueue(req)
            toast("Download läuft: $n")
        } catch (e: Exception) {
            toast("Download fehlgeschlagen: ${e.message}")
        }
    }

    private fun openUri(u: Uri, mime: String) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (_: Exception) {
            try { startActivity(Intent.createChooser(i, "Öffnen mit")) } catch (_: Exception) { toast("Keine App zum Öffnen gefunden") }
        }
    }

    private fun shareUris(uris: List<Uri>, mime: String, title: String, text: String) {
        val i = if (uris.size > 1) {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        } else Intent(Intent.ACTION_SEND).apply { if (uris.isNotEmpty()) putExtra(Intent.EXTRA_STREAM, uris[0]) }
        i.type = if (uris.isEmpty()) "text/plain" else mime
        if (text.isNotEmpty()) i.putExtra(Intent.EXTRA_TEXT, text)
        if (title.isNotEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title)
        if (uris.isNotEmpty()) {
            val clip = ClipData.newRawUri("", uris[0])
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            i.clipData = clip
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(i, title.ifEmpty { "Teilen" })) } catch (_: Exception) { toast("Teilen nicht möglich") }
    }

    // ---------- Drucken ----------

    private fun print(w: WebView, title: String) {
        val job = title.ifBlank { cfg.name }
        val inner = w.createPrintDocumentAdapter(job)
        val adapter = object : PrintDocumentAdapter() {
            override fun onStart() = inner.onStart()
            override fun onLayout(o: PrintAttributes?, n: PrintAttributes, c: CancellationSignal?, cb: PrintDocumentAdapter.LayoutResultCallback, e: Bundle?) =
                inner.onLayout(o, n, c, cb, e)
            override fun onWrite(p: Array<out PageRange>, d: ParcelFileDescriptor, c: CancellationSignal?, cb: PrintDocumentAdapter.WriteResultCallback) =
                inner.onWrite(p, d, c, cb)
            override fun onFinish() {
                inner.onFinish()
                try { w.evaluateJavascript("window.__shellAfterPrint&&__shellAfterPrint()", null) } catch (_: Exception) {}
            }
        }
        try {
            getSystemService(PrintManager::class.java).print(job, adapter, PrintAttributes.Builder().build())
        } catch (e: Exception) {
            toast("Drucken nicht möglich: ${e.message}")
        }
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_LONG).show() }

    // ---------- Bruecke fuer shell.js ----------

    private inner class Bridge(private val w: WebView) {
        @Volatile private var host: String? = null

        /** Nur Seiten der eigenen App duerfen die Bruecke benutzen */
        private fun allowed(): Boolean {
            val latch = java.util.concurrent.CountDownLatch(1)
            h.post { host = try { Uri.parse(w.url ?: "").host } catch (_: Exception) { null }; latch.countDown() }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            return cfg.isInternal(host)
        }

        @JavascriptInterface
        fun saveFile(name: String, mime: String, b64: String, open: Boolean) {
            if (!allowed()) return
            try {
                val m = mimeOf(name, mime)
                val uri = saveBytes(name, m, Base64.decode(b64, Base64.DEFAULT))
                runOnUiThread {
                    showBar("${safeName(name)} in Downloads", uri, m)
                    if (open) openUri(uri, m)
                }
            } catch (e: Exception) {
                toast("Speichern fehlgeschlagen: ${e.message}")
            }
        }

        @JavascriptInterface
        fun downloadUrl(url: String, name: String) {
            if (!allowed()) return
            runOnUiThread { downloadHttp(url, w.settings.userAgentString, name.ifBlank { URLUtil.guessFileName(url, null, null) }, null) }
        }

        @JavascriptInterface
        fun share(json: String) {
            if (!allowed()) return
            try {
                val j = JSONObject(json)
                val files = j.optJSONArray("files")
                val dir = File(cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val uris = ArrayList<Uri>()
                var mime = "*/*"
                val types = HashSet<String>()
                if (files != null) for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val name = safeName(f.optString("name", "datei"))
                    val file = File(dir, name)
                    file.writeBytes(Base64.decode(f.getString("data"), Base64.DEFAULT))
                    uris.add(FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file))
                    types.add(mimeOf(name, f.optString("type")))
                }
                if (types.size == 1) mime = types.first()
                val text = listOf(j.optString("text"), j.optString("url")).filter { it.isNotEmpty() }.joinToString("\n")
                runOnUiThread { shareUris(uris, mime, j.optString("title"), text) }
            } catch (e: Exception) {
                toast("Teilen fehlgeschlagen: ${e.message}")
            }
        }

        @JavascriptInterface
        fun print(title: String) {
            if (!allowed()) return
            runOnUiThread { this@MainActivity.print(w, title) }
        }

        @JavascriptInterface
        fun toast(s: String) {
            this@MainActivity.toast(s)
        }
    }
}
