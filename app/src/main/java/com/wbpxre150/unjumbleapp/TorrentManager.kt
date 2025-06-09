package com.wbpxre150.unjumbleapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
import org.libtorrent4j.swig.*

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
    private var zeroPeerStartTime: Long = 0
    private var hasHadPeers = false
    private var isVerifying = false
    private var verificationStartTime: Long = 0
    private val VERIFICATION_TIMEOUT_MS = 120000L // 2 minutes timeout for verification
    private val networkManager = NetworkManager.getInstance(context)
    
    companion object {
        private const val TAG = "TorrentManager"
        private const val ZERO_PEER_TIMEOUT_MS = 600000L // 10 minutes with zero peers before timeout
        
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
            
            // Configure session for better peer discovery
            val settingsPack = SettingsPack()
            settingsPack.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true)
            settingsPack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true) // Local Service Discovery
            settingsPack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            settingsPack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            settingsPack.setInteger(settings_pack.int_types.listen_queue_size.swigValue(), 50)
            
            settingsPack.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:6881,[::]:6881")
            
            sessionManager?.applySettings(settingsPack)
            sessionManager?.start()
            
            isLibraryAvailable = true
            Log.d(TAG, "LibTorrent4j session initialized successfully with peer discovery")
            
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "LibTorrent4j native library not available: ${e.message}")
            isLibraryAvailable = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize LibTorrent session: ${e.message}")
            isLibraryAvailable = false
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
        
        // Initialize zero-peer tracking
        zeroPeerStartTime = System.currentTimeMillis()
        hasHadPeers = false
        
        // LibTorrent4j should already be initialized in init block
        continueDownloadAfterInit(magnetLink, downloadPath, listener)
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
            
            Log.d(TAG, "Starting P2P download for: $magnetLink")
            
            // Fetch magnet metadata asynchronously to avoid blocking
            Thread {
                try {
                    Log.d(TAG, "Fetching magnet metadata...")
                    handler.post {
                        listener.onVerifying(0.0f)
                    }
                    
                    // Use longer timeout to allow DHT network to properly connect and find peers
                    val data = sessionManager?.fetchMagnet(magnetLink, 300, downloadDir ?: File(currentDownloadPath))
                    
                    if (data != null) {
                        val torrentInfo = TorrentInfo.bdecode(data)
                        Log.d(TAG, "Magnet metadata downloaded successfully, starting verification before download...")
                        
                        // Add torrent to session
                        sessionManager?.download(torrentInfo, downloadDir ?: File(currentDownloadPath))
                        currentTorrentHandle = sessionManager?.find(torrentInfo.infoHash())
                        
                        if (currentTorrentHandle == null) {
                            handler.post {
                                listener.onError("Failed to add torrent to session")
                            }
                            isDownloading = false
                            return@Thread
                        }
                        
                        Log.d(TAG, "Torrent added to session, waiting for metadata and peers...")
                        
                        // Skip verification during download - only verify for seeding
                        Log.d(TAG, "Skipping verification during download to prevent conflicts")
                        
                        // Start monitoring for metadata and peer discovery
                        startMetadataAndPeerMonitoring(listener)
                        
                    } else {
                        Log.w(TAG, "Failed to fetch magnet metadata after 5 minutes - no peers available or network issues")
                        handler.post {
                            listener.onError("No peers found after 5 minutes. This may be due to network issues or the file is temporarily unavailable.")
                        }
                        isDownloading = false
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed during magnet metadata fetch", e)
                    handler.post {
                        listener.onError("Failed to start P2P download: ${e.message}")
                    }
                    isDownloading = false
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
            
            while (isDownloading && currentTorrentHandle != null && currentTorrentHandle!!.isValid) {
                try {
                    val status = currentTorrentHandle!!.status()
                    val numPeers = status.numPeers()
                    val hasMetadataFlag = status.hasMetadata()
                    
                    Log.d(TAG, "Status: peers=$numPeers, hasMetadata=$hasMetadataFlag, state=${status.state()}, downloadRate=${status.downloadRate()}")
                    
                    // Check zero-peer timeout
                    if (checkZeroPeerTimeout(numPeers, listener)) {
                        break
                    }
                    
                    // Check if we got metadata
                    if (!hasMetadata && hasMetadataFlag) {
                        hasMetadata = true
                        Log.d(TAG, "Metadata received! Starting download monitoring...")
                        handler.post {
                            listener.onProgress(0, status.totalWanted(), 0, numPeers)
                        }
                    }
                    
                    // If we have metadata, switch to full progress monitoring
                    if (hasMetadata) {
                        Log.d(TAG, "Metadata acquired, total size: ${status.totalWanted()} bytes")
                        startRealProgressMonitoring()
                        break
                    }
                    
                    // Update progress even without metadata to show peer discovery
                    handler.post {
                        // Use 0 total to indicate we're still fetching metadata
                        listener.onProgress(0, 0, 0, numPeers)
                    }
                    
                    Thread.sleep(2000) // Check every 2 seconds for metadata/peers
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring metadata/peers", e)
                    handler.post {
                        listener.onError("Metadata monitoring error: ${e.message}")
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
            while (isDownloading && currentTorrentHandle != null && currentTorrentHandle!!.isValid) {
                try {
                    val status = currentTorrentHandle!!.status()
                    val totalWanted = status.totalWanted()
                    val totalWantedDone = status.totalWantedDone()
                    val downloadRate = status.downloadRate()
                    val numPeers = status.numPeers()
                    
                    Log.d(TAG, "Progress: ${totalWantedDone}/${totalWanted} bytes, rate=${downloadRate}B/s, peers=$numPeers")
                    
                    // Check zero-peer timeout
                    if (checkZeroPeerTimeout(numPeers, downloadListener!!)) {
                        break
                    }
                    
                    // Enhanced completion detection
                    val state = status.state()
                    val isComplete = status.isFinished || 
                                   (totalWanted > 0 && totalWantedDone >= totalWanted) ||
                                   state == TorrentStatus.State.SEEDING ||
                                   (state == TorrentStatus.State.CHECKING_FILES && status.progress() >= 1.0f)
                    
                    Log.d(TAG, "Completion check: isFinished=${status.isFinished}, progress=${status.progress()}, state=$state, totalDone/Wanted=$totalWantedDone/$totalWanted")
                    
                    if (isComplete) {
                        Log.d(TAG, "Download completed - state: $state, progress: ${status.progress()}")
                        handler.post {
                            downloadListener?.onCompleted(currentDownloadPath)
                        }
                        isDownloading = false
                        break
                    }
                    
                    // Handle verification phase progress
                    if (state == TorrentStatus.State.CHECKING_FILES) {
                        val verifyProgress = status.progress()
                        Log.d(TAG, "In verification phase: ${(verifyProgress * 100).toInt()}%")
                        handler.post {
                            downloadListener?.onVerifying(verifyProgress)
                        }
                    }
                    
                    // Basic error checking
                    if (!currentTorrentHandle!!.isValid) {
                        handler.post {
                            downloadListener?.onError("Torrent handle became invalid")
                        }
                        isDownloading = false
                        break
                    }
                    
                    // Update progress
                    handler.post {
                        downloadListener?.onProgress(totalWantedDone, totalWanted, downloadRate, numPeers)
                    }
                    
                    // Log progress every 10 seconds for debugging
                    if (System.currentTimeMillis() % 10000 < 1000) {
                        val progressPercent = if (totalWanted > 0) (totalWantedDone.toFloat() / totalWanted * 100).toInt() else 0
                        Log.d(TAG, "Download progress: $progressPercent% - $numPeers peers - ${downloadRate}B/s")
                    }
                    
                    Thread.sleep(1000)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring download progress", e)
                    handler.post {
                        downloadListener?.onError("Progress monitoring error: ${e.message}")
                    }
                    break
                }
            }
        }
        progressMonitorThread?.start()
    }
    
    private fun checkZeroPeerTimeout(numPeers: Int, listener: TorrentDownloadListener): Boolean {
        val currentTime = System.currentTimeMillis()
        
        if (numPeers > 0) {
            // We have peers - reset the zero-peer timer
            hasHadPeers = true
            zeroPeerStartTime = currentTime
            return false
        }
        
        // No peers currently
        val zeroPeerDuration = currentTime - zeroPeerStartTime
        
        // If we've had peers before but now have zero, and it's been too long, timeout
        // OR if we've never had peers and it's been too long, timeout
        if (zeroPeerDuration > ZERO_PEER_TIMEOUT_MS) {
            Log.d(TAG, "Zero peers for ${zeroPeerDuration}ms, timing out P2P download")
            handler.post {
                listener.onTimeout()
            }
            isDownloading = false
            return true
        }
        
        return false
    }
    
    fun stopDownload() {
        isDownloading = false
        isVerifying = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        progressMonitorThread?.interrupt()
        progressMonitorThread = null
        
        // Reset zero-peer tracking
        zeroPeerStartTime = 0
        hasHadPeers = false
        
        // Remove torrent from session if it exists
        currentTorrentHandle?.let { handle ->
            if (handle.isValid) {
                sessionManager?.remove(handle)
            }
        }
        currentTorrentHandle = null
        downloadListener = null
    }
    
    fun seedFile(filePath: String, listener: TorrentDownloadListener? = null) {
        Log.d(TAG, "seedFile() called with: $filePath")
        
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
            
            // Fetch magnet metadata and add torrent for seeding with longer timeout for better reliability
            Log.d(TAG, "Fetching magnet metadata for seeding restoration...")
            Log.d(TAG, "Using 60 second timeout to allow DHT network connection and peer discovery")
            
            // Notify listener that we're fetching metadata
            listener?.let {
                handler.post {
                    it.onVerifying(0.0f) // 0% indicates we're starting metadata fetch
                }
            }
            
            val data = sessionManager?.fetchMagnet(magnetLink, 60, downloadDir ?: File(filePath).parentFile ?: File("."))
            Log.d(TAG, "Magnet fetch result: ${if (data != null) "success (${data.size} bytes)" else "failed or null (timeout or no peers)"}")
            
            if (data != null) {
                val torrentInfo = TorrentInfo.bdecode(data)
                Log.d(TAG, "Magnet metadata downloaded for seeding, starting verification...")
                
                // Add torrent to session for seeding (file should already exist)
                sessionManager?.download(torrentInfo, downloadDir ?: File(filePath).parentFile ?: File("."))
                currentTorrentHandle = sessionManager?.find(torrentInfo.infoHash())
                
                // Start verification before seeding
                startVerificationForSeeding(listener)
                
                currentTorrentHandle?.let { handle ->
                    if (handle.isValid) {
                        // Check if file is already complete
                        val status = handle.status()
                        Log.d(TAG, "Restored torrent status: state=${status.state()}, finished=${status.isFinished}")
                        
                        if (status.isFinished || status.totalWanted() == status.totalWantedDone()) {
                            isSeeding = true
                            Log.d(TAG, "Successfully restored seeding - torrent complete")
                            Log.d(TAG, "isSeeding flag set to TRUE - torrent ready for seeding")
                        } else {
                            isSeeding = true
                            Log.d(TAG, "Torrent handle restored - will seed when complete")
                            Log.d(TAG, "isSeeding flag set to TRUE - torrent will start seeding when download completes")
                        }
                        
                        // Add additional verification
                        Log.d(TAG, "Final verification: isSeeding=${isSeeding}, handle.isValid=${handle.isValid}")
                    } else {
                        Log.w(TAG, "Restored torrent handle is invalid")
                        currentTorrentHandle = null
                    }
                } ?: run {
                    Log.e(TAG, "Failed to get torrent handle after restoration")
                    Log.w(TAG, "Seeding restoration failed - no valid torrent handle")
                    // Don't set isSeeding = false here since it might already be true from seedFile()
                    Log.d(TAG, "Keeping existing seeding state: isSeeding=$isSeeding")
                }
            } else {
                Log.w(TAG, "Failed to fetch magnet metadata for seeding restoration after 60 second timeout")
                Log.d(TAG, "This is likely due to network issues, no available peers, or DHT connectivity problems")
                Log.d(TAG, "File is still valid for the app, seeding just won't work right now")
                Log.d(TAG, "Keeping existing seeding state since we have a valid file")
                
                // Keep seeding flag as-is (likely already true from seedFile()) since we have the file
                if (!isSeeding) {
                    isSeeding = true
                    Log.d(TAG, "Seeding enabled without verification - torrent will seed if peers connect later")
                } else {
                    Log.d(TAG, "Seeding already enabled - verification failed but file is valid")
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
        isSeeding = false
        
        // Remove torrent from session if it exists
        currentTorrentHandle?.let { handle ->
            if (handle.isValid) {
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
    
    private fun startNetworkMonitoring() {
        networkManager.startNetworkMonitoring { isOnWiFi ->
            Log.d(TAG, "Network change detected - WiFi: $isOnWiFi")
            
            if (isSeeding && !isOnWiFi && networkManager.isOnMobileData() && !isMobileDataSeedingEnabled()) {
                Log.d(TAG, "Switched to mobile data and mobile data seeding is disabled - stopping seeding")
                stopSeeding()
            } else if (!isSeeding && isOnWiFi && isSeedingEnabled()) {
                Log.d(TAG, "Switched to WiFi and seeding is enabled - attempting to resume seeding")
                // Try to resume seeding if we have a valid file
                if (currentDownloadPath.isNotEmpty() && File(currentDownloadPath).exists()) {
                    seedFile(currentDownloadPath)
                }
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