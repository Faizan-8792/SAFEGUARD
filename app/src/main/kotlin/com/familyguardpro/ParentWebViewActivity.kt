package com.familyguardpro

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Parent Dashboard WebView Activity
 * Loads the web dashboard inside the app for a native-like experience
 */
class ParentWebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    
    private val baseDashboardUrl = "${FamilyGuardApp.BASE_URL}dashboard/"
    
    // Dashboard URL with auth token to avoid double login
    private val dashboardUrl: String
        get() {
            val app = application as FamilyGuardApp
            val token = app.preferenceManager.getAuthToken() ?: ""
            return if (token.isNotEmpty()) {
                "$baseDashboardUrl?token=$token"
            } else {
                baseDashboardUrl
            }
        }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_webview)
        
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        
        setupWebView()
        setupSwipeRefresh()
        
        // Load dashboard - web page has its own login form, no need to redirect
        loadDashboard()
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            // Enable JavaScript
            javaScriptEnabled = true
            
            // Enable DOM storage for localStorage
            domStorageEnabled = true
            
            // Enable database/IndexedDB
            databaseEnabled = true
            
            // Allow mixed content (http and https)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            
            // Enable zoom controls
            builtInZoomControls = true
            displayZoomControls = false
            
            // Viewport settings for responsive design
            useWideViewPort = true
            loadWithOverviewMode = true
            
            // Enable caching
            cacheMode = WebSettings.LOAD_DEFAULT
            
            // Allow file access
            allowFileAccess = true
            allowContentAccess = true
            
            // User agent
            userAgentString = "$userAgentString FamilyGuardApp/1.0"
            
            // Media playback
            mediaPlaybackRequiresUserGesture = false
        }
        
        // WebView client to handle page loading
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                
                // Token is now passed via URL parameter, so no injection needed
                // injectAuthToken() - removed to avoid double login issue
            }
            
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    showOfflineError()
                }
            }
            
            // Handle URL loading
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                // Open external links in browser
                if (!url.contains(FamilyGuardApp.BASE_URL.replace("https://", "").replace("http://", ""))) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
                
                return false
            }
        }
        
        // WebChromeClient for JavaScript alerts, file uploads, etc.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
            }
            
            // Handle JavaScript alerts
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@ParentWebViewActivity)
                    .setTitle("FamilyGuard")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setCancelable(false)
                    .show()
                return true
            }
            
            // Handle JavaScript confirm dialogs
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@ParentWebViewActivity)
                    .setTitle("Confirm")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result?.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setCancelable(false)
                    .show()
                return true
            }
            
            // Handle JavaScript prompts
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                val input = android.widget.EditText(this@ParentWebViewActivity)
                input.setText(defaultValue)
                
                AlertDialog.Builder(this@ParentWebViewActivity)
                    .setTitle("Input")
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton("OK") { _, _ -> result?.confirm(input.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> result?.cancel() }
                    .setCancelable(false)
                    .show()
                return true
            }
            
            // Console messages for debugging
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                android.util.Log.d("WebView", "${consoleMessage?.message()} -- From line ${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return true
            }
        }
        
        // Enable debugging (can connect via chrome://inspect)
        WebView.setWebContentsDebuggingEnabled(true)
    }
    
    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeResources(
            R.color.primary,
            R.color.primary_dark
        )
        
        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }
    }
    
    private fun loadDashboard() {
        webView.loadUrl(dashboardUrl)
    }
    
    private fun showOfflineError() {
        AlertDialog.Builder(this)
            .setTitle("Connection Error")
            .setMessage("Unable to load dashboard. Please check your internet connection.")
            .setPositiveButton("Retry") { _, _ -> loadDashboard() }
            .setNegativeButton("Exit") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
    
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            // Show confirm dialog before exiting
            AlertDialog.Builder(this)
                .setTitle("Exit Dashboard")
                .setMessage("Are you sure you want to exit?")
                .setPositiveButton("Yes") { _, _ -> 
                    // Clear auth and go to main
                    val app = application as FamilyGuardApp
                    app.preferenceManager.clearAuthToken()
                    app.preferenceManager.setChildMode(false)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                .setNegativeButton("No", null)
                .show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        webView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        webView.onPause()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
