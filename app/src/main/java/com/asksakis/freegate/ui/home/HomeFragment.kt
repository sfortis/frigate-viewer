package com.asksakis.freegate.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.asksakis.freegate.R
import com.asksakis.freegate.databinding.FragmentHomeBinding
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.TopSwipeRefreshLayout
import kotlin.system.exitProcess

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var networkUtils: NetworkUtils
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    // Permission launcher removed - app doesn't use notifications
    
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var previousNetworkSsid: String = ""
    private var currentLoadedUrl: String? = null
    private var networkTransitionInProgress = false
    private var lastUrlChangeTime: Long = 0
    private val URL_CHANGE_DEBOUNCE_MS = 3000L
    private var pendingUrl: String? = null
    private val NETWORK_CHECK_TIMEOUT_MS = 5000L
    
    companion object {
        private const val TAG = "HomeFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        networkUtils = NetworkUtils.getInstance(requireContext())
        
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root
        
        setupNetworkObservers()
        setupWebView()
        setupFileChooserLauncher()
        // Permission launcher setup removed - app doesn't use notifications
        setupBackPressedCallback()
        
        return root
    }
    
    private fun setupNetworkObservers() {
        homeViewModel.currentUrl.observe(viewLifecycleOwner) { url ->
            Log.d(TAG, "URL LiveData updated: $url")
            
            if (url != null) {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastChange = currentTime - lastUrlChangeTime
                
                if (currentLoadedUrl == null) {
                    // Initial load
                    Log.d(TAG, "Initial URL load: $url")
                    binding.loadingProgress.visibility = View.VISIBLE
                    
                    // Check network connectivity before loading
                    checkNetworkConnectivity { isConnected ->
                        if (isConnected) {
                            binding.webView.loadUrl(url)
                            currentLoadedUrl = url
                            lastUrlChangeTime = currentTime
                        } else {
                            Log.d(TAG, "No connectivity for initial load - will retry when network available")
                        }
                    }
                } else if (url != currentLoadedUrl && !networkTransitionInProgress 
                    && timeSinceLastChange > URL_CHANGE_DEBOUNCE_MS) {
                    // URL changed and not during transition, and enough time has passed
                    Log.d(TAG, "URL changed from $currentLoadedUrl to $url (time since last: ${timeSinceLastChange}ms)")
                    binding.loadingProgress.visibility = View.VISIBLE
                    
                    // Check network connectivity before loading new URL
                    checkNetworkConnectivity { isConnected ->
                        if (isConnected) {
                            binding.webView.loadUrl(url)
                            currentLoadedUrl = url
                            lastUrlChangeTime = currentTime
                        } else {
                            Log.d(TAG, "No connectivity for URL change - will retry when network available")
                            // Set a flag to retry this URL when network becomes available
                            pendingUrl = url
                        }
                    }
                } else if (url != currentLoadedUrl) {
                    Log.d(TAG, "URL change ignored - too soon or in transition (time since last: ${timeSinceLastChange}ms)")
                }
            }
        }
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initial URL load is now handled in setupNetworkObservers
        // Removed duplicate URL observer
        
        homeViewModel.currentNetwork.observe(viewLifecycleOwner) { networkInfo ->
            Log.d(TAG, "Network changed: $networkInfo")
            
            val ssid = networkInfo?.substringAfter(": ")?.trim() ?: ""
            
            if (previousNetworkSsid != ssid && ssid.isNotEmpty()) {
                Log.d(TAG, "Network state changed from '$previousNetworkSsid' to '$ssid'")
                
                // Set transition flag immediately to prevent multiple reloads
                networkTransitionInProgress = true
                
                // Use a delayed handler to refresh status after network stabilizes
                binding.webView.postDelayed({
                    homeViewModel.refreshStatus()
                    // Reset transition flag after another delay
                    binding.webView.postDelayed({
                        networkTransitionInProgress = false
                    }, URL_CHANGE_DEBOUNCE_MS)
                }, 1000) // Wait 1 second for network to stabilize
            }
            
            previousNetworkSsid = ssid
        }
    }
    
    private fun setupFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (fileUploadCallback == null) {
                return@registerForActivityResult
            }
            
            val data = result.data
            var results: Array<Uri>? = null
            
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                if (data?.dataString != null) {
                    results = arrayOf(Uri.parse(data.dataString))
                } else if (data?.clipData != null) {
                    val count = data.clipData!!.itemCount
                    results = Array(count) { i ->
                        data.clipData!!.getItemAt(i).uri
                    }
                }
            }
            
            fileUploadCallback?.onReceiveValue(results)
            fileUploadCallback = null
        }
    }
    
    // Permission launcher setup removed - app doesn't use notifications
    
    private fun setupWebView() {
        // Combine all WebViewClient functionality into one object
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                // Accept all SSL certificates including self-signed
                // WARNING: This bypasses SSL security - only use for trusted internal networks
                val primaryError = when (error?.primaryError) {
                    android.net.http.SslError.SSL_NOTYETVALID -> "Certificate not yet valid"
                    android.net.http.SslError.SSL_EXPIRED -> "Certificate expired"
                    android.net.http.SslError.SSL_IDMISMATCH -> "Certificate ID mismatch"
                    android.net.http.SslError.SSL_UNTRUSTED -> "Certificate not trusted"
                    android.net.http.SslError.SSL_DATE_INVALID -> "Certificate date invalid"
                    android.net.http.SslError.SSL_INVALID -> "Certificate invalid"
                    else -> "Unknown SSL error"
                }
                Log.w(TAG, "SSL error occurred: $primaryError for URL: ${error?.url} - proceeding anyway")
                handler?.proceed()
            }
            
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                Log.d(TAG, "shouldOverrideUrlLoading: $url")
                
                // Handle intent:// URLs
                if (url?.startsWith("intent://") == true) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        if (intent != null) {
                            val packageManager = view?.context?.packageManager
                            val info = packageManager?.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                            if (info != null) {
                                view.context?.startActivity(intent)
                            } else {
                                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                                if (fallbackUrl != null) {
                                    view?.loadUrl(fallbackUrl)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return true
                }
                
                // Handle regular URLs
                return if (url?.startsWith("http://") == true || url?.startsWith("https://") == true) {
                    view?.loadUrl(url)
                    true
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Error launching intent for URL: $url", e)
                        false
                    }
                }
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val safeBinding = _binding ?: return
                safeBinding.loadingProgress.visibility = View.GONE
                currentLoadedUrl = url
                Log.d(TAG, "Page finished loading: $url")
                
                // Reset network transition flag when page finishes loading
                networkTransitionInProgress = false
                
                safeBinding.swipeRefresh.isRefreshing = false
            }
            
            override fun onReceivedError(
                view: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                val safeBinding = _binding ?: return
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.e(TAG, "WebView error: ${error.errorCode} - ${error.description} at ${request.url}")
                    safeBinding.loadingProgress.visibility = View.GONE
                    
                    // Check if it's a connection error (like ERR_CONNECTION_RESET or ERR_INTERNET_DISCONNECTED)
                    if (error.errorCode == android.webkit.WebViewClient.ERROR_CONNECT ||
                        error.errorCode == android.webkit.WebViewClient.ERROR_HOST_LOOKUP ||
                        error.errorCode == android.webkit.WebViewClient.ERROR_TIMEOUT ||
                        error.errorCode == android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) {
                        
                        Log.d(TAG, "Network-related error detected, will retry when network is available")
                        
                        // Check if the error is for the main page or a resource
                        val isMainFrameError = request.isForMainFrame || 
                            (currentLoadedUrl != null && request.url.toString().startsWith(currentLoadedUrl!!))
                        
                        // Only handle main frame errors, ignore resource errors like analytics
                        if (isMainFrameError) {
                            // Get the current URL or the pending one
                            val mainUrl = if (request.isForMainFrame) request.url.toString() else currentLoadedUrl
                            
                            // If this is a Cloudflare analytics error, we can safely ignore it
                            if (request.url.toString().contains("cloudflareinsights") || 
                                request.url.toString().contains("analytics")) {
                                Log.d(TAG, "Ignoring analytics error - not critical for page loading")
                                return
                            }
                            
                            Log.d(TAG, "Critical page-loading error, will retry when DNS is available")
                            pendingUrl = mainUrl
                            
                            // Set up a periodic network check with DNS resolution to retry
                            val handler = android.os.Handler(android.os.Looper.getMainLooper())
                            val retryRunnable = object : Runnable {
                                var retryCount = 0
                                
                                override fun run() {
                                    retryCount++
                                    
                                    if (retryCount > 10) {
                                        Log.d(TAG, "Giving up after 10 retry attempts")
                                        return
                                    }
                                    
                                    checkNetworkConnectivity { isConnected ->
                                        if (isConnected && pendingUrl != null) {
                                            Log.d(TAG, "Network with DNS is now available, retrying URL: $pendingUrl")
                                            pendingUrl?.let { url ->
                                                view.loadUrl(url)
                                                pendingUrl = null
                                            }
                                        } else if (!isConnected) {
                                            // Schedule another check in 3 seconds
                                            handler.postDelayed(this, 3000)
                                        }
                                    }
                                }
                            }
                            
                            // Start checking for network connectivity
                            handler.postDelayed(retryRunnable, 3000)
                        } else {
                            // For resource errors (especially cloudflareinsights), log but don't retry
                            Log.d(TAG, "Resource error for ${request.url} - ignoring as it's not critical")
                        }
                    }
                }
            }
            
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val safeBinding = _binding ?: return
                
                if (!isReload) {
                    safeBinding.loadingProgress.visibility = View.VISIBLE
                    view?.reload()
                }
            }
        }
        
        
        // Setup WebChromeClient
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(TAG, "Console: ${consoleMessage?.message()} -- From line " +
                        "${consoleMessage?.lineNumber()} of ${consoleMessage?.sourceId()}")
                return true
            }
            
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val safeBinding = _binding ?: return false
                
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                
                val intent = fileChooserParams?.createIntent()
                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    fileUploadCallback = null
                    // Toast removed - unnecessary notification
                    return false
                }
                return true
            }
            
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                val safeBinding = _binding ?: return
                
                if (newProgress < 100) {
                    safeBinding.loadingProgress.visibility = View.VISIBLE
                    safeBinding.loadingProgress.progress = newProgress
                } else {
                    safeBinding.loadingProgress.visibility = View.GONE
                }
            }
            
            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                val safeBinding = _binding ?: return
                
                request?.resources?.let { resources ->
                    if (resources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE) ||
                        resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        
                        if (ContextCompat.checkSelfPermission(safeBinding.root.context, 
                                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(safeBinding.root.context, 
                                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(resources)
                        } else {
                            request.deny()
                        }
                    } else {
                        request.grant(resources)
                    }
                } ?: request?.deny()
            }
        }
        
        val webSettings = binding.webView.settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        
        webSettings.javaScriptEnabled = prefs.getBoolean("enable_javascript", true)
        webSettings.domStorageEnabled = prefs.getBoolean("enable_dom_storage", true)
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webSettings.safeBrowsingEnabled = true
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        
        webSettings.mediaPlaybackRequiresUserGesture = false
        
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        
        // Removed deprecated file access settings - targeting Android 13+
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        
        val prefs2 = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val useCustomUserAgent = prefs2.getBoolean("use_custom_user_agent", false)
        
        webSettings.userAgentString = if (useCustomUserAgent) {
            prefs2.getString("custom_user_agent", 
                "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36"
            ) ?: "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36"
        } else {
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Mobile Safari/537.36"
        }
        
        webSettings.javaScriptCanOpenWindowsAutomatically = true
        
        @Suppress("DEPRECATION")
        webSettings.databaseEnabled = true
        
        binding.swipeRefresh.setOnRefreshListener {
            networkUtils.checkStatus()
            binding.webView.reload()
            binding.swipeRefresh.isRefreshing = false
        }
        
        if (context != null) {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            val isConnected = networkCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            
            if (!isConnected) {
                // Error message removed from layout
                // Toast removed - unnecessary notification
            }
        }
    }
    
    // Notification permission method removed - app doesn't use notifications
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }
    
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        
        binding.webView.run {
            clearHistory()
            clearCache(true)
            loadUrl("about:blank")
            onPause()
            removeAllViews()
            destroy()
        }
        
        _binding = null
    }
    
    fun refreshNetworkStatus() {
        activity?.runOnUiThread {
            try {
                networkUtils.checkStatus()
                networkUtils.checkStatus()
                
                val currentUrl = homeViewModel.currentUrl.value
                Log.d(TAG, "Current URL from HomeViewModel: $currentUrl")
                Log.d(TAG, "Current loaded URL: $currentLoadedUrl")
                Log.d(TAG, "WebView current URL: ${binding.webView.url}")
                
                if (currentUrl != binding.webView.url && currentUrl != null) {
                    Log.d(TAG, "URL mismatch - forcing reload to: $currentUrl")
                    binding.webView.loadUrl(currentUrl)
                    currentLoadedUrl = currentUrl
                }
                
                // Toast removed - unnecessary notification
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing network status", e)
                // Toast removed - error already logged
            }
        }
    }
    
    fun forceNetworkRefresh() {
        activity?.runOnUiThread {
            try {
                Log.d(TAG, "Force network refresh requested")
                
                networkUtils.checkStatus()
                
                val currentUrl = homeViewModel.currentUrl.value
                val ssid = networkUtils.getSsid()
                val isHomeNetwork = networkUtils.isHome()
                val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val internalUrl = sharedPrefs.getString("internal_url", "http://frigate.local") ?: "http://frigate.local"
                val externalUrl = sharedPrefs.getString("external_url", "https://example.com/frigate") ?: "https://example.com/frigate"
                val expectedUrl = if (isHomeNetwork) internalUrl else externalUrl
                
                Log.d(TAG, "Force refresh details:")
                Log.d(TAG, "- Current SSID: $ssid")
                Log.d(TAG, "- Is home network: $isHomeNetwork")
                Log.d(TAG, "- Current URL: $currentUrl")
                Log.d(TAG, "- Expected URL: $expectedUrl")
                Log.d(TAG, "- Current loaded URL: $currentLoadedUrl")
                Log.d(TAG, "- WebView current URL: ${binding.webView.url}")
                
                if (expectedUrl != currentLoadedUrl) {
                    Log.d(TAG, "URL mismatch detected - forcing reload to: $expectedUrl")
                    binding.webView.loadUrl(expectedUrl)
                    currentLoadedUrl = expectedUrl
                    
                    Toast.makeText(
                        context,
                        "Switching to ${if (isHomeNetwork) "internal" else "external"} URL",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Log.d(TAG, "URL is already correct - performing regular reload")
                    binding.webView.reload()
                    
                    // Toast removed - unnecessary notification
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during force network refresh", e)
                // Toast removed - error already logged
            }
        }
    }
    
    fun injectFullscreenButton(jsCode: String) {
        activity?.runOnUiThread {
            try {
                binding.webView.evaluateJavascript(jsCode) { result ->
                    if (result != null) {
                        Log.d(TAG, "Fullscreen button injection result: $result")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error injecting fullscreen button", e)
            }
        }
    }
    
    fun handleExitApp() {
        activity?.finishAffinity()
        exitProcess(0)
    }
    
    private fun setupBackPressedCallback() {
        val callback = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    // No more history, exit the app
                    handleExitApp()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    }
    
    /**
     * Checks if network is available and connected before proceeding with a URL load
     * Uses a timeout to prevent indefinite waiting
     * Also verifies DNS resolution is working properly
     */
    private fun checkNetworkConnectivity(callback: (Boolean) -> Unit) {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.e(TAG, "ConnectivityManager is null")
            callback(false)
            return
        }
        
        // Get the active network and capabilities
        val activeNetwork = cm.activeNetwork
        val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val hasInternetCapability = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val hasValidatedCapability = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        
        // If there's already connectivity, do a DNS resolution test to ensure it's fully functional
        if (hasInternetCapability && hasValidatedCapability) {
            Log.d(TAG, "Network appears to be available - testing DNS resolution")
            
            // Set up a timeout for the DNS check
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                Log.d(TAG, "DNS resolution test timed out - network might not be fully ready")
                callback(false)
            }
            
            // Give the DNS check 3 seconds to complete
            handler.postDelayed(timeoutRunnable, 3000)
            
            // Run a DNS test in a background thread
            Thread {
                try {
                    // Try to resolve a reliable domain (google.com)
                    val inetAddress = java.net.InetAddress.getByName("google.com")
                    val hostAddress = inetAddress.hostAddress
                    
                    Log.d(TAG, "DNS resolution successful: google.com -> $hostAddress")
                    
                    // Remove the timeout and report success
                    handler.removeCallbacks(timeoutRunnable)
                    
                    // Add a small delay to ensure the network stack is fully ready
                    handler.postDelayed({
                        callback(true)
                    }, 1000)
                } catch (e: Exception) {
                    Log.e(TAG, "DNS resolution failed: ${e.message}")
                    
                    // DNS failed - wait a bit longer and try again once more
                    try {
                        // Wait a moment and try another domain
                        Thread.sleep(1500)
                        val backupAddress = java.net.InetAddress.getByName("cloudflare.com")
                        Log.d(TAG, "Backup DNS resolution successful: cloudflare.com -> ${backupAddress.hostAddress}")
                        
                        handler.removeCallbacks(timeoutRunnable)
                        handler.post { callback(true) }
                    } catch (e2: Exception) {
                        Log.e(TAG, "Backup DNS resolution also failed: ${e2.message}")
                        handler.removeCallbacks(timeoutRunnable)
                        handler.post { callback(false) }
                    }
                }
            }.start()
            
            return
        }
        
        // No immediate connectivity, wait for it with a timeout
        Log.d(TAG, "Network not immediately available, waiting with timeout")
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            Log.d(TAG, "Network connectivity check timed out after ${NETWORK_CHECK_TIMEOUT_MS}ms")
            callback(false)
        }
        
        // Set a timeout to prevent indefinite waiting
        handler.postDelayed(timeoutRunnable, NETWORK_CHECK_TIMEOUT_MS)
        
        // Register a one-time network callback
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network became available during check")
                
                // Instead of immediately calling back, do a DNS check
                handler.removeCallbacks(timeoutRunnable)
                
                // Wait a bit for DNS to be fully operational (mobile networks need this)
                handler.postDelayed({
                    // Run a DNS resolution test
                    Thread {
                        try {
                            val inetAddress = java.net.InetAddress.getByName("google.com")
                            Log.d(TAG, "DNS resolution successful after network became available")
                            
                            handler.post {
                                callback(true)
                                try {
                                    cm.unregisterNetworkCallback(this)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error unregistering callback: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "DNS resolution failed after network became available: ${e.message}")
                            
                            // Try once more after a delay
                            try {
                                Thread.sleep(2000)
                                val backupAddress = java.net.InetAddress.getByName("cloudflare.com")
                                Log.d(TAG, "Backup DNS resolution successful after retry")
                                
                                handler.post {
                                    callback(true)
                                    try {
                                        cm.unregisterNetworkCallback(this)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error unregistering callback: ${e.message}")
                                    }
                                }
                            } catch (e2: Exception) {
                                Log.e(TAG, "Backup DNS resolution also failed: ${e2.message}")
                                handler.post {
                                    callback(false)
                                    try {
                                        cm.unregisterNetworkCallback(this)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error unregistering callback: ${e.message}")
                                    }
                                }
                            }
                        }
                    }.start()
                }, 2000) // Wait 2 seconds for DNS to be operational
            }
            
            override fun onLost(network: Network) {
                // Network lost during check period
                Log.d(TAG, "Network lost during connectivity check")
            }
            
            override fun onUnavailable() {
                Log.d(TAG, "Network declared unavailable during check")
                handler.removeCallbacks(timeoutRunnable)
                callback(false)
                try {
                    cm.unregisterNetworkCallback(this)
                } catch (e: Exception) {
                    Log.e(TAG, "Error unregistering callback: ${e.message}")
                }
            }
        }
        
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback: ${e.message}")
            handler.removeCallbacks(timeoutRunnable)
            callback(false)
        }
    }
    
    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
    }
    
    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }
    
    private fun getDefaultLauncherPackageName(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }
    
    private fun isAppSetAsDefaultLauncher(context: Context): Boolean {
        val packageName = context.packageName
        val defaultLauncherPackageName = getDefaultLauncherPackageName(context)
        return packageName == defaultLauncherPackageName
    }
    
    override fun onStop() {
        super.onStop()
        binding.swipeRefresh.isEnabled = false
    }
    
    override fun onStart() {
        super.onStart()
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isGestureEnabled = prefs.getBoolean("enable_gesture_refresh", false)
        binding.swipeRefresh.isEnabled = isGestureEnabled
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            WebView(requireContext()).destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}