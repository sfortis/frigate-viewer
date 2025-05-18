package com.asksakis.freegate.ui.home

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import com.asksakis.freegate.R
import com.asksakis.freegate.databinding.FragmentHomeBinding
import com.asksakis.freegate.utils.NetworkUtils
import com.google.android.material.snackbar.Snackbar

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var networkUtils: NetworkUtils
    // Track the current loaded URL to prevent redundant loads
    private var currentLoadedUrl = ""
    // WebView state bundle for restoration
    private var webViewState: Bundle? = null
    // Modern implementation doesn't use BroadcastReceiver, network status is checked proactively

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]
        networkUtils = NetworkUtils(requireContext())

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root
        
        // Restore saved state if available
        savedInstanceState?.let { bundle ->
            webViewState = bundle.getBundle("webViewState")
            currentLoadedUrl = bundle.getString("currentLoadedUrl", "")
        }
        

        // Setup WebChromeClient to suppress logs and handle advanced features
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                // Suppress all console messages to reduce logcat spam
                return true
            }
            
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                // Update progress silently without logging
                if (newProgress < 100) {
                    binding.loadingProgress.visibility = View.VISIBLE
                } else {
                    binding.loadingProgress.visibility = View.GONE
                }
            }
        }
        
        // Setup WebView with enhanced error handling
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d("HomeFragment", "Starting to load URL: $url")
                binding.loadingProgress.visibility = View.VISIBLE
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("HomeFragment", "Finished loading URL: $url")
                
                // Hide loading indicator for all pages (including blank pages)
                binding.loadingProgress.visibility = View.GONE
                
                // Update current URL if it's not a blank page
                if (url != "about:blank") {
                    currentLoadedUrl = url ?: ""
                }
                
                // Show network info again when page is loaded
                displayNetworkInfo()
            }
            
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                binding.loadingProgress.visibility = View.GONE
                
                // Only show errors for main frame requests, not for resources
                if (request?.isForMainFrame != true) {
                    return
                }
                
                val errorUrl = request.url.toString()
                val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    error?.errorCode
                } else {
                    -1
                }
                val errorDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    error?.description
                } else {
                    "Unknown error"
                }
                
                Log.e("HomeFragment", "Error loading URL: $errorUrl, code: $errorCode, description: $errorDescription")
                
                // Attempt to check if this is a network related error
                val isNetworkError = when (errorCode) {
                    WebViewClient.ERROR_HOST_LOOKUP,
                    WebViewClient.ERROR_CONNECT,
                    WebViewClient.ERROR_TIMEOUT,
                    WebViewClient.ERROR_UNKNOWN,
                    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
                    WebViewClient.ERROR_PROXY_AUTHENTICATION,
                    WebViewClient.ERROR_IO -> true
                    -6 -> true // ERROR_CONNECTION_ABORTED (not in constants)
                    else -> false
                }
                
                // Check if this is a connection abort due to URL switching
                if (errorCode == -6 && errorDescription?.contains("ERR_CONNECTION_ABORTED") == true) {
                    // This is expected when switching URLs, don't show error
                    Log.d("HomeFragment", "Connection aborted during URL switch - this is expected")
                    return
                }
                
                val message = when {
                    errorCode == WebViewClient.ERROR_HOST_LOOKUP -> {
                        // DNS/Host resolution error - the URL might be wrong
                        val urlType = if (homeViewModel.isHomeNetwork.value == true) "Internal" else "External"
                        "Cannot find server for $urlType URL.\nCheck the URL in Settings is correct."
                    }
                    errorCode == WebViewClient.ERROR_CONNECT -> {
                        // Connection refused or timeout
                        val urlType = if (homeViewModel.isHomeNetwork.value == true) "Internal" else "External"
                        "Cannot connect to $urlType URL.\nThe server might be down or unreachable."
                    }
                    errorCode == WebViewClient.ERROR_TIMEOUT -> {
                        "Connection timed out.\nThe server is taking too long to respond."
                    }
                    isNetworkError -> {
                        // Generic network error
                        val isCurrentlyUsingInternal = homeViewModel.isHomeNetwork.value == true
                        val alternateType = if (isCurrentlyUsingInternal) "External" else "Internal"
                        "Network error accessing the ${if (isCurrentlyUsingInternal) "Internal" else "External"} URL.\n" +
                        "Try switching to the $alternateType URL in Settings."
                    }
                    else -> {
                        // Non-network error
                        "Error loading page: $errorDescription"
                    }
                }
                
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                    .setAction("Settings") {
                        // Navigate to settings
                        requireActivity().findNavController(R.id.nav_host_fragment_content_main)
                            .navigate(R.id.nav_settings)
                    }.show()
                
                // Toggle refresh icon to indicate a refresh is possible
                binding.swipeRefresh.isRefreshing = false
            }
            
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                // Handle renderer crash
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val didCrash = detail?.didCrash() == true
                    
                    Log.e("HomeFragment", "WebView renderer gone - crashed: $didCrash")
                    
                    // Show loading progress immediately
                    binding.loadingProgress.visibility = View.VISIBLE
                    
                    // Clear the WebView's state
                    view?.apply {
                        stopLoading()
                        clearCache(true)
                        clearHistory()
                        clearFormData()
                    }
                    
                    // Show appropriate message
                    if (didCrash) {
                        Snackbar.make(binding.root, "Page crashed. Reloading...", Snackbar.LENGTH_LONG).show()
                    }
                    
                    // Reload the current URL with a delay
                    view?.postDelayed({
                        val currentUrl = homeViewModel.currentUrl.value
                        if (!currentUrl.isNullOrEmpty()) {
                            Log.d("HomeFragment", "Reloading after renderer crash: $currentUrl")
                            binding.webView.loadUrl(currentUrl)
                            currentLoadedUrl = currentUrl
                        } else {
                            // No URL to load, hide progress
                            binding.loadingProgress.visibility = View.GONE
                        }
                    }, 1000)
                    
                    return true // Prevent app crash
                }
                
                // For older Android versions, show progress and reload
                Log.e("HomeFragment", "WebView renderer process gone (pre-O)")
                binding.loadingProgress.visibility = View.VISIBLE
                view?.reload()
                return true
            }
        }
        
        // Configure WebView settings
        val webSettings = binding.webView.settings
        val settings = homeViewModel.getWebViewSettings()
        
        webSettings.javaScriptEnabled = settings["javascript"] ?: true
        webSettings.domStorageEnabled = settings["dom_storage"] ?: true
        webSettings.setSupportZoom(true)
        webSettings.builtInZoomControls = true
        webSettings.displayZoomControls = false
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        
        // Apply "Keep screen on" setting
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        binding.webView.keepScreenOn = keepScreenOn
        
        // Configure viewport for proper zoom behavior
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.setSupportMultipleWindows(false)
        webSettings.javaScriptCanOpenWindowsAutomatically = false
        
        // Additional settings for better compatibility with web apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Allow mixed content (HTTP content in HTTPS pages) for compatibility
            webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webSettings.mediaPlaybackRequiresUserGesture = false
        }
        
        // Make sure the WebView can load content from any source including cleartext HTTP
        webSettings.blockNetworkLoads = false
        
        // Set user agent to a desktop-like string to get desktop version where possible
        webSettings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36"
        
        // Configure for stability
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // Enable web contents debugging for better error tracking in debug builds
            WebView.setWebContentsDebuggingEnabled(true)
        }
        
        // Set up memory management for better stability
        @Suppress("DEPRECATION")
        webSettings.databaseEnabled = true
        
        // Setup SwipeRefreshLayout
        binding.swipeRefresh.setOnRefreshListener {
            homeViewModel.checkNetworkStatus()
            binding.webView.reload()
            binding.swipeRefresh.isRefreshing = false
        }
        
        // Monitor WebView scroll position to enable/disable swipe refresh
        binding.webView.viewTreeObserver.addOnScrollChangedListener {
            // Check if binding is still valid before accessing it
            _binding?.let { safeBinding ->
                // Enable swipe refresh only when WebView is at the top
                safeBinding.swipeRefresh.isEnabled = safeBinding.webView.scrollY == 0
            }
        }
        
        // Restore WebView state if available
        webViewState?.let { state ->
            Log.d("HomeFragment", "Restoring WebView state")
            binding.webView.restoreState(state)
            // Hide loading progress after restoring state
            binding.loadingProgress.visibility = View.GONE
        } ?: run {
            // Only load initial URL if no state to restore
            val initialUrl = homeViewModel.currentUrl.value
            if (!initialUrl.isNullOrEmpty() && currentLoadedUrl.isEmpty()) {
                Log.d("HomeFragment", "Loading initial URL: $initialUrl")
                binding.webView.loadUrl(initialUrl)
                currentLoadedUrl = initialUrl
            }
        }
        
        // Track if network status has been shown to avoid repeated notifications
        var hasShownDetectionFailureNotice = false
        var previousNetworkType = ""
        
        // Observe network type changes (this is what should trigger URL changes)
        homeViewModel.isHomeNetwork.observe(viewLifecycleOwner) { isHome ->
            val currentType = if (isHome) "Internal" else "External"
            
            // Only process if the network type actually changed
            if (currentType != previousNetworkType) {
                Log.d("HomeFragment", "Network type changed from '$previousNetworkType' to '$currentType'")
                previousNetworkType = currentType
                
                // Get the current URL
                val url = homeViewModel.currentUrl.value ?: return@observe
                
                // Reload WebView when network changes (even if URL is the same)
                Log.d("HomeFragment", "Network changed - refreshing page with URL: $url")
                
                // Stop any current loading before switching/refreshing
                binding.webView.stopLoading()
                
                // Clear the WebView cache to avoid stale content
                binding.webView.clearCache(false)
                
                // Small delay to ensure clean transition
                binding.webView.postDelayed({
                    if (url != currentLoadedUrl) {
                        // URL changed - load the new URL
                        binding.webView.loadUrl(url)
                        currentLoadedUrl = url
                    } else {
                        // Same URL - just refresh
                        binding.webView.reload()
                    }
                }, 300)
                
                // Show loading indicator during transition
                binding.loadingProgress.visibility = View.VISIBLE
                
                // Show a notification about the network change
                displayNetworkInfo(quiet = false)
            }
        }
        
        // Make sure we load the initial URL
        homeViewModel.currentUrl.observe(viewLifecycleOwner) { url ->
            // This is just for initial loading - network changes are handled above
            if (currentLoadedUrl.isEmpty() && url.isNotEmpty()) {
                Log.d("HomeFragment", "Initial URL load: $url")
                binding.webView.loadUrl(url)
                currentLoadedUrl = url
            }
        }
        
        // Track previous connection state to avoid duplicate messages
        var previousConnectionState = true
        var previousNetworkSsid = ""
        var networkTransitionInProgress = false
        
        // Only show disconnection notifications when actually disconnected
        homeViewModel.isConnected.observe(viewLifecycleOwner) { isConnected ->
            if (!isConnected && previousConnectionState && !networkTransitionInProgress) {
                // Only show if we transitioned from connected to disconnected and not in transition
                Log.d("HomeFragment", "Network connection lost")
                // Don't show message during network transitions
            }
            previousConnectionState = isConnected
        }
        
        // Also observe network changes to catch WiFi to mobile transitions
        homeViewModel.currentNetwork.observe(viewLifecycleOwner) { network ->
            // Extract the actual SSID from the network string
            val ssid = when {
                network.startsWith("Home: ") -> network.removePrefix("Home: ")
                network.startsWith("External: ") -> network.removePrefix("External: ")
                network == "Not Connected" -> ""
                else -> network
            }
            
            Log.d("HomeFragment", "Network changed from '$previousNetworkSsid' to '$ssid'")
            
            // Check if we switched from WiFi to mobile (SSID becomes empty) or between WiFi networks
            if (previousNetworkSsid.isNotEmpty() && ssid.isEmpty()) {
                // Switched from WiFi to mobile data
                Log.d("HomeFragment", "Switched from WiFi to mobile data - loading external URL")
                
                // Mark transition in progress
                networkTransitionInProgress = true
                
                // Force network check to update URL
                homeViewModel.checkNetworkStatus()
                
                // Wait for network to stabilize before loading
                binding.webView.postDelayed({
                    // Get the updated URL (should be external now)
                    val newUrl = homeViewModel.currentUrl.value
                    if (newUrl != null && newUrl != currentLoadedUrl) {
                        binding.webView.loadUrl(newUrl)
                        currentLoadedUrl = newUrl
                    } else {
                        binding.webView.reload()
                    }
                    binding.loadingProgress.visibility = View.VISIBLE
                    
                    // Clear transition flag after a delay
                    binding.webView.postDelayed({
                        networkTransitionInProgress = false
                    }, 2000)
                }, 1000) // Increased delay for network stabilization
                
            } else if (previousNetworkSsid != ssid && previousNetworkSsid.isNotEmpty() && ssid.isNotEmpty()) {
                // Switched between different WiFi networks
                Log.d("HomeFragment", "Switched between WiFi networks - checking if URL needs update")
                
                // Mark transition in progress
                networkTransitionInProgress = true
                
                // Force network check to update URL if needed
                homeViewModel.checkNetworkStatus()
                
                // Wait for network to stabilize before loading
                binding.webView.postDelayed({
                    // Get the potentially updated URL
                    val newUrl = homeViewModel.currentUrl.value
                    if (newUrl != null && newUrl != currentLoadedUrl) {
                        binding.webView.loadUrl(newUrl)
                        currentLoadedUrl = newUrl
                    } else {
                        binding.webView.reload()
                    }
                    binding.loadingProgress.visibility = View.VISIBLE
                    
                    // Clear transition flag after a delay
                    binding.webView.postDelayed({
                        networkTransitionInProgress = false
                    }, 2000)
                }, 1000) // Increased delay for network stabilization
            }
            
            previousNetworkSsid = ssid
        }
        
        // Observe network changes but limit notifications
        homeViewModel.currentNetwork.observe(viewLifecycleOwner) { ssid ->
            Log.d("HomeFragment", "Current network changed: '$ssid'")
            
            // Use NetworkUtils to get the most reliable display SSID
            val displaySsid = if (ssid == "Current WiFi" || ssid == "<unknown ssid>" || ssid.isEmpty()) {
                // Try to get SSID directly using NetworkUtils' reliable detection
                val detectedSsid = networkUtils.getSsidWithBestMethod()
                
                if (detectedSsid != null && detectedSsid != "<unknown ssid>") {
                    // Success! Use the detected SSID
                    detectedSsid
                } else {
                    // Check for manual override
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val manualOverride = prefs.getString("manual_home_network", "") ?: ""
                    
                    if (manualOverride.isNotEmpty()) {
                        // Use manual override if set
                        manualOverride
                    } else {
                        // Get home networks
                        val homeNetworks = prefs.getStringSet("home_wifi_networks", emptySet()) ?: emptySet()
                        if (homeNetworks.isNotEmpty()) {
                            // Use first home network name if available
                            homeNetworks.first()
                        } else {
                            // If all else fails, default to URL type
                            if (networkUtils.isHomeNetwork()) "Home WiFi" else "External WiFi"
                        }
                    }
                }
            } else {
                ssid
            }
            
            // Use the cleaned-up display SSID but only log it, don't show notification
            // This prevents constant notifications
            Log.d("HomeFragment", "Prepared display SSID: $displaySsid")
            
            // Get manual override to check if it's set
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val manualOverride = prefs.getString("manual_home_network", "") ?: ""
            
            // Only show the help notification if:
            // 1. We have a detection failure
            // 2. We haven't shown the notice yet
            // 3. Manual override is NOT set
            if (!hasShownDetectionFailureNotice && 
                manualOverride.isEmpty() && 
                (ssid == "Current WiFi" || ssid.contains("Failed") || ssid == "WiFi (Permission Issue)")
            ) {
                // Show a single Snackbar instead of both Toast and Snackbar
                Snackbar.make(
                    binding.root,
                    "WiFi name detection failed. This is common on Android 13+.\nTap Settings to enter your WiFi name manually.",
                    Snackbar.LENGTH_LONG
                ).setAction("Settings") {
                    requireActivity().findNavController(R.id.nav_host_fragment_content_main)
                        .navigate(R.id.nav_settings)
                }.show()
                
                // Mark that we've shown the notice
                hasShownDetectionFailureNotice = true
            }
        }
        
        return root
    }
    
    override fun onResume() {
        super.onResume()
        
        // Check if WebView needs to be resumed (with null safety)
        _binding?.webView?.onResume()
        
        // Apply "Keep screen on" setting to the WebView
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        _binding?.webView?.keepScreenOn = keepScreenOn
        
        // Check if we already have a URL loaded
        if (currentLoadedUrl.isNotEmpty()) {
            // We have a URL loaded, just make sure the loading progress is hidden
            _binding?.loadingProgress?.visibility = View.GONE
            Log.d("HomeFragment", "onResume - already loaded: $currentLoadedUrl")
            return
        }
        
        // First time load - ensure we load the initial URL
        val url = homeViewModel.currentUrl.value
        if (!url.isNullOrEmpty()) {
            Log.d("HomeFragment", "Loading initial URL: $url")
            _binding?.webView?.loadUrl(url)
            currentLoadedUrl = url
        }
    }
    
    /**
     * Displays network and URL information in a Snackbar
     * Shows WiFi name, URL type, and provides quick access to settings
     * @param overrideSsid Optional SSID to display instead of the current one
     * @param quiet If true, will only log the information without showing a Snackbar
     */
    private fun displayNetworkInfo(overrideSsid: String? = null, quiet: Boolean = true) {
        // Use override SSID if provided, otherwise get from view model
        val ssid = overrideSsid ?: homeViewModel.currentNetwork.value ?: ""
        val isHome = homeViewModel.isHomeNetwork.value ?: false
        val isConnected = homeViewModel.isConnected.value ?: false
        val currentUrl = homeViewModel.currentUrl.value ?: "No URL"
        
        val urlType = if (isHome) "Internal" else "External"
        
        // Get current status and log for debugging
        Log.d("HomeFragment", "Raw SSID value: '$ssid'")
        Log.d("HomeFragment", "Home network: $isHome, Connected: $isConnected, Using URL: $currentUrl")
        
        // Check for the special "Current WiFi" or "Auto-detection Failed" cases
        val isAutoDetectionFailed = ssid.contains("Failed") || ssid == "Current WiFi" || ssid == "WiFi (Permission Issue)"
        
        val message = when {
            !isConnected -> "No internet connection\nCheck your network settings"
            isAutoDetectionFailed -> "Using $urlType URL\nWiFi auto-detection failed\nGo to Settings to set manual WiFi name"
            ssid.isNotEmpty() -> "Using $urlType URL\nConnected to: $ssid"
            else -> "Using $urlType URL\nNo WiFi connected"
        }
        
        Log.d("HomeFragment", "URL status: Using $urlType URL ($currentUrl), SSID=$ssid, Connected=$isConnected")
        
        // Only show the Snackbar if not in quiet mode
        if (!quiet) {
            Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
                .setAction("Settings") {
                    // Navigate to settings
                    requireActivity().findNavController(R.id.nav_host_fragment_content_main)
                        .navigate(R.id.nav_settings)
                }.show()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Save WebView state before pausing
        _binding?.webView?.let { webView ->
            webViewState = Bundle()
            webView.saveState(webViewState!!)
            webView.onPause()
        }
    }
    
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save the WebView state if available
        webViewState?.let {
            outState.putBundle("webViewState", it)
        }
        outState.putString("currentLoadedUrl", currentLoadedUrl)
    }

    
    override fun onDestroyView() {
        super.onDestroyView()
        binding.webView.stopLoading()
        binding.webView.destroy()
        _binding = null
    }
    
    /**
     * Handle back press for WebView navigation
     * @return true if the back press was handled, false otherwise
     */
    fun handleBackPress(): Boolean {
        val webView = _binding?.webView
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }
}