package com.asksakis.freegate.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {
    
    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API_URL = "https://api.github.com/repos/sfortis/frigate-viewer/releases/latest"
        private const val UPDATE_CHECK_INTERVAL = 24 * 60 * 60 * 1000L // 24 hours
        private const val PREFS_NAME = "update_prefs"
        private const val KEY_LAST_CHECK = "last_update_check"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val publishedAt: String,
        val fileSize: Long
    )
    
    suspend fun checkForUpdates(force: Boolean = false): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                // Check if we should perform the check
                if (!force && !shouldCheckForUpdates()) {
                    Log.d(TAG, "Skipping update check - too soon since last check")
                    return@withContext null
                }
                
                // Save last check time
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                
                // Fetch release info from GitHub
                val url = URL(GITHUB_API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    
                    val latestVersion = json.getString("tag_name").removePrefix("v")
                    val currentVersion = getAppVersion()
                    
                    Log.d(TAG, "Current version: $currentVersion, Latest version: $latestVersion")
                    
                    // Check if update is available
                    if (isNewerVersion(currentVersion, latestVersion)) {
                        
                        // Find APK asset
                        val assets = json.getJSONArray("assets")
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.getString("name")
                            if (name.endsWith(".apk")) {
                                return@withContext UpdateInfo(
                                    version = latestVersion,
                                    downloadUrl = asset.getString("browser_download_url"),
                                    releaseNotes = json.getString("body"),
                                    publishedAt = json.getString("published_at"),
                                    fileSize = asset.getLong("size")
                                )
                            }
                        }
                    }
                }
                
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for updates", e)
                null
            }
        }
    }
    
    private fun shouldCheckForUpdates(): Boolean {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        return System.currentTimeMillis() - lastCheck > UPDATE_CHECK_INTERVAL
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, 
                    android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }
    
    private fun isNewerVersion(current: String, latest: String): Boolean {
        try {
            val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
            val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
            
            for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
                val currentPart = currentParts.getOrNull(i) ?: 0
                val latestPart = latestParts.getOrNull(i) ?: 0
                
                if (latestPart > currentPart) return true
                if (latestPart < currentPart) return false
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions", e)
            return false
        }
    }
    
    fun showUpdateDialog(activity: androidx.appcompat.app.AppCompatActivity, updateInfo: UpdateInfo) {
        val fileSizeMB = updateInfo.fileSize / (1024 * 1024)
        
        // Create a scrollable text view for release notes
        val scrollView = android.widget.ScrollView(activity).apply {
            setPadding(40, 20, 40, 20)
        }
        
        val textView = android.widget.TextView(activity).apply {
            text = """
                Version ${updateInfo.version} is available! (${fileSizeMB}MB)
                
                What's New:
                ${updateInfo.releaseNotes.take(300)}${if (updateInfo.releaseNotes.length > 300) "..." else ""}
            """.trimIndent()
            textSize = 14f
        }
        
        scrollView.addView(textView)
        
        AlertDialog.Builder(activity)
            .setTitle("Update Available")
            .setView(scrollView)
            .setPositiveButton("Download") { _, _ ->
                downloadAndInstallUpdate(activity, updateInfo)
            }
            .setNegativeButton("Later") { _, _ ->
                // User chose to update later
            }
            .show()
    }
    
    private fun downloadAndInstallUpdate(activity: androidx.appcompat.app.AppCompatActivity, updateInfo: UpdateInfo) {
        // Create progress dialog for download
        val progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        
        val textView = android.widget.TextView(activity).apply {
            text = "Downloading update..."
            setPadding(0, 0, 0, 16)
        }
        
        val layout = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            addView(textView)
            addView(progressBar)
        }
        
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle("Downloading Update")
            .setView(layout)
            .setCancelable(false)
            .create()
        
        progressDialog.show()
        
        // Download in background
        CoroutineScope(Dispatchers.Main).launch {
            val apkFile = downloadUpdateInApp(updateInfo) { progress ->
                activity.runOnUiThread {
                    progressBar.progress = progress
                    textView.text = "Downloading update... $progress%"
                }
            }
            
            progressDialog.dismiss()
            
            if (apkFile != null) {
                // Install the downloaded APK
                installUpdate(activity, apkFile)
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("Download Failed")
                    .setMessage("Unable to download the update. Please try again later or download manually from GitHub.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
    
    suspend fun downloadUpdateInApp(updateInfo: UpdateInfo, onProgress: (Int) -> Unit): File? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(updateInfo.downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()
                
                val fileLength = connection.contentLength
                val input = BufferedInputStream(connection.inputStream)
                
                // Save to app's cache directory
                val outputFile = File(context.cacheDir, "update_${updateInfo.version}.apk")
                val output = FileOutputStream(outputFile)
                
                val buffer = ByteArray(4096)
                var total = 0L
                var count: Int
                
                while (input.read(buffer).also { count = it } != -1) {
                    total += count
                    output.write(buffer, 0, count)
                    
                    // Report progress
                    if (fileLength > 0) {
                        val progress = (total * 100 / fileLength).toInt()
                        withContext(Dispatchers.Main) {
                            onProgress(progress)
                        }
                    }
                }
                
                output.flush()
                output.close()
                input.close()
                
                outputFile
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading update", e)
                null
            }
        }
    }
    
    fun installUpdate(activity: androidx.appcompat.app.AppCompatActivity, apkFile: File) {
        try {
            // Log file details for debugging
            Log.d(TAG, "APK file path: ${apkFile.absolutePath}")
            Log.d(TAG, "APK file size: ${apkFile.length()} bytes")
            Log.d(TAG, "APK file exists: ${apkFile.exists()}")
            Log.d(TAG, "APK file readable: ${apkFile.canRead()}")
            
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            
            Log.d(TAG, "Install URI: $uri")
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                
                // Add additional flags for Android 14+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
            }
            
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error installing update: ${e.message}", e)
            AlertDialog.Builder(activity)
                .setTitle("Installation Failed")
                .setMessage("Unable to install the update: ${e.message}\n\nPlease download manually from GitHub.")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}