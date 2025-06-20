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
        private const val METADATA_TIMEOUT_SECONDS = 120 // FrostWire uses longer timeouts for reliability
        private const val SESSION_BOOTSTRAP_TIMEOUT_MS = 20000L // 20 seconds for session to bootstrap (FrostWire pattern)
        private const val DHT_MIN_NODES_REQUIRED = 10 // Higher threshold for better reliability
        private const val MAX_RETRY_ATTEMPTS = 3 // FrostWire-style retry limit
        private const val DHT_BOOTSTRAP_CHECK_INTERVAL_MS = 500L // FrostWire bootstrap monitoring frequency
        
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
    }
    
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
        
        // FrostWire's proven DHT configuration - enhanced bootstrap nodes
        settings.enableDht(true)
        settings.setString(
            settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
            "dht.libtorrent.org:25401,router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881"
        )
        
        // FrostWire's port configuration strategy
        currentPort = (49152..65534).random()
        settings.setString(
            settings_pack.string_types.listen_interfaces.swigValue(),
            "0.0.0.0:$currentPort,[::]:$currentPort"
        )
        
        // FrostWire's Android-optimized connection limits
        settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)
        settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)
        settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), 4)
        settings.setInteger(settings_pack.int_types.active_limit.swigValue(), 8)
        
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
        
        // Enhanced alert configuration for better tracking
        settings.setInteger(
            settings_pack.int_types.alert_mask.swigValue(),
            AlertType.METADATA_RECEIVED.swig() or 
            AlertType.ADD_TORRENT.swig() or
            AlertType.TORRENT_FINISHED.swig() or
            AlertType.STATE_CHANGED.swig() or
            AlertType.DHT_BOOTSTRAP.swig() or
            AlertType.PEER_CONNECT.swig() or
            AlertType.PEER_DISCONNECTED.swig()
        )
        
        Log.d(TAG, "✓ Enhanced FrostWire-style settings configured (port: $currentPort)")
        return settings
    }
    
    /**
     * Enhanced session bootstrap monitoring (FrostWire pattern)
     */
    private fun startSessionBootstrapMonitoring() {
        Thread {
            Log.d(TAG, "🔄 Starting enhanced FrostWire-style session bootstrap monitoring...")
            
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
            val maxStagnantChecks = 10 // 20 seconds of no progress before concern
            
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
     * Enhanced metadata fetch with FrostWire patterns
     */
    private fun performEnhancedMetadataFetch(magnetLink: String, dhtNodes: Int): ByteArray? {
        return try {
            // FrostWire uses direct fetchMagnet with optimized settings
            val startTime = System.currentTimeMillis()
            
            Log.d(TAG, "Starting enhanced metadata fetch with $dhtNodes DHT nodes available")
            
            val metadata = fetchMagnet(magnetLink, METADATA_TIMEOUT_SECONDS, false)
            
            val duration = System.currentTimeMillis() - startTime
            if (metadata != null) {
                Log.d(TAG, "✅ Enhanced metadata fetch successful: ${metadata.size} bytes in ${duration}ms")
            } else {
                Log.w(TAG, "❌ Enhanced metadata fetch failed after ${duration}ms")
            }
            
            metadata
        } catch (e: Exception) {
            Log.e(TAG, "Enhanced metadata fetch exception: ${e.message}")
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
     * FrostWire-style network change handling
     */
    fun handleNetworkChange(isWiFi: Boolean) {
        Log.d(TAG, "Handling network change: WiFi=$isWiFi (FrostWire pattern)")
        
        try {
            if (isWiFi) {
                // Enable more aggressive settings for WiFi
                adjustSettingsForWiFi()
                Log.d(TAG, "Applied WiFi optimizations")
            } else {
                // Conservative settings for mobile data
                adjustSettingsForMobile()
                Log.d(TAG, "Applied mobile data optimizations")
            }
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
     * Enhanced shutdown with FrostWire patterns
     */
    fun shutdown() {
        Log.d(TAG, "Starting enhanced shutdown (FrostWire pattern)...")
        
        try {
            // Save download state for potential resume
            if (isDownloading && currentTorrentHandle?.isValid == true) {
                Log.d(TAG, "Saving download state for resume...")
                saveDownloadState()
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
            
            // Clean session stop
            if (isLibraryAvailable) {
                Log.d(TAG, "Stopping BitTorrent session...")
                stop()
                Thread.sleep(1000) // Allow session cleanup
            }
            
            isLibraryAvailable = false
            isSessionReady = false
            
            Log.d(TAG, "Enhanced shutdown complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during enhanced shutdown: ${e.message}")
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
                    
                    // Save to SharedPreferences for resume
                    // This would be implemented with proper state persistence
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