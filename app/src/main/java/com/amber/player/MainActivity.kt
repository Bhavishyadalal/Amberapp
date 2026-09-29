package com.amber.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingPanel: LinearLayout
    private lateinit var errorPanel: LinearLayout
    private lateinit var loadingStatus: TextView
    private lateinit var errorDetail: TextView
    private lateinit var retryButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val serverStarted = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)

    private val port = 8877
    private val baseUrl = "http://127.0.0.1:$port/"

    private var amberServer: AmberServer? = null
    private var nativeMediaPlayer: MediaPlayer? = null
    private var isNativePlaying = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.parseColor("#150F1B")
        window.navigationBarColor = Color.parseColor("#150F1B")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        webView = findViewById(R.id.webView)
        loadingPanel = findViewById(R.id.loadingPanel)
        errorPanel = findViewById(R.id.errorPanel)
        loadingStatus = findViewById(R.id.loadingStatus)
        errorDetail = findViewById(R.id.errorDetail)
        retryButton = findViewById(R.id.retryButton)

        retryButton.setOnClickListener { startAmber() }

        setupWebView()
        requestNotificationPermissionIfNeeded()

        if (intent?.getBooleanExtra(AmberService.EXTRA_STOP, false) == true) {
            finishAndRemoveTask()
            return
        }

        handleDeepLink(intent)
        startAmber()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(AmberService.EXTRA_STOP, false)) {
            finishAndRemoveTask()
            return
        }
        handleDeepLink(intent)
    }

    override fun onDestroy() {
        try {
            webView.destroy()
        } catch (_: Exception) {}
        try {
            nativeMediaPlayer?.release()
            nativeMediaPlayer = null
        } catch (_: Exception) {}
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun startAmber() {
        if (!starting.compareAndSet(false, true)) return
        showLoading(getString(R.string.starting_server))
        errorPanel.visibility = View.GONE

        worker.execute {
            try {
                if (!serverStarted.get()) {
                    amberServer = AmberServer(applicationContext, port)
                    amberServer?.start()
                    serverStarted.set(true)
                }

                waitForHealth()

                mainHandler.post {
                    AmberService.start(this@MainActivity)
                    showWebView()
                    pendingYoutubeUrl?.let { url ->
                        injectYoutubeUrl(url)
                        pendingYoutubeUrl = null
                    }
                    starting.set(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    starting.set(false)
                    showError(e.message ?: e.toString())
                }
            }
        }
    }

    private fun waitForHealth(timeoutMs: Long = 25000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                val url = java.net.URL("${baseUrl}api/health")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..299) return
            } catch (e: Exception) {
                lastError = e
            }
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                throw RuntimeException("Interrupted while waiting for server")
            }
        }
        throw RuntimeException(
            "Local server did not become ready in time. ${lastError?.message ?: ""}"
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.setSupportZoom(false)
        s.userAgentString = s.userAgentString + " AmberAndroid/1.0"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = false
        }

        webView.setBackgroundColor(Color.parseColor("#150F1B"))
        webView.addJavascriptInterface(AmberBridge(this), "AmberAndroid")

        webView.webChromeClient = object : WebChromeClient() {}

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) {
                    false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(
                    """
                    (function(){
                      if (window.__amberAndroidReady) return;
                      window.__amberAndroidReady = true;
                      window.AmberIsAndroid = true;
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }
    }

    private fun showWebView() {
        loadingPanel.visibility = View.GONE
        errorPanel.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(baseUrl)
    }

    private fun showLoading(msg: String) {
        loadingPanel.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        webView.visibility = View.GONE
        loadingStatus.text = msg
    }

    private fun showError(detail: String) {
        loadingPanel.visibility = View.GONE
        webView.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
        errorDetail.text = detail
    }

    private var pendingYoutubeUrl: String? = null

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        val url = data.toString()
        if (url.contains("youtube.com") || url.contains("youtu.be")) {
            if (serverStarted.get() && webView.visibility == View.VISIBLE) {
                injectYoutubeUrl(url)
            } else {
                pendingYoutubeUrl = url
            }
        }
    }

    private fun injectYoutubeUrl(url: String) {
        val safe = url.replace("\\", "\\\\").replace("'", "\\'")
        webView.evaluateJavascript(
            """
            (function(){
              try {
                var input = document.getElementById('q') || document.querySelector('input[type="text"]');
                if (input) {
                  input.value = '$safe';
                  input.dispatchEvent(new Event('input', {bubbles:true}));
                  input.dispatchEvent(new KeyboardEvent('keydown', {'key':'Enter'}));
                }
              } catch (e) {}
            })();
            """.trimIndent(),
            null
        )
        Toast.makeText(this, "Loaded YouTube link", Toast.LENGTH_SHORT).show()
    }

    fun playWithNativePlayer(url: String, title: String, channel: String, thumb: String, durMs: Long, startMs: Long) {
        try {
            nativeMediaPlayer?.release()
            nativeMediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { mp ->
                    if (startMs > 0) mp.seekTo(startMs.toInt())
                    mp.start()
                    isNativePlaying = true
                    notifyNativeState(true, mp.currentPosition.toLong(), mp.duration.toLong())
                }
                setOnCompletionListener {
                    isNativePlaying = false
                    notifyNativeTrackEnded()
                }
                setOnErrorListener { _, _, _ ->
                    isNativePlaying = false
                    notifyNativeTrackError()
                    true
                }
            }
        } catch (_: Exception) {
            notifyNativeTrackError()
        }
    }

    fun pauseNativeAudio() {
        try {
            nativeMediaPlayer?.pause()
            isNativePlaying = false
            notifyNativeState(false, nativeMediaPlayer?.currentPosition?.toLong() ?: 0, nativeMediaPlayer?.duration?.toLong() ?: 0)
        } catch (_: Exception) {}
    }

    fun resumeNativeAudio() {
        try {
            nativeMediaPlayer?.start()
            isNativePlaying = true
            notifyNativeState(true, nativeMediaPlayer?.currentPosition?.toLong() ?: 0, nativeMediaPlayer?.duration?.toLong() ?: 0)
        } catch (_: Exception) {}
    }

    fun seekNativeAudio(posMs: Long) {
        try {
            nativeMediaPlayer?.seekTo(posMs.toInt())
            notifyNativeState(isNativePlaying, posMs, nativeMediaPlayer?.duration?.toLong() ?: 0)
        } catch (_: Exception) {}
    }

    private fun notifyNativeState(isPlaying: Boolean, posMs: Long, durMs: Long) {
        mainHandler.post {
            webView.evaluateJavascript("window.onNativeStateChanged && window.onNativeStateChanged($isPlaying, $posMs, $durMs);", null)
        }
    }

    private fun notifyNativeTrackEnded() {
        mainHandler.post {
            webView.evaluateJavascript("window.onNativeTrackEnded && window.onNativeTrackEnded();", null)
        }
    }

    private fun notifyNativeTrackError() {
        mainHandler.post {
            webView.evaluateJavascript("window.onNativeTrackError && window.onNativeTrackError();", null)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
