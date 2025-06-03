package com.wbpxre150.unjumbleapp

interface TorrentDownloadListener {
    fun onProgress(downloaded: Long, total: Long, downloadRate: Int, peers: Int)
    fun onCompleted(filePath: String)
    fun onError(error: String)
    fun onTimeout()
}