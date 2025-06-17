package com.wbpxre150.unjumbleapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.alerts.*
import com.frostwire.jlibtorrent.swig.*

/**
 * Simplified TorrentManager following FrostWire's BTEngine pattern
 * Key changes from original:
 * 1. Extends SessionManager directly (like FrostWire's BTEngine)
 * 2. Removes custom fetchMetadataConcurrently wrapper
 * 3. Uses direct fetchMagnet calls
 * 4. Simplified session management
 * 5. Minimal UI updates during critical operations
 */
class SimpleTorrentManager private constructor(private val context: Context) : SessionManager() {
    private val handler = Handler(Looper.getMainLooper())
    private var downloadListener: TorrentDownloadListener? = null
    private var isDownloading = false
    private var currentTorrentHandle: TorrentHandle? = null
    private var currentDownloadPath: String = ""
    private var isLibraryAvailable = false
    private var currentPort = 0
    private var sessionStartTime: Long = 0
    private var isSessionReady = false
    
    companion object {
        private const val TAG = "SimpleTorrentManager"
        private const val METADATA_TIMEOUT_SECONDS = 90 // Increased from 30 to 90 seconds
        private const val SESSION_BOOTSTRAP_TIMEOUT_MS = 15000L // 15 seconds for session to bootstrap
        private const val DHT_MIN_NODES_REQUIRED = 5 // Minimum DHT nodes before attempting download
        
        @Volatile
        private var INSTANCE: SimpleTorrentManager? = null
        
        fun getInstance(context: Context): SimpleTorrentManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SimpleTorrentManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    init {
        initializeSession()
    }
    
    private fun initializeSession() {
        Log.d(TAG, "Initializing jlibtorrent session (FrostWire pattern)...")
        
        try {
            // Load native library (same as FrostWire approach)
            System.loadLibrary("jlibtorrent")
            Log.d(TAG, "✓ Native library loaded")
            
            // Create FrostWire-style settings
            val settingsPack = createFrostWireSettings()
            
            // Start session with settings (FrostWire pattern)
            start(SessionParams(settingsPack))
            sessionStartTime = System.currentTimeMillis()
            Log.d(TAG, "✓ Session started with FrostWire settings")
            
            isLibraryAvailable = true
            
            // Start session readiness monitoring
            startSessionBootstrapMonitoring()
            
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ Failed to load native library: ${e.message}")
            isLibraryAvailable = false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Session initialization error: ${e.message}")
            isLibraryAvailable = false
        }
    }
    
    private fun createFrostWireSettings(): SettingsPack {
        val settings = SettingsPack()
        
        // FrostWire's proven configuration
        settings.enableDht(true)
        settings.setString(
            settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
            "dht.libtorrent.org:25401,router.bittorrent.com:6881"
        )
        
        // Random port for security
        currentPort = (49152..65534).random()
        settings.setString(
            settings_pack.string_types.listen_interfaces.swigValue(),
            "0.0.0.0:$currentPort"
        )
        
        // Android-optimized connection limits
        settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)
        settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
        settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), 4)
        
        // Enable UPnP and NAT-PMP
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        
        // Alert configuration for metadata and progress tracking
        settings.setInteger(
            settings_pack.int_types.alert_mask.swigValue(),
            AlertType.METADATA_RECEIVED.swig() or 
            AlertType.ADD_TORRENT.swig() or
            AlertType.TORRENT_FINISHED.swig() or
            AlertType.STATE_CHANGED.swig()
        )
        
        Log.d(TAG, "✓ FrostWire-style settings configured (port: $currentPort)")
        return settings
    }
    
    /**
     * Monitor session bootstrap and DHT connectivity (FrostWire pattern)
     */
    private fun startSessionBootstrapMonitoring() {
        Thread {
            Log.d(TAG, "🔄 Starting session bootstrap monitoring...")
            
            var attempts = 0
            val maxAttempts = 30 // 15 seconds total (500ms * 30)
            
            while (attempts < maxAttempts && !isSessionReady) {
                try {
                    val sessionStats = stats()
                    val dhtNodes = sessionStats?.dhtNodes()?.toInt() ?: 0
                    val isDhtRunning = try {
                        isDhtRunning()
                    } catch (e: Exception) {
                        false
                    }
                    
                    val elapsed = System.currentTimeMillis() - sessionStartTime
                    
                    Log.d(TAG, "Bootstrap check $attempts: DHT running=$isDhtRunning, nodes=$dhtNodes (${elapsed}ms elapsed)")
                    
                    // Session is ready when DHT is running and has minimum nodes
                    if (isDhtRunning && dhtNodes >= DHT_MIN_NODES_REQUIRED) {
                        isSessionReady = true
                        Log.d(TAG, "✅ Session ready! DHT connected with $dhtNodes nodes after ${elapsed}ms")
                        break
                    }
                    
                    Thread.sleep(500) // Check every 500ms
                    attempts++
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Bootstrap monitoring error: ${e.message}")
                    attempts++
                }
            }
            
            if (!isSessionReady) {
                val elapsed = System.currentTimeMillis() - sessionStartTime
                Log.w(TAG, "⚠️ Session bootstrap timeout after ${elapsed}ms - will attempt downloads anyway")
                // Don't set isSessionReady = true here, let downloads attempt with longer timeout
            }
            
        }.start()
    }
    
    /**
     * Check if session is ready for downloads (FrostWire approach)
     */
    private fun waitForSessionReadiness(timeoutMs: Long = SESSION_BOOTSTRAP_TIMEOUT_MS): Boolean {
        val startTime = System.currentTimeMillis()
        
        while (!isSessionReady && (System.currentTimeMillis() - startTime) < timeoutMs) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        
        return isSessionReady
    }
    
    /**
     * Simple download method following FrostWire's pattern:
     * 1. Direct fetchMagnet call (no custom wrappers)
     * 2. Simple error handling
     * 3. Minimal UI updates during operation
     */
    fun downloadFile(magnetLink: String, downloadPath: String, listener: TorrentDownloadListener) {
        downloadFileWithRetry(magnetLink, downloadPath, listener, retryCount = 0)
    }
    
    /**
     * Download with progressive retry strategy
     */
    private fun downloadFileWithRetry(magnetLink: String, downloadPath: String, listener: TorrentDownloadListener, retryCount: Int) {
        if (isDownloading) {
            listener.onError("Download already in progress")
            return
        }
        
        if (!isLibraryAvailable) {
            listener.onError("BitTorrent library not available")
            return
        }
        
        downloadListener = listener
        isDownloading = true
        currentDownloadPath = downloadPath
        
        Log.d(TAG, "Starting download using FrostWire pattern (attempt ${retryCount + 1})...")
        
        // Phase 1: Wait for session readiness (FrostWire approach)
        handler.post {
            listener.onDhtConnecting()
            listener.onPhaseChanged(DownloadPhase.METADATA_FETCHING, 120) // Increased timeout
        }
        
        // Phase 2: FrostWire-style metadata fetch (background thread)
        Thread {
            try {
                // CRITICAL: Wait for session to be ready before attempting fetchMagnet
                Log.d(TAG, "⏳ Waiting for session readiness before fetchMagnet...")
                
                handler.post {
                    listener.onSessionDiagnostic("Waiting for DHT bootstrap before metadata fetch...")
                }
                
                val sessionReady = waitForSessionReadiness()
                
                // Check session status regardless of readiness flag
                val dhtNodes = stats()?.dhtNodes()?.toInt() ?: 0
                val isDhtRunning = try { isDhtRunning() } catch (e: Exception) { false }
                val elapsed = System.currentTimeMillis() - sessionStartTime
                
                Log.d(TAG, "Session status: ready=$sessionReady, DHT running=$isDhtRunning, nodes=$dhtNodes (${elapsed}ms elapsed)")
                
                handler.post {
                    listener.onDhtConnected(dhtNodes)
                    if (sessionReady) {
                        listener.onDhtDiagnostic("✅ Session ready: DHT running with $dhtNodes nodes")
                    } else {
                        listener.onDhtDiagnostic("⚠️ Session not fully ready but proceeding: DHT nodes=$dhtNodes")
                    }
                    listener.onMetadataFetching()
                }
                
                // Add pre-fetchMagnet validation
                if (!isLibraryAvailable || !isDhtRunning) {
                    val error = when {
                        !isLibraryAvailable -> "BitTorrent library not available"
                        !isDhtRunning -> "DHT not running - cannot fetch metadata"
                        else -> "Session not ready for downloads"
                    }
                    
                    Log.e(TAG, "❌ Pre-fetchMagnet validation failed: $error")
                    handler.post {
                        listener.onError("Session not ready: $error")
                    }
                    isDownloading = false
                    return@Thread
                }
                
                Log.d(TAG, "🔍 Starting fetchMagnet with ${METADATA_TIMEOUT_SECONDS}s timeout...")
                
                handler.post {
                    listener.onSessionDiagnostic("Fetching metadata: DHT($dhtNodes) + Trackers - ${METADATA_TIMEOUT_SECONDS}s timeout")
                }
                
                // Direct fetchMagnet call with longer timeout
                val metadata = fetchMagnet(magnetLink, METADATA_TIMEOUT_SECONDS, false)
                
                if (metadata != null && metadata.isNotEmpty()) {
                    Log.d(TAG, "✅ Metadata fetched: ${metadata.size} bytes")
                    
                    handler.post {
                        listener.onMetadataComplete()
                        listener.onPhaseChanged(DownloadPhase.ACTIVE_DOWNLOADING, 240)
                    }
                    
                    // Phase 3: Start download with metadata (FrostWire pattern)
                    val torrentInfo = TorrentInfo.bdecode(metadata)
                    val downloadDir = File(currentDownloadPath).parentFile
                    
                    Log.d(TAG, "Starting download: ${torrentInfo.name()}")
                    
                    // Direct download call (FrostWire method)
                    download(torrentInfo, downloadDir)
                    
                    // Give session time to process
                    Thread.sleep(1000)
                    
                    // Find and track the torrent handle
                    currentTorrentHandle = find(torrentInfo.infoHash())
                    
                    if (currentTorrentHandle?.isValid == true) {
                        Log.d(TAG, "✅ Download started successfully")
                        currentTorrentHandle?.resume() // Ensure it's active
                        
                        // Notify peer discovery
                        handler.post {
                            listener.onDiscoveringPeers()
                        }
                        
                        // Start simple progress monitoring
                        startProgressMonitoring()
                        
                    } else {
                        Log.e(TAG, "❌ Failed to create torrent handle")
                        handler.post {
                            listener.onError("Failed to start download")
                        }
                        isDownloading = false
                    }
                    
                } else {
                    // Distinguish between different failure types
                    val currentDhtNodes = stats()?.dhtNodes()?.toInt() ?: 0
                    val fetchDuration = System.currentTimeMillis() - sessionStartTime
                    
                    val errorType = when {
                        currentDhtNodes == 0 -> "DHT_NO_NODES"
                        !sessionReady -> "SESSION_NOT_READY" 
                        fetchDuration < 5000 -> "IMMEDIATE_FAILURE"
                        else -> "GENUINE_TIMEOUT"
                    }
                    
                    Log.e(TAG, "❌ fetchMagnet failed: type=$errorType, duration=${fetchDuration}ms, DHT nodes=$currentDhtNodes")
                    
                    // Progressive retry logic
                    val maxRetries = 2
                    val shouldRetry = retryCount < maxRetries && (errorType == "SESSION_NOT_READY" || errorType == "DHT_NO_NODES")
                    
                    if (shouldRetry) {
                        Log.d(TAG, "🔄 Retrying download (attempt ${retryCount + 2}/${maxRetries + 1}) after ${errorType}")
                        
                        handler.post {
                            listener.onSessionDiagnostic("🔄 Retry ${retryCount + 2}: Waiting for session to stabilize...")
                        }
                        
                        // Reset state for retry
                        isDownloading = false
                        
                        // Wait before retry (increasing delay)
                        Thread.sleep((retryCount + 1) * 3000L) // 3s, 6s delays
                        
                        // Retry with incremented count
                        downloadFileWithRetry(magnetLink, downloadPath, listener, retryCount + 1)
                        return@Thread
                        
                    } else {
                        // Final failure after retries
                        val errorMessage = when (errorType) {
                            "DHT_NO_NODES" -> "No DHT connections available for P2P download (${retryCount + 1} attempts)"
                            "SESSION_NOT_READY" -> "BitTorrent session not fully initialized (${retryCount + 1} attempts)"
                            "IMMEDIATE_FAILURE" -> "Immediate fetch failure - possible network issue"
                            else -> "Metadata fetch timeout after ${METADATA_TIMEOUT_SECONDS}s - no peers responded"
                        }
                        
                        handler.post {
                            if (errorType == "GENUINE_TIMEOUT") {
                                listener.onTimeout()
                            }
                            listener.onSessionDiagnostic("❌ P2P failure after ${retryCount + 1} attempts: $errorMessage")
                            listener.onError(errorMessage)
                        }
                        isDownloading = false
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Download error: ${e.message}")
                handler.post {
                    listener.onError("Download failed: ${e.message}")
                }
                isDownloading = false
            }
        }.start()
    }
    
    /**
     * Simple progress monitoring (minimal UI updates)
     */
    private fun startProgressMonitoring() {
        Thread {
            while (isDownloading && currentTorrentHandle?.isValid == true) {
                try {
                    val status = currentTorrentHandle?.status()
                    if (status != null) {
                        val downloaded = status.totalDone()
                        val total = status.totalWanted()
                        val downloadRate = status.downloadRate()
                        val numPeers = status.numPeers()
                        
                        // Simple progress update
                        handler.post {
                            downloadListener?.onProgress(downloaded, total, downloadRate, numPeers)
                            
                            // Notify when peers are found
                            if (numPeers > 0) {
                                downloadListener?.onSeedsFound(numPeers, numPeers)
                            }
                        }
                        
                        // Check completion
                        if (status.isFinished || downloaded >= total) {
                            Log.d(TAG, "✅ Download completed")
                            
                            handler.post {
                                downloadListener?.onCompleted(currentDownloadPath)
                            }
                            
                            isDownloading = false
                            break
                        }
                    }
                    
                    Thread.sleep(2000) // Update every 2 seconds
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Progress monitoring error: ${e.message}")
                    break
                }
            }
        }.start()
    }
    
    /**
     * Stop current download
     */
    fun stopDownload() {
        isDownloading = false
        currentTorrentHandle?.let { handle ->
            if (handle.isValid) {
                remove(handle, SessionHandle.DELETE_FILES)
                Log.d(TAG, "Download stopped and removed")
            }
        }
        currentTorrentHandle = null
    }
    
    /**
     * Simple seeding method (FrostWire pattern)
     */
    fun seedFile(filePath: String, listener: TorrentDownloadListener) {
        Log.d(TAG, "Starting seeding for: $filePath")
        
        try {
            val file = File(filePath)
            if (!file.exists()) {
                listener.onError("File not found for seeding: $filePath")
                return
            }
            
            // For simplicity, just notify ready to seed
            handler.post {
                listener.onReadyToSeed()
            }
            
            Log.d(TAG, "✅ File ready for seeding")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Seeding error: ${e.message}")
            listener.onError("Seeding failed: ${e.message}")
        }
    }
    
    /**
     * Check if library is available (compatibility method)
     */
    fun isLibraryReady(): Boolean = isLibraryAvailable
    
    /**
     * Check if library is loaded (compatibility method)
     */
    fun isLibraryLoaded(): Boolean = isLibraryAvailable
    
    /**
     * Get current port (compatibility method)
     */
    fun getCurrentPort(): Int = currentPort
    
    /**
     * Get current download status
     */
    fun isCurrentlyDownloading(): Boolean = isDownloading
}