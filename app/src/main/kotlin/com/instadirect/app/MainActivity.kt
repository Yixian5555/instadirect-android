package com.instadirect.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()
        requestNotificationPermission()
        schedulePolling()

        webView = findViewById(R.id.webView)
        configureWebView()
        webView.loadUrl(INBOX_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = MOBILE_USER_AGENT
            setSupportZoom(false)
            builtInZoomControls = false
        }

        webView.addJavascriptInterface(NotificationBridge(applicationContext), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                try {
                    fileChooserLauncher.launch(params.createIntent())
                } catch (e: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                injectNotificationInterceptor(view)
                saveCookies()
                val path = Uri.parse(url).path ?: ""
                if (path == "/" || path.isEmpty()) {
                    view.clearHistory()
                    view.loadUrl(INBOX_URL)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return if (isAllowedUrl(request.url.toString())) false
                else { view.loadUrl(INBOX_URL); true }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val path = Uri.parse(url).path ?: ""
                val isDmPage = path.startsWith("/direct") || path.startsWith("/accounts") ||
                        path.startsWith("/challenge") || path.startsWith("/two_factor") || path == "/"
                if (!isDmPage) {
                    view.post { view.loadUrl(INBOX_URL) }
                }
            }
        }
    }

    private fun injectNotificationInterceptor(view: WebView) {
        view.evaluateJavascript("""
            (function() {
                if (window.__notifPatched) return;
                window.__notifPatched = true;
                const Orig = window.Notification;
                function PatchedNotification(title, opts) {
                    AndroidBridge.showNotification(
                        String(title || ''),
                        String((opts && opts.body) || '')
                    );
                    try { return new Orig(title, opts); } catch(e) {}
                }
                PatchedNotification.permission = 'granted';
                PatchedNotification.requestPermission = () => Promise.resolve('granted');
                window.Notification = PatchedNotification;
            })();
        """.trimIndent(), null)
    }

    private fun isAllowedUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        val host = uri.host ?: return false
        val isInstagram = host == "www.instagram.com" || host == "instagram.com" ||
                host.endsWith(".cdninstagram.com") || host.endsWith(".fbcdn.net")
        if (!isInstagram) return false
        val path = uri.path ?: return true
        return path.startsWith("/direct") ||
                path.startsWith("/accounts") ||
                path.startsWith("/challenge") ||
                path.startsWith("/two_factor") ||
                path.startsWith("/api") ||
                path == "/"
    }

    private fun saveCookies() {
        val cookies = android.webkit.CookieManager.getInstance()
            .getCookie("https://www.instagram.com") ?: return
        getSharedPreferences(DmCheckWorker.PREFS, Context.MODE_PRIVATE)
            .edit().putString(DmCheckWorker.KEY_COOKIES, cookies).apply()
    }

    private fun schedulePolling() {
        val request = PeriodicWorkRequestBuilder<DmCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "dm_check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Instagram DMs", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "New direct messages" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.pauseTimers()
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    inner class NotificationBridge(private val ctx: Context) {
        @JavascriptInterface
        fun showNotification(title: String, body: String) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title.ifEmpty { "Instagram DM" })
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx)
                .notify(System.currentTimeMillis().toInt(), notification)
        }
    }

    companion object {
        private const val INBOX_URL = "https://www.instagram.com/direct/inbox/"
        private const val CHANNEL_ID = "dm_channel"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Mobile Safari/537.36"
    }
}
