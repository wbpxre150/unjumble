package com.wbpxre150.unjumbleapp

enum class DownloadPhase {
    METADATA_FETCHING,
    PEER_DISCOVERY, 
    ACTIVE_DOWNLOADING,
    VERIFICATION;
    
    // FrostWire-style phase descriptions
    fun getDescription(): String {
        return when (this) {
            METADATA_FETCHING -> "Fetching torrent metadata from DHT and trackers"
            PEER_DISCOVERY -> "Discovering and connecting to peers sharing this file"
            ACTIVE_DOWNLOADING -> "Actively downloading file data from connected peers"
            VERIFICATION -> "Verifying downloaded file integrity and completing"
        }
    }
    
    fun getExpectedDurationSeconds(): Int {
        return when (this) {
            METADATA_FETCHING -> 120 // FrostWire uses longer timeouts
            PEER_DISCOVERY -> 60
            ACTIVE_DOWNLOADING -> 300 // Depends on file size and peers
            VERIFICATION -> 30
        }
    }
}

interface TorrentDownloadListener {
    fun onProgress(downloaded: Long, total: Long, downloadRate: Int, peers: Int)
    fun onCompleted(filePath: String)
    fun onError(error: String)
    fun onTimeout()
    fun onVerifying(progress: Float)
    
    // Enhanced status callbacks for better torrent lifecycle tracking
    fun onDhtConnecting()
    fun onDhtConnected(nodeCount: Int)
    fun onDiscoveringPeers()
    fun onSeedsFound(seedCount: Int, peerCount: Int)
    fun onMetadataFetching()
    fun onMetadataComplete()
    fun onReadyToSeed()
    
    // Enhanced diagnostics callbacks (FrostWire pattern)
    fun onDhtDiagnostic(message: String)
    fun onSessionDiagnostic(message: String)
    
    // Phase-based communication to prevent timeout conflicts
    fun onPhaseChanged(phase: DownloadPhase, timeoutSeconds: Int)
    
    // FrostWire-style enhanced callbacks
    fun onPeerStatusChanged(totalPeers: Int, seeds: Int, connectingPeers: Int) {
        // Default implementation for backward compatibility
        onSeedsFound(seeds, totalPeers)
    }
    
    fun onDownloadStagnant(stagnantTimeSeconds: Int, activePeers: Int) {
        // Default implementation for backward compatibility  
        onSessionDiagnostic("Download stagnant for ${stagnantTimeSeconds}s despite $activePeers peers")
    }
    
    fun onSessionQualityChanged(dhtNodes: Int, downloadRate: Int, uploadRate: Int) {
        // Default implementation for backward compatibility
        onSessionDiagnostic("Session quality: DHT($dhtNodes) ↓${downloadRate/1024}KB/s ↑${uploadRate/1024}KB/s")
    }
}