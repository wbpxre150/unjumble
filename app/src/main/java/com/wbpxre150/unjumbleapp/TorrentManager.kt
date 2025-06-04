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
    
    companion object {
        private const val TAG = "TorrentManager"
        private const val DOWNLOAD_TIMEOUT_MS = 180000L // 3 minutes
        private const val PEER_DISCOVERY_TIMEOUT_MS = 120000L // 2 minutes for peer discovery
        
        @Volatile
        private var INSTANCE: TorrentManager? = null
        
        fun getInstance(context: Context): TorrentManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorrentManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    init {
        initializeSession()
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
        
        if (!isLibraryAvailable || sessionManager == null) {
            Log.d(TAG, "LibTorrent not available - falling back to HTTPS")
            timeoutRunnable = Runnable {
                if (isDownloading) {
                    stopDownload()
                    listener.onTimeout()
                }
            }
            handler.postDelayed(timeoutRunnable!!, PEER_DISCOVERY_TIMEOUT_MS) // 2 minutes for fallback discovery
            return
        }
        
        try {
            val downloadDir = File(downloadPath).parentFile
            if (downloadDir != null && !downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            Log.d(TAG, "Starting P2P download for: $magnetLink")
            
            // Add torrent from magnet link using fetchMagnet
            Log.d(TAG, "Fetching magnet metadata...")
            val data = sessionManager?.fetchMagnet(magnetLink, 30, downloadDir ?: File(currentDownloadPath))
            
            if (data != null) {
                val torrentInfo = TorrentInfo.bdecode(data)
                sessionManager?.download(torrentInfo, downloadDir ?: File(currentDownloadPath))
                currentTorrentHandle = sessionManager?.find(torrentInfo.infoHash())
            } else {
                listener.onError("Failed to fetch magnet metadata")
                isDownloading = false
                return
            }
            
            if (currentTorrentHandle == null) {
                listener.onError("Failed to add torrent to session")
                isDownloading = false
                return
            }
            
            Log.d(TAG, "Torrent added to session, waiting for metadata and peers...")
            
            // Start monitoring for metadata and peer discovery
            startMetadataAndPeerMonitoring(listener)
            
            // Set timeout for P2P download
            timeoutRunnable = Runnable {
                if (isDownloading) {
                    Log.d(TAG, "P2P download timeout, falling back to HTTPS")
                    stopDownload()
                    listener.onTimeout()
                }
            }
            handler.postDelayed(timeoutRunnable!!, DOWNLOAD_TIMEOUT_MS)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start P2P download", e)
            listener.onError("Failed to start P2P download: ${e.message}")
            isDownloading = false
        }
    }
    
    private fun startMetadataAndPeerMonitoring(listener: TorrentDownloadListener) {
        progressMonitorThread = Thread {
            var hasMetadata = false
            var startTime = System.currentTimeMillis()
            
            while (isDownloading && currentTorrentHandle != null && currentTorrentHandle!!.isValid) {
                try {
                    val status = currentTorrentHandle!!.status()
                    val numPeers = status.numPeers()
                    val hasMetadataFlag = status.hasMetadata()
                    
                    Log.d(TAG, "Status: peers=$numPeers, hasMetadata=$hasMetadataFlag, state=${status.state()}")
                    
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
                        startRealProgressMonitoring()
                        break
                    }
                    
                    // Update progress even without metadata to show peer discovery
                    handler.post {
                        listener.onProgress(0, 100, 0, numPeers)
                    }
                    
                    // Give up after too long without any peers or metadata
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > PEER_DISCOVERY_TIMEOUT_MS && numPeers == 0) {
                        Log.d(TAG, "No peers found after timeout, giving up")
                        handler.post {
                            listener.onTimeout()
                        }
                        break
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
                    
                    // Check if download is complete
                    if (status.isFinished || (totalWanted > 0 && totalWantedDone >= totalWanted)) {
                        handler.post {
                            downloadListener?.onCompleted(currentDownloadPath)
                        }
                        isDownloading = false
                        break
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
    
    fun stopDownload() {
        isDownloading = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        progressMonitorThread?.interrupt()
        progressMonitorThread = null
        
        // Remove torrent from session if it exists
        currentTorrentHandle?.let { handle ->
            if (handle.isValid) {
                sessionManager?.remove(handle)
            }
        }
        currentTorrentHandle = null
        downloadListener = null
    }
    
    fun seedFile(filePath: String) {
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
            
            Log.w(TAG, "No valid torrent handle found - cannot seed standalone file without original magnet/torrent")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start seeding", e)
            isSeeding = false
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
        return prefs.getBoolean("enable_seeding", true)
    }
    
    fun setSeedingEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enable_seeding", enabled).apply()
        
        if (!enabled) {
            stopSeeding()
        }
    }
    
    fun isSeedingEnabled(): Boolean {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("enable_seeding", true)
    }
    
    fun isLibraryLoaded(): Boolean {
        return isLibraryAvailable
    }
    
    fun shutdown() {
        stopDownload()
        stopSeeding()
        sessionManager?.stop()
        sessionManager = null
    }
}