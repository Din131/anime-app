package com.din131.aniview

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.Settings
import android.util.Rational
import android.webkit.JavascriptInterface
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var fullscreenContainer: GestureFrameLayout
    private lateinit var gestureIndicator: View
    private lateinit var gestureText: TextView
    private lateinit var gestureBar: ProgressBar
    private lateinit var audio: AudioManager

    // Gestur kecerahan / volume
    private var brightness = 0.5f
    private var volume = 0f
    private val hideIndicator = Runnable { gestureIndicator.visibility = View.GONE }

    // Picture-in-Picture
    private var videoPlaying = false
    private var videoRatio = Rational(16, 9)
    private val pipSupported by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private var popupView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    /** Domain yang boleh dibuka di halaman utama (beserta subdomainnya). */
    private val allowedDomains = mutableSetOf<String>()
    private var initialLoad = true
    private var lastBackPress = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AdBlocker.init(this)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        errorView = findViewById(R.id.errorView)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        gestureIndicator = findViewById(R.id.gestureIndicator)
        gestureText = findViewById(R.id.gestureText)
        gestureBar = findViewById(R.id.gestureBar)
        audio = getSystemService(AUDIO_SERVICE) as AudioManager
        fullscreenContainer.listener = gestureListener

        // Beri jarak untuk status bar, navigasi, notch, dan keyboard
        // (Android 15+ menggambar aplikasi sampai ke belakang status bar).
        // Saat video fullscreen, jarak dihilangkan supaya video penuh satu layar.
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            if (customView != null) {
                v.setPadding(0, 0, 0, 0)
            } else {
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            }
            WindowInsetsCompat.CONSUMED
        }
        // Ikon status bar putih di atas latar gelap
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val startUrl = getString(R.string.start_url)
        Uri.parse(startUrl).host?.let { allowedDomains.add(baseDomain(it)) }

        // Layar tidak mati selama aplikasi terbuka
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Pop-up ditangani sendiri di onCreateWindow
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            // Sembunyikan penanda "wv" supaya situs memperlakukan seperti Chrome biasa
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.setBackgroundColor(getColor(R.color.bg))
        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        webView.setDownloadListener { _, _, _, _, _ -> /* abaikan unduhan (biasanya iklan) */ }
        // Jembatan kecil: player.js hanya melaporkan status putar video (untuk PiP)
        webView.addJavascriptInterface(Bridge(), "AniView")

        // Ketuk 2x kiri/kanan video untuk mundur/maju 10 detik.
        // Disuntikkan ke semua frame, termasuk iframe player dari domain lain.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            val playerJs = assets.open("player.js").bufferedReader().use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(webView, playerJs, setOf("*"))
        }

        errorView.setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> webView.webChromeClient?.onHideCustomView()
                    popupView != null -> popupView!!.let { if (it.canGoBack()) it.goBack() else closePopup() }
                    webView.canGoBack() -> webView.goBack()
                    System.currentTimeMillis() - lastBackPress < 2000 -> finish()
                    else -> {
                        lastBackPress = System.currentTimeMillis()
                        Toast.makeText(this@MainActivity, R.string.press_back_again, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            initialLoad = false
        } else {
            webView.loadUrl(startUrl)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        // Saat masuk PiP video harus tetap jalan
        if (!isInPictureInPictureMode) webView.onPause()
        super.onPause()
    }

    override fun onStop() {
        // Jendela PiP ditutup / aplikasi benar-benar di belakang
        webView.onPause()
        // Simpan cookie login ke disk supaya tetap login setelah aplikasi ditutup
        CookieManager.getInstance().flush()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        webView.onResume()
    }

    // ---------- Picture-in-Picture ----------

    private inner class Bridge {
        @JavascriptInterface
        fun onVideoState(playing: Boolean, width: Int, height: Int) {
            runOnUiThread {
                videoPlaying = playing
                if (width > 0 && height > 0) {
                    // Batas rasio yang diizinkan Android: 1:2.39 sampai 2.39:1
                    val r = (width.toFloat() / height).coerceIn(0.42f, 2.39f)
                    videoRatio = Rational((r * 1000).toInt(), 1000)
                }
                updatePipParams()
            }
        }
    }

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(videoRatio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: otomatis masuk PiP saat keluar aplikasi ketika video diputar
            builder.setAutoEnterEnabled(videoPlaying).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParams() {
        if (!pipSupported) return
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 8-11: masuk PiP manual saat tombol Home ditekan
        if (pipSupported && videoPlaying && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            try { enterPictureInPictureMode(pipParams()) } catch (_: Exception) {}
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) {
            progress.visibility = View.GONE
            gestureIndicator.visibility = View.GONE
        }
        // Kalau tidak sedang fullscreen, perbesar video di dalam halaman supaya PiP hanya berisi video
        if (customView == null) {
            webView.evaluateJavascript("window.__aniviewPip && window.__aniviewPip($isInPip)", null)
        }
    }

    // ---------- Gestur kecerahan / volume di fullscreen ----------

    private val gestureListener = object : GestureFrameLayout.Listener {
        override fun onGestureStart(side: Int) {
            if (side < 0) {
                val cur = window.attributes.screenBrightness
                brightness = if (cur >= 0f) cur else try {
                    Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                } catch (_: Exception) { 0.5f }
            } else {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }
            gestureIndicator.removeCallbacks(hideIndicator)
            gestureIndicator.visibility = View.VISIBLE
            onGestureDrag(side, 0f)
        }

        override fun onGestureDrag(side: Int, delta: Float) {
            if (side < 0) {
                brightness = (brightness + delta * 1.2f).coerceIn(0.01f, 1f)
                window.attributes = window.attributes.apply { screenBrightness = brightness }
                val pct = (brightness * 100).toInt()
                gestureText.text = getString(R.string.gesture_brightness, pct)
                gestureBar.progress = pct
            } else {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volume = (volume + delta * max * 1.2f).coerceIn(0f, max.toFloat())
                val v = volume.toInt()
                if (v != audio.getStreamVolume(AudioManager.STREAM_MUSIC)) {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                }
                val pct = v * 100 / max
                gestureText.text = getString(R.string.gesture_volume, pct)
                gestureBar.progress = pct
            }
        }

        override fun onGestureEnd() {
            gestureIndicator.postDelayed(hideIndicator, 600)
        }
    }

    private fun resetBrightness() {
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // ---------- Helper ----------

    /** "www.anikoto.su" -> "anikoto.su" */
    private fun baseDomain(host: String): String {
        val parts = host.lowercase().split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase()
    }

    private fun isAllowed(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return allowedDomains.any { h == it || h.endsWith(".$it") }
    }

    /** Halaman login & captcha pihak ketiga yang boleh dibuka. */
    private val authDomains = setOf(
        "accounts.google.com", "accounts.youtube.com", "www.google.com",
        "discord.com", "discordapp.com",
        "facebook.com", "x.com", "twitter.com", "appleid.apple.com",
        "challenges.cloudflare.com", "hcaptcha.com"
    )

    private fun isAuth(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.lowercase()
        return authDomains.any { h == it || h.endsWith(".$it") }
    }

    private fun blockedToast(host: String?) {
        if (host.isNullOrEmpty()) return
        Toast.makeText(this, getString(R.string.link_blocked, host), Toast.LENGTH_SHORT).show()
    }

    private val emptyResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    // CSS untuk menyembunyikan sisa elemen iklan yang lolos
    private val adHideJs = """
        (function(){
          if (document.getElementById('__aniview_css')) return;
          var s = document.createElement('style');
          s.id = '__aniview_css';
          s.textContent = 'ins.adsbygoogle,.adsbygoogle,[id^="ad-banner"],[class^="ad-banner"],' +
            '[id*="popunder"],[class*="popunder"],[id^="ads-"],[class^="ads-"],' +
            'iframe[src*="/ads/"],iframe[src*="doubleclick"],div[id^="div-gpt-ad"]' +
            '{display:none!important;visibility:hidden!important;height:0!important;}';
          (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    // ---------- WebViewClient: adblock + kunci domain ----------

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host
            // Jangan blokir domain situs utama sendiri
            if (!isAllowed(host) && AdBlocker.isBlocked(host)) return emptyResponse
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()

            // intent://, market://, dll. biasanya redirect iklan ke aplikasi lain
            if (scheme != "http" && scheme != "https") {
                return scheme != "about" && scheme != "blob" && scheme != "data" && scheme != "javascript"
            }

            val host = url.host
            if (AdBlocker.isBlocked(host) && !isAllowed(host)) return true

            // Navigasi di dalam iframe player dibiarkan
            if (!request.isForMainFrame) return false

            if (isAllowed(host) || isAuth(host)) return false

            // Saat pertama buka, ikuti redirect domain (misalnya situs pindah domain)
            if (initialLoad && request.isRedirect && host != null) {
                allowedDomains.add(baseDomain(host))
                return false
            }

            blockedToast(host)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            errorView.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            initialLoad = false
            view.evaluateJavascript(adHideJs, null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) errorView.visibility = View.VISIBLE
        }
    }

    // ---------- WebChromeClient: progress, fullscreen, pop-up ----------

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            fullscreenContainer.visibility = View.VISIBLE
            webView.visibility = View.GONE
            setSystemBarsHidden(true)
            ViewCompat.requestApplyInsets(findViewById(R.id.root))
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        override fun onHideCustomView() {
            val view = customView ?: return
            fullscreenContainer.removeView(view)
            fullscreenContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            setSystemBarsHidden(false)
            ViewCompat.requestApplyInsets(findViewById(R.id.root))
            resetBrightness()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        /**
         * Tab/jendela baru: tangkap URL tujuannya.
         * - Situs yang sama  -> buka di jendela utama
         * - Halaman login (Google, Discord, dll.) -> tampilkan sebagai jendela pop-up
         * - Domain lain (iklan) -> buang
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val temp = WebView(this@MainActivity)
            setupWebView(temp)
            temp.webViewClient = object : WebViewClient() {
                private var decided = false

                /** true = batalkan pemuatan */
                private fun decide(url: Uri?): Boolean {
                    val scheme = url?.scheme?.lowercase()
                    if (url == null || scheme == "about" || scheme == "javascript") return false // tunggu URL sebenarnya
                    decided = true
                    return when {
                        isAuth(url.host) -> { showPopup(temp); false }
                        isAllowed(url.host) -> {
                            webView.loadUrl(url.toString())
                            temp.post { temp.destroy() }
                            true
                        }
                        else -> {
                            blockedToast(url.host)
                            temp.post { temp.destroy() }
                            true
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    // Setelah jadi pop-up login, biarkan alurnya berpindah domain
                    if (decided) return false
                    return decide(request.url)
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    if (!decided && decide(url?.let { Uri.parse(it) })) v.stopLoading()
                }
            }
            temp.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) = closePopup()
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }

    // ---------- Pop-up login ----------

    private fun showPopup(popup: WebView) {
        if (popupView === popup) return
        closePopup()
        popupView = popup
        findViewById<FrameLayout>(R.id.root).addView(popup, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun closePopup() {
        val p = popupView ?: return
        popupView = null
        findViewById<FrameLayout>(R.id.root).removeView(p)
        p.post { p.destroy() }
        CookieManager.getInstance().flush()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(w: WebView) {
        with(w.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(getColor(R.color.bg))
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
