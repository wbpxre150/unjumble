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
    private lateinit var torrentManager: TorrentManager
    private var isP2PAttemptFinished = false
    private var downloadStartTime: Long = 0
    private var lastProgressUpdate: Long = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var currentPhase: DownloadPhase = DownloadPhase.METADATA_FETCHING
    private var phaseStartTime: Long = 0
    private var phaseTimeoutSeconds: Int = 0
    private var isDhtConnected = false
    private var currentPeerCount = 0
    private var hasActiveProgress = false
    private var lastProgressBytes: Long = 0

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
        torrentManager = TorrentManager.getInstance(this)

        GlobalScope.launch(Dispatchers.Main) {
            try {
                startP2PDownload()
            } catch (e: Exception) {
                e.printStackTrace()
                statusTextView.text = "Error: ${e.message}"
                android.util.Log.e("DownloadActivity", "Error during download/extraction", e)
            }
        }
    }

    private fun startP2PDownload() {
        statusTextView.text = "Initializing P2P download..."
        timeRemainingTextView.text = "Starting P2P download..."
        
        val magnetLink = getString(R.string.pictures_magnet_link)
        val downloadPath = File(filesDir, "pictures.tar.gz").absolutePath
        
        // Store magnet link for future seeding sessions
        val prefs = getSharedPreferences("torrent_prefs", MODE_PRIVATE)
        prefs.edit().putString("magnet_link", magnetLink).apply()
        
        statusTextView.text = "Starting P2P download..."
        timeRemainingTextView.text = "Connecting to DHT network..."
        
        // Phase-based timeout management - let TorrentManager handle timeouts
        phaseStartTime = System.currentTimeMillis()
        currentPhase = DownloadPhase.METADATA_FETCHING
        
        torrentManager.downloadFile(magnetLink, downloadPath, this)
    }

    // TorrentDownloadListener implementation
    override fun onProgress(downloaded: Long, total: Long, downloadRate: Int, peers: Int) {
        currentPeerCount = peers
        
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
        statusTextView.text = when (currentPhase) {
            DownloadPhase.METADATA_FETCHING -> {
                if (total > 0) "File info received, starting download..." 
                else "Fetching file information from $peers peers"
            }
            DownloadPhase.PEER_DISCOVERY -> {
                "Found $peers peers, establishing connections..."
            }
            DownloadPhase.ACTIVE_DOWNLOADING -> {
                if (total > 0) {
                    "Downloading: $progress% (${downloadedMB}MB/${totalMB}MB) from $peers peers"
                } else {
                    "Preparing download from $peers peers..."
                }
            }
            DownloadPhase.VERIFICATION -> {
                "Verifying download..."
            }
        }
        
        timeRemainingTextView.text = if (downloadRate > 0) {
            "Speed: ${downloadSpeedKB}KB/s | ETA: ${formatTime(estimatedSeconds)}"
        } else if (peers > 0) {
            when (currentPhase) {
                DownloadPhase.METADATA_FETCHING -> "Getting file details from peers..."
                DownloadPhase.PEER_DISCOVERY -> "Connecting to $peers available peers..."
                DownloadPhase.ACTIVE_DOWNLOADING -> "Connected to $peers peers - waiting for data..."
                DownloadPhase.VERIFICATION -> "Checking file integrity..."
            }
        } else {
            when (currentPhase) {
                DownloadPhase.METADATA_FETCHING -> "Searching for peers with this file..."
                DownloadPhase.PEER_DISCOVERY -> "No peers found yet, still searching..."
                DownloadPhase.ACTIVE_DOWNLOADING -> "No active peers, reconnecting..."
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
        hasActiveProgress = false
        lastProgressBytes = 0
        android.util.Log.w("DownloadActivity", "P2P download failed: $error")
        GlobalScope.launch(Dispatchers.Main) {
            statusTextView.text = "P2P download failed, trying HTTPS fallback..."
            timeRemainingTextView.text = "Switching to direct download"
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
        hasActiveProgress = false
        lastProgressBytes = 0
        android.util.Log.w("DownloadActivity", "P2P download timed out")
        GlobalScope.launch(Dispatchers.Main) {
            statusTextView.text = "P2P download timed out, trying HTTPS fallback..."
            timeRemainingTextView.text = "Switching to direct download"
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

    // Enhanced status callbacks for better torrent lifecycle tracking
    override fun onDhtConnecting() {
        android.util.Log.d("DownloadActivity", "DHT connecting...")
        statusTextView.text = "Connecting to DHT network..."
        timeRemainingTextView.text = "Initializing P2P connection"
    }

    override fun onDhtConnected(nodeCount: Int) {
        isDhtConnected = true
        android.util.Log.d("DownloadActivity", "DHT connected with $nodeCount nodes")
        statusTextView.text = "DHT network connected"
        timeRemainingTextView.text = if (nodeCount > 0) "Connected to $nodeCount DHT nodes" else "DHT connection established"
    }

    override fun onDiscoveringPeers() {
        android.util.Log.d("DownloadActivity", "Discovering peers...")
        statusTextView.text = "Searching for peers..."
        timeRemainingTextView.text = "Looking for other users sharing this file"
    }

    override fun onSeedsFound(seedCount: Int, peerCount: Int) {
        android.util.Log.d("DownloadActivity", "Found $seedCount seeds, $peerCount total peers")
        statusTextView.text = "Found peers: $seedCount seeds, ${peerCount - seedCount} leechers"
        timeRemainingTextView.text = "Connecting to $peerCount peers for download"
        
        // Update current peer count
        currentPeerCount = peerCount
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
    
    private suspend fun downloadFromUrl(urlString: String, urlType: String): Boolean {
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
}