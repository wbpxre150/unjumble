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

class DownloadActivity : Activity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var timeRemainingTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)

        progressBar = findViewById(R.id.progressBar)
        statusTextView = findViewById(R.id.statusTextView)
        timeRemainingTextView = findViewById(R.id.timeRemainingTextView)

        GlobalScope.launch(Dispatchers.Main) {
            try {
                downloadAndExtractFiles()
            } catch (e: Exception) {
                e.printStackTrace()
                statusTextView.text = "Error: ${e.message}"
                android.util.Log.e("DownloadActivity", "Error during download/extraction", e)
            }
        }
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

            withContext(Dispatchers.Main) {
                statusTextView.text = "Extracting files..."
                timeRemainingTextView.text = ""
            }

            val tarInputStream = TarArchiveInputStream(GZIPInputStream(outputFile.inputStream()))
            val picturesDir = File(filesDir, "pictures")
            picturesDir.mkdirs()

            var entry = tarInputStream.nextTarEntry
            var filesExtracted = 0
            while (entry != null) {
                val file = File(picturesDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()  // Ensure parent directories exist
                    val fileOutputStream = FileOutputStream(file)
                    tarInputStream.copyTo(fileOutputStream)
                    fileOutputStream.close()
                    filesExtracted++
                }
                entry = tarInputStream.nextTarEntry
            }
            tarInputStream.close()

            outputFile.delete()

            withContext(Dispatchers.Main) {
                statusTextView.text = "Extraction complete. $filesExtracted files extracted."
            }

            // Log the number of files extracted and their location
            android.util.Log.d("DownloadActivity", "$filesExtracted files extracted to ${picturesDir.absolutePath}")

            // List the extracted files
            picturesDir.listFiles()?.forEach {
                android.util.Log.d("DownloadActivity", "Extracted file: ${it.name}")
            }
        }

        getSharedPreferences("AppState", MODE_PRIVATE).edit().putBoolean("filesDownloaded", true).apply()

        // Delay to show the extraction complete message
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