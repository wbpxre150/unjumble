package com.wbpxre150.unjumbleapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

class DownloadActivity : Activity(), TorrentDownloadListener {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var timeRemainingTextView: TextView
    private lateinit var torrentManager: SimpleTorrentManager
    private var isP2PAttemptFinished = false
    private var downloadStartTime: Long = 0
    private var lastProgressUpdate: Long = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentPhase: DownloadPhase = DownloadPhase.METADATA_FETCHING
    private var phaseStartTime: Long = 0
    private var phaseTimeoutSeconds: Int = 0
    private var isDhtConnected = false
    private var currentPeerCount = 0
    private var totalPeersFound = 0
    private var connectedPeers = 0
    private var hasActiveProgress = false
    private var lastProgressBytes: Long = 0
    private var dhtNodeCount = 0
    private var seedCount = 0
    private var leecherCount = 0
    private var maxPeersObserved = 0
    private var peerSearchStartTime = 0L
    private val networkManager by lazy { NetworkManager.getInstance(this) }
    private var networkChangeListener: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if files are already downloaded
        val sharedPreferences = getSharedPreferences("AppState", MODE_PRIVATE)
        if (sharedPreferences.getBoolean("filesDownloaded", false)) {
            // Files already exist, proceed directly to MainActivity
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_download)

        progressBar = findViewById(R.id.progressBar)
        statusTextView = findViewById(R.id.statusTextView)
        timeRemainingTextView = findViewById(R.id.timeRemainingTextView)
        torrentManager = SimpleTorrentManager.getInstance(this)

        // Start network monitoring for FrostWire integration
        startNetworkMonitoring()
        
        GlobalScope.launch(Dispatchers.Main) {
            try {
                startDownload()
            } catch (e: Exception) {
                e.printStackTrace()
                statusTextView.text = "Error: ${e.message}"
                android.util.Log.e("DownloadActivity", "Error during download/extraction", e)
            }
        }
    }

    private fun startDownload() {
        // Check network type and decide download strategy
        when {
            networkManager.isOnMobileData() -> {
                android.util.Log.d("DownloadActivity", "On mobile data - DHT discovery doesn't work, using HTTPS download")
                statusTextView.text = "Mobile data detected - using direct download"
                timeRemainingTextView.text = "P2P downloads require WiFi connection"
                startHttpDownload()
            }
            networkManager.isOnWiFi() -> {
                android.util.Log.d("DownloadActivity", "On WiFi - attempting P2P download first")
                statusTextView.text = "WiFi detected - attempting P2P download"
                timeRemainingTextView.text = "P2P downloads work best on WiFi"
                startP2PDownload()
            }
            else -> {
                android.util.Log.d("DownloadActivity", "Network type unknown - attempting P2P download")
                statusTextView.text = "Checking network - attempting P2P download"
                timeRemainingTextView.text = "Starting download process"
                startP2PDownload()
            }
        }
    }

    private fun startP2PDownload() {
        val magnetLink = getString(R.string.pictures_magnet_link)
        val downloadPath = File(filesDir, "pictures.tar.gz").absolutePath
        
        // Check for resume data first
        val resumePrefs = getSharedPreferences("torrent_resume", MODE_PRIVATE)
        val hasResumeData = resumePrefs.getBoolean("has_resume_data", false)
        
        if (hasResumeData) {
            val downloadedSize = resumePrefs.getLong("downloaded_size", 0)
            val totalSize = resumePrefs.getLong("total_size", 0)
            
            val downloadedMB = downloadedSize / (1024 * 1024)
            val totalMB = totalSize / (1024 * 1024)
            val progressPercent = if (totalSize > 0) (downloadedSize.toFloat() / totalSize * 100).toInt() else 0
            
            statusTextView.text = "Resuming P2P download from $progressPercent%..."
            timeRemainingTextView.text = "Found ${downloadedMB}MB of ${totalMB}MB already downloaded"
            
            android.util.Log.d("DownloadActivity", "Resume data found: $progressPercent% complete (${downloadedMB}MB/${totalMB}MB)")
        } else {
            statusTextView.text = "Initializing P2P download..."
            timeRemainingTextView.text = "Starting fresh P2P download..."
        }
        
        // Store magnet link for future seeding sessions
        val prefs = getSharedPreferences("torrent_prefs", MODE_PRIVATE)
        prefs.edit().putString("magnet_link", magnetLink).apply()
        
        // Phase-based timeout management - let TorrentManager handle timeouts
        phaseStartTime = System.currentTimeMillis()
        currentPhase = DownloadPhase.METADATA_FETCHING
        
        // TorrentManager will automatically check for resume data and either resume or start fresh
        torrentManager.downloadFile(magnetLink, downloadPath, this)
    }

    // TorrentDownloadListener implementation
    override fun onProgress(downloaded: Long, total: Long, downloadRate: Int, peers: Int) {
        currentPeerCount = peers
        connectedPeers = peers // Connected peers are those actively transferring data
        
        // Track progress for timeout prevention
        hasActiveProgress = downloaded > lastProgressBytes
        lastProgressBytes = downloaded
        
        val progress = if (total > 0) (downloaded.toFloat() / total * 100).toInt() else 0
        progressBar.progress = progress
        
        val downloadSpeedKB = downloadRate / 1024
        val remainingBytes = total - downloaded
        val estimatedSeconds = if (downloadRate > 0) (remainingBytes / downloadRate).toInt() else 0
        
        val downloadedMB = downloaded / (1024 * 1024)
        val totalMB = total / (1024 * 1024)
        
        // Phase-aware status messages
        val port = torrentManager.getCurrentPort()
        android.util.Log.d("DownloadActivity", "Current port: $port, Phase: $currentPhase")
        val portStr = when {
            port > 0 -> " [P2P:$port]"
            port == 0 && torrentManager.isLibraryLoaded() -> " [P2P:random]"
            else -> " [P2P:6881]" // Default when library not loaded or uninitialized
        }
        
        // Track maximum peers observed for better diagnostics
        if (peers > maxPeersObserved) {
            maxPeersObserved = peers
        }
        
        statusTextView.text = when (currentPhase) {
            DownloadPhase.METADATA_FETCHING -> {
                if (total > 0) "File info received, starting download...$portStr" 
                else if (totalPeersFound > 0) {
                    "Fetching file info ($connectedPeers/$totalPeersFound peers, seeds:$seedCount)$portStr"
                } else if (peers > 0) {
                    "Fetching file info from $peers peers$portStr"
                } else if (isDhtConnected && dhtNodeCount > 0) {
                    "Searching for peers (DHT: $dhtNodeCount nodes)$portStr"
                } else {
                    "Connecting to DHT network...$portStr"
                }
            }
            DownloadPhase.PEER_DISCOVERY -> {
                if (totalPeersFound > 0) {
                    "Connecting to peers ($connectedPeers/$totalPeersFound, S:$seedCount L:$leecherCount)$portStr"
                } else if (peers > 0) {
                    "Found $peers peers, establishing connections...$portStr"
                } else if (isDhtConnected && dhtNodeCount > 0) {
                    val searchTime = (System.currentTimeMillis() - peerSearchStartTime) / 1000
                    "Searching for peers (${searchTime}s, DHT:$dhtNodeCount nodes)$portStr"
                } else {
                    "No peers found, checking DHT connectivity...$portStr"
                }
            }
            DownloadPhase.ACTIVE_DOWNLOADING -> {
                if (total > 0 && downloadRate > 0) {
                    // Show progress and speed when actively downloading
                    "Downloading: $progress% (${downloadedMB}MB/${totalMB}MB) at ${downloadSpeedKB}KB/s$portStr"
                } else if (total > 0) {
                    // Show progress without speed when paused/slow
                    "Downloaded: $progress% (${downloadedMB}MB/${totalMB}MB) from $peers peers$portStr"
                } else if (peers > 0) {
                    "Connected to $peers peers, waiting for data...$portStr"
                } else {
                    "No active peers (max seen: $maxPeersObserved), reconnecting...$portStr"
                }
            }
            DownloadPhase.VERIFICATION -> {
                "Verifying download...$portStr"
            }
        }
        
        timeRemainingTextView.text = if (downloadRate > 0 && currentPhase == DownloadPhase.ACTIVE_DOWNLOADING) {
            // During active downloading, show detailed speed and ETA info
            "Speed: ${downloadSpeedKB}KB/s | ETA: ${formatTime(estimatedSeconds)} | Peers: $peers"
        } else if (downloadRate > 0) {
            // During other phases with download rate, show basic speed
            "Speed: ${downloadSpeedKB}KB/s | Peers: $peers"
        } else if (peers > 0) {
            when (currentPhase) {
                DownloadPhase.METADATA_FETCHING -> "Getting file details from $peers peers..."
                DownloadPhase.PEER_DISCOVERY -> "Connecting to $peers peers (seeds:$seedCount, leechers:$leecherCount)..."
                DownloadPhase.ACTIVE_DOWNLOADING -> "Connected to $peers peers - waiting for data transfer..."
                DownloadPhase.VERIFICATION -> "Checking file integrity..."
            }
        } else {
            when (currentPhase) {
                DownloadPhase.METADATA_FETCHING -> {
                    if (isDhtConnected && dhtNodeCount > 0) {
                        "DHT connected ($dhtNodeCount nodes) - searching for peers..."
                    } else {
                        "Establishing DHT connection to find peers..."
                    }
                }
                DownloadPhase.PEER_DISCOVERY -> {
                    val searchTime = (System.currentTimeMillis() - peerSearchStartTime) / 1000
                    if (isDhtConnected && dhtNodeCount > 0) {
                        "No peers found after ${searchTime}s (DHT: $dhtNodeCount nodes, max seen: $maxPeersObserved)"
                    } else {
                        "DHT connection issues - peer discovery limited"
                    }
                }
                DownloadPhase.ACTIVE_DOWNLOADING -> {
                    if (maxPeersObserved > 0) {
                        "Lost connection to peers (previously had $maxPeersObserved)"
                    } else {
                        "No peers available for download"
                    }
                }
                DownloadPhase.VERIFICATION -> "Verifying downloaded data..."
            }
        }
    }

    override fun onCompleted(filePath: String) {
        if (isP2PAttemptFinished) {
            android.util.Log.d("DownloadActivity", "P2P attempt already finished, ignoring completion")
            return
        }
        isP2PAttemptFinished = true
        // Reset tracking variables
        isDhtConnected = false
        currentPeerCount = 0
        totalPeersFound = 0
        connectedPeers = 0
        dhtNodeCount = 0
        seedCount = 0
        leecherCount = 0
        maxPeersObserved = 0
        peerSearchStartTime = 0L
        hasActiveProgress = false
        lastProgressBytes = 0
        GlobalScope.launch(Dispatchers.Main) {
            try {
                extractFiles(File(filePath))
                // Start seeding after successful extraction, keeping the original file
                torrentManager.seedFile(filePath, this@DownloadActivity)
            } catch (e: Exception) {
                e.printStackTrace()
                statusTextView.text = "Error extracting files: ${e.message}"
            }
        }
    }

    override fun onError(error: String) {
        if (isP2PAttemptFinished) {
            android.util.Log.d("DownloadActivity", "P2P attempt already finished, ignoring error: $error")
            return
        }
        isP2PAttemptFinished = true
        // Reset tracking variables
        isDhtConnected = false
        currentPeerCount = 0
        totalPeersFound = 0
        connectedPeers = 0
        dhtNodeCount = 0
        seedCount = 0
        leecherCount = 0
        maxPeersObserved = 0
        peerSearchStartTime = 0L
        hasActiveProgress = false
        lastProgressBytes = 0
        
        // Clear resume data on error to prevent corrupted state
        clearResumeData()
        
        android.util.Log.w("DownloadActivity", "P2P download failed: $error")
        GlobalScope.launch(Dispatchers.Main) {
            statusTextView.text = "P2P download failed, trying HTTPS fallback..."
            timeRemainingTextView.text = if (networkManager.isOnMobileData()) {
                "Mobile data may have caused P2P failure - using direct download"
            } else {
                "Switching to direct download"
            }
            startHttpDownload()
        }
    }

    override fun onTimeout() {
        if (isP2PAttemptFinished) {
            android.util.Log.d("DownloadActivity", "P2P attempt already finished, ignoring timeout")
            return
        }
        isP2PAttemptFinished = true
        // Reset tracking variables
        isDhtConnected = false
        currentPeerCount = 0
        totalPeersFound = 0
        connectedPeers = 0
        dhtNodeCount = 0
        seedCount = 0
        leecherCount = 0
        maxPeersObserved = 0
        peerSearchStartTime = 0L
        hasActiveProgress = false
        lastProgressBytes = 0
        
        // Keep resume data on timeout - user might want to retry P2P later
        android.util.Log.d("DownloadActivity", "P2P timeout - keeping resume data for potential retry")
        
        android.util.Log.w("DownloadActivity", "P2P download timed out")
        GlobalScope.launch(Dispatchers.Main) {
            statusTextView.text = "P2P download timed out, trying HTTPS fallback..."
            timeRemainingTextView.text = if (networkManager.isOnMobileData()) {
                "Mobile data may have caused P2P timeout - using direct download"
            } else {
                "No peers found - switching to direct download"
            }
            startHttpDownload()
        }
    }

    override fun onVerifying(progress: Float) {
        val progressPercent = (progress * 100).toInt()
        progressBar.progress = progressPercent
        if (progress == 0.0f) {
            // Don't override the progressive timeout messages
            // The failsafe timeout will handle the status updates
        } else {
            statusTextView.text = "Verifying torrent: $progressPercent%"
            timeRemainingTextView.text = "Checking file integrity..."
        }
    }

    // Enhanced status callbacks for better torrent lifecycle tracking (FrostWire pattern)
    override fun onDhtConnecting() {
        android.util.Log.d("DownloadActivity", "DHT connecting (FrostWire enhanced)...")
        statusTextView.text = "Connecting to enhanced DHT network..."
        timeRemainingTextView.text = "Initializing optimized P2P connection"
    }

    override fun onDhtConnected(nodeCount: Int) {
        isDhtConnected = true
        dhtNodeCount = nodeCount
        android.util.Log.d("DownloadActivity", "Enhanced DHT connected with $nodeCount nodes (FrostWire optimization active)")
        
        val qualityIndicator = when {
            nodeCount >= 50 -> "Excellent"
            nodeCount >= 20 -> "Good"
            nodeCount >= 10 -> "Fair"
            nodeCount > 0 -> "Limited"
            else -> "Poor"
        }
        
        statusTextView.text = "Enhanced DHT connected: $nodeCount nodes ($qualityIndicator)"
        timeRemainingTextView.text = if (nodeCount >= 10) {
            "Strong DHT connectivity - enhanced peer discovery active"
        } else {
            "Limited DHT connectivity - basic peer discovery only"
        }
    }

    override fun onDiscoveringPeers() {
        peerSearchStartTime = System.currentTimeMillis()
        android.util.Log.d("DownloadActivity", "Discovering peers...")
        statusTextView.text = if (isDhtConnected && dhtNodeCount > 0) {
            "Searching for peers (DHT: $dhtNodeCount nodes)..."
        } else {
            "Searching for peers (DHT status unknown)..."
        }
        timeRemainingTextView.text = "Looking for other users sharing this file"
    }

    override fun onSeedsFound(seedCount: Int, peerCount: Int) {
        this.seedCount = seedCount
        this.leecherCount = peerCount - seedCount
        totalPeersFound = peerCount
        
        android.util.Log.d("DownloadActivity", "Found $seedCount seeds, $peerCount total peers (${this.leecherCount} leechers)")
        statusTextView.text = "Found $peerCount peers: $seedCount seeds, ${this.leecherCount} leechers"
        timeRemainingTextView.text = "Establishing connections to $peerCount peers for download"
        
        // Update current peer count
        currentPeerCount = peerCount
        if (peerCount > maxPeersObserved) {
            maxPeersObserved = peerCount
        }
    }

    override fun onMetadataFetching() {
        android.util.Log.d("DownloadActivity", "Fetching metadata...")
        statusTextView.text = "Fetching file metadata..."
        timeRemainingTextView.text = "Getting file information from peers"
    }

    override fun onMetadataComplete() {
        android.util.Log.d("DownloadActivity", "Metadata complete")
        statusTextView.text = "Metadata received successfully"
        timeRemainingTextView.text = "File information downloaded, starting transfer"
    }

    override fun onReadyToSeed() {
        android.util.Log.d("DownloadActivity", "Ready to seed")
        statusTextView.text = "File verified, ready to share"
        timeRemainingTextView.text = "Preparing to seed file to other users"
    }

    override fun onPhaseChanged(phase: DownloadPhase, timeoutSeconds: Int) {
        android.util.Log.d("DownloadActivity", "Phase changed to $phase with ${timeoutSeconds}s timeout")
        currentPhase = phase
        phaseStartTime = System.currentTimeMillis()
        phaseTimeoutSeconds = timeoutSeconds
        
        // Update UI based on new phase
        when (phase) {
            DownloadPhase.METADATA_FETCHING -> {
                statusTextView.text = "Fetching file information..."
                timeRemainingTextView.text = "Getting details from torrent network"
            }
            DownloadPhase.PEER_DISCOVERY -> {
                statusTextView.text = "Searching for peers..."
                timeRemainingTextView.text = "Finding users sharing this file"
            }
            DownloadPhase.ACTIVE_DOWNLOADING -> {
                statusTextView.text = "Starting download..."
                timeRemainingTextView.text = "Beginning file transfer"
            }
            DownloadPhase.VERIFICATION -> {
                statusTextView.text = "Verifying download..."
                timeRemainingTextView.text = "Checking file integrity"
            }
        }
    }

    // DHT diagnostics callbacks to show detailed status in UI
    override fun onDhtDiagnostic(message: String) {
        android.util.Log.d("DownloadActivity", "DHT Diagnostic: $message")
        statusTextView.text = "DHT Status: $message"
        // Show peer count information instead of generic diagnostics message
        timeRemainingTextView.text = "Found: $totalPeersFound peers | Connected: $connectedPeers peers"
    }

    override fun onSessionDiagnostic(message: String) {
        android.util.Log.d("DownloadActivity", "Enhanced Session Diagnostic: $message")
        timeRemainingTextView.text = "Enhanced Session: $message"
    }
    
    // FrostWire-style enhanced callbacks implementation
    override fun onPeerStatusChanged(totalPeers: Int, seeds: Int, connectingPeers: Int) {
        val leechers = totalPeers - seeds
        android.util.Log.d("DownloadActivity", "Enhanced peer status: $totalPeers total ($seeds seeds, $leechers leechers, $connectingPeers connecting)")
        
        this.seedCount = seeds
        this.leecherCount = leechers
        this.totalPeersFound = totalPeers
        this.currentPeerCount = totalPeers
        
        if (totalPeers > maxPeersObserved) {
            maxPeersObserved = totalPeers
        }
        
        // Enhanced peer status display
        val peerQuality = when {
            seeds >= 5 && totalPeers >= 10 -> "Excellent"
            seeds >= 2 && totalPeers >= 5 -> "Good"
            seeds >= 1 && totalPeers >= 2 -> "Fair"
            totalPeers > 0 -> "Limited"
            else -> "None"
        }
        
        statusTextView.text = "Peer Status ($peerQuality): $totalPeers peers ($seeds seeds, $leechers leechers)"
        timeRemainingTextView.text = if (connectingPeers > 0) {
            "Active connections: $totalPeers | Connecting: $connectingPeers"
        } else {
            "Connected peers: $totalPeers | Seeds: $seeds | Download sources available"
        }
    }
    
    override fun onDownloadStagnant(stagnantTimeSeconds: Int, activePeers: Int) {
        android.util.Log.w("DownloadActivity", "Enhanced download stagnant for ${stagnantTimeSeconds}s with $activePeers active peers")
        
        val stagnantReason = when {
            activePeers == 0 -> "No active peers available"
            activePeers < 3 -> "Limited peer availability"
            else -> "Network or peer issues possible"
        }
        
        statusTextView.text = "⚠️ Download stagnant (${stagnantTimeSeconds}s): $stagnantReason"
        timeRemainingTextView.text = "Active peers: $activePeers | Attempting to find additional sources..."
    }
    
    override fun onSessionQualityChanged(dhtNodes: Int, downloadRate: Int, uploadRate: Int) {
        android.util.Log.d("DownloadActivity", "Enhanced session quality: DHT($dhtNodes) ↓${downloadRate/1024}KB/s ↑${uploadRate/1024}KB/s")
        
        // Update our tracking variables
        this.dhtNodeCount = dhtNodes
        
        val sessionQuality = when {
            dhtNodes >= 50 && downloadRate > 50000 -> "Excellent"
            dhtNodes >= 20 && downloadRate > 10000 -> "Good"
            dhtNodes >= 10 && downloadRate > 1000 -> "Fair"
            dhtNodes > 0 -> "Limited"
            else -> "Poor"
        }
        
        // Only update UI if this represents a significant change
        if (downloadRate > 0) {
            val downloadSpeedKB = downloadRate / 1024
            val uploadSpeedKB = uploadRate / 1024
            timeRemainingTextView.text = "Session Quality: $sessionQuality | DHT: $dhtNodes nodes | ↓${downloadSpeedKB}KB/s ↑${uploadSpeedKB}KB/s"
        }
    }
    
    /**
     * Start network monitoring for FrostWire integration
     */
    private fun startNetworkMonitoring() {
        networkChangeListener = { isWiFi ->
            android.util.Log.d("DownloadActivity", "Network change detected: WiFi=$isWiFi")
            
            // Notify TorrentManager of network change (FrostWire pattern)
            torrentManager.handleNetworkChange(isWiFi)
            
            // Update UI based on network type
            val networkType = if (isWiFi) "WiFi/Ethernet" else "Mobile Data"
            
            runOnUiThread {
                if (!isP2PAttemptFinished) {
                    timeRemainingTextView.text = "Network changed to $networkType - optimizing settings..."
                }
            }
        }
        
        networkManager.startNetworkMonitoring(networkChangeListener!!)
        android.util.Log.d("DownloadActivity", "Network monitoring started (FrostWire integration)")
    }
    
    /**
     * Stop network monitoring
     */
    private fun stopNetworkMonitoring() {
        networkManager.stopNetworkMonitoring()
        networkChangeListener = null
        android.util.Log.d("DownloadActivity", "Network monitoring stopped")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Ensure cleanup of network monitoring and torrent manager
        stopNetworkMonitoring()
        
        if (this::torrentManager.isInitialized) {
            torrentManager.shutdown()
        }
    }


    private suspend fun extractFiles(archiveFile: File) {
        withContext(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                statusTextView.text = "Extracting files..."
                timeRemainingTextView.text = ""
            }

            val tarInputStream = TarArchiveInputStream(GZIPInputStream(archiveFile.inputStream()))
            val picturesDir = File(filesDir, "pictures")
            picturesDir.mkdirs()

            var entry = tarInputStream.nextTarEntry
            var filesExtracted = 0
            while (entry != null) {
                val file = File(picturesDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    val fileOutputStream = FileOutputStream(file)
                    tarInputStream.copyTo(fileOutputStream)
                    fileOutputStream.close()
                    filesExtracted++
                }
                entry = tarInputStream.nextTarEntry
            }
            tarInputStream.close()

            // Always keep the archive file for potential seeding
            // This ensures the file is available for seeding even after app updates
            android.util.Log.d("DownloadActivity", "Archive kept for seeding")

            withContext(Dispatchers.Main) {
                statusTextView.text = "Extraction complete. $filesExtracted files extracted. File kept for seeding."
            }

            android.util.Log.d("DownloadActivity", "$filesExtracted files extracted to ${picturesDir.absolutePath}")

            picturesDir.listFiles()?.forEach {
                android.util.Log.d("DownloadActivity", "Extracted file: ${it.name}")
            }
        }

        getSharedPreferences("AppState", MODE_PRIVATE).edit().putBoolean("filesDownloaded", true).apply()

        delay(2000)

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun startHttpDownload() {
        val primaryUrl = getString(R.string.pictures_primary_url)
        
        GlobalScope.launch(Dispatchers.IO) {
            val success = downloadFromUrl(primaryUrl, "Direct")
            
            if (!success) {
                GlobalScope.launch(Dispatchers.Main) {
                    statusTextView.text = "Download failed"
                    timeRemainingTextView.text = "Please check your internet connection and try again"
                }
            }
        }
    }
    
    private suspend fun downloadFromUrl(urlString: String, @Suppress("UNUSED_PARAMETER") urlType: String): Boolean {
        return try {
            withContext(Dispatchers.Main) {
                statusTextView.text = "HTTPS Download: Connecting..."
                timeRemainingTextView.text = "Establishing connection..."
                progressBar.progress = 0
            }
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()
            
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                android.util.Log.w("DownloadActivity", "HTTPS download failed: ${connection.responseCode}")
                withContext(Dispatchers.Main) {
                    statusTextView.text = "HTTPS download failed (${connection.responseCode})"
                }
                return false
            }
            
            val fileLength = connection.contentLength
            val downloadFile = File(filesDir, "pictures.tar.gz")
            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(downloadFile)
            
            downloadStartTime = System.currentTimeMillis()
            lastProgressUpdate = downloadStartTime
            
            val buffer = ByteArray(8192)
            var totalDownloaded = 0L
            var bytesRead: Int
            
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalDownloaded += bytesRead
                
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastProgressUpdate > 500) { // Update every 500ms
                    lastProgressUpdate = currentTime
                    
                    val progress = if (fileLength > 0) {
                        (totalDownloaded.toFloat() / fileLength * 100).toInt()
                    } else 0
                    
                    val elapsedSeconds = (currentTime - downloadStartTime) / 1000
                    val downloadRate = if (elapsedSeconds > 0) {
                        (totalDownloaded / elapsedSeconds).toInt()
                    } else 0
                    
                    val downloadedMB = totalDownloaded / (1024 * 1024)
                    val totalMB = fileLength / (1024 * 1024)
                    val downloadSpeedKB = downloadRate / 1024
                    
                    val estimatedSeconds = if (downloadRate > 0 && fileLength > 0) {
                        ((fileLength - totalDownloaded) / downloadRate).toInt()
                    } else 0
                    
                    withContext(Dispatchers.Main) {
                        progressBar.progress = progress
                        statusTextView.text = if (fileLength > 0) {
                            "HTTPS Download: $progress% (${downloadedMB}MB/${totalMB}MB)"
                        } else {
                            "HTTPS Download: ${downloadedMB}MB downloaded"
                        }
                        timeRemainingTextView.text = if (downloadRate > 0) {
                            "Speed: ${downloadSpeedKB}KB/s | ETA: ${formatTime(estimatedSeconds)}"
                        } else {
                            "Downloading..."
                        }
                    }
                }
            }
            
            outputStream.close()
            inputStream.close()
            connection.disconnect()
            
            android.util.Log.d("DownloadActivity", "HTTPS download completed successfully")
            
            // Clear any P2P resume data since HTTPS download succeeded
            clearResumeData()
            
            withContext(Dispatchers.Main) {
                extractFiles(downloadFile)
            }
            
            true
        } catch (e: Exception) {
            android.util.Log.e("DownloadActivity", "HTTPS download failed", e)
            withContext(Dispatchers.Main) {
                statusTextView.text = "HTTPS download failed: ${e.message}"
            }
            false
        }
    }

    private fun formatTime(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return when {
            hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, remainingSeconds)
            minutes > 0 -> String.format("%d:%02d", minutes, remainingSeconds)
            else -> String.format("%d seconds", remainingSeconds)
        }
    }
    
    private fun clearResumeData() {
        try {
            // Clear resume preferences
            val resumePrefs = getSharedPreferences("torrent_resume", MODE_PRIVATE)
            resumePrefs.edit().clear().apply()
            
            android.util.Log.d("DownloadActivity", "Resume data cleared from DownloadActivity")
        } catch (e: Exception) {
            android.util.Log.e("DownloadActivity", "Error clearing resume data", e)
        }
    }
}