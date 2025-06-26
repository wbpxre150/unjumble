package com.wbpxre150.unjumbleapp

import android.content.Context
import android.os.Handler
import android.util.Log
import com.frostwire.jlibtorrent.SessionManager
import java.io.File

object TorrentUtils {
    private const val TAG = "TorrentUtils"
    
    fun extractTrackersFromMagnet(magnetLink: String): List<String> {
        val trackers = mutableListOf<String>()
        try {
            val parts = magnetLink.split("&")
            for (part in parts) {
                if (part.startsWith("tr=")) {
                    val trackerUrl = java.net.URLDecoder.decode(part.substring(3), "UTF-8")
                    if (isValidTrackerUrl(trackerUrl)) {
                        trackers.add(trackerUrl)
                        Log.d(TAG, "📡 Valid tracker found: $trackerUrl")
                    } else {
                        Log.w(TAG, "⚠️ Invalid tracker URL: $trackerUrl")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error extracting trackers from magnet", e)
        }
        return trackers
    }
    
    fun isValidTrackerUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "udp") && 
            uri.host != null && uri.port > 0
        } catch (e: Exception) {
            false
        }
    }
    
    fun extractInfoHashFromMagnet(magnetLink: String): String {
        return try {
            val xtPart = magnetLink.substringAfter("xt=urn:btih:").substringBefore("&")
            if (xtPart.length == 40 || xtPart.length == 32) {
                xtPart
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error extracting info hash from magnet", e)
            ""
        }
    }
    
    fun fetchMetadataConcurrently(
        magnetLink: String, 
        trackerUrls: List<String>, 
        listener: TorrentDownloadListener,
        sessionManager: SessionManager?,
        handler: Handler,
        context: Context
    ): ByteArray? {
        Log.d(TAG, "🔄 Starting concurrent metadata resolution...")
        Log.d(TAG, "  📡 ${trackerUrls.size} trackers available")
        Log.d(TAG, "  🌐 DHT nodes: ${sessionManager?.stats()?.dhtNodes() ?: 0}")
        
        return try {
            val timeout = 120 // Increased timeout to match SimpleTorrentManager
            Log.d(TAG, "🎯 Attempting enhanced fetchMagnet with ${timeout}s timeout (FrostWire pattern)...")
            
            handler.post {
                val dhtNodes = sessionManager?.stats()?.dhtNodes()?.toInt() ?: 0
                listener.onSessionDiagnostic("Fetching metadata: DHT($dhtNodes) + Trackers(${trackerUrls.size}) - ${timeout}s timeout")
            }
            
            val startTime = System.currentTimeMillis()
            
            val progressThread = Thread {
                try {
                    while (true) {
                        Thread.sleep(5000)
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                        val remaining = timeout - elapsed
                        
                        if (remaining <= 0) break
                        
                        handler.post {
                            val dhtNodes = sessionManager?.stats()?.dhtNodes()?.toInt() ?: 0
                            listener.onDhtDiagnostic("Fetching... ${elapsed.toInt()}s elapsed, ${remaining.toInt()}s left (DHT: $dhtNodes nodes)")
                        }
                    }
                } catch (e: InterruptedException) {
                    // Thread interrupted, stop updates
                }
            }
            progressThread.start()
            
            val tempDir = File(context.cacheDir, "magnet_temp")
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            val metadata = sessionManager?.fetchMagnet(magnetLink, timeout, tempDir)
            
            // Cleanup temp directory
            try {
                tempDir.listFiles()?.let { files ->
                    for (file in files) {
                        if (file.isFile && file.lastModified() < System.currentTimeMillis() - 3600000) {
                            file.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup temp dir: ${e.message}")
            }
            
            progressThread.interrupt()
            
            val endTime = System.currentTimeMillis()
            val duration = (endTime - startTime) / 1000.0
            
            if (metadata != null && metadata.isNotEmpty()) {
                Log.d(TAG, "✅ Metadata fetched successfully in ${duration}s (${metadata.size} bytes)")
                
                val sourceMessage = when {
                    duration < 10 -> "✅ FAST (${duration.toInt()}s) - likely from trackers"
                    duration < 30 -> "✅ MEDIUM (${duration.toInt()}s) - mixed DHT/tracker"
                    else -> "✅ SLOW (${duration.toInt()}s) - likely from DHT"
                }
                
                handler.post {
                    listener.onSessionDiagnostic("Metadata found! $sourceMessage (${metadata.size} bytes)")
                }
                
                if (metadata.size > 2 * 1024 * 1024) {
                    Log.w(TAG, "⚠️ Metadata size (${metadata.size} bytes) exceeds 2MB limit")
                    handler.post {
                        listener.onSessionDiagnostic("❌ Metadata too large (${metadata.size} bytes > 2MB limit)")
                    }
                    return null
                }
                
                handler.post {
                    val dhtNodes = sessionManager?.stats()?.dhtNodes()?.toInt() ?: 0
                    val seedCount = Math.min(trackerUrls.size, 5)
                    listener.onSeedsFound(seedCount, dhtNodes)
                }
                
                metadata
            } else {
                Log.e(TAG, "❌ fetchMagnet returned null or empty metadata after ${duration}s")
                handler.post {
                    val dhtNodes = sessionManager?.stats()?.dhtNodes()?.toInt() ?: 0
                    listener.onSessionDiagnostic("❌ Metadata fetch failed after ${duration.toInt()}s (DHT: $dhtNodes, Trackers: ${trackerUrls.size})")
                }
                null
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error during concurrent metadata fetch", e)
            
            val errorMessage = when {
                e.message?.contains("timeout", ignoreCase = true) == true -> {
                    val dhtNodes = sessionManager?.stats()?.dhtNodes()?.toInt() ?: 0
                    "⏱️ TIMEOUT: Neither DHT($dhtNodes) nor trackers(${trackerUrls.size}) responded"
                }
                e.message?.contains("network", ignoreCase = true) == true -> {
                    "🌐 NETWORK ERROR: ${e.message}"
                }
                e.message?.contains("interrupted", ignoreCase = true) == true -> {
                    "⏹️ CANCELLED: Metadata fetch interrupted"
                }
                else -> {
                    "💥 ERROR: ${e.message ?: "Unknown error"}"
                }
            }
            
            handler.post {
                listener.onSessionDiagnostic(errorMessage)
            }
            
            null
        }
    }
}