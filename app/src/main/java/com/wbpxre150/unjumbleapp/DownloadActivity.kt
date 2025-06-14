package com.wbpxre150.unjumbleapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

class DownloadActivity : Activity(), TorrentDownloadListener {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var timeRemainingTextView: TextView
    private lateinit var torrentManager: TorrentManager
    private var isP2PAttemptFinished = false

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
        
        torrentManager.downloadFile(magnetLink, downloadPath, this)
    }

    // TorrentDownloadListener implementation
    override fun onProgress(downloaded: Long, total: Long, downloadRate: Int, peers: Int) {
        val progress = if (total > 0) (downloaded.toFloat() / total * 100).toInt() else 0
        progressBar.progress = progress
        
        val downloadSpeedKB = downloadRate / 1024
        val remainingBytes = total - downloaded
        val estimatedSeconds = if (downloadRate > 0) (remainingBytes / downloadRate).toInt() else 0
        
        val downloadedMB = downloaded / (1024 * 1024)
        val totalMB = total / (1024 * 1024)
        
        statusTextView.text = if (total > 0) {
            "P2P Download: $progress% (${downloadedMB}MB/${totalMB}MB) - $peers peers"
        } else {
            "P2P Download: Connecting... ($peers peers)"
        }
        
        timeRemainingTextView.text = if (downloadRate > 0) {
            "Speed: ${downloadSpeedKB}KB/s | ETA: ${formatTime(estimatedSeconds)}"
        } else if (peers > 0) {
            "Connected to $peers peers - waiting for data..."
        } else {
            "Searching for peers..."
        }
    }

    override fun onCompleted(filePath: String) {
        if (isP2PAttemptFinished) {
            android.util.Log.d("DownloadActivity", "P2P attempt already finished, ignoring completion")
            return
        }
        isP2PAttemptFinished = true
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
        android.util.Log.w("DownloadActivity", "P2P download failed: $error")
        GlobalScope.launch(Dispatchers.Main) {
            statusTextView.text = "P2P download failed: $error"
            timeRemainingTextView.text = "Please check your internet connection and try again"
        }
    }

    override fun onTimeout() {
        if (isP2PAttemptFinished) {
            android.util.Log.d("DownloadActivity", "P2P attempt already finished, ignoring timeout")
            return
        }
        isP2PAttemptFinished = true
        android.util.Log.w("DownloadActivity", "P2P download timed out")
        GlobalScope.launch(Dispatchers.Main) {
            statusTextView.text = "P2P download timed out"
            timeRemainingTextView.text = "No peers found. Please try again later."
        }
    }

    override fun onVerifying(progress: Float) {
        val progressPercent = (progress * 100).toInt()
        progressBar.progress = progressPercent
        if (progress == 0.0f) {
            statusTextView.text = "Connecting to P2P network..."
            timeRemainingTextView.text = "Finding peers (this may take a few minutes)..."
        } else {
            statusTextView.text = "Verifying torrent: $progressPercent%"
            timeRemainingTextView.text = "Checking file integrity..."
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