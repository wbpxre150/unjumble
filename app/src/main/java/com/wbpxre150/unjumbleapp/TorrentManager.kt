package com.wbpxre150.unjumbleapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import org.libtorrent4j.swig.*
import java.io.FileInputStream
import java.io.FileOutputStream

class TorrentManager private constructor(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var downloadListener: TorrentDownloadListener? = null
    private var isDownloading = false
    private var timeoutRunnable: Runnable? = null
    private var isSeeding = false
    private var isLibraryAvailable = false
    private var sessionManager: SessionManager? = null
    private var currentTorrentHandle: TorrentHandle? = null
    private var progressMonitorThread: Thread? = null
    private var currentDownloadPath: String = ""
    private var isVerifying = false
    private var verificationStartTime: Long = 0
    private val VERIFICATION_TIMEOUT_MS = 120000L // 2 minutes timeout for verification
    private val networkManager = NetworkManager.getInstance(context)
    private var isNetworkTransitioning = false
    private var networkTransitionStartTime: Long = 0
    private val NETWORK_TRANSITION_TIMEOUT_MS = 15000L // 15 seconds for network transitions
    private var lastProgressTime: Long = 0
    private var lastBytesDownloaded: Long = 0
    private var downloadPhase: DownloadPhase = DownloadPhase.METADATA_FETCHING
    private var metadataStartTime: Long = 0
    private var lastResumeDataSave: Long = 0
    private val RESUME_DATA_SAVE_INTERVAL_MS = 30000L // Save resume data every 30 seconds
    private var pendingResumeDataSave = false
    private var currentPort: Int = 0
    private var waitingForMetadata = false
    private var currentMagnetLink: String = ""
    
    companion object {
        private const val TAG = "TorrentManager"
        private const val NO_PROGRESS_TIMEOUT_MS = 240000L // 4 minutes with no download progress before timeout
        private const val METADATA_TIMEOUT_MS = 60000L // 60 seconds for metadata fetching
        private const val RESUME_PREFS = "torrent_resume"
        
        @Volatile
        private var INSTANCE: TorrentManager? = null
        
        fun getInstance(context: Context): TorrentManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorrentManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    init {
        // Initialize LibTorrent4j immediately when TorrentManager singleton is created
        initializeSession()
        // Start network monitoring to handle dynamic seeding control
        startNetworkMonitoring()
    }
    
    private fun initializeSession() {
        try {
            // Try to load libtorrent4j
            System.loadLibrary("torrent4j")
            
            // Initialize session manager with peer discovery settings
            sessionManager = SessionManager()
            
            
            // Configure session for optimal peer connectivity
            val settingsPack = SettingsPack()
            
            // Enable all peer discovery methods
            settingsPack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            settingsPack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true) // Local Service Discovery
            settingsPack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            settingsPack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            
            // Optimize peer connection settings for better stability
            settingsPack.setInteger(settings_pack.int_types.connections_limit.swigValue(), 100) // Increase max connections
            settingsPack.setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), 3000) // Larger peer list
            settingsPack.setInteger(settings_pack.int_types.max_paused_peerlist_size.swigValue(), 1000)
            settingsPack.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), 60) // 60 second reconnect delay
            settingsPack.setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 15) // 15 second connect timeout
            settingsPack.setInteger(settings_pack.int_types.listen_queue_size.swigValue(), 100) // Increased queue size
            
            // Peer exchange and connection persistence
            settingsPack.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
            settingsPack.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
            settingsPack.setBoolean(settings_pack.bool_types.prefer_udp_trackers.swigValue(), true)
            
            // Dynamic port allocation - try multiple ports for better connectivity
            var portBound = false
            currentPort = 6881
            
            for (port in 6881..6891) {
                try {
                    settingsPack.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:$port,[::]:$port")
                    sessionManager?.applySettings(settingsPack)
                    sessionManager?.start()
                    
                    // Test if port binding worked
                    Thread.sleep(1000)
                    portBound = true
                    currentPort = port
                    Log.d(TAG, "Successfully bound to port $port")
                    break
                } catch (e: Exception) {
                    Log.d(TAG, "Port $port failed, trying next: ${e.message}")
                    sessionManager?.stop()
                    sessionManager = SessionManager()
                }
            }
            
            if (!portBound) {
                // Fall back to random port if all standard ports fail
                settingsPack.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:0,[::]:0")
                sessionManager?.applySettings(settingsPack)
                sessionManager?.start()
                currentPort = 0 // 0 means random port
                Log.d(TAG, "All standard ports failed, using random port")
            }
            
            isLibraryAvailable = true
            Log.d(TAG, "LibTorrent4j session initialized successfully on port $currentPort")
            Log.d(TAG, "Peer settings: max_connections=100, max_peerlist=3000, reconnect_time=60s")
            
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "LibTorrent4j native library not available: ${e.message}")
            isLibraryAvailable = false
            currentPort = 6881 // Set default port even when library is not available
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize LibTorrent session: ${e.message}")
            isLibraryAvailable = false
            currentPort = 6881 // Set default port even when initialization fails
        }
    }
    
    
    
    fun downloadFile(magnetLink: String, downloadPath: String, listener: TorrentDownloadListener) {
        if (isDownloading) {
            listener.onError("Download already in progress")
            return
        }
        
        downloadListener = listener
        isDownloading = true
        currentDownloadPath = downloadPath
        
        // Initialize tracking variables
        lastProgressTime = System.currentTimeMillis()
        lastBytesDownloaded = 0
        metadataStartTime = System.currentTimeMillis()
        lastResumeDataSave = System.currentTimeMillis()
        downloadPhase = DownloadPhase.METADATA_FETCHING
        pendingResumeDataSave = false
        
        // Check for existing resume data first
        if (hasResumeData(magnetLink, downloadPath)) {
            Log.d(TAG, "Found resume data - attempting to resume download")
            resumeDownload(magnetLink, downloadPath, listener)
            return
        }
        
        // Store magnet link for resume data
        val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("magnet_link", magnetLink).apply()
        
        // Notify initial phase
        handler.post {
            listener.onPhaseChanged(DownloadPhase.METADATA_FETCHING, 60)
        }
        
        // LibTorrent4j should already be initialized in init block
        continueDownloadAfterInit(magnetLink, downloadPath, listener)
    }
    
    private fun hasResumeData(magnetLink: String, downloadPath: String): Boolean {
        val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
        val downloadFile = File(downloadPath)
        
        // Check if we have progress metadata and the partial download file exists
        return prefs.getBoolean("has_resume_data", false) &&
               prefs.getString("magnet_link", "") == magnetLink &&
               prefs.getString("download_path", "") == downloadPath &&
               downloadFile.exists() &&
               downloadFile.length() > 0
    }
    
    private fun resumeDownload(magnetLink: String, downloadPath: String, listener: TorrentDownloadListener) {
        if (!isLibraryAvailable || sessionManager == null) {
            Log.d(TAG, "LibTorrent not available - falling back to fresh download")
            continueDownloadAfterInit(magnetLink, downloadPath, listener)
            return
        }
        
        try {
            val downloadFile = File(downloadPath)
            if (!downloadFile.exists() || downloadFile.length() == 0L) {
                Log.w(TAG, "Resume: No partial download file found - starting fresh")
                clearResumeData()
                continueDownloadAfterInit(magnetLink, downloadPath, listener)
                return
            }
            
            val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            val savedSize = prefs.getLong("downloaded_size", 0L)
            val fileSize = downloadFile.length()
            
            Log.d(TAG, "Resume: Found partial file ${fileSize} bytes, saved progress was ${savedSize} bytes")
            
            // Use existing fetchMagnet approach for resume
            val downloadDir = File(downloadPath).parentFile
            Log.d(TAG, "Resume: Fetching magnet metadata for resume...")
            
            Thread {
                try {
                    handler.post {
                        listener.onDhtConnecting()
                    }
                    
                    Thread.sleep(1000)
                    
                    handler.post {
                        listener.onDhtConnected(0)
                        listener.onMetadataFetching()
                    }
                    
                    val data = sessionManager?.fetchMagnet(magnetLink, 30, downloadDir ?: File(currentDownloadPath))
                    
                    if (data != null && isDownloading) {
                        val torrentInfo = TorrentInfo.bdecode(data)
                        Log.d(TAG, "Resume: Magnet metadata downloaded for resume")
                        
                        handler.post {
                            listener.onMetadataComplete()
                        }
                        
                        // Add torrent to session - LibTorrent will auto-detect existing partial file
                        sessionManager?.download(torrentInfo, downloadDir ?: File(currentDownloadPath))
                        currentTorrentHandle = sessionManager?.find(torrentInfo.infoHash())
                        
                        if (currentTorrentHandle?.isValid == true) {
                            Log.d(TAG, "Resume: Torrent added, LibTorrent will scan existing file...")
                            transitionToPhase(DownloadPhase.ACTIVE_DOWNLOADING, listener)
                            startRealProgressMonitoring()
                        } else {
                            Log.w(TAG, "Resume: Failed to add torrent for resume - starting fresh")
                            clearResumeData()
                            continueDownloadAfterInit(magnetLink, downloadPath, listener)
                        }
                    } else if (isDownloading) {
                        Log.w(TAG, "Resume: Failed to fetch magnet metadata for resume - starting fresh")
                        clearResumeData()
                        continueDownloadAfterInit(magnetLink, downloadPath, listener)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Resume: Error during resume attempt", e)
                    if (isDownloading) {
                        clearResumeData()
                        continueDownloadAfterInit(magnetLink, downloadPath, listener)
                    }
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Resume: Error during resume attempt - starting fresh download", e)
            clearResumeData()
            continueDownloadAfterInit(magnetLink, downloadPath, listener)
        }
    }
    
    private fun saveResumeData() {
        if (!isDownloading || currentTorrentHandle == null || !currentTorrentHandle!!.isValid) {
            return
        }
        
        if (pendingResumeDataSave) {
            Log.d(TAG, "Resume data save already pending")
            return
        }
        
        try {
            pendingResumeDataSave = true
            Log.d(TAG, "Saving torrent progress metadata...")
            
            // Instead of complex resume data, save current progress metadata
            // This will help us know if we should attempt resume
            val status = currentTorrentHandle!!.status()
            val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            
            prefs.edit().apply {
                putBoolean("has_resume_data", true)
                putString("download_path", currentDownloadPath)
                putLong("total_size", status.totalWanted())
                putLong("downloaded_size", status.totalWantedDone())
                putLong("last_save_time", System.currentTimeMillis())
                if (currentTorrentHandle?.isValid == true) {
                    putString("info_hash", currentTorrentHandle!!.infoHash().toString())
                }
                apply()
            }
            
            lastResumeDataSave = System.currentTimeMillis()
            
            val downloadedMB = status.totalWantedDone() / (1024 * 1024)
            val totalMB = status.totalWanted() / (1024 * 1024)
            val progress = if (status.totalWanted() > 0) {
                (status.totalWantedDone().toFloat() / status.totalWanted() * 100).toInt()
            } else 0
            
            Log.d(TAG, "Progress metadata saved: $progress% complete (${downloadedMB}MB/${totalMB}MB)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving progress metadata", e)
        } finally {
            pendingResumeDataSave = false
        }
    }
    
    // This method is no longer needed with the simplified approach
    // LibTorrent will auto-resume based on existing partial files
    
    private fun clearResumeData() {
        try {
            val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            
            Log.d(TAG, "Resume progress metadata cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing resume data", e)
        }
    }
    
    private fun continueDownloadAfterInit(magnetLink: String, downloadPath: String, listener: TorrentDownloadListener) {
        if (!isLibraryAvailable || sessionManager == null) {
            Log.d(TAG, "LibTorrent not available - calling onError to trigger fallback")
            isDownloading = false
            listener.onError("P2P library not available")
            return
        }
        
        try {
            val downloadDir = File(downloadPath).parentFile
            if (downloadDir != null && !downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            Log.d(TAG, "Starting improved non-blocking P2P download for: $magnetLink")
            currentMagnetLink = magnetLink
            
            // Use a separate thread for fetchMagnet but provide immediate feedback
            Thread {
                try {
                    Log.d(TAG, "Starting DHT connection...")
                    handler.post {
                        listener.onDhtConnecting()
                    }
                    
                    // Give DHT a moment to initialize
                    Thread.sleep(1000)
                    
                    handler.post {
                        listener.onDhtConnected(0)
                        listener.onMetadataFetching()
                    }
                    
                    // Simulate progress updates during metadata fetching
                    for (i in 1..10) {
                        if (!isDownloading) break
                        Thread.sleep(2000)
                        handler.post {
                            listener.onProgress(0, 0, 0, i) // Show increasing peer count simulation
                        }
                    }
                    
                    Log.d(TAG, "Starting metadata fetch with timeout...")
                    val data = sessionManager?.fetchMagnet(magnetLink, 30, downloadDir ?: File(currentDownloadPath))
                    
                    if (data != null && isDownloading) {
                        val torrentInfo = TorrentInfo.bdecode(data)
                        Log.d(TAG, "Magnet metadata downloaded successfully")
                        
                        handler.post {
                            listener.onMetadataComplete()
                        }
                        
                        // Add torrent to session
                        sessionManager?.download(torrentInfo, downloadDir ?: File(currentDownloadPath))
                        currentTorrentHandle = sessionManager?.find(torrentInfo.infoHash())
                        
                        if (currentTorrentHandle?.isValid == true) {
                            Log.d(TAG, "Torrent added to session successfully")
                            transitionToPhase(DownloadPhase.PEER_DISCOVERY, listener)
                            
                            handler.post {
                                listener.onDiscoveringPeers()
                            }
                            
                            // Start monitoring
                            startMetadataAndPeerMonitoring(listener)
                        } else {
                            handler.post {
                                listener.onError("Failed to add torrent to session")
                            }
                            isDownloading = false
                        }
                    } else if (isDownloading) {
                        Log.w(TAG, "Failed to fetch magnet metadata")
                        handler.post {
                            listener.onTimeout()
                        }
                        isDownloading = false
                    }
                    
                } catch (e: InterruptedException) {
                    Log.d(TAG, "Magnet fetch interrupted")
                    if (isDownloading) {
                        handler.post {
                            listener.onTimeout()
                        }
                        isDownloading = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed during magnet metadata fetch", e)
                    if (isDownloading) {
                        handler.post {
                            listener.onError("Failed to start P2P download: ${e.message}")
                        }
                        isDownloading = false
                    }
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start P2P download", e)
            listener.onError("Failed to start P2P download: ${e.message}")
            isDownloading = false
        }
    }
    
    private fun startMetadataAndPeerMonitoring(listener: TorrentDownloadListener) {
        progressMonitorThread = Thread {
            var hasMetadata = false
            var seedsReported = false
            var maxPeersObserved = 0
            var peerDropWarningTime = 0L
            
            while (isDownloading && currentTorrentHandle != null && currentTorrentHandle!!.isValid) {
                try {
                    val status = currentTorrentHandle!!.status()
                    val numPeers = status.numPeers()
                    val numSeeds = try { status.numSeeds() } catch (e: Exception) { 0 }
                    val hasMetadataFlag = status.hasMetadata()
                    val currentTime = System.currentTimeMillis()
                    
                    // Track maximum peers observed for connection stability analysis
                    if (numPeers > maxPeersObserved) {
                        maxPeersObserved = numPeers
                        Log.d(TAG, "[PEER TRACKING] New peak peer count: $maxPeersObserved")
                    }
                    
                    // Warn about significant peer drops (the main issue we're fixing)
                    if (maxPeersObserved >= 2 && numPeers < maxPeersObserved && currentTime - peerDropWarningTime > 30000) {
                        Log.w(TAG, "[PEER DROP DETECTED] Peers dropped from $maxPeersObserved to $numPeers - investigating cause")
                        Log.w(TAG, "[PEER DROP] Network transitioning: $isNetworkTransitioning, Session valid: ${sessionManager != null}")
                        peerDropWarningTime = currentTime
                    }
                    
                    Log.d(TAG, "[$downloadPhase] Status: peers=$numPeers, seeds=$numSeeds, hasMetadata=$hasMetadataFlag, state=${status.state()}, downloadRate=${status.downloadRate()}")
                    
                    // Report seeds found if we have them and haven't reported yet
                    if (!seedsReported && (numSeeds > 0 || numPeers > 0) && isDownloading) {
                        seedsReported = true
                        Log.d(TAG, "[PEER DISCOVERY] Found peers/seeds: $numSeeds seeds, $numPeers total peers")
                        Log.d(TAG, "[PEER DISCOVERY] Connection established successfully - monitoring for stability")
                        handler.post {
                            listener.onSeedsFound(numSeeds, numPeers)
                        }
                    }
                    
                    // Check for metadata timeout
                    if (checkTimeouts(0, listener)) {
                        break
                    }
                    
                    // Check if we got metadata
                    if (!hasMetadata && hasMetadataFlag && isDownloading) {
                        hasMetadata = true
                        Log.d(TAG, "Metadata received! Starting download monitoring...")
                        handler.post {
                            listener.onProgress(0, status.totalWanted(), 0, numPeers)
                        }
                    }
                    
                    // If we have metadata, switch to full progress monitoring
                    if (hasMetadata) {
                        Log.d(TAG, "Metadata acquired, total size: ${status.totalWanted()} bytes")
                        transitionToPhase(DownloadPhase.ACTIVE_DOWNLOADING, downloadListener!!)
                        startRealProgressMonitoring()
                        break
                    }
                    
                    // Update progress even without metadata to show peer discovery
                    if (isDownloading) {
                        handler.post {
                            // Use 0 total to indicate we're still fetching metadata
                            listener.onProgress(0, 0, 0, numPeers)
                        }
                    }
                    
                    Thread.sleep(2000) // Check every 2 seconds for metadata/peers
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring metadata/peers", e)
                    if (isDownloading) {
                        handler.post {
                            listener.onError("Metadata monitoring error: ${e.message}")
                        }
                    }
                    break
                }
            }
        }
        progressMonitorThread?.start()
    }
    
    private fun startVerification(listener: TorrentDownloadListener) {
        currentTorrentHandle?.let { handle ->
            if (!handle.isValid) {
                listener.onError("Invalid torrent handle for verification")
                return
            }
            
            isVerifying = true
            verificationStartTime = System.currentTimeMillis()
            transitionToPhase(DownloadPhase.VERIFICATION, listener)
            Log.d(TAG, "Starting torrent verification...")
            
            // Force verification of pieces
            handle.forceRecheck()
            
            // Start verification monitoring
            progressMonitorThread = Thread {
                while (isVerifying && handle.isValid) {
                    try {
                        val status = handle.status()
                        val verifyProgress = status.progress()
                        val state = status.state()
                        val currentTime = System.currentTimeMillis()
                        
                        Log.d(TAG, "Verification progress: ${(verifyProgress * 100).toInt()}%, state: $state")
                        
                        // Check verification timeout
                        if (currentTime - verificationStartTime > VERIFICATION_TIMEOUT_MS) {
                            Log.w(TAG, "Verification timeout after ${VERIFICATION_TIMEOUT_MS}ms - forcing completion")
                            isVerifying = false
                            handler.post {
                                listener.onProgress(0, 0, 0, 0)
                            }
                            break
                        }
                        
                        // Update verification progress
                        handler.post {
                            listener.onVerifying(verifyProgress)
                        }
                        
                        // Check if verification is complete
                        if (state == TorrentStatus.State.DOWNLOADING || state == TorrentStatus.State.FINISHED) {
                            isVerifying = false
                            Log.d(TAG, "Verification completed, starting download monitoring...")
                            
                            // Start normal download monitoring
                            startMetadataAndPeerMonitoring(listener)
                            break
                        }
                        
                        Thread.sleep(1000) // Check every second during verification
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during verification", e)
                        isVerifying = false
                        handler.post {
                            listener.onError("Verification error: ${e.message}")
                        }
                        break
                    }
                }
            }
            progressMonitorThread?.start()
        } ?: run {
            listener.onError("No torrent handle available for verification")
        }
    }
    
    private fun startVerificationForSeeding(listener: TorrentDownloadListener? = null) {
        currentTorrentHandle?.let { handle ->
            if (!handle.isValid) {
                Log.e(TAG, "Invalid torrent handle for seeding verification")
                return
            }
            
            isVerifying = true
            verificationStartTime = System.currentTimeMillis()
            transitionToPhase(DownloadPhase.VERIFICATION, null)
            Log.d(TAG, "Starting torrent verification for seeding...")
            
            // Force verification of pieces
            handle.forceRecheck()
            
            // Start verification monitoring for seeding
            Thread {
                while (isVerifying && handle.isValid) {
                    try {
                        val status = handle.status()
                        val verifyProgress = status.progress()
                        val state = status.state()
                        val currentTime = System.currentTimeMillis()
                        
                        Log.d(TAG, "Seeding verification progress: ${(verifyProgress * 100).toInt()}%, state: $state")
                        
                        // Check verification timeout for seeding
                        if (currentTime - verificationStartTime > VERIFICATION_TIMEOUT_MS) {
                            Log.w(TAG, "Seeding verification timeout after ${VERIFICATION_TIMEOUT_MS}ms - assuming complete")
                            isVerifying = false
                            isSeeding = true
                            break
                        }
                        
                        // Update verification progress if listener is available
                        listener?.let {
                            handler.post {
                                it.onVerifying(verifyProgress)
                            }
                        }
                        
                        // Check if verification is complete
                        if (state == TorrentStatus.State.SEEDING || state == TorrentStatus.State.FINISHED) {
                            isVerifying = false
                            isSeeding = true
                            Log.d(TAG, "Verification for seeding completed - now seeding")
                            break
                        } else if (state == TorrentStatus.State.DOWNLOADING) {
                            isVerifying = false
                            Log.d(TAG, "Verification completed but file incomplete - starting download to complete")
                            break
                        }
                        
                        Thread.sleep(1000) // Check every second during verification
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during seeding verification", e)
                        isVerifying = false
                        break
                    }
                }
            }.start()
        } ?: run {
            Log.e(TAG, "No torrent handle available for seeding verification")
        }
    }
    
    private fun startRealProgressMonitoring() {
        // Stop the metadata monitoring thread first
        progressMonitorThread?.interrupt()
        
        progressMonitorThread = Thread {
            var lastPeerLogTime = 0L
            while (isDownloading && currentTorrentHandle != null && currentTorrentHandle!!.isValid) {
                try {
                    val status = currentTorrentHandle!!.status()
                    val totalWanted = status.totalWanted()
                    val totalWantedDone = status.totalWantedDone()
                    val downloadRate = status.downloadRate()
                    val numPeers = status.numPeers()
                    val numSeeds = try { status.numSeeds() } catch (e: Exception) { 0 }
                    val progressPercent = if (totalWanted > 0) (totalWantedDone.toFloat() / totalWanted * 100).toInt() else 0
                    val currentTime = System.currentTimeMillis()
                    
                    // Enhanced peer connection logging every 10 seconds
                    if (currentTime - lastPeerLogTime > 10000) {
                        Log.d(TAG, "[PEER STATUS] Seeds: $numSeeds, Total Peers: $numPeers, Download Rate: ${downloadRate}B/s")
                        Log.d(TAG, "[PEER STATUS] State: ${status.state()}, Progress: $progressPercent%")
                        
                        // Log peer connection details
                        if (numPeers > 0) {
                            Log.d(TAG, "[PEER STABILITY] Maintaining $numPeers peer connections successfully")
                        } else {
                            Log.w(TAG, "[PEER WARNING] No peer connections - attempting reconnection")
                        }
                        lastPeerLogTime = currentTime
                    }
                    
                    Log.d(TAG, "[$downloadPhase] Progress: ${totalWantedDone}/${totalWanted} bytes ($progressPercent%), rate=${downloadRate}B/s, peers=$numPeers, seeds=$numSeeds")
                    
                    // Additional debug info for timeouts
                    when (downloadPhase) {
                        DownloadPhase.METADATA_FETCHING -> {
                            val metadataTime = System.currentTimeMillis() - metadataStartTime
                            val remaining = (METADATA_TIMEOUT_MS - metadataTime) / 1000
                            if (metadataTime > 20000) { // Log after 20 seconds
                                Log.d(TAG, "  Metadata fetching for ${metadataTime / 1000}s, timeout in ${remaining}s")
                            }
                        }
                        DownloadPhase.ACTIVE_DOWNLOADING -> {
                            val noProgressTime = System.currentTimeMillis() - lastProgressTime
                            val remaining = (NO_PROGRESS_TIMEOUT_MS - noProgressTime) / 1000
                            if (noProgressTime > 30000) { // Only log if no progress for >30s
                                Log.d(TAG, "  No progress for ${noProgressTime / 1000}s, timeout in ${remaining}s")
                            }
                        }
                        else -> { /* No timeout logging for other phases */ }
                    }
                    
                    // Check for download progress timeout only
                    if (checkTimeouts(totalWantedDone, downloadListener!!)) {
                        break
                    }
                    
                    // Enhanced completion detection
                    val state = status.state()
                    val isComplete = status.isFinished || 
                                   (totalWanted > 0 && totalWantedDone >= totalWanted) ||
                                   state == TorrentStatus.State.SEEDING ||
                                   (state == TorrentStatus.State.CHECKING_FILES && status.progress() >= 1.0f)
                    
                    Log.d(TAG, "Completion check: isFinished=${status.isFinished}, progress=${status.progress()}, state=$state, totalDone/Wanted=$totalWantedDone/$totalWanted")
                    
                    if (isComplete && isDownloading) {
                        Log.d(TAG, "Download completed - state: $state, progress: ${status.progress()}")
                        
                        // Clear resume data since download is complete
                        clearResumeData()
                        Log.d(TAG, "Resume data cleared after completion")
                        
                        handler.post {
                            downloadListener?.onCompleted(currentDownloadPath)
                        }
                        isDownloading = false
                        break
                    } else if (isComplete && !isDownloading) {
                        Log.d(TAG, "Download was already stopped, not reporting completion")
                        break
                    }
                    
                    // Handle verification phase progress
                    if (state == TorrentStatus.State.CHECKING_FILES && isDownloading) {
                        if (downloadPhase != DownloadPhase.VERIFICATION) {
                            transitionToPhase(DownloadPhase.VERIFICATION, downloadListener)
                        }
                        val verifyProgress = status.progress()
                        Log.d(TAG, "In verification phase: ${(verifyProgress * 100).toInt()}%")
                        handler.post {
                            downloadListener?.onVerifying(verifyProgress)
                        }
                    }
                    
                    // Basic error checking
                    if (!currentTorrentHandle!!.isValid) {
                        if (isDownloading) {
                            handler.post {
                                downloadListener?.onError("Torrent handle became invalid")
                            }
                            isDownloading = false
                        }
                        break
                    }
                    
                    // Update progress only if still downloading
                    if (isDownloading) {
                        handler.post {
                            downloadListener?.onProgress(totalWantedDone, totalWanted, downloadRate, numPeers)
                        }
                        
                        // Periodic resume data saving
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastResumeDataSave > RESUME_DATA_SAVE_INTERVAL_MS && !pendingResumeDataSave) {
                            Log.d(TAG, "Saving resume data (periodic)")
                            saveResumeData()
                        }
                    }
                    
                    // Enhanced progress logging with peer stability tracking
                    if (currentTime % 10000 < 1000) {
                        Log.d(TAG, "[DOWNLOAD PROGRESS] $progressPercent% - Seeds: $numSeeds, Peers: $numPeers - Rate: ${downloadRate}B/s")
                        
                        // Warn if peer count is dropping
                        if (numPeers > 0 && numPeers < 3) {
                            Log.w(TAG, "[PEER WARNING] Low peer count detected: $numPeers peers - connection may be unstable")
                        }
                    }
                    
                    Thread.sleep(1000)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring download progress", e)
                    if (isDownloading) {
                        handler.post {
                            downloadListener?.onError("Progress monitoring error: ${e.message}")
                        }
                    }
                    break
                }
            }
        }
        progressMonitorThread?.start()
    }
    
    private fun checkTimeouts(totalDone: Long, listener: TorrentDownloadListener): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Update progress tracking - be more lenient about what constitutes progress
        val hasProgress = totalDone > lastBytesDownloaded || 
                         (currentTorrentHandle?.status()?.downloadRate() ?: 0) > 0
        if (hasProgress) {
            lastProgressTime = currentTime
            if (totalDone > lastBytesDownloaded) {
                val bytesGained = totalDone - lastBytesDownloaded
                Log.d(TAG, "Progress detected: $bytesGained bytes downloaded")
                lastBytesDownloaded = totalDone
            } else {
                Log.d(TAG, "Active download rate detected - resetting progress timer")
            }
        }
        
        // Check timeouts based on current phase
        return when (downloadPhase) {
            DownloadPhase.METADATA_FETCHING -> {
                // Timeout if metadata fetching takes too long
                val metadataTime = currentTime - metadataStartTime
                if (metadataTime > METADATA_TIMEOUT_MS) {
                    Log.d(TAG, "Metadata timeout: fetching took ${metadataTime}ms")
                    triggerTimeout(listener, "Metadata fetching timeout after ${metadataTime / 1000} seconds")
                    true
                } else {
                    val remainingTime = (METADATA_TIMEOUT_MS - metadataTime) / 1000
                    if (metadataTime > 30000) { // Log after 30 seconds
                        Log.d(TAG, "Metadata fetching for ${metadataTime / 1000}s, timeout in ${remainingTime}s")
                    }
                    false
                }
            }
            DownloadPhase.PEER_DISCOVERY -> {
                // Don't timeout during peer discovery - give P2P time to connect
                false
            }
            DownloadPhase.ACTIVE_DOWNLOADING -> {
                // Only timeout if no download progress for specified time
                val noProgressTime = currentTime - lastProgressTime
                val downloadRate = currentTorrentHandle?.status()?.downloadRate() ?: 0
                val peerCount = currentTorrentHandle?.status()?.numPeers() ?: 0
                
                // Don't timeout if we have active download rate or recently had progress
                val shouldTimeout = noProgressTime > NO_PROGRESS_TIMEOUT_MS && 
                                  downloadRate == 0 && 
                                  peerCount == 0
                
                if (shouldTimeout) {
                    Log.d(TAG, "Progress timeout: no download progress for ${noProgressTime}ms, no peers, no download rate")
                    triggerTimeout(listener, "No download progress for ${noProgressTime / 1000} seconds with no active peers")
                    true
                } else {
                    val remainingTime = (NO_PROGRESS_TIMEOUT_MS - noProgressTime) / 1000
                    if (noProgressTime > 60000) { // Log every minute after first minute
                        val status = when {
                            downloadRate > 0 -> "downloading at ${downloadRate}B/s"
                            peerCount > 0 -> "connected to $peerCount peers"
                            else -> "no activity"
                        }
                        Log.d(TAG, "No progress for ${noProgressTime / 1000}s ($status), will timeout in ${remainingTime}s if no progress")
                    }
                    false
                }
            }
            DownloadPhase.VERIFICATION -> {
                // Don't timeout during verification
                false
            }
        }
    }
    
    private fun triggerTimeout(listener: TorrentDownloadListener, reason: String) {
        Log.w(TAG, "[TIMEOUT] Phase: $downloadPhase, Reason: $reason")
        Log.w(TAG, "[TIMEOUT] No progress time: ${System.currentTimeMillis() - lastProgressTime}ms")
        Log.w(TAG, "[TIMEOUT] Last bytes downloaded: $lastBytesDownloaded")
        Log.w(TAG, "[TIMEOUT] Explicitly stopping P2P download before HTTPS fallback")
        
        // Set flag to stop download immediately
        isDownloading = false
        
        // Notify listener for HTTPS fallback BEFORE cleaning up
        handler.post {
            listener.onTimeout()
        }
        
        // Then clean up P2P resources 
        handler.postDelayed({
            stopDownload()
        }, 100) // Small delay to ensure onTimeout is processed first
    }
    
    private fun transitionToPhase(newPhase: DownloadPhase, listener: TorrentDownloadListener?) {
        val oldPhase = downloadPhase
        downloadPhase = newPhase
        val currentTime = System.currentTimeMillis()
        
        // Determine timeout for this phase
        val timeoutSeconds = when (newPhase) {
            DownloadPhase.METADATA_FETCHING -> {
                metadataStartTime = currentTime
                60 // 60 seconds for metadata
            }
            DownloadPhase.PEER_DISCOVERY -> {
                0 // No timeout during peer discovery
            }
            DownloadPhase.ACTIVE_DOWNLOADING -> {
                lastProgressTime = currentTime
                240 // 4 minutes for no progress
            }
            DownloadPhase.VERIFICATION -> {
                0 // No timeout during verification
            }
        }
        
        Log.d(TAG, "[PHASE TRANSITION] $oldPhase -> $newPhase (${timeoutSeconds}s timeout)")
        
        // Notify listener of phase change
        listener?.let {
            handler.post {
                it.onPhaseChanged(newPhase, timeoutSeconds)
            }
        }
    }
    
    fun stopDownload() {
        // Save resume data before stopping if download was active
        if (isDownloading && currentTorrentHandle?.isValid == true) {
            Log.d(TAG, "Saving resume data before stopping download")
            saveResumeDataSync() // Synchronous save before cleanup
        }
        
        isDownloading = false
        isVerifying = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        progressMonitorThread?.interrupt()
        progressMonitorThread = null
        
        // Reset metadata waiting state
        waitingForMetadata = false
        
        // Reset tracking variables
        lastProgressTime = 0
        lastBytesDownloaded = 0
        metadataStartTime = 0
        downloadPhase = DownloadPhase.METADATA_FETCHING
        pendingResumeDataSave = false
        
        // Remove torrent from session if it exists
        currentTorrentHandle?.let { handle ->
            if (handle.isValid) {
                sessionManager?.remove(handle)
            }
        }
        currentTorrentHandle = null
        downloadListener = null
    }
    
    private fun saveResumeDataSync() {
        if (currentTorrentHandle == null || !currentTorrentHandle!!.isValid) {
            return
        }
        
        try {
            Log.d(TAG, "Synchronous progress metadata save before stop")
            
            // Save final progress state
            val status = currentTorrentHandle!!.status()
            val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            
            prefs.edit().apply {
                putBoolean("has_resume_data", true)
                putString("download_path", currentDownloadPath)
                putLong("total_size", status.totalWanted())
                putLong("downloaded_size", status.totalWantedDone())
                putLong("last_save_time", System.currentTimeMillis())
                if (currentTorrentHandle?.isValid == true) {
                    putString("info_hash", currentTorrentHandle!!.infoHash().toString())
                }
                apply()
            }
            
            val progress = if (status.totalWanted() > 0) {
                (status.totalWantedDone().toFloat() / status.totalWanted() * 100).toInt()
            } else 0
            
            Log.d(TAG, "Final progress saved before stop: $progress%")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving final progress state", e)
        }
    }
    
    fun seedFile(filePath: String, listener: TorrentDownloadListener? = null) {
        Log.d(TAG, "seedFile() called with: $filePath")
        Log.d(TAG, "Current state - isSeeding: $isSeeding, isNetworkTransitioning: $isNetworkTransitioning, isLibraryAvailable: $isLibraryAvailable")
        
        if (!shouldSeed()) {
            Log.d(TAG, "Seeding disabled by user preference")
            return
        }
        
        if (!File(filePath).exists()) {
            Log.e(TAG, "Torrent file does not exist: $filePath")
            return
        }
        
        if (!isLibraryAvailable || sessionManager == null) {
            Log.d(TAG, "LibTorrent not available for seeding")
            return
        }
        
        try {
            val file = File(filePath)
            Log.d(TAG, "Starting seeding for: $filePath (size: ${file.length()} bytes)")
            
            // If we already have the torrent handle from download, configure it for seeding
            currentTorrentHandle?.let { handle ->
                if (handle.isValid) {
                    Log.d(TAG, "Found existing valid torrent handle")
                    
                    // Check the current state
                    val status = handle.status()
                    Log.d(TAG, "Torrent status: state=${status.state()}, finished=${status.isFinished}, seeding=${status.isSeeding}")
                    
                    // If download is complete, start seeding
                    if (status.isFinished || status.isSeeding || status.totalWanted() == status.totalWantedDone()) {
                        isSeeding = true
                        Log.d(TAG, "Torrent is complete - now seeding")
                        return
                    } else {
                        Log.d(TAG, "Torrent not yet complete for seeding: ${status.totalWantedDone()}/${status.totalWanted()}")
                        // Still mark as seeding since we have the handle and will seed when complete
                        isSeeding = true
                        return
                    }
                } else {
                    Log.w(TAG, "Current torrent handle is invalid")
                    currentTorrentHandle = null
                }
            }
            
            // No valid handle found - try to restore from stored magnet link with verification
            Log.d(TAG, "No valid torrent handle found - attempting to restore from stored magnet link")
            
            // Start seeding immediately since we have a valid file, then try verification in background
            isSeeding = true
            Log.d(TAG, "Seeding enabled immediately with valid file - verification will happen in background")
            
            // Notify listener immediately that seeding started
            listener?.let {
                handler.post {
                    Log.d(TAG, "Notifying listener that seeding started immediately")
                    // This should trigger MainActivity to clear isSeedingInitializing
                }
            }
            
            // Try metadata fetch with verification in background thread (non-blocking)
            Thread {
                try {
                    restoreSeedingFromMagnetLink(filePath, listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Background metadata fetch failed: ${e.message}")
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start seeding", e)
            isSeeding = false
        }
    }
    
    fun restoreSeedingFromMagnetLink(filePath: String, listener: TorrentDownloadListener? = null) {
        Log.d(TAG, "restoreSeedingFromMagnetLink() called with: $filePath")
        
        if (!shouldSeed()) {
            Log.d(TAG, "Seeding disabled by user preference")
            return
        }
        
        if (!isLibraryAvailable || sessionManager == null) {
            Log.d(TAG, "LibTorrent not available for seeding restoration")
            return
        }
        
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        val magnetLink = prefs.getString("magnet_link", null)
        
        if (magnetLink == null) {
            Log.w(TAG, "No stored magnet link found - cannot restore seeding")
            return
        }
        
        if (!File(filePath).exists()) {
            Log.e(TAG, "File does not exist for seeding: $filePath")
            return
        }
        
        try {
            val downloadDir = File(filePath).parentFile
            Log.d(TAG, "Restoring seeding using magnet link: $magnetLink")
            
            // Use fetchMagnet approach for seeding restoration with shorter timeout
            Log.d(TAG, "Fetching magnet metadata for seeding restoration...")
            
            val timeout = if (isNetworkTransitioning) {
                Log.d(TAG, "Network transitioning - using reduced 20 second timeout")
                20
            } else {
                Log.d(TAG, "Stable network - using standard 60 second timeout")
                60
            }
            
            // Notify listener that we're starting the process
            listener?.let {
                handler.post {
                    it.onDhtConnecting()
                }
            }
            
            // Give DHT time to connect
            Thread.sleep(1000)
            
            // Notify listener that we're fetching metadata
            listener?.let {
                handler.post {
                    it.onDhtConnected(0)
                    it.onMetadataFetching()
                }
            }
            
            val data = sessionManager?.fetchMagnet(magnetLink, timeout, downloadDir ?: File(filePath).parentFile ?: File("."))
            Log.d(TAG, "Magnet fetch result: ${if (data != null) "success (${data.size} bytes)" else "failed or null"}")
            
            if (data != null) {
                val torrentInfo = TorrentInfo.bdecode(data)
                Log.d(TAG, "Magnet metadata downloaded for seeding, starting verification...")
                
                // Notify metadata complete
                listener?.let {
                    handler.post {
                        it.onMetadataComplete()
                    }
                }
                
                // Add torrent to session for seeding (file should already exist)
                sessionManager?.download(torrentInfo, downloadDir ?: File(filePath).parentFile ?: File("."))
                currentTorrentHandle = sessionManager?.find(torrentInfo.infoHash())
                
                // Notify ready to seed
                listener?.let {
                    handler.post {
                        it.onReadyToSeed()
                    }
                }
                
                currentTorrentHandle?.let { handle ->
                    if (handle.isValid) {
                        isSeeding = true
                        Log.d(TAG, "Successfully restored seeding - torrent complete")
                    } else {
                        Log.w(TAG, "Restored torrent handle is invalid")
                        currentTorrentHandle = null
                    }
                } ?: run {
                    Log.e(TAG, "Failed to get torrent handle after restoration")
                }
            } else {
                Log.w(TAG, "Failed to fetch magnet metadata for seeding restoration")
                if (!isSeeding) {
                    isSeeding = true
                    Log.d(TAG, "Seeding enabled without verification")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore seeding from magnet link: ${e.message}", e)
            Log.d(TAG, "Exception during magnet restoration - this is usually a network or timeout issue")
            // Don't set isSeeding = false here since it might already be true from seedFile()
            Log.d(TAG, "Keeping existing seeding state due to exception: isSeeding=$isSeeding")
        }
    }
    
    fun getPeerCount(): Int {
        return try {
            if (isSeeding && currentTorrentHandle?.isValid == true) {
                currentTorrentHandle!!.status().numPeers()
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    fun getUploadRate(): Int {
        return try {
            if (isSeeding && currentTorrentHandle?.isValid == true) {
                currentTorrentHandle!!.status().uploadRate()
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    fun isSeeding(): Boolean {
        Log.d(TAG, "isSeeding() called - returning: $isSeeding")
        return isSeeding
    }
    
    fun stopSeeding() {
        // CRITICAL: Never interfere with active downloads
        if (isDownloading) {
            Log.w(TAG, "stopSeeding() called during active download - COMPLETELY IGNORING to prevent peer disconnection")
            Log.w(TAG, "Download in progress: isDownloading=$isDownloading, currentTorrentHandle=${currentTorrentHandle?.isValid}")
            return
        }
        
        Log.d(TAG, "Stopping seeding - no active download detected")
        isSeeding = false
        
        // Remove torrent from session if it exists (only if not downloading)
        currentTorrentHandle?.let { handle ->
            if (handle.isValid) {
                Log.d(TAG, "Removing torrent handle for seeding stop")
                sessionManager?.remove(handle)
            }
        }
        currentTorrentHandle = null
    }
    
    private fun shouldSeed(): Boolean {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        val seedingEnabled = prefs.getBoolean("enable_seeding", true)
        val allowMobileDataSeeding = prefs.getBoolean("allow_mobile_data_seeding", false)
        
        if (!seedingEnabled) {
            Log.d(TAG, "Seeding disabled by user preference")
            return false
        }
        
        val isOnWiFi = networkManager.isOnWiFi()
        val isOnMobileData = networkManager.isOnMobileData()
        
        return when {
            isOnWiFi -> {
                Log.d(TAG, "On WiFi - seeding allowed")
                true
            }
            isOnMobileData && allowMobileDataSeeding -> {
                Log.d(TAG, "On mobile data but user allows mobile data seeding")
                true
            }
            isOnMobileData -> {
                Log.d(TAG, "On mobile data and mobile data seeding disabled - blocking seeding")
                false
            }
            else -> {
                Log.d(TAG, "Unknown network type - allowing seeding")
                true
            }
        }
    }
    
    fun setSeedingEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enable_seeding", enabled).apply()
        
        if (!enabled) {
            stopSeeding()
        }
    }
    
    fun setMobileDataSeedingEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("allow_mobile_data_seeding", enabled).apply()
        
        if (!enabled && networkManager.isOnMobileData()) {
            Log.d(TAG, "Mobile data seeding disabled and currently on mobile data - stopping seeding")
            stopSeeding()
        }
    }
    
    fun isMobileDataSeedingEnabled(): Boolean {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("allow_mobile_data_seeding", false)
    }
    
    fun isSeedingEnabled(): Boolean {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("enable_seeding", true)
    }
    
    fun isLibraryLoaded(): Boolean {
        return isLibraryAvailable
    }
    
    fun isNetworkTransitioning(): Boolean {
        val currentTime = System.currentTimeMillis()
        val inTransition = isNetworkTransitioning && (currentTime - networkTransitionStartTime) < NETWORK_TRANSITION_TIMEOUT_MS
        
        // Auto-clear the transition flag if timeout exceeded
        if (isNetworkTransitioning && (currentTime - networkTransitionStartTime) >= NETWORK_TRANSITION_TIMEOUT_MS) {
            Log.d(TAG, "Network transition timeout exceeded, clearing flag")
            isNetworkTransitioning = false
        }
        
        return inTransition
    }
    
    fun isActivelyDownloading(): Boolean {
        return isDownloading
    }
    
    fun getCurrentTorrentHandle(): TorrentHandle? {
        return currentTorrentHandle
    }
    
    fun getPeerConnectionHealth(): String {
        return try {
            if (currentTorrentHandle?.isValid == true) {
                val status = currentTorrentHandle!!.status()
                val peers = status.numPeers()
                val seeds = try { status.numSeeds() } catch (e: Exception) { 0 }
                val downloadRate = status.downloadRate()
                val state = status.state()
                
                "Peers: $peers, Seeds: $seeds, Rate: ${downloadRate}B/s, State: $state, Valid: true"
            } else {
                "No valid torrent handle available"
            }
        } catch (e: Exception) {
            "Error getting peer info: ${e.message}"
        }
    }
    
    fun getCurrentPort(): Int {
        return currentPort
    }
    
    private fun startNetworkMonitoring() {
        networkManager.startNetworkMonitoring { isOnWiFi ->
            Log.d(TAG, "Network change detected - WiFi: $isOnWiFi")
            
            // CRITICAL: Completely avoid any interference during active downloads
            if (isDownloading) {
                Log.w(TAG, "ACTIVE DOWNLOAD DETECTED - Ignoring all network change actions to prevent peer disconnection")
                Log.w(TAG, "Network monitoring will resume normal operation after download completes")
                return@startNetworkMonitoring
            }
            
            // Mark network transition period only for non-download operations
            isNetworkTransitioning = true
            networkTransitionStartTime = System.currentTimeMillis()
            
            // Handle network changes only when not downloading
            if (isSeeding && !isOnWiFi && networkManager.isOnMobileData() && !isMobileDataSeedingEnabled()) {
                Log.d(TAG, "Switched to mobile data and mobile data seeding is disabled - stopping seeding")
                stopSeeding()
            } else if (isOnWiFi && isSeedingEnabled()) {
                Log.d(TAG, "Switched to WiFi - checking seeding state and attempting resume")
                
                // Only reset seeding session if we're not downloading
                if (isSeeding) {
                    Log.d(TAG, "Already seeding but network changed - resetting session for stability")
                    stopSeeding()
                    isSeeding = false
                }
                
                // Wait for network to stabilize before attempting seeding
                Thread seedingRestartThread@{
                    try {
                        Thread.sleep(5000) // 5 second delay for network stabilization
                        
                        // Double-check we're still not downloading before proceeding
                        if (isDownloading) {
                            Log.w(TAG, "Download started during network transition - aborting seeding restart")
                            isNetworkTransitioning = false
                            return@seedingRestartThread
                        }
                        
                        // Check if we still have a valid file and are on WiFi
                        if (networkManager.isOnWiFi() && currentDownloadPath.isNotEmpty() && File(currentDownloadPath).exists()) {
                            Log.d(TAG, "Network stabilized, starting fresh seeding session")
                            seedFile(currentDownloadPath)
                        } else if (networkManager.isOnWiFi()) {
                            // Try to find the torrent file in internal files
                            val internalFile = File(context.filesDir, "pictures.tar.gz")
                            if (internalFile.exists()) {
                                Log.d(TAG, "Found torrent file in internal storage, starting seeding")
                                currentDownloadPath = internalFile.absolutePath
                                seedFile(internalFile.absolutePath)
                            }
                        }
                    } catch (e: InterruptedException) {
                        Log.d(TAG, "Network transition wait interrupted")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during network transition seeding restart", e)
                    } finally {
                        isNetworkTransitioning = false
                    }
                }.start()
            } else {
                // Clear transition flag after a short delay if no action needed
                Thread transitionCleanupThread@{
                    try {
                        Thread.sleep(3000) // Reduced from 5 to 3 seconds
                    } catch (e: InterruptedException) {
                        // Ignore
                    } finally {
                        isNetworkTransitioning = false
                    }
                }.start()
            }
        }
    }
    
    fun shutdown() {
        stopDownload()
        stopSeeding()
        networkManager.stopNetworkMonitoring()
        sessionManager?.stop()
        sessionManager = null
    }
    
    
}