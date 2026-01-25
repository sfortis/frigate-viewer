package com.asksakis.freegate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.asksakis.freegate.databinding.ActivityMainBinding
// NetworkFixer has been consolidated into NetworkUtils
import com.asksakis.freegate.utils.NetworkUtils
import com.asksakis.freegate.utils.UpdateChecker
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var networkUtils: NetworkUtils
    // NetworkFixer functionality has been consolidated into NetworkUtils
    private var networkIndicator: TextView? = null
    
    // Permission request launcher for nearby devices (Android 13+)
    private val requestNearbyDevicesPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, refresh network status
            networkUtils.checkAndUpdateUrl()
            
            // Refresh the current fragment if it's the home fragment
            if (::navController.isInitialized && 
                navController.currentDestination?.id == R.id.nav_home) {
                navController.navigate(R.id.nav_home)
            }
        } else {
            // Permission denied, show a more helpful message
            Snackbar.make(
                binding.root, 
                "Freegate needs permission to detect WiFi networks for automatic URL switching.",
                Snackbar.LENGTH_LONG
            ).setAction("Grant") {
                requestRequiredPermissions()  
            }.show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.appBarMain.toolbar)
        
        // Handle intent if launched from custom scheme
        handleIntent(intent)
        
        // Check and apply "Keep screen on" setting
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        // Check if this is the first run
        val isFirstRun = prefs.getBoolean("is_first_run", true)
        if (isFirstRun) {
            // Show a toast prompting the user to configure URLs
            Toast.makeText(
                this,
                "Welcome! Configure Frigate URLs in Settings.",
                Toast.LENGTH_LONG
            ).show()
            
            // Save that we've shown the first-run message
            prefs.edit().putBoolean("is_first_run", false).apply()
        }
        
        // Initialize network utilities (singleton)
        networkUtils = NetworkUtils.getInstance(this)
        
        // Find network indicator
        networkIndicator = findViewById(R.id.network_indicator)
        
        // Log current permission status
        logPermissionStatus()
        
        // Always force-check permissions on each startup
        // This is critical for WiFi detection to work
        requestRequiredPermissions()
        
        
        // Show a message about why we need permissions only if we don't have them
        if (!hasRequiredPermissions()) {
            Snackbar.make(
                binding.root,
                "WiFi detection requires permissions for automatic URL switching.",
                Snackbar.LENGTH_LONG
            ).setAction("Grant") {
                requestRequiredPermissions()
            }.show()
        }

        // FAB refresh button has been removed per user request
        // Network status is checked automatically in other ways
        
        val drawerLayout: DrawerLayout = binding.drawerLayout
        val navView: NavigationView = binding.navView
        
        // With FragmentContainerView, we need to wait for the fragment to be attached
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
        if (navHostFragment != null) {
            navController = navHostFragment.navController
            
            // Passing each menu ID as a set of Ids because each
            // menu should be considered as top level destinations.
            appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.nav_home, R.id.nav_settings
                ), drawerLayout
            )
            setupActionBarWithNavController(navController, appBarConfiguration)
            navView.setupWithNavController(navController)

            // Handle custom menu items (like Refresh)
            navView.setNavigationItemSelectedListener { menuItem ->
                when (menuItem.itemId) {
                    R.id.nav_refresh -> {
                        // Get HomeFragment and refresh WebView
                        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
                        val homeFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull { it is com.asksakis.freegate.ui.home.HomeFragment } as? com.asksakis.freegate.ui.home.HomeFragment
                        homeFragment?.forceNetworkRefresh()
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                        true
                    }
                    else -> {
                        // Let NavigationUI handle other items
                        val handled = androidx.navigation.ui.NavigationUI.onNavDestinationSelected(menuItem, navController)
                        if (handled) binding.drawerLayout.closeDrawer(GravityCompat.START)
                        handled
                    }
                }
            }

            // Setup navigation listener to update the network indicator
            navController.addOnDestinationChangedListener { _, destination, _ ->
                updateNetworkIndicator(destination)
            }
            
            // Observe URL changes
            networkUtils.currentUrl.observe(this) { _ ->
                updateNetworkIndicator(navController.currentDestination)
            }
        } else {
            // Fragment is not ready yet, we'll set it up in onPostCreate
            Log.d("MainActivity", "NavHostFragment not ready yet")
        }
        
        // Check for updates on app start
        checkForUpdates()
    }
    
    /**
     * Check required permissions and request them if needed
     */
    private fun checkRequiredPermissions() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        }
    }
    
    /**
     * Request the appropriate permissions based on Android version
     * Using the exact approach from the example code
     */
    /**
     * Request the appropriate permissions based on Android version
     * For Android 13+, we use NEARBY_WIFI_DEVICES with neverForLocation flag
     */
    private fun requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            Log.d("MainActivity", "Requesting NEARBY_WIFI_DEVICES permission for Android 13+/16+")
            
            // Request all needed permissions for Android 13+ and 16+
            val permissionsToRequest = arrayOf(
                // NEARBY_WIFI_DEVICES is the main permission for WiFi SSID on Android 13+ and 16+
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE,
                // Also request location permissions as they might still be needed on some devices
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                // Audio permissions for WebRTC
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS
            )
            
            // Check each permission individually
            permissionsToRequest.forEach { permission ->
                val hasPermission = ContextCompat.checkSelfPermission(this, permission) == 
                    PackageManager.PERMISSION_GRANTED
                Log.d("MainActivity", "Permission $permission granted: $hasPermission")
            }
            
            // Request all permissions directly
            ActivityCompat.requestPermissions(this, permissionsToRequest, 100)
            
            // Also use the activity result launcher for the most critical permission
            requestNearbyDevicesPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            
            // Removed the Snackbar message as requested
            
            // Removed the toast message as requested
        } else {
            // For older versions, we need location permission
            Log.d("MainActivity", "Requesting ACCESS_FINE_LOCATION permission for Android <13")
            
            // Request both location permissions
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.ACCESS_NETWORK_STATE,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS
                ),
                101
            )
            
            // Show a toast explaining why we need location permission
            Toast.makeText(
                this,
                "Freegate needs location permission to detect WiFi networks.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    
    /**
     * Check if we have the required permissions based on Android version
     */
    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * Log the status of all permissions needed for WiFi detection
     * This helps with debugging permission issues
     */
    private fun logPermissionStatus() {
        Log.d("MainActivity", "===== PERMISSION STATUS =====")
        
        // Log basic info
        Log.d("MainActivity", "Android SDK Version: ${Build.VERSION.SDK_INT}")
        Log.d("MainActivity", "Android VERSION.RELEASE: ${Build.VERSION.RELEASE}")
        
        // Check all potentially relevant permissions
        val permissions = arrayOf(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        
        permissions.forEach { permission ->
            val hasPermission = ContextCompat.checkSelfPermission(this, permission) == 
                PackageManager.PERMISSION_GRANTED
            Log.d("MainActivity", "Permission $permission: ${if (hasPermission) "GRANTED" else "DENIED"}")
        }
        
        // Check and log WiFi status
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        Log.d("MainActivity", "WiFi Enabled: ${wifiManager.isWifiEnabled}")
        
        // Try to get the SSID in multiple ways for debugging
        try {
            @Suppress("DEPRECATION")
            val wifiInfoSsid = wifiManager.connectionInfo?.ssid
            Log.d("MainActivity", "Debug - WiFi SSID direct from WifiManager: $wifiInfoSsid")
            
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
            Log.d("MainActivity", "Debug - Has WiFi transport: ${networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true}")
            
            val transportInfo = networkCapabilities?.transportInfo
            Log.d("MainActivity", "Debug - TransportInfo is not null: ${transportInfo != null}")
            
            if (transportInfo != null && transportInfo is android.net.wifi.WifiInfo) {
                @Suppress("DEPRECATION")
                val transportSsid = transportInfo.ssid
                Log.d("MainActivity", "Debug - WiFi SSID from TransportInfo: $transportSsid")
            }
            
            // Try with direct settings access
            val systemSsid = android.provider.Settings.System.getString(contentResolver, "wifi_ssid")
            Log.d("MainActivity", "Debug - SSID from System settings: $systemSsid")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in SSID debug: ${e.message}")
        }
        
        // Check main permission requirement
        val mainPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        val hasMainPermission = ContextCompat.checkSelfPermission(this, mainPermission) == 
            PackageManager.PERMISSION_GRANTED
            
        Log.d("MainActivity", "Main required permission ($mainPermission): ${if (hasMainPermission) "GRANTED" else "DENIED"}")
        Log.d("MainActivity", "===== END PERMISSION STATUS =====")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Don't inflate the menu
        return false
    }
    
    /**
     * Updates the network indicator based on the current network type
     */
    private fun updateNetworkIndicator(destination: NavDestination?) {
        try {
            // Get the indicator
            val indicator = networkIndicator ?: return
            
            // Get settings to determine connection mode
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val connectionMode = prefs.getString("connection_mode", "auto") ?: "auto"
            
            // Check if we're on a home network
            val isHomeNetwork = networkUtils.isHome()
            
            // In manual mode, use the fixed setting; in auto mode, use actual connection
            val isInternal = when (connectionMode) {
                "internal" -> true
                "external" -> false
                else -> isHomeNetwork
            }
            
            // Update indicator text
            indicator.text = if (isInternal) "INT" else "EXT"
            
            // Update indicator color
            val backgroundColor = if (isInternal) "#4CAF50" else "#F44336" // Green for INT, Red for EXT
            val drawable = indicator.background.mutate()
            drawable.setTint(Color.parseColor(backgroundColor))
            indicator.background = drawable
            
            // Only show on HomeFragment or when connection mode is forced
            val isHome = destination?.id == R.id.nav_home
            indicator.visibility = if (isHome || connectionMode != "auto") {
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
            
        } catch (e: Exception) {
            Log.e("MainActivity", "Error updating network indicator: ${e.message}")
        }
    }

    // This is replaced by the enhanced version below
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Log.d("MainActivity", "onBackPressed called - drawer open: ${binding.drawerLayout.isDrawerOpen(GravityCompat.START)}")
        
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
    
    /**
     * Update indicator when activity resumes
     */
    override fun onResume() {
        super.onResume()
        
        // Check and apply "Keep screen on" setting
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val keepScreenOn = prefs.getBoolean("keep_screen_on", false)
        if (keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        
        // Update the network indicator when activity resumes
        if (::navController.isInitialized) {
            updateNetworkIndicator(navController.currentDestination)
        }
    }
    
    override fun onDestroy() {
        // Unregister network callbacks to prevent memory leaks
        if (::networkUtils.isInitialized) {
            networkUtils.unregisterCallback()
        }
        
        super.onDestroy()
    }
    
    // Override to improve navigation consistency
    override fun onSupportNavigateUp(): Boolean {
        if (!::navController.isInitialized) {
            return super.onSupportNavigateUp()
        }
        
        val currentDestId = navController.currentDestination?.id
        
        // If we're in settings, always navigate back to home on up button
        if (currentDestId == R.id.nav_settings) {
            navController.navigate(R.id.nav_home)
            return true
        }
        
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
    
    // Handle late initialization of NavController
    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        
        // If NavController wasn't initialized in onCreate, try again
        if (!::navController.isInitialized) {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as? NavHostFragment
            if (navHostFragment != null) {
                navController = navHostFragment.navController
                
                val drawerLayout: DrawerLayout = binding.drawerLayout
                val navView: NavigationView = binding.navView
                
                appBarConfiguration = AppBarConfiguration(
                    setOf(
                        R.id.nav_home, R.id.nav_settings
                    ), drawerLayout
                )
                setupActionBarWithNavController(navController, appBarConfiguration)
                navView.setupWithNavController(navController)
                
                // Setup navigation listener to update the network indicator
                navController.addOnDestinationChangedListener { _, destination, _ ->
                    updateNetworkIndicator(destination)
                }
                
                // Observe URL changes only
                networkUtils.currentUrl.observe(this) { _ ->
                    updateNetworkIndicator(navController.currentDestination)
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }
    
    private fun handleIntent(intent: Intent?) {
        intent?.let { 
            Log.d("MainActivity", "Intent action: ${it.action}")
            Log.d("MainActivity", "Intent data: ${it.data}")
            
            // Check if launched via custom scheme
            if (it.action == Intent.ACTION_VIEW && it.data != null) {
                val uri = it.data
                if (uri?.scheme == "freegate") {
                    Log.d("MainActivity", "Launched via freegate:// scheme")
                    
                    // Parse the URI for specific actions
                    val host = uri.host
                    val path = uri.path
                    val queryParams = uri.queryParameterNames
                    
                    Log.d("MainActivity", "Host: $host, Path: $path")
                    
                    // Handle different paths/actions
                    when (host) {
                        "home", "cameras" -> {
                            // Navigate to home fragment
                            if (::navController.isInitialized) {
                                navController.navigate(R.id.nav_home)
                            }
                        }
                        "settings" -> {
                            // Navigate to settings
                            if (::navController.isInitialized) {
                                navController.navigate(R.id.nav_settings)
                            }
                        }
                        "camera" -> {
                            // Could handle specific camera with path or query param
                            val cameraId = uri.getQueryParameter("id") ?: path?.trimStart('/')
                            Log.d("MainActivity", "Camera ID: $cameraId")
                            // Navigate to home and potentially pass camera ID
                            if (::navController.isInitialized) {
                                navController.navigate(R.id.nav_home)
                                // TODO: Pass camera ID to HomeFragment if needed
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Check for app updates from GitHub
     */
    private fun checkForUpdates() {
        val updateChecker = UpdateChecker(this)
        lifecycleScope.launch {
            val updateInfo = updateChecker.checkForUpdates()
            updateInfo?.let {
                updateChecker.showUpdateDialog(this@MainActivity, it)
            }
        }
    }
}