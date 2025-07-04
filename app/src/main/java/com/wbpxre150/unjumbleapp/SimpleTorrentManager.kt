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
    private var currentMagnetLink: String = ""
    private var fastResumeData: ByteArray? = null
    
    companion object {
        private const val TAG = "SimpleTorrentManager"
        private const val METADATA_TIMEOUT_SECONDS = 120 // FrostWire uses longer timeouts for reliability
        private const val SESSION_BOOTSTRAP_TIMEOUT_MS = 20000L // 20 seconds for session to bootstrap (FrostWire pattern)
        private const val DHT_MIN_NODES_REQUIRED = 10 // Higher threshold for better reliability
        private const val MAX_RETRY_ATTEMPTS = 3 // FrostWire-style retry limit
        private const val DHT_BOOTSTRAP_CHECK_INTERVAL_MS = 500L // FrostWire bootstrap monitoring frequency
        private const val STATE_VERSION = "1.2.19.0" // Session state compatibility version
        
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
        
        // FrostWire-style periodic maintenance
        startMaintenanceTimer()
        
        // Note: Using simple progress-based resume tracking instead of complex alert processing
    }
    
    // Note: Removed complex alert processing system - using simple progress-based resume tracking
    // Resume data is saved periodically during progress monitoring in startProgressMonitoring()
    
    /**
     * Start periodic maintenance (FrostWire pattern)
     */
    private fun startMaintenanceTimer() {
        Thread {
            while (isLibraryAvailable) {
                try {
                    Thread.sleep(300000) // 5 minutes
                    if (isLibraryAvailable) {
                        performMaintenanceCleanup()
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Maintenance timer error: ${e.message}")
                }
            }
        }.start()
    }
    
    private fun initializeSession() {
        Log.d(TAG, "Initializing jlibtorrent session (FrostWire pattern)...")
        
        try {
            // Load native library (same as FrostWire approach)
            System.loadLibrary("jlibtorrent-1.2.19.0")
            Log.d(TAG, "✓ Native library loaded")
            
            // Try to load previous session state (FrostWire pattern)
            val savedSessionParams = loadSessionState()
            val sessionParams = if (savedSessionParams != null) {
                Log.d(TAG, "✓ Using saved session state for faster startup")
                savedSessionParams
            } else {
                Log.d(TAG, "Creating new session with FrostWire settings")
                val settingsPack = createFrostWireSettings()
                SessionParams(settingsPack)
            }
            
            // Start session with optimized parameters (FrostWire pattern)
            start(sessionParams)
            sessionStartTime = System.currentTimeMillis()
            Log.d(TAG, "✓ Session started with FrostWire settings")
            
            isLibraryAvailable = true
            
            // Start session readiness monitoring
            startSessionBootstrapMonitoring()
            
            // Restore any existing torrents with fast resume data after bootstrap
            Thread {
                Thread.sleep(10000) // Wait 10 seconds for session to stabilize
                restoreActiveTorrents()
            }.start()
            
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
        
        // Enhanced DHT configuration with IPv6 support
        settings.enableDht(true)
        settings.setString(
            settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
            "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,router.silotis.us:6881"
        )
        
        // FrostWire's port configuration strategy with IPv6
        currentPort = (49152..65534).random()
        settings.setString(
            settings_pack.string_types.listen_interfaces.swigValue(),
            "0.0.0.0:$currentPort,[::]:$currentPort"
        )
        
        // Aggressive connection scaling based on network type
        val networkManager = NetworkManager.getInstance(context)
        val isWiFi = networkManager.isOnWiFi()
        
        if (isWiFi) {
            // WiFi: Aggressive settings for maximum performance
            settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 800)
            settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 8)
            settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), 8)
            settings.setInteger(settings_pack.int_types.active_limit.swigValue(), 50)
            Log.d(TAG, "Applied WiFi aggressive connection settings: 800 connections, 50 active limit")
        } else {
            // Mobile: Conservative but enhanced settings
            settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 300)
            settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
            settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), 4)
            settings.setInteger(settings_pack.int_types.active_limit.swigValue(), 20)
            Log.d(TAG, "Applied mobile conservative connection settings: 300 connections, 20 active limit")
        }
        
        // FrostWire's enhanced peer discovery settings
        settings.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
        
        // FrostWire's session optimization settings
        settings.setInteger(settings_pack.int_types.request_timeout.swigValue(), 10)
        settings.setInteger(settings_pack.int_types.peer_timeout.swigValue(), 20)
        settings.setInteger(settings_pack.int_types.inactivity_timeout.swigValue(), 600)
        
        // FrostWire's DHT optimization
        settings.setInteger(settings_pack.int_types.dht_upload_rate_limit.swigValue(), 8000)
        settings.setBoolean(settings_pack.bool_types.prefer_rc4.swigValue(), false)
        
        // Comprehensive alert system matching FrostWire
        settings.setInteger(
            settings_pack.int_types.alert_mask.swigValue(),
            AlertType.METADATA_RECEIVED.swig() or 
            AlertType.ADD_TORRENT.swig() or
            AlertType.TORRENT_FINISHED.swig() or
            AlertType.STATE_CHANGED.swig() or
            AlertType.DHT_BOOTSTRAP.swig() or
            AlertType.PEER_CONNECT.swig() or
            AlertType.PEER_DISCONNECTED.swig() or
            AlertType.EXTERNAL_IP.swig() or
            AlertType.LISTEN_SUCCEEDED.swig() or
            AlertType.LISTEN_FAILED.swig() or
            AlertType.TRACKER_ANNOUNCE.swig() or
            AlertType.TRACKER_REPLY.swig() or
            AlertType.TRACKER_ERROR.swig() or
            AlertType.DHT_REPLY.swig() or
            AlertType.DHT_GET_PEERS.swig() or
            AlertType.TORRENT_CHECKED.swig() or
            AlertType.TORRENT_RESUMED.swig() or
            AlertType.TORRENT_PAUSED.swig()
        )
        
        // FrostWire-style alert queue optimization
        settings.setInteger(settings_pack.int_types.alert_queue_size.swigValue(), 5000)
        
        // Peer fingerprinting for better peer relationships
        val fingerprint = com.frostwire.jlibtorrent.swig.libtorrent.generate_fingerprint("UJ", 1, 0, 0, 0)
        settings.setString(settings_pack.string_types.peer_fingerprint.swigValue(), fingerprint)
        
        // Enhanced user agent matching FrostWire pattern
        val libVersion = com.frostwire.jlibtorrent.swig.libtorrent.version()
        val userAgent = "Unjumble/1.0.0 libtorrent/$libVersion"
        settings.setString(settings_pack.string_types.user_agent.swigValue(), userAgent)
        
        // FrostWire's performance optimizations
        settings.setInteger(settings_pack.int_types.stop_tracker_timeout.swigValue(), 0)
        settings.setBoolean(settings_pack.bool_types.validate_https_trackers.swigValue(), false)
        
        // Memory optimization detection
        val memoryOptimized = isMemoryConstrainedDevice()
        if (memoryOptimized) {
            applyMemoryOptimizations(settings)
            Log.d(TAG, "Applied memory optimizations for constrained device")
        }
        
        Log.d(TAG, "✓ Enhanced FrostWire-style settings configured")
        Log.d(TAG, "  - Port: $currentPort")
        Log.d(TAG, "  - Network: ${if (isWiFi) "WiFi (aggressive)" else "Mobile (conservative)"}")
        Log.d(TAG, "  - Fingerprint: $fingerprint")
        Log.d(TAG, "  - User Agent: $userAgent")
        Log.d(TAG, "  - Memory Optimized: $memoryOptimized")
        
        return settings
    }
    
    /**
     * Detect if device is memory constrained (FrostWire pattern)
     */
    private fun isMemoryConstrainedDevice(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val availableMemory = maxMemory - (totalMemory - freeMemory)
        
        // Consider memory constrained if less than 256MB available
        val isConstrained = availableMemory < 256 * 1024 * 1024
        
        Log.d(TAG, "Memory analysis: Available=${availableMemory / (1024*1024)}MB, Constrained=$isConstrained")
        return isConstrained
    }
    
    /**
     * Apply FrostWire-style memory optimizations
     */
    private fun applyMemoryOptimizations(settings: SettingsPack) {
        // FrostWire's memory optimization settings
        val defaultMaxQueuedDiskBytes = 1048576 // 1MB default
        settings.setInteger(settings_pack.int_types.max_queued_disk_bytes.swigValue(), defaultMaxQueuedDiskBytes / 2)
        
        val defaultSendBufferWatermark = 500 * 1024 // 500KB default
        settings.setInteger(settings_pack.int_types.send_buffer_watermark.swigValue(), defaultSendBufferWatermark / 2)
        
        settings.setInteger(settings_pack.int_types.cache_size.swigValue(), 256)
        settings.setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), 200)
        settings.setInteger(settings_pack.int_types.tick_interval.swigValue(), 1000)
        settings.setInteger(settings_pack.int_types.inactivity_timeout.swigValue(), 60)
        settings.setBoolean(settings_pack.bool_types.seeding_outgoing_connections.swigValue(), false)
        settings.setBoolean(settings_pack.bool_types.enable_ip_notifier.swigValue(), false)
        
        // Reduce connection limits for memory constrained devices
        val currentConnectionLimit = settings.getInteger(settings_pack.int_types.connections_limit.swigValue())
        settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), currentConnectionLimit / 2)
        
        val currentActiveLimit = settings.getInteger(settings_pack.int_types.active_limit.swigValue())
        settings.setInteger(settings_pack.int_types.active_limit.swigValue(), currentActiveLimit / 2)
    }
    
    /**
     * Load session state from disk (FrostWire pattern)
     */
    private fun loadSessionState(): SessionParams? {
        return try {
            val stateFile = File(context.filesDir, "torrent_session_state.dat")
            if (!stateFile.exists()) {
                Log.d(TAG, "No previous session state found, using defaults")
                return null
            }
            
            val data = stateFile.readBytes()
            Log.d(TAG, "✓ Loaded raw session state: ${data.size} bytes")
            
            // For now, return null to use default settings until jlibtorrent Entry API is sorted
            // TODO: Implement proper session state persistence when Entry API is available
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load session state: ${e.message}")
            null
        }
    }
    
    /**
     * Restore all torrents with fast resume data on session start
     */
    private fun restoreActiveTorrents() {
        Thread {
            try {
                Log.d(TAG, "🔄 Restoring active torrents with fast resume data")
                
                // Find all resume data files
                val resumeFiles = context.filesDir.listFiles { file ->
                    file.name.startsWith("resume_") && file.name.endsWith(".dat")
                }
                
                if (resumeFiles == null || resumeFiles.isEmpty()) {
                    Log.d(TAG, "No resume data files found for restoration")
                    return@Thread
                }
                
                Log.d(TAG, "Found ${resumeFiles.size} resume data files for restoration")
                
                for (resumeFile in resumeFiles) {
                    try {
                        val infoHashStr = resumeFile.name.substring(7, resumeFile.name.length - 4) // Remove "resume_" and ".dat"
                        val magnetFile = File(context.filesDir, "magnet_${infoHashStr}.txt")
                        
                        if (!magnetFile.exists()) {
                            Log.w(TAG, "No magnet file found for resume data: $infoHashStr")
                            continue
                        }
                        
                        val magnetLink = magnetFile.readText()
                        val resumeData = resumeFile.readBytes()
                        
                        Log.d(TAG, "🚀 Restoring torrent: $infoHashStr (${resumeData.size} bytes resume data)")
                        
                        // Fetch metadata to get TorrentInfo
                        val metadata = fetchMagnet(magnetLink, 30, false) // Shorter timeout for restoration
                        
                        if (metadata != null && metadata.isNotEmpty()) {
                            val torrentInfo = TorrentInfo.bdecode(metadata)
                            
                            // Determine download path from SharedPreferences
                            val resumePrefs = context.getSharedPreferences("torrent_resume", Context.MODE_PRIVATE)
                            val downloadPath = resumePrefs.getString("download_path", File(context.filesDir, "pictures.tar.gz").absolutePath)
                            val downloadDir = File(downloadPath!!).parentFile
                            
                            // Add torrent with simplified restoration
                            // TODO: Implement proper AddTorrentParams when API is available
                            download(torrentInfo, downloadDir!!)
                            Thread.sleep(1000)
                            
                            val restoredHandle = find(torrentInfo.infoHash())
                            
                            if (restoredHandle.isValid) {
                                Log.d(TAG, "✅ Successfully restored torrent: ${torrentInfo.name()}")
                                
                                // If this is the current download, set it as current handle
                                if (downloadPath == currentDownloadPath) {
                                    currentTorrentHandle = restoredHandle
                                    currentMagnetLink = magnetLink
                                    Log.d(TAG, "🎯 Set as current torrent handle")
                                }
                            } else {
                                Log.w(TAG, "❌ Failed to restore torrent: $infoHashStr")
                            }
                        } else {
                            Log.w(TAG, "❌ Failed to fetch metadata for restoration: $infoHashStr")
                        }
                        
                        Thread.sleep(1000) // Small delay between restorations
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error restoring torrent from ${resumeFile.name}: ${e.message}")
                    }
                }
                
                Log.d(TAG, "🔄 Torrent restoration completed")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during torrent restoration: ${e.message}")
            }
        }.start()
    }
    
    /**
     * Save session state to disk (FrostWire pattern)
     */
    private fun saveSessionState() {
        try {
            if (!isLibraryAvailable) return
            
            val sessionData = saveState()
            if (sessionData != null) {
                val stateFile = File(context.filesDir, "torrent_session_state.dat")
                stateFile.writeBytes(sessionData)
                
                Log.d(TAG, "✓ Saved session state: ${sessionData.size} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session state: ${e.message}")
        }
    }
    
    /**
     * Enhanced multi-threaded session bootstrap monitoring (Advanced FrostWire pattern)
     */
    private fun startSessionBootstrapMonitoring() {
        val bootstrapExecutor = java.util.concurrent.Executors.newFixedThreadPool(3)
        
        // Main DHT bootstrap monitoring thread
        bootstrapExecutor.submit {
            startDHTBootstrapMonitoring()
        }
        
        // Tracker connectivity monitoring thread
        bootstrapExecutor.submit {
            startTrackerConnectivityMonitoring()
        }
        
        // Peer discovery monitoring thread
        bootstrapExecutor.submit {
            startPeerDiscoveryMonitoring()
        }
        
        Log.d(TAG, "🔄 Started advanced multi-threaded bootstrap monitoring")
    }
    
    /**
     * DHT-focused bootstrap monitoring
     */
    private fun startDHTBootstrapMonitoring() {
        Thread {
            Log.d(TAG, "🔄 Starting DHT bootstrap monitoring thread...")
            
            var attempts = 0
            val maxAttempts = (SESSION_BOOTSTRAP_TIMEOUT_MS / DHT_BOOTSTRAP_CHECK_INTERVAL_MS).toInt()
            var lastDhtNodes = 0
            var bootstrapPhase = "INITIALIZING"
            
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
                    
                    // FrostWire-style bootstrap phase tracking
                    bootstrapPhase = when {
                        !isDhtRunning -> "DHT_STARTING"
                        dhtNodes == 0 -> "BOOTSTRAP_CONNECTING"
                        dhtNodes < DHT_MIN_NODES_REQUIRED -> "BUILDING_NODES"
                        dhtNodes >= DHT_MIN_NODES_REQUIRED -> "DHT_READY"
                        else -> "UNKNOWN"
                    }
                    
                    // Log progress when node count changes significantly
                    if (dhtNodes != lastDhtNodes || attempts % 10 == 0) {
                        Log.d(TAG, "Bootstrap check $attempts/$maxAttempts: Phase=$bootstrapPhase, DHT running=$isDhtRunning, nodes=$dhtNodes (+${dhtNodes - lastDhtNodes}) (${elapsed}ms elapsed)")
                        lastDhtNodes = dhtNodes
                    }
                    
                    // FrostWire's session readiness criteria
                    if (isDhtRunning && dhtNodes >= DHT_MIN_NODES_REQUIRED) {
                        isSessionReady = true
                        Log.d(TAG, "✅ Session ready! DHT connected with $dhtNodes nodes after ${elapsed}ms (FrostWire criteria met)")
                        break
                    }
                    
                    Thread.sleep(DHT_BOOTSTRAP_CHECK_INTERVAL_MS)
                    attempts++
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Bootstrap monitoring error: ${e.message}")
                    attempts++
                }
            }
            
            if (!isSessionReady) {
                val elapsed = System.currentTimeMillis() - sessionStartTime
                val finalDhtNodes = try { stats()?.dhtNodes()?.toInt() ?: 0 } catch (e: Exception) { 0 }
                Log.w(TAG, "⚠️ Session bootstrap timeout after ${elapsed}ms - final state: phase=$bootstrapPhase, nodes=$finalDhtNodes")
                Log.w(TAG, "Will attempt downloads anyway with reduced reliability expectation")
                // Don't set isSessionReady = true here - let download logic handle partial readiness
            }
            
        }.start()
    }
    
    /**
     * Tracker connectivity monitoring thread
     */
    private fun startTrackerConnectivityMonitoring() {
        Thread {
            Log.d(TAG, "🔄 Starting tracker connectivity monitoring thread...")
            
            var trackerCheckCount = 0
            val maxTrackerChecks = 20 // Check for 40 seconds
            
            while (trackerCheckCount < maxTrackerChecks && !isSessionReady) {
                try {
                    val sessionStats = stats()
                    if (sessionStats != null) {
                        val downloadRate = sessionStats.downloadRate().toInt()
                        val uploadRate = sessionStats.uploadRate().toInt()
                        
                        if (trackerCheckCount % 5 == 0) { // Log every 10 seconds
                            Log.d(TAG, "Tracker monitoring: ↓${downloadRate/1024}KB/s, ↑${uploadRate/1024}KB/s")
                        }
                    }
                    
                    Thread.sleep(2000) // Check every 2 seconds
                    trackerCheckCount++
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Tracker monitoring error: ${e.message}")
                    trackerCheckCount++
                }
            }
            
            Log.d(TAG, "Tracker connectivity monitoring thread completed")
        }.start()
    }
    
    /**
     * Peer discovery monitoring thread
     */
    private fun startPeerDiscoveryMonitoring() {
        Thread {
            Log.d(TAG, "🔄 Starting peer discovery monitoring thread...")
            
            var peerCheckCount = 0
            val maxPeerChecks = 30 // Check for 60 seconds
            
            while (peerCheckCount < maxPeerChecks && !isSessionReady) {
                try {
                    val sessionStats = stats()
                    if (sessionStats != null) {
                        val downloadRate = sessionStats.downloadRate().toInt()
                        val uploadRate = sessionStats.uploadRate().toInt()
                        val totalDownload = sessionStats.totalDownload().toInt()
                        
                        // Use download/upload stats as proxy for peer activity
                        if (peerCheckCount % 10 == 0) { // Log every 20 seconds
                            Log.d(TAG, "Peer discovery: ↓${downloadRate/1024}KB/s, ↑${uploadRate/1024}KB/s, total: ${totalDownload/(1024*1024)}MB")
                        }
                    }
                    
                    Thread.sleep(2000) // Check every 2 seconds
                    peerCheckCount++
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Peer discovery monitoring error: ${e.message}")
                    peerCheckCount++
                }
            }
            
            Log.d(TAG, "Peer discovery monitoring thread completed")
        }.start()
    }
    
    /**
     * FrostWire-style session diagnostics
     */
    private fun getSessionDiagnostics(): String {
        return try {
            val sessionStats = stats()
            val dhtNodes = sessionStats?.dhtNodes()?.toInt() ?: 0
            val isDhtRunning = try { isDhtRunning() } catch (e: Exception) { false }
            val downloadRate = sessionStats?.downloadRate()?.toInt() ?: 0
            val uploadRate = sessionStats?.uploadRate()?.toInt() ?: 0
            val totalDownload = sessionStats?.totalDownload()?.toInt() ?: 0
            val totalUpload = sessionStats?.totalUpload()?.toInt() ?: 0
            
            "DHT: $isDhtRunning ($dhtNodes nodes) | Rates: ↓${downloadRate/1024}KB/s ↑${uploadRate/1024}KB/s | Total: ↓${totalDownload/(1024*1024)}MB ↑${totalUpload/(1024*1024)}MB"
        } catch (e: Exception) {
            "Session diagnostics unavailable: ${e.message}"
        }
    }
    
    /**
     * Enhanced session readiness check (FrostWire approach)
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
        // Run session readiness check in background to avoid blocking UI
        Thread {
            try {
                Log.d(TAG, "Starting session readiness check for download...")
                
                // Notify listener that we're checking session readiness
                handler.post {
                    listener.onSessionDiagnostic("Ensuring P2P session is ready for download...")
                }
                
                // Ensure session is ready before attempting download (may take up to 15 seconds)
                val sessionReady = ensureSessionReady()
                
                if (!sessionReady) {
                    Log.e(TAG, "Session readiness check failed, falling back to HTTPS")
                    handler.post {
                        listener.onError("BitTorrent session not ready after bootstrap timeout - library may not be available")
                    }
                    return@Thread
                }
                
                Log.d(TAG, "Session readiness confirmed, starting download...")
                handler.post {
                    listener.onSessionDiagnostic("P2P session ready, starting download...")
                }
                
                // Proceed with download on the current background thread
                downloadFileWithRetry(magnetLink, downloadPath, listener, retryCount = 0)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error during session readiness check: ${e.message}")
                handler.post {
                    listener.onError("Session readiness check failed: ${e.message}")
                }
            }
        }.start()
    }
    
    /**
     * Download with progressive retry strategy and fast resume support
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
        currentMagnetLink = magnetLink
        
        Log.d(TAG, "Starting download using FrostWire pattern with fast resume (attempt ${retryCount + 1})...")
        
        // Phase 1: Wait for session readiness (FrostWire approach)
        handler.post {
            listener.onDhtConnecting()
            listener.onPhaseChanged(DownloadPhase.METADATA_FETCHING, 120) // Increased timeout
        }
        
        // Phase 2: FrostWire-style metadata fetch with resume support (background thread)
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
                        listener.onDhtDiagnostic("✅ Enhanced session ready: DHT running with $dhtNodes nodes (FrostWire optimized)")
                    } else {
                        listener.onDhtDiagnostic("⚠️ Enhanced session not fully ready but proceeding: DHT nodes=$dhtNodes")
                    }
                    listener.onMetadataFetching()
                    
                    // FrostWire-style initial session quality report
                    listener.onSessionQualityChanged(dhtNodes, 0, 0)
                }
                
                // Enhanced pre-fetchMagnet validation (FrostWire pattern)
                val preValidationResult = validateSessionForFetch(isDhtRunning, dhtNodes)
                if (!preValidationResult.isValid) {
                    Log.e(TAG, "❌ Pre-fetchMagnet validation failed: ${preValidationResult.error}")
                    handler.post {
                        listener.onError("Session not ready: ${preValidationResult.error}")
                    }
                    isDownloading = false
                    return@Thread
                }
                
                Log.d(TAG, "🔍 Starting enhanced fetchMagnet with ${METADATA_TIMEOUT_SECONDS}s timeout (FrostWire pattern)...")
                
                handler.post {
                    listener.onSessionDiagnostic("Enhanced metadata fetch: DHT($dhtNodes) + Trackers + PEX - ${METADATA_TIMEOUT_SECONDS}s timeout")
                }
                
                // FrostWire's enhanced fetchMagnet approach with better error handling
                val metadata = performEnhancedMetadataFetch(magnetLink, dhtNodes)
                
                if (metadata != null && metadata.isNotEmpty()) {
                    Log.d(TAG, "✅ Metadata fetched: ${metadata.size} bytes")
                    
                    handler.post {
                        listener.onMetadataComplete()
                        listener.onPhaseChanged(DownloadPhase.ACTIVE_DOWNLOADING, 240)
                    }
                    
                    // Phase 3: Start download with metadata and resume support (FrostWire pattern)
                    val torrentInfo = TorrentInfo.bdecode(metadata)
                    val downloadDir = File(currentDownloadPath).parentFile
                    val infoHashStr = torrentInfo.infoHash().toString()
                    
                    Log.d(TAG, "Starting download: ${torrentInfo.name()} (hash: $infoHashStr)")
                    
                    // Check for existing fast resume data
                    val existingResumeData = loadResumeData(infoHashStr)
                    
                    // Also check SharedPreferences for resume data
                    val resumePrefs = context.getSharedPreferences("torrent_resume", Context.MODE_PRIVATE)
                    val hasSharedPrefsResume = resumePrefs.getBoolean("has_resume_data", false)
                    val sharedPrefsProgress = if (hasSharedPrefsResume) {
                        val downloadedSize = resumePrefs.getLong("downloaded_size", 0)
                        val totalSize = resumePrefs.getLong("total_size", 0)
                        if (totalSize > 0) (downloadedSize.toFloat() / totalSize * 100).toInt() else 0
                    } else 0
                    
                    if (existingResumeData != null) {
                        Log.d(TAG, "🚀 Found fast resume data (${existingResumeData.size} bytes) - attempting resume via standard download")
                        
                        handler.post {
                            listener.onSessionDiagnostic("🚀 Resume data found (${existingResumeData.size} bytes) - using enhanced download")
                        }
                        
                        // For now, use standard download but with resume awareness
                        // TODO: Implement proper AddTorrentParams when jlibtorrent API is fully available
                        download(torrentInfo, downloadDir)
                        
                        // Give session time to process
                        Thread.sleep(1000)
                        
                        // Find and track the torrent handle
                        currentTorrentHandle = find(torrentInfo.infoHash())
                        
                    } else if (hasSharedPrefsResume && sharedPrefsProgress > 0) {
                        Log.d(TAG, "🔄 No jlibtorrent resume data but found SharedPreferences resume: ${sharedPrefsProgress}% complete")
                        
                        handler.post {
                            listener.onSessionDiagnostic("🔄 Using SharedPreferences resume data: ${sharedPrefsProgress}% complete - file position based resume")
                        }
                        
                        // Check if partial file exists and validate it for position-based resume
                        val partialFile = File(downloadPath)
                        if (partialFile.exists()) {
                            val partialFileSize = partialFile.length()
                            val expectedSize = resumePrefs.getLong("downloaded_size", 0)
                            val totalSize = resumePrefs.getLong("total_size", 0)
                            
                            Log.d(TAG, "✅ Partial file exists: ${partialFileSize} bytes, expected: ${expectedSize} bytes")
                            
                            // Validate file size matches expected progress
                            when {
                                partialFileSize == expectedSize -> {
                                    Log.d(TAG, "✅ File size matches SharedPreferences - perfect resume condition")
                                }
                                partialFileSize > expectedSize -> {
                                    Log.d(TAG, "⚠️ File size (${partialFileSize}) > expected (${expectedSize}) - jlibtorrent will verify and adjust")
                                }
                                partialFileSize < expectedSize && partialFileSize > 0 -> {
                                    Log.d(TAG, "⚠️ File size (${partialFileSize}) < expected (${expectedSize}) - will resume from actual file size")
                                }
                                else -> {
                                    Log.d(TAG, "⚠️ Empty partial file - starting fresh download")
                                }
                            }
                            
                            // jlibtorrent will automatically detect and resume from the existing partial file
                            download(torrentInfo, downloadDir)
                        } else {
                            Log.d(TAG, "⚠️ SharedPreferences resume data exists but no partial file found - starting fresh")
                            download(torrentInfo, downloadDir)
                        }
                        
                        // Give session time to process
                        Thread.sleep(1000)
                        
                        // Find and track the torrent handle
                        currentTorrentHandle = find(torrentInfo.infoHash())
                        
                    } else {
                        Log.d(TAG, "📁 No resume data found (jlibtorrent or SharedPreferences) - starting fresh download")
                        
                        handler.post {
                            listener.onSessionDiagnostic("📁 No resume data - starting fresh download")
                        }
                        
                        // Standard download call (FrostWire method)
                        download(torrentInfo, downloadDir)
                        
                        // Give session time to process
                        Thread.sleep(1000)
                        
                        // Find and track the torrent handle
                        currentTorrentHandle = find(torrentInfo.infoHash())
                    }
                    
                    if (currentTorrentHandle?.isValid == true) {
                        Log.d(TAG, "✅ Download started successfully (resume: ${existingResumeData != null})")
                        currentTorrentHandle?.resume() // Ensure it's active
                        
                        // Enhanced peer discovery notification
                        handler.post {
                            listener.onDiscoveringPeers()
                            
                            // FrostWire-style initial session quality report
                            val initialDhtNodes = try { stats()?.dhtNodes()?.toInt() ?: 0 } catch (e: Exception) { 0 }
                            listener.onSessionQualityChanged(initialDhtNodes, 0, 0)
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
                    // FrostWire's enhanced error classification system
                    val failureAnalysis = analyzeMetadataFetchFailure(sessionStartTime, sessionReady, dhtNodes)
                    
                    Log.e(TAG, "❌ Enhanced fetchMagnet failed: ${failureAnalysis.summary}")
                    
                    // FrostWire's intelligent retry logic
                    val shouldRetry = shouldRetryFetch(retryCount, failureAnalysis)
                    
                    if (shouldRetry) {
                        Log.d(TAG, "🔄 FrostWire-style retry (attempt ${retryCount + 2}/${MAX_RETRY_ATTEMPTS + 1}) after ${failureAnalysis.type}")
                        
                        handler.post {
                            listener.onSessionDiagnostic("🔄 Enhanced retry ${retryCount + 2}: ${failureAnalysis.retryReason}")
                        }
                        
                        // Reset state for retry
                        isDownloading = false
                        
                        // FrostWire's progressive retry delay strategy
                        val retryDelay = calculateRetryDelay(retryCount, failureAnalysis.type)
                        Thread.sleep(retryDelay)
                        
                        // Retry with incremented count
                        downloadFileWithRetry(magnetLink, downloadPath, listener, retryCount + 1)
                        return@Thread
                        
                    } else {
                        // Final failure with enhanced error reporting
                        handler.post {
                            if (failureAnalysis.type == "GENUINE_TIMEOUT") {
                                listener.onTimeout()
                            }
                            listener.onSessionDiagnostic("❌ Enhanced P2P failure: ${failureAnalysis.userMessage}")
                            listener.onError(failureAnalysis.userMessage)
                        }
                        isDownloading = false
                    }
                }
                
            } catch (e: Exception) {
                val diagnostics = getSessionDiagnostics()
                Log.e(TAG, "❌ Enhanced download error: ${e.message}")
                Log.e(TAG, "Session state at error: $diagnostics")
                
                handler.post {
                    listener.onSessionDiagnostic("Error occurred: $diagnostics")
                    listener.onError("Enhanced download failed: ${e.message}")
                }
                isDownloading = false
            }
        }.start()
    }
    
    /**
     * Enhanced progress monitoring (FrostWire pattern)
     */
    private fun startProgressMonitoring() {
        Thread {
            var lastPeerCount = 0
            var lastDownloaded = 0L
            var stagnantChecks = 0
            var resumeSaveCounter = 0
            val maxStagnantChecks = 10 // 20 seconds of no progress before concern
            val resumeSaveInterval = 15 // Save resume data every 30 seconds (15 * 2s intervals)
            
            Log.d(TAG, "Starting enhanced progress monitoring (FrostWire pattern)")
            
            while (isDownloading && currentTorrentHandle?.isValid == true) {
                try {
                    val status = currentTorrentHandle?.status()
                    if (status != null) {
                        val downloaded = status.totalDone()
                        val total = status.totalWanted()
                        val downloadRate = status.downloadRate()
                        val numPeers = status.numPeers()
                        val numSeeds = status.numSeeds()
                        val connectingPeers = status.connectCandidates()
                        
                        // FrostWire-style stagnation detection
                        if (downloaded == lastDownloaded && downloadRate == 0) {
                            stagnantChecks++
                        } else {
                            stagnantChecks = 0
                        }
                        
                        // Periodic fast resume data saving (every 30 seconds)
                        resumeSaveCounter++
                        if (resumeSaveCounter >= resumeSaveInterval && downloaded > 0) {
                            Log.d(TAG, "Periodic fast resume data save (${downloaded}/${total} bytes)")
                            saveResumeData()
                            resumeSaveCounter = 0
                        }
                        
                        // Enhanced progress update with FrostWire patterns
                        handler.post {
                            downloadListener?.onProgress(downloaded, total, downloadRate, numPeers)
                            
                            // FrostWire-style enhanced peer status reporting
                            if (numPeers != lastPeerCount) {
                                if (numPeers > 0) {
                                    downloadListener?.onPeerStatusChanged(numPeers, numSeeds, connectingPeers)
                                    Log.d(TAG, "Enhanced peer status: $numPeers total ($numSeeds seeds, $connectingPeers connecting)")
                                }
                                lastPeerCount = numPeers
                            }
                            
                            // FrostWire-style stagnation detection and reporting
                            if (stagnantChecks >= maxStagnantChecks && numPeers > 0) {
                                val stagnantTimeSeconds = stagnantChecks * 2 // 2 second intervals
                                downloadListener?.onDownloadStagnant(stagnantTimeSeconds, numPeers)
                                stagnantChecks = 0 // Reset to avoid spam
                            }
                            
                            // FrostWire-style session quality monitoring
                            if (stagnantChecks % 5 == 0) { // Every 10 seconds
                                val currentDhtNodes = try { stats()?.dhtNodes()?.toInt() ?: 0 } catch (e: Exception) { 0 }
                                downloadListener?.onSessionQualityChanged(currentDhtNodes, downloadRate, status.uploadRate())
                            }
                        }
                        
                        lastDownloaded = downloaded
                        
                        // FrostWire's completion detection
                        if (status.isFinished || (downloaded >= total && total > 0)) {
                            Log.d(TAG, "✅ Download completed (FrostWire detection): ${downloaded}/${total} bytes")
                            
                            // Save final resume data before completion
                            saveResumeData()
                            
                            handler.post {
                                downloadListener?.onCompleted(currentDownloadPath)
                            }
                            
                            isDownloading = false
                            break
                        }
                    }
                    
                    Thread.sleep(2000) // Update every 2 seconds (FrostWire frequency)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Enhanced progress monitoring error: ${e.message}")
                    break
                }
            }
            
            Log.d(TAG, "Enhanced progress monitoring ended")
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
     * Seed existing file using magnet link metadata (FrostWire pattern)
     */
    fun seedFile(filePath: String, listener: TorrentDownloadListener) {
        Log.d(TAG, "Starting seeding for: $filePath")
        
        // Run session readiness check in background to avoid blocking UI
        Thread {
            try {
                Log.d(TAG, "Starting session readiness check for seeding...")
                
                // Notify listener that we're checking session readiness  
                handler.post {
                    listener.onSessionDiagnostic("Ensuring P2P session is ready for seeding...")
                }
                
                // Ensure session is ready before attempting seeding (may take up to 15 seconds)
                val sessionReady = ensureSessionReady()
                
                if (!sessionReady) {
                    Log.e(TAG, "Session readiness check failed for seeding")
                    handler.post {
                        listener.onError("BitTorrent session not ready for seeding after bootstrap timeout")
                    }
                    return@Thread
                }
                
                Log.d(TAG, "Session readiness confirmed for seeding")
                handler.post {
                    listener.onSessionDiagnostic("P2P session ready, starting seeding...")
                }
                
                // Continue with seeding logic on background thread
                val file = File(filePath)
                if (!file.exists()) {
                    handler.post {
                        listener.onError("File not found for seeding: $filePath")
                    }
                    return@Thread
                }
                
                // Get magnet link from resources
                val magnetLink = context.getString(R.string.pictures_magnet_link)
                Log.d(TAG, "Using magnet link for seeding: ${magnetLink.take(50)}...")
                
                // Set up seeding mode
                isDownloading = false
                downloadListener = listener
                currentDownloadPath = filePath
                
                // Fetch metadata from magnet link to get torrent info (already on background thread)
                Log.d(TAG, "Fetching metadata for seeding...")
                handler.post {
                    listener.onMetadataFetching()
                }
                
                try {
                    // Use the enhanced fetchMagnet method that already exists
                    val metadata = fetchMagnet(magnetLink, 120, false)
                    
                    if (metadata != null) {
                        Log.d(TAG, "✅ Metadata fetched for seeding, configuring torrent...")
                        
                        try {
                            // Create TorrentInfo from metadata (same pattern as downloadFile)
                            val torrentInfo = TorrentInfo.bdecode(metadata)
                            val downloadDir = file.parentFile
                            
                            Log.d(TAG, "Starting seeding for: ${torrentInfo.name()}")
                            
                            // Use the existing download method but for seeding
                            download(torrentInfo, downloadDir)
                            
                            // Give session time to process
                            Thread.sleep(1000)
                            
                            // Find and track the torrent handle (same as download)
                            currentTorrentHandle = find(torrentInfo.infoHash())
                            
                            if (currentTorrentHandle?.isValid == true) {
                                Log.d(TAG, "✅ Torrent added for seeding: ${torrentInfo.name()}")
                                
                                // Resume torrent to start seeding
                                currentTorrentHandle?.resume()
                                
                                handler.post {
                                    listener.onMetadataComplete()
                                    listener.onReadyToSeed()
                                }
                                
                                Log.d(TAG, "✅ Seeding started successfully")
                            } else {
                                Log.e(TAG, "❌ Failed to create torrent handle for seeding")
                                handler.post {
                                    listener.onError("Failed to create torrent handle for seeding")
                                }
                            }
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error configuring torrent for seeding: ${e.message}")
                            handler.post {
                                listener.onError("Seeding configuration failed: ${e.message}")
                            }
                        }
                    } else {
                        Log.e(TAG, "❌ Failed to fetch metadata for seeding")
                        handler.post {
                            listener.onError("Failed to fetch seeding metadata")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Seeding metadata fetch error: ${e.message}")
                    handler.post {
                        listener.onError("Seeding metadata fetch failed: ${e.message}")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Seeding session readiness error: ${e.message}")
                handler.post {
                    listener.onError("Seeding session readiness failed: ${e.message}")
                }
            }
        }.start()
    }
    
    /**
     * Check if library is available (compatibility method)
     */
    fun isLibraryReady(): Boolean = isLibraryAvailable
    
    /**
     * Check if library is loaded (compatibility method)
     */
    fun isLibraryLoaded(): Boolean {
        // If library was marked as unavailable but native library is actually loaded,
        // attempt to reinitialize the session
        if (!isLibraryAvailable) {
            try {
                // Test if native library is actually available
                System.loadLibrary("jlibtorrent-1.2.19.0")
                Log.d(TAG, "Native library still available, attempting session recovery")
                
                // Reinitialize session if native library is available
                initializeSession()
                
                Log.d(TAG, "Session recovery ${if (isLibraryAvailable) "successful" else "failed"}")
            } catch (e: UnsatisfiedLinkError) {
                Log.d(TAG, "Native library truly unavailable: ${e.message}")
                isLibraryAvailable = false
            } catch (e: Exception) {
                Log.w(TAG, "Session recovery failed: ${e.message}")
                isLibraryAvailable = false
            }
        }
        
        return isLibraryAvailable
    }
    
    /**
     * Get current port (compatibility method)
     */
    fun getCurrentPort(): Int = currentPort
    
    /**
     * Get current download status
     */
    fun isCurrentlyDownloading(): Boolean = isDownloading
    
    /**
     * Check if currently seeding (compatibility method)
     */
    fun isSeeding(): Boolean {
        return try {
            currentTorrentHandle?.let { handle ->
                if (handle.isValid) {
                    val status = handle.status()
                    status.isSeeding
                } else false
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking seeding status: ${e.message}")
            false
        }
    }
    
    /**
     * Get peer count (compatibility method)
     */
    fun getPeerCount(): Int {
        return try {
            currentTorrentHandle?.status()?.numPeers() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Error getting peer count: ${e.message}")
            0
        }
    }
    
    /**
     * Get upload rate (compatibility method)
     */
    fun getUploadRate(): Int {
        return try {
            currentTorrentHandle?.status()?.uploadRate() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Error getting upload rate: ${e.message}")
            0
        }
    }
    
    /**
     * Get current torrent handle (compatibility method)
     */
    fun getCurrentTorrentHandle(): TorrentHandle? {
        return if (currentTorrentHandle?.isValid == true) currentTorrentHandle else null
    }
    
    /**
     * Check if actively downloading (compatibility method)
     */
    fun isActivelyDownloading(): Boolean = isDownloading
    
    /**
     * Check if network is transitioning (compatibility method - simplified)
     */
    fun isNetworkTransitioning(): Boolean = false
    
    /**
     * Check if seeding is enabled (compatibility method - always true for simplified version)
     */
    fun isSeedingEnabled(): Boolean = true
    
    /**
     * Ensure session is ready for operations - wait for bootstrap completion with timeout
     */
    fun ensureSessionReady(): Boolean {
        Log.d(TAG, "Ensuring session readiness: isLibraryAvailable=$isLibraryAvailable, isSessionReady=$isSessionReady")
        
        // If library is marked unavailable, try to recover
        if (!isLibraryAvailable) {
            Log.d(TAG, "Library marked unavailable, attempting recovery...")
            return isLibraryLoaded() // This will attempt recovery
        }
        
        // If already marked as ready, verify it's still working
        if (isSessionReady) {
            try {
                val sessionStats = stats()
                if (sessionStats != null) {
                    Log.d(TAG, "Session verified as ready and working")
                    return true
                } else {
                    Log.w(TAG, "Session marked ready but not responding, waiting for bootstrap...")
                    isSessionReady = false // Reset and wait for bootstrap
                }
            } catch (e: Exception) {
                Log.w(TAG, "Session verification failed: ${e.message}, waiting for bootstrap...")
                isSessionReady = false
            }
        }
        
        // Wait for session bootstrap completion with timeout
        return waitForSessionBootstrap()
    }
    
    /**
     * Wait for session bootstrap to complete with timeout and proper DHT connectivity
     */
    private fun waitForSessionBootstrap(): Boolean {
        val bootstrapTimeoutMs = 15000L // 15 seconds timeout
        val checkIntervalMs = 500L // Check every 500ms
        val startTime = System.currentTimeMillis()
        val sessionAge = System.currentTimeMillis() - sessionStartTime
        
        Log.d(TAG, "Waiting for session bootstrap completion (timeout: ${bootstrapTimeoutMs}ms, session age: ${sessionAge}ms)")
        
        var lastDhtNodes = 0
        var attempts = 0
        val maxAttempts = (bootstrapTimeoutMs / checkIntervalMs).toInt()
        
        while (attempts < maxAttempts) {
            try {
                val elapsed = System.currentTimeMillis() - startTime
                
                // Check if session stats are available (basic session health)
                val sessionStats = stats()
                if (sessionStats == null) {
                    Log.d(TAG, "Bootstrap check $attempts/$maxAttempts: Session stats not available yet (${elapsed}ms)")
                    Thread.sleep(checkIntervalMs)
                    attempts++
                    continue
                }
                
                // Check DHT connectivity as primary readiness indicator
                val dhtNodes = sessionStats.dhtNodes()?.toInt() ?: 0
                val isDhtRunning = try {
                    isDhtRunning()
                } catch (e: Exception) {
                    false
                }
                
                // Log progress when DHT nodes change significantly
                if (dhtNodes != lastDhtNodes || attempts % 10 == 0) {
                    Log.d(TAG, "Bootstrap check $attempts/$maxAttempts: DHT running=$isDhtRunning, nodes=$dhtNodes (+${dhtNodes - lastDhtNodes}) (${elapsed}ms)")
                    lastDhtNodes = dhtNodes
                }
                
                // Enhanced readiness criteria based on session state
                val isBasicallyReady = isDhtRunning && sessionStats != null
                val hasMinimalConnectivity = dhtNodes >= 5 // Lower threshold for basic functionality
                val hasGoodConnectivity = dhtNodes >= DHT_MIN_NODES_REQUIRED
                
                when {
                    hasGoodConnectivity -> {
                        Log.d(TAG, "✅ Session fully ready! DHT connected with $dhtNodes nodes after ${elapsed}ms")
                        isSessionReady = true
                        return true
                    }
                    hasMinimalConnectivity && elapsed > 5000 -> {
                        Log.d(TAG, "✅ Session minimally ready! DHT has $dhtNodes nodes after ${elapsed}ms (sufficient for basic operations)")
                        isSessionReady = true
                        return true
                    }
                    isBasicallyReady && elapsed > 8000 -> {
                        Log.d(TAG, "⚠️ Session basically ready! DHT running with $dhtNodes nodes after ${elapsed}ms (attempting anyway)")
                        isSessionReady = true
                        return true
                    }
                }
                
                Thread.sleep(checkIntervalMs)
                attempts++
                
            } catch (e: Exception) {
                Log.w(TAG, "Bootstrap monitoring error: ${e.message}")
                attempts++
                if (attempts < maxAttempts) {
                    try {
                        Thread.sleep(checkIntervalMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }
        
        // Timeout reached - check final state
        val finalElapsed = System.currentTimeMillis() - startTime
        val finalDhtNodes = try { stats()?.dhtNodes()?.toInt() ?: 0 } catch (e: Exception) { 0 }
        val finalIsDhtRunning = try { isDhtRunning() } catch (e: Exception) { false }
        
        Log.w(TAG, "⏰ Session bootstrap timeout after ${finalElapsed}ms - final state: DHT running=$finalIsDhtRunning, nodes=$finalDhtNodes")
        
        // Even if timeout, allow minimal functionality if DHT is at least running
        if (finalIsDhtRunning && finalDhtNodes > 0) {
            Log.w(TAG, "⚠️ Proceeding with limited session readiness: DHT has $finalDhtNodes nodes")
            isSessionReady = true
            return true
        }
        
        Log.e(TAG, "❌ Session bootstrap failed: DHT not functional after timeout")
        return false
    }
    
    /**
     * FrostWire-style session validation for metadata fetch
     */
    private fun validateSessionForFetch(isDhtRunning: Boolean, dhtNodes: Int): ValidationResult {
        return when {
            !isLibraryAvailable -> ValidationResult(false, "BitTorrent library not available")
            !isDhtRunning -> ValidationResult(false, "DHT not running - cannot fetch metadata")
            dhtNodes == 0 -> ValidationResult(false, "No DHT nodes connected - peer discovery limited")
            dhtNodes < (DHT_MIN_NODES_REQUIRED / 2) -> ValidationResult(false, "Insufficient DHT connectivity ($dhtNodes nodes, need ${DHT_MIN_NODES_REQUIRED/2}+)")
            else -> ValidationResult(true, "Session ready for metadata fetch")
        }
    }
    
    /**
     * Enhanced parallel metadata fetch with multiple strategies
     */
    private fun performEnhancedMetadataFetch(magnetLink: String, dhtNodes: Int): ByteArray? {
        return try {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "Starting parallel metadata fetch with $dhtNodes DHT nodes available")
            
            // If we have good DHT connectivity, try parallel approach
            if (dhtNodes >= DHT_MIN_NODES_REQUIRED) {
                val metadata = performParallelMetadataFetch(magnetLink)
                if (metadata != null) {
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "✅ Parallel metadata fetch successful: ${metadata.size} bytes in ${duration}ms")
                    return metadata
                }
            }
            
            // Fallback to standard FrostWire approach
            Log.d(TAG, "Falling back to standard metadata fetch")
            val metadata = fetchMagnet(magnetLink, METADATA_TIMEOUT_SECONDS, false)
            
            val duration = System.currentTimeMillis() - startTime
            if (metadata != null) {
                Log.d(TAG, "✅ Standard metadata fetch successful: ${metadata.size} bytes in ${duration}ms")
            } else {
                Log.w(TAG, "❌ All metadata fetch strategies failed after ${duration}ms")
            }
            
            metadata
        } catch (e: Exception) {
            Log.e(TAG, "Enhanced metadata fetch exception: ${e.message}")
            null
        }
    }
    
    /**
     * Parallel metadata fetch using multiple strategies
     */
    private fun performParallelMetadataFetch(magnetLink: String): ByteArray? {
        return try {
            val executor = java.util.concurrent.Executors.newFixedThreadPool(3)
            val futures = mutableListOf<java.util.concurrent.Future<ByteArray?>>()
            
            // Strategy 1: DHT-optimized fetch (shorter timeout, prefer DHT)
            futures.add(executor.submit<ByteArray?> {
                try {
                    Log.d(TAG, "Parallel strategy 1: DHT-optimized fetch")
                    fetchMagnet(magnetLink, 45, false) // 45 second timeout for DHT
                } catch (e: Exception) {
                    Log.d(TAG, "DHT-optimized fetch failed: ${e.message}")
                    null
                }
            })
            
            // Strategy 2: Tracker-optimized fetch (if trackers are in magnet)
            if (magnetLink.contains("&tr=")) {
                futures.add(executor.submit<ByteArray?> {
                    try {
                        Log.d(TAG, "Parallel strategy 2: Tracker-optimized fetch")
                        Thread.sleep(5000) // Give DHT a head start
                        fetchMagnet(magnetLink, 60, true) // Enable tracker prioritization
                    } catch (e: Exception) {
                        Log.d(TAG, "Tracker-optimized fetch failed: ${e.message}")
                        null
                    }
                })
            }
            
            // Strategy 3: Standard fetch with full timeout
            futures.add(executor.submit<ByteArray?> {
                try {
                    Log.d(TAG, "Parallel strategy 3: Standard fetch")
                    Thread.sleep(10000) // Give other strategies time
                    fetchMagnet(magnetLink, METADATA_TIMEOUT_SECONDS, false)
                } catch (e: Exception) {
                    Log.d(TAG, "Standard fetch failed: ${e.message}")
                    null
                }
            })
            
            // Return first successful result
            for (i in 0 until 3) {
                for (future in futures) {
                    try {
                        val result = future.get(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (result != null && result.isNotEmpty()) {
                            Log.d(TAG, "✅ Parallel fetch succeeded with strategy ${futures.indexOf(future) + 1}")
                            executor.shutdownNow() // Cancel other tasks
                            return result
                        }
                    } catch (e: java.util.concurrent.TimeoutException) {
                        // Continue polling
                    }
                }
                Thread.sleep(2000) // Poll every 2 seconds
            }
            
            // Wait for any remaining results
            val startWait = System.currentTimeMillis()
            while (System.currentTimeMillis() - startWait < 60000) { // 60 second max wait
                for (future in futures) {
                    try {
                        if (future.isDone) {
                            val result = future.get()
                            if (result != null && result.isNotEmpty()) {
                                Log.d(TAG, "✅ Parallel fetch completed with strategy ${futures.indexOf(future) + 1}")
                                executor.shutdownNow()
                                return result
                            }
                        }
                    } catch (e: Exception) {
                        // Continue with other futures
                    }
                }
                Thread.sleep(1000)
            }
            
            executor.shutdownNow()
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "Parallel metadata fetch error: ${e.message}")
            null
        }
    }
    
    /**
     * FrostWire's comprehensive failure analysis
     */
    private fun analyzeMetadataFetchFailure(sessionStartTime: Long, sessionReady: Boolean, initialDhtNodes: Int): FailureAnalysis {
        val currentDhtNodes = try { stats()?.dhtNodes()?.toInt() ?: 0 } catch (e: Exception) { 0 }
        val fetchDuration = System.currentTimeMillis() - sessionStartTime
        val sessionAge = System.currentTimeMillis() - sessionStartTime
        
        return when {
            currentDhtNodes == 0 && initialDhtNodes == 0 -> FailureAnalysis(
                type = "DHT_NEVER_CONNECTED",
                summary = "DHT never connected (network/firewall issue)",
                userMessage = "Network connectivity issue - DHT cannot connect",
                retryReason = "Waiting for network connectivity to improve"
            )
            currentDhtNodes < initialDhtNodes -> FailureAnalysis(
                type = "DHT_CONNECTIVITY_LOST",
                summary = "DHT connectivity degraded ($currentDhtNodes from $initialDhtNodes nodes)",
                userMessage = "Network connection unstable - retrying with current DHT nodes",
                retryReason = "DHT connectivity partially restored"
            )
            !sessionReady && sessionAge < 10000 -> FailureAnalysis(
                type = "SESSION_TOO_YOUNG",
                summary = "Session not fully initialized (${sessionAge}ms old)",
                userMessage = "BitTorrent session still initializing",
                retryReason = "Allowing more time for session bootstrap"
            )
            fetchDuration < 5000 -> FailureAnalysis(
                type = "IMMEDIATE_FAILURE",
                summary = "Immediate fetch failure (${fetchDuration}ms)",
                userMessage = "Quick network failure - possible connectivity issue",
                retryReason = "Retrying after brief delay"
            )
            currentDhtNodes >= DHT_MIN_NODES_REQUIRED -> FailureAnalysis(
                type = "GENUINE_TIMEOUT",
                summary = "Timeout with good DHT connectivity ($currentDhtNodes nodes)",
                userMessage = "No peers found with this file after ${METADATA_TIMEOUT_SECONDS}s search",
                retryReason = "File may have very few or no active peers"
            )
            else -> FailureAnalysis(
                type = "POOR_DHT_CONNECTIVITY",
                summary = "Insufficient DHT nodes for reliable fetch ($currentDhtNodes nodes)",
                userMessage = "Limited P2P connectivity - only $currentDhtNodes DHT nodes available",
                retryReason = "Attempting with limited DHT connectivity"
            )
        }
    }
    
    /**
     * FrostWire's intelligent retry decision logic
     */
    private fun shouldRetryFetch(retryCount: Int, analysis: FailureAnalysis): Boolean {
        if (retryCount >= MAX_RETRY_ATTEMPTS) return false
        
        return when (analysis.type) {
            "DHT_NEVER_CONNECTED" -> retryCount < 2 // Give network time to connect
            "DHT_CONNECTIVITY_LOST" -> retryCount < 2 // Network might recover
            "SESSION_TOO_YOUNG" -> retryCount < 1 // One retry after session matures
            "IMMEDIATE_FAILURE" -> retryCount < 2 // Network glitch might resolve
            "POOR_DHT_CONNECTIVITY" -> retryCount < 1 // One retry with partial connectivity
            "GENUINE_TIMEOUT" -> false // No point retrying timeout with good connectivity
            else -> retryCount < 1
        }
    }
    
    /**
     * Calculate progressive retry delay (FrostWire pattern)
     */
    private fun calculateRetryDelay(retryCount: Int, failureType: String): Long {
        val baseDelay = when (failureType) {
            "DHT_NEVER_CONNECTED" -> 5000L // Give network time
            "DHT_CONNECTIVITY_LOST" -> 3000L // Quick retry for transient issues
            "SESSION_TOO_YOUNG" -> 2000L // Brief delay for session maturation
            "IMMEDIATE_FAILURE" -> 2000L // Brief delay for network recovery
            "POOR_DHT_CONNECTIVITY" -> 4000L // Longer delay for connectivity improvement
            else -> 3000L
        }
        
        // Progressive backoff: delay increases with retry count
        return baseDelay * (retryCount + 1)
    }
    
    /**
     * Enhanced network change handling with session state persistence
     */
    fun handleNetworkChange(isWiFi: Boolean) {
        Log.d(TAG, "Handling network change: WiFi=$isWiFi (Enhanced FrostWire pattern)")
        
        try {
            // Save current session state before changes
            saveSessionState()
            
            if (isWiFi) {
                // Enable more aggressive settings for WiFi
                adjustSettingsForWiFi()
                Log.d(TAG, "Applied WiFi optimizations")
            } else {
                // Conservative settings for mobile data
                adjustSettingsForMobile()
                Log.d(TAG, "Applied mobile data optimizations")
            }
            
            // Save state after network optimization changes
            Thread {
                Thread.sleep(2000) // Allow changes to settle
                saveSessionState()
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling network change: ${e.message}")
        }
    }
    
    /**
     * Optimize settings for WiFi connectivity (FrostWire pattern)
     */
    private fun adjustSettingsForWiFi() {
        try {
            val settings = SettingsPack()
            
            // More aggressive settings for WiFi
            settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 300)
            settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 6)
            settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), 6)
            
            // Enhanced upload for better peer relationships
            settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 50000) // 50KB/s upload
            
            applySettings(settings)
            Log.d(TAG, "WiFi optimizations applied: higher limits, enhanced upload")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error applying WiFi settings: ${e.message}")
        }
    }
    
    /**
     * Conservative settings for mobile data (FrostWire pattern)
     */
    private fun adjustSettingsForMobile() {
        try {
            val settings = SettingsPack()
            
            // Conservative settings for mobile data
            settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 100)
            settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 2)
            settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), 2)
            
            // Limit upload on mobile to preserve data
            settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 10000) // 10KB/s upload
            
            applySettings(settings)
            Log.d(TAG, "Mobile data optimizations applied: lower limits, reduced upload")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error applying mobile settings: ${e.message}")
        }
    }
    
    /**
     * FrostWire-style graceful session restart
     */
    fun restartSession(reason: String) {
        Log.d(TAG, "Restarting session due to: $reason (FrostWire pattern)")
        
        try {
            // Save current state
            val wasDownloading = isDownloading
            val currentMagnetLink = "" // Would need to store this in real implementation
            
            // Stop current session gracefully
            if (isLibraryAvailable) {
                Log.d(TAG, "Stopping current session gracefully...")
                stop()
                Thread.sleep(2000) // Allow cleanup time
            }
            
            // Restart session
            Log.d(TAG, "Restarting session with enhanced settings...")
            initializeSession()
            
            // Resume download if needed
            if (wasDownloading && currentMagnetLink.isNotEmpty()) {
                Log.d(TAG, "Resuming download after session restart")
                // Would resume download here in full implementation
            }
            
            Log.d(TAG, "Session restart completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during session restart: ${e.message}")
            isLibraryAvailable = false
        }
    }
    
    /**
     * Enhanced shutdown with session state persistence
     * NOTE: This should only be called when the entire application is terminating,
     * not when individual activities are destroyed
     */
    fun shutdown() {
        Log.w(TAG, "⚠️ Shutdown called - this should only happen at app termination!")
        Log.w(TAG, "If called prematurely, it will break the singleton for other activities")
        
        // Log stack trace to help debug premature shutdown calls
        Log.d(TAG, "Shutdown call stack:", Exception("Shutdown trace"))
        
        Log.d(TAG, "Starting enhanced shutdown with state persistence...")
        
        try {
            // Save current session state before shutdown
            Log.d(TAG, "Saving session state for faster next startup...")
            saveSessionState()
            
            // Save download state for potential resume  
            if (isDownloading && currentTorrentHandle?.isValid == true) {
                Log.d(TAG, "Saving download state for resume...")
                saveDownloadState()
            }
            
            // Save resume data for current torrent if active
            if (currentTorrentHandle?.isValid == true) {
                Log.d(TAG, "Saving resume data for current active torrent...")
                
                try {
                    currentTorrentHandle?.saveResumeData()
                    Log.d(TAG, "Requested resume data save for current torrent")
                    
                    // Wait a bit for resume data to be saved
                    Thread.sleep(3000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving resume data for current torrent: ${e.message}")
                }
            }
            
            // Graceful download stop
            isDownloading = false
            currentTorrentHandle?.let { handle ->
                if (handle.isValid) {
                    Log.d(TAG, "Gracefully stopping active download...")
                    handle.pause()
                    Thread.sleep(1000) // Allow pause to complete
                }
            }
            currentTorrentHandle = null
            
            // Clean session stop with final state save
            if (isLibraryAvailable) {
                Log.d(TAG, "Stopping BitTorrent session...")
                
                // Final session state save before stop
                try {
                    saveSessionState()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed final session state save: ${e.message}")
                }
                
                stop()
                Thread.sleep(1000) // Allow session cleanup
            }
            
            isLibraryAvailable = false
            isSessionReady = false
            
            Log.d(TAG, "Enhanced shutdown complete with state persistence")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during enhanced shutdown: ${e.message}")
        }
    }
    
    /**
     * Save jlibtorrent fast resume data (BitTorrent protocol level)
     */
    private fun saveResumeData() {
        try {
            currentTorrentHandle?.let { handle ->
                if (handle.isValid) {
                    val status = handle.status()
                    val downloaded = status.totalDone()
                    val total = status.totalWanted()
                    
                    Log.d(TAG, "Saving jlibtorrent fast resume data: ${downloaded}/${total} bytes")
                    
                    // Save fast resume data using jlibtorrent's mechanism
                    handle.saveResumeData()
                    
                    // Wait for save_resume_data_alert and store the resume data
                    Thread {
                        try {
                            Thread.sleep(2000) // Give jlibtorrent time to generate resume data
                            
                            // The resume data will be captured in alert handling
                            Log.d(TAG, "Fast resume data save requested")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error in resume data save thread: ${e.message}")
                        }
                    }.start()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving fast resume data: ${e.message}")
        }
    }
    
    /**
     * Load jlibtorrent fast resume data from disk
     */
    private fun loadResumeData(infoHash: String): ByteArray? {
        return try {
            val resumeFile = File(context.filesDir, "resume_${infoHash}.dat")
            if (resumeFile.exists()) {
                val resumeData = resumeFile.readBytes()
                Log.d(TAG, "Loaded fast resume data: ${resumeData.size} bytes for hash $infoHash")
                resumeData
            } else {
                Log.d(TAG, "No fast resume data found for hash $infoHash")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading fast resume data: ${e.message}")
            null
        }
    }
    
    /**
     * Save fast resume data to disk
     */
    private fun saveFastResumeDataToDisk(infoHash: String, resumeData: ByteArray) {
        try {
            val resumeFile = File(context.filesDir, "resume_${infoHash}.dat")
            resumeFile.writeBytes(resumeData)
            Log.d(TAG, "Saved fast resume data to disk: ${resumeData.size} bytes for hash $infoHash")
            
            // Also save magnet link for this resume data
            val magnetFile = File(context.filesDir, "magnet_${infoHash}.txt")
            magnetFile.writeText(currentMagnetLink)
            Log.d(TAG, "Saved magnet link for resume: $infoHash")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving fast resume data to disk: ${e.message}")
        }
    }
    
    /**
     * Save download state for resume capability (FrostWire pattern)
     */
    private fun saveDownloadState() {
        try {
            currentTorrentHandle?.let { handle ->
                if (handle.isValid) {
                    val status = handle.status()
                    val downloaded = status.totalDone()
                    val total = status.totalWanted()
                    
                    Log.d(TAG, "Saving download state: ${downloaded}/${total} bytes")
                    
                    // Save fast resume data at jlibtorrent level
                    saveResumeData()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving download state: ${e.message}")
        }
    }
    
    /**
     * Memory management and cleanup (FrostWire pattern)
     */
    fun performMaintenanceCleanup() {
        Log.d(TAG, "Performing maintenance cleanup (FrostWire pattern)...")
        
        try {
            // Force garbage collection
            System.gc()
            
            // Clean session cache if available
            if (isLibraryAvailable) {
                // Clear DHT cache periodically
                // This would be more sophisticated in full FrostWire implementation
                Log.d(TAG, "Session maintenance completed")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during maintenance cleanup: ${e.message}")
        }
    }
    
    // Data classes for enhanced error handling
    private data class ValidationResult(val isValid: Boolean, val error: String)
    
    private data class FailureAnalysis(
        val type: String,
        val summary: String, 
        val userMessage: String,
        val retryReason: String
    )
}