package com.wbpxre150.unjumbleapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*

class SplashActivity : Activity() {

    private lateinit var statusTextView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        statusTextView = findViewById(R.id.splashStatusTextView)
        statusTextView.text = "Initializing P2P library..."

        // Initialize LibTorrent4j on background thread
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // This will initialize SimpleTorrentManager and load jlibtorrent
                val torrentManager = SimpleTorrentManager.getInstance(this@SplashActivity)
                
                withContext(Dispatchers.Main) {
                    if (torrentManager.isLibraryLoaded()) {
                        statusTextView.text = "P2P library loaded successfully"
                    } else {
                        statusTextView.text = "P2P library not available, using HTTPS"
                    }
                    
                    // Small delay to show the status
                    delay(1000)
                    
                    // Proceed to DownloadActivity
                    proceedToDownloadActivity()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    statusTextView.text = "Library initialization failed, using HTTPS"
                    delay(1000)
                    proceedToDownloadActivity()
                }
            }
        }
    }

    private fun proceedToDownloadActivity() {
        startActivity(Intent(this, DownloadActivity::class.java))
        finish()
    }
}