package com.wbpxre150.unjumbleapp

enum class DownloadPhase {
    METADATA_FETCHING,
    PEER_DISCOVERY,
    ACTIVE_DOWNLOADING,
    VERIFICATION
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
    
    // DHT diagnostics callbacks for detailed status reporting
    fun onDhtDiagnostic(message: String)
    fun onSessionDiagnostic(message: String)
    
    // Phase-based communication to prevent timeout conflicts
    fun onPhaseChanged(phase: DownloadPhase, timeoutSeconds: Int)
}