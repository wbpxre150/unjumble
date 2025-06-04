package com.wbpxre150.unjumbleapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import kotlin.math.roundToInt

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
        statusTextView.text = "Connecting to peers..."
        timeRemainingTextView.text = "Attempting P2P download..."
        
        val magnetLink = getString(R.string.pictures_magnet_link)
        val downloadPath = File(cacheDir, "pictures.tar.gz").absolutePath
        
        torrentManager.downloadFile(magnetLink, downloadPath, this)
    }

    // TorrentDownloadListener implementation
    override fun onProgress(downloaded: Long, total: Long, downloadRate: Int, peers: Int) {
        val progress = if (total > 0) (downloaded.toFloat() / total * 100).toInt() else 0
        progressBar.progress = progress
        
        val downloadSpeedKB = downloadRate / 1024
        val remainingBytes = total - downloaded
        val estimatedSeconds = if (downloadRate > 0) (remainingBytes / downloadRate).toInt() else 0
        
        statusTextView.text = "P2P Download: $progress% ($peers peers)"
        timeRemainingTextView.text = "Speed: ${downloadSpeedKB}KB/s | ETA: ${formatTime(estimatedSeconds)}"
    }

    override fun onCompleted(filePath: String) {
        isP2PAttemptFinished = true
        GlobalScope.launch(Dispatchers.Main) {
            try {
                extractFiles(File(filePath))
                // Start seeding after successful extraction, keeping the original file
                torrentManager.seedFile(filePath)
            } catch (e: Exception) {
                e.printStackTrace()
                statusTextView.text = "Error extracting files: ${e.message}"
            }
        }
    }

    override fun onError(error: String) {
        isP2PAttemptFinished = true
        android.util.Log.w("DownloadActivity", "P2P download failed: $error")
        GlobalScope.launch(Dispatchers.Main) {
            fallbackToHttpsDownload()
        }
    }

    override fun onTimeout() {
        isP2PAttemptFinished = true
        android.util.Log.w("DownloadActivity", "P2P download timed out")
        GlobalScope.launch(Dispatchers.Main) {
            fallbackToHttpsDownload()
        }
    }

    private suspend fun fallbackToHttpsDownload() {
        statusTextView.text = "P2P unavailable, downloading directly..."
        timeRemainingTextView.text = "Switching to HTTPS download..."
        downloadAndExtractFiles()
    }

    private suspend fun downloadAndExtractFiles() {
        val url = URL("https://unjumble.au/files/pictures.tar.gz")

        withContext(Dispatchers.IO) {
            val connection = url.openConnection()
            val fileLength = connection.contentLength.toLong()

            val inputStream = connection.getInputStream()
            val outputFile = File(cacheDir, "pictures.tar.gz")
            val outputStream = FileOutputStream(outputFile)

            var bytesRead = 0L
            val buffer = ByteArray(8192)
            var count: Int
            var startTime = System.currentTimeMillis()
            var lastUpdateTime = startTime

            while (inputStream.read(buffer).also { count = it } != -1) {
                outputStream.write(buffer, 0, count)
                bytesRead += count
                val progress = (bytesRead.toFloat() / fileLength * 100).toInt()

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastUpdateTime >= 1000) { // Update every second
                    val elapsedSeconds = (currentTime - startTime) / 1000.0
                    val downloadSpeed = bytesRead / elapsedSeconds // bytes per second
                    val remainingBytes = fileLength - bytesRead
                    val estimatedRemainingSeconds = (remainingBytes / downloadSpeed).roundToInt()

                    withContext(Dispatchers.Main) {
                        progressBar.progress = progress
                        statusTextView.text = "Downloading: $progress%"
                        timeRemainingTextView.text = "Estimated time remaining: ${formatTime(estimatedRemainingSeconds)}"
                    }

                    lastUpdateTime = currentTime
                }
            }

            outputStream.close()
            inputStream.close()

            extractFiles(outputFile)
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

            // Only delete the archive file if seeding is disabled
            val shouldDeleteArchive = !torrentManager.isSeedingEnabled()
            if (shouldDeleteArchive) {
                archiveFile.delete()
                android.util.Log.d("DownloadActivity", "Archive deleted (seeding disabled)")
            } else {
                android.util.Log.d("DownloadActivity", "Archive kept for seeding")
            }

            withContext(Dispatchers.Main) {
                val statusMsg = if (shouldDeleteArchive) {
                    "Extraction complete. $filesExtracted files extracted."
                } else {
                    "Extraction complete. $filesExtracted files extracted. File kept for seeding."
                }
                statusTextView.text = statusMsg
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