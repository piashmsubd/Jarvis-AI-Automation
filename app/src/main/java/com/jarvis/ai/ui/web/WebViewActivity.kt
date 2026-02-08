package com.jarvis.ai.ui.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.ai.databinding.ActivityWebviewBinding

/**
 * WebViewActivity — Full browser for search and web access.
 *
 * Launch modes:
 *   1. SEARCH query: Opens Google search with the query.
 *   2. Direct URL: Loads the URL directly.
 *
 * Features:
 *   - Full JavaScript execution
 *   - DOM storage (localStorage, sessionStorage)
 *   - File upload support
 *   - Download handling
 *   - Back navigation within the WebView
 *   - Progress bar
 *   - Page title extraction (for AI context)
 *
 * Usage from code:
 *   WebViewActivity.launchSearch(context, "latest Android news")
 *   WebViewActivity.launchUrl(context, "https://developer.android.com")
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_QUERY = "extra_query"
        private const val GOOGLE_SEARCH_URL = "https://www.google.com/search?q="

        /** Launch with a search query — opens Google search. */
        fun launchSearch(context: Context, query: String) {
            val intent = Intent(context, WebViewActivity::class.java).apply {
                putExtra(EXTRA_QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        /** Launch with a direct URL. */
        fun launchUrl(context: Context, url: String) {
            val intent = Intent(context, WebViewActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        /** The last page content extracted — available for AI context injection. */
        @Volatile
        var lastPageTitle: String = ""
            private set

        @Volatile
        var lastPageUrl: String = ""
            private set

        @Volatile
        var lastExtractedText: String = ""
            private set
    }

    private lateinit var binding: ActivityWebviewBinding

    // ------------------------------------------------------------------ //
    //  Lifecycle                                                          //
    // ------------------------------------------------------------------ //

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupToolbar()

        // Determine what to load
        val query = intent.getStringExtra(EXTRA_QUERY)
        val url = intent.getStringExtra(EXTRA_URL)

        when {
            !query.isNullOrBlank() -> {
                val searchUrl = GOOGLE_SEARCH_URL + Uri.encode(query)
                binding.webView.loadUrl(searchUrl)
                binding.tvTitle.text = "Searching: $query"
            }
            !url.isNullOrBlank() -> {
                val safeUrl = if (!url.startsWith("http")) "https://$url" else url
                binding.webView.loadUrl(safeUrl)
                binding.tvTitle.text = "Loading..."
            }
            else -> {
                binding.webView.loadUrl("https://www.google.com")
                binding.tvTitle.text = "Google"
            }
        }
    }

    override fun onDestroy() {
        // Extract page text before closing for AI context
        extractPageContent()
        binding.webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && binding.webView.canGoBack()) {
            binding.webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ //
    //  WebView Configuration                                              //
    // ------------------------------------------------------------------ //

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            // Core features
            javaScriptEnabled = true
            domStorageEnabled = true                // localStorage + sessionStorage
            databaseEnabled = true

            // Layout
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)

            // Cache
            cacheMode = WebSettings.LOAD_DEFAULT

            // Media
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true

            // Mixed content (some sites still serve HTTP resources on HTTPS pages)
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // User agent — identify as standard Chrome to avoid mobile-hostile responses
            userAgentString = userAgentString.replace("; wv", "")
        }

        // Handle page loading events
        binding.webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progressBar.visibility = View.VISIBLE
                binding.tvUrl.text = url ?: ""
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE

                // Update title
                val title = view?.title ?: ""
                binding.tvTitle.text = title

                // Store for AI context
                lastPageTitle = title
                lastPageUrl = url ?: ""

                // Extract visible text from the page for AI context
                extractPageContent()

                // Update navigation buttons
                binding.btnBack.alpha = if (binding.webView.canGoBack()) 1.0f else 0.3f
                binding.btnForward.alpha = if (binding.webView.canGoForward()) 1.0f else 0.3f
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Handle special schemes (tel:, mailto:, intent:)
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    false  // Let WebView handle it
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) { }
                    true
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    binding.tvTitle.text = "Error loading page"
                }
            }
        }

        // Handle progress
        binding.webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                binding.tvTitle.text = title ?: ""
            }
        }

        // Handle downloads
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (_: Exception) { }
        }
    }

    // ------------------------------------------------------------------ //
    //  Toolbar                                                            //
    // ------------------------------------------------------------------ //

    private fun setupToolbar() {
        binding.btnClose.setOnClickListener { finish() }

        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }

        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }

        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
        }

        binding.btnShare.setOnClickListener {
            val url = binding.webView.url ?: return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_SUBJECT, binding.webView.title)
            }
            startActivity(Intent.createChooser(shareIntent, "Share URL"))
        }
    }

    // ------------------------------------------------------------------ //
    //  Page Content Extraction (for AI Context)                           //
    // ------------------------------------------------------------------ //

    /**
     * Extracts visible text content from the current page via JavaScript.
     * The result is stored in [lastExtractedText] for the AI brain to use.
     */
    private fun extractPageContent() {
        binding.webView.evaluateJavascript(
            """
            (function() {
                // Get the main content text, stripping scripts/styles
                var body = document.body;
                if (!body) return '';
                
                var clone = body.cloneNode(true);
                var scripts = clone.querySelectorAll('script, style, noscript, iframe');
                scripts.forEach(function(el) { el.remove(); });
                
                var text = clone.innerText || clone.textContent || '';
                // Collapse whitespace and limit length
                text = text.replace(/\s+/g, ' ').trim();
                return text.substring(0, 3000);
            })();
            """.trimIndent()
        ) { result ->
            // Result comes quoted from JS — strip quotes
            val cleaned = result
                ?.removeSurrounding("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\t", " ")
                ?.trim()
                ?: ""
            lastExtractedText = cleaned
        }
    }
}
