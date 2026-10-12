package com.din131.aniview

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var fullscreenContainer: FrameLayout

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

        errorView.setOnClickListener {
            errorView.visibility = View.GONE
            webView.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> webView.webChromeClient?.onHideCustomView()
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
        webView.onPause()
        super.onPause()
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

            if (isAllowed(host)) return false

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
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        /**
         * Tab/jendela baru: tangkap URL tujuannya. Kalau masih situs yang sama,
         * buka di jendela utama; kalau domain lain (iklan), buang.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) return false
            val temp = WebView(this@MainActivity)
            temp.webViewClient = object : WebViewClient() {
                private var handled = false
                private fun handle(url: Uri?) {
                    if (handled) return
                    handled = true
                    if (url != null && isAllowed(url.host)) webView.loadUrl(url.toString())
                    else blockedToast(url?.host)
                    temp.post { temp.destroy() }
                }
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    handle(request.url)
                    return true
                }
                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    v.stopLoading()
                    handle(url?.let { Uri.parse(it) })
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }
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
