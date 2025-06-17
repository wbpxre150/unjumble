package com.wbpxre150.unjumbleapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.alerts.*
import com.frostwire.jlibtorrent.swig.*
import java.io.FileInputStream
import java.io.FileOutputStream

class TorrentManager private constructor(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var downloadListener: TorrentDownloadListener? = null
    private var isDownloading = false
    private var timeoutRunnable: Runnable? = null
    private var isSeeding = false
    private var isLibraryAvailable = false
    private var sessionManager: SessionManager? = null
    private var currentTorrentHandle: TorrentHandle? = null
    private var progressMonitorThread: Thread? = null
    private var dhtMonitorThread: Thread? = null
    private var currentDownloadPath: String = ""
    private var isVerifying = false
    private var verificationStartTime: Long = 0
    private val VERIFICATION_TIMEOUT_MS = 120000L // 2 minutes timeout for verification
    private val networkManager = NetworkManager.getInstance(context)
    private var isNetworkTransitioning = false
    private var networkTransitionStartTime: Long = 0
    private val NETWORK_TRANSITION_TIMEOUT_MS = 15000L // 15 seconds for network transitions
    private var lastProgressTime: Long = 0
    private var lastBytesDownloaded: Long = 0
    private var downloadPhase: DownloadPhase = DownloadPhase.METADATA_FETCHING
    private var metadataStartTime: Long = 0
    private var phaseStartTime: Long = 0
    private var lastResumeDataSave: Long = 0
    private val RESUME_DATA_SAVE_INTERVAL_MS = 30000L // Save resume data every 30 seconds
    private var pendingResumeDataSave = false
    private var currentPort: Int = 0
    private var waitingForMetadata = false
    private var currentMagnetLink: String = ""
    private var alertProcessingThread: Thread? = null
    private var isAlertProcessingActive = false
    private var lastProgressUpdateTime: Long = 0
    private var lastDownloadRate: Long = 0
    private val progressCalculationWindow = 5000L // 5 seconds for rate calculation
    private var bytesDownloadedInWindow: Long = 0
    private var windowStartTime: Long = 0
    
    companion object {
        private const val TAG = "TorrentManager"
        private const val NO_PROGRESS_TIMEOUT_MS = 240000L // 4 minutes with no download progress before timeout
        private const val METADATA_TIMEOUT_MS = 180000L // 3 minutes for metadata fetching with alert-based processing
        private const val RESUME_PREFS = "torrent_resume"
        
        @Volatile
        private var INSTANCE: TorrentManager? = null
        
        fun getInstance(context: Context): TorrentManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TorrentManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    init {
        // Initialize jlibtorrent immediately when TorrentManager singleton is created
        initializeSession()
        // Start network monitoring to handle dynamic seeding control
        startNetworkMonitoring()
    }
    
    private fun initializeSession() {
        Log.d(TAG, "Starting jlibtorrent initialization...")
        
        try {
            // Step 1: Try to load jlibtorrent native library
            Log.d(TAG, "Step 1: Loading jlibtorrent native library...")
            // Try the versioned library name first
            try {
                System.loadLibrary("jlibtorrent-1.2.19.0")
                Log.d(TAG, "✓ Native library 'jlibtorrent-1.2.19.0' loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Failed to load versioned library, trying generic name...")
                System.loadLibrary("jlibtorrent")
                Log.d(TAG, "✓ Native library 'jlibtorrent' loaded successfully")
            }
            
            // Step 2: Initialize session manager
            Log.d(TAG, "Step 2: Creating SessionManager...")
            sessionManager = SessionManager()
            Log.d(TAG, "✓ SessionManager created successfully")
            
            // Step 3: FrostWire-style session state management
            Log.d(TAG, "Step 3: Loading session state (FrostWire approach)...")
            val settingsPack = loadSessionSettings()
            Log.d(TAG, "✓ Session settings loaded with FrostWire optimizations and state persistence")
            Log.d(TAG, "  ✓ FIXED: No aggressive tracker timeouts - trackers can respond naturally")
            Log.d(TAG, "  ✓ DHT + trackers run concurrently for faster peer discovery") 
            Log.d(TAG, "  ✓ Using optimized Android settings with session state persistence")
            
            // Step 4: Apply settings to session
            Log.d(TAG, "Step 4: Applying settings to session...")
            sessionManager?.applySettings(settingsPack)
            Log.d(TAG, "✓ Settings applied successfully")
            
            // Step 5: Start the session
            Log.d(TAG, "Step 5: Starting jlibtorrent session...")
            sessionManager?.start()
            Log.d(TAG, "✓ Session started successfully")
            
            // Step 5a: Comprehensive session state logging
            Log.d(TAG, "Step 5a: Logging session state after start...")
            try {
                val stats = sessionManager?.stats()
                val sessionDiag = "Session stats available: ${stats != null}"
                Log.d(TAG, "  $sessionDiag")
                
                if (stats != null) {
                    val dhtDiag = "DHT nodes: ${stats.dhtNodes()}, DL: ${stats.downloadRate()}B/s, UL: ${stats.uploadRate()}B/s"
                    Log.d(TAG, "  $dhtDiag")
                } else {
                    Log.w(TAG, "  Session stats not available yet")
                }
            } catch (e: Exception) {
                Log.w(TAG, "  Session stats error: ${e.message}")
            }
            
            // Step 5b: FIXED - Let DHT bootstrap naturally (true FrostWire pattern)
            Log.d(TAG, "Step 5b: DHT will bootstrap naturally during session lifecycle...")
            // REMOVED: Explicit sessionManager?.startDht() call
            // FrostWire lets DHT start automatically when DHT is enabled in settings
            Log.d(TAG, "✓ DHT will connect automatically - no explicit start needed")
            
            // Step 5c: Quick DHT status check (non-blocking)
            Log.d(TAG, "Step 5c: Quick DHT status check...")
            try {
                val stats = sessionManager?.stats()
                val isDhtRunning = try {
                    sessionManager?.isDhtRunning() ?: false
                } catch (e: Exception) {
                    Log.w(TAG, "  isDhtRunning() error: ${e.message}")
                    false
                }
                
                Log.d(TAG, "  DHT status: running=$isDhtRunning, will connect asynchronously")
                if (stats != null) {
                    Log.d(TAG, "  Initial DHT nodes: ${stats.dhtNodes()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "  DHT status check error: ${e.message}")
            }
            
            // Step 6: FrostWire approach - let DHT connect naturally during downloads
            Log.d(TAG, "Step 6: DHT will connect asynchronously during downloads (FrostWire pattern)")
            Log.d(TAG, "  ✓ DHT initialization complete - connections will happen during fetchMagnet operations")
            
            // Port will be set by loadSessionSettings or configureDefaultSettings
            Log.d(TAG, "Current session port: $currentPort (configured during settings load)")
            isLibraryAvailable = true
            
            // Step 7: Start alert processing loop for metadata and download events
            Log.d(TAG, "Step 7: Starting alert processing loop...")
            startAlertProcessingLoop()
            
            Log.d(TAG, "🎉 jlibtorrent initialization completed successfully!")
            Log.d(TAG, "   Library available: $isLibraryAvailable")
            Log.d(TAG, "   Default port: $currentPort")
            Log.d(TAG, "   Alert processing: $isAlertProcessingActive")
            
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "❌ NATIVE LIBRARY LOADING FAILED")
            Log.e(TAG, "   Error type: UnsatisfiedLinkError")
            Log.e(TAG, "   Error message: ${e.message}")
            Log.e(TAG, "   Stack trace: ${e.stackTraceToString()}")
            isLibraryAvailable = false
            currentPort = 6881
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "❌ CLASS NOT FOUND ERROR")
            Log.e(TAG, "   Error type: NoClassDefFoundError") 
            Log.e(TAG, "   Error message: ${e.message}")
            Log.e(TAG, "   Missing class likely due to ProGuard or dependency issue")
            isLibraryAvailable = false
            currentPort = 6881
        } catch (e: Exception) {
            Log.e(TAG, "❌ GENERAL INITIALIZATION ERROR")
            Log.e(TAG, "   Error type: ${e.javaClass.simpleName}")
            Log.e(TAG, "   Error message: ${e.message}")
            Log.e(TAG, "   Stack trace: ${e.stackTraceToString()}")
            isLibraryAvailable = false
            currentPort = 6881
        }
        
        Log.d(TAG, "Final initialization state: isLibraryAvailable = $isLibraryAvailable")
    }
    
    // FrostWire-style session state persistence for better peer discovery
    private fun loadSessionSettings(): SettingsPack {
        val settingsPack = SettingsPack()
        
        try {
            val prefs = context.getSharedPreferences("torrent_session", Context.MODE_PRIVATE)
            
            // Load saved session configuration if available
            if (prefs.getBoolean("has_saved_settings", false)) {
                Log.d(TAG, "📂 Loading saved session settings (FrostWire-style persistence)...")
                
                // Restore DHT settings
                val dhtEnabled = prefs.getBoolean("dht_enabled", true)
                settingsPack.enableDht(dhtEnabled)
                
                // Restore connection settings
                val connectionsLimit = prefs.getInt("connections_limit", 200)
                settingsPack.setInteger(settings_pack.int_types.connections_limit.swigValue(), connectionsLimit)
                
                // Restore active limits
                val activeDownloads = prefs.getInt("active_downloads", 4)
                val activeSeeds = prefs.getInt("active_seeds", 4)
                settingsPack.setInteger(settings_pack.int_types.active_downloads.swigValue(), activeDownloads)
                settingsPack.setInteger(settings_pack.int_types.active_seeds.swigValue(), activeSeeds)
                
                // Restore additional FrostWire-style settings
                val uploadRateLimit = prefs.getInt("upload_rate_limit", 0)
                val downloadRateLimit = prefs.getInt("download_rate_limit", 0)
                settingsPack.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), uploadRateLimit)
                settingsPack.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), downloadRateLimit)
                
                val upnpEnabled = prefs.getBoolean("upnp_enabled", true)
                val natpmpEnabled = prefs.getBoolean("natpmp_enabled", true)
                settingsPack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), upnpEnabled)
                settingsPack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), natpmpEnabled)
                
                // Configure network interface with random high port (always fresh for security)
                val randomPort = (49152..65534).random()
                settingsPack.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:$randomPort")
                currentPort = randomPort
                
                val lastSessionTime = prefs.getLong("last_session_time", 0)
                Log.d(TAG, "✅ Session settings restored: DHT=$dhtEnabled, Connections=$connectionsLimit, Port=$currentPort, LastSession=${if (lastSessionTime > 0) "${(System.currentTimeMillis() - lastSessionTime) / 1000}s ago" else "never"}")
            } else {
                Log.d(TAG, "🆕 No saved settings found - using optimized defaults")
                configureDefaultSettings(settingsPack)
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to load session settings: ${e.message}")
            configureDefaultSettings(settingsPack)
        }
        
        return settingsPack
    }
    
    private fun configureDefaultSettings(settingsPack: SettingsPack) {
        // Configure all the optimized settings that were previously in initializeSession
        Log.d(TAG, "🔧 Configuring optimized default settings...")
        
        // Enable DHT for peer discovery
        settingsPack.enableDht(true)
        
        // Configure explicit DHT bootstrap nodes (same as FrostWire)
        val dhtBootstrapNodes = "dht.libtorrent.org:25401,router.bittorrent.com:6881,dht.transmissionbt.com:6881,router.silotis.us:6881"
        settingsPack.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), dhtBootstrapNodes)
        
        // FrostWire's critical session settings for DHT connectivity (FIXED)
        settingsPack.setInteger(settings_pack.int_types.alert_queue_size.swigValue(), 1000)  // FrostWire standard
        settingsPack.setInteger(settings_pack.int_types.active_limit.swigValue(), 2000)
        settingsPack.setInteger(settings_pack.int_types.stop_tracker_timeout.swigValue(), 0)  // FrostWire: no timeout
        
        // Network interface configuration - use random high port
        val randomPort = (49152..65534).random()
        settingsPack.setString(settings_pack.string_types.listen_interfaces.swigValue(), "0.0.0.0:$randomPort")
        currentPort = randomPort
        
        // Android-optimized connection limits (FrostWire memory-optimized approach)
        settingsPack.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200)   // Reduced for mobile
        settingsPack.setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), 8)    // Mobile-friendly
        settingsPack.setInteger(settings_pack.int_types.active_downloads.swigValue(), 4)       // FrostWire optimized
        settingsPack.setInteger(settings_pack.int_types.active_seeds.swigValue(), 4)          // FrostWire optimized
        
        // CRITICAL FIX: Make DHT work concurrently with trackers
        settingsPack.setBoolean(settings_pack.bool_types.use_dht_as_fallback.swigValue(), false)
        settingsPack.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        settingsPack.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        
        // Enhanced peer discovery settings
        settingsPack.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true)
        settingsPack.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
        settingsPack.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
        settingsPack.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
        settingsPack.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
        
        // Optimize DHT announcement timing
        settingsPack.setInteger(settings_pack.int_types.dht_announce_interval.swigValue(), 300)
        
        // Enhanced alert types
        settingsPack.setInteger(settings_pack.int_types.alert_mask.swigValue(), 
            AlertType.METADATA_RECEIVED.swig() or AlertType.DHT_BOOTSTRAP.swig() or 
            AlertType.TRACKER_ANNOUNCE.swig() or AlertType.TRACKER_REPLY.swig() or 
            AlertType.PEER_CONNECT.swig() or AlertType.ADD_TORRENT.swig() or
            AlertType.DHT_REPLY.swig() or AlertType.PEER_DISCONNECTED.swig() or
            AlertType.STATE_CHANGED.swig() or AlertType.TRACKER_ERROR.swig() or
            AlertType.DHT_GET_PEERS.swig() or AlertType.PIECE_FINISHED.swig() or
            AlertType.BLOCK_FINISHED.swig() or AlertType.TORRENT_FINISHED.swig() or
            AlertType.TORRENT_RESUMED.swig() or AlertType.TORRENT_PAUSED.swig() or
            AlertType.TORRENT_CHECKED.swig() or AlertType.FILE_COMPLETED.swig() or
            AlertType.TORRENT_ERROR.swig() or AlertType.FILE_ERROR.swig() or
            AlertType.METADATA_FAILED.swig())
        
        // CRITICAL FIX: Remove aggressive tracker timeouts (FrostWire approach)
        settingsPack.setInteger(settings_pack.int_types.auto_manage_startup.swigValue(), 10)
        
        // Save these default settings for next session
        saveSessionSettings(settingsPack)
    }
    
    private fun saveSessionSettings(settingsPack: SettingsPack) {
        try {
            val prefs = context.getSharedPreferences("torrent_session", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Save key settings for session restoration like FrostWire
            editor.putBoolean("has_saved_settings", true)
            
            // Save actual current session settings
            try {
                editor.putBoolean("dht_enabled", settingsPack.getBoolean(settings_pack.bool_types.enable_dht.swigValue()))
                editor.putInt("connections_limit", settingsPack.getInteger(settings_pack.int_types.connections_limit.swigValue()))
                editor.putInt("active_downloads", settingsPack.getInteger(settings_pack.int_types.active_downloads.swigValue()))
                editor.putInt("active_seeds", settingsPack.getInteger(settings_pack.int_types.active_seeds.swigValue()))
                
                // Save additional FrostWire-style settings for better persistence
                editor.putInt("upload_rate_limit", settingsPack.getInteger(settings_pack.int_types.upload_rate_limit.swigValue()))
                editor.putInt("download_rate_limit", settingsPack.getInteger(settings_pack.int_types.download_rate_limit.swigValue()))
                editor.putBoolean("upnp_enabled", settingsPack.getBoolean(settings_pack.bool_types.enable_upnp.swigValue()))
                editor.putBoolean("natpmp_enabled", settingsPack.getBoolean(settings_pack.bool_types.enable_natpmp.swigValue()))
                
                Log.d(TAG, "💾 Current session settings saved: DHT=${settingsPack.getBoolean(settings_pack.bool_types.enable_dht.swigValue())}, Connections=${settingsPack.getInteger(settings_pack.int_types.connections_limit.swigValue())}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to read current settings, using defaults for save")
                // Fallback to defaults if we can't read current settings
                editor.putBoolean("dht_enabled", true)
                editor.putInt("connections_limit", 200)
                editor.putInt("active_downloads", 4)
                editor.putInt("active_seeds", 4)
            }
            
            editor.putLong("last_session_time", System.currentTimeMillis())
            editor.apply()
            Log.d(TAG, "💾 Session settings saved for next startup (FrostWire-style persistence)")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Failed to save session settings: ${e.message}")
        }
    }
    
    fun downloadFile(magnetLink: String, downloadPath: String, listener: TorrentDownloadListener) {
        if (isDownloading) {
            listener.onError("Download already in progress")
            return
        }
        
        downloadListener = listener
        isDownloading = true
        currentDownloadPath = downloadPath
        
        // Initialize tracking variables
        lastProgressTime = System.currentTimeMillis()
        lastBytesDownloaded = 0
        metadataStartTime = System.currentTimeMillis()
        lastResumeDataSave = System.currentTimeMillis()
        downloadPhase = DownloadPhase.METADATA_FETCHING
        pendingResumeDataSave = false
        
        // Check for existing resume data first
        if (hasResumeData(magnetLink, downloadPath)) {
            Log.d(TAG, "Found resume data - attempting to resume download")
            resumeDownload(magnetLink, downloadPath, listener)
            return
        }
        
        // Store magnet link for resume data
        val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString("magnet_link", magnetLink).apply()
        
        // Notify initial phase
        handler.post {
            listener.onPhaseChanged(DownloadPhase.METADATA_FETCHING, 60)
        }
        
        // jlibtorrent should already be initialized in init block
        continueDownloadAfterInit(magnetLink, downloadPath, listener)
    }
    
    private fun hasResumeData(magnetLink: String, downloadPath: String): Boolean {
        val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
        val downloadFile = File(downloadPath)
        
        // Check if we have progress metadata and the partial download file exists
        return prefs.getBoolean("has_resume_data", false) &&
               prefs.getString("magnet_link", "") == magnetLink &&
               prefs.getString("download_path", "") == downloadPath &&
               downloadFile.exists() &&
               downloadFile.length() > 0
    }
    
    private fun resumeDownload(magnetLink: String, downloadPath: String, listener: TorrentDownloadListener) {
        if (!isLibraryAvailable || sessionManager == null) {
            Log.d(TAG, "🔄 jlibtorrent not available - falling back to fresh download")
            continueDownloadAfterInit(magnetLink, downloadPath, listener)
            return
        }
        
        try {
            val downloadFile = File(downloadPath)
            if (!downloadFile.exists() || downloadFile.length() == 0L) {
                Log.w(TAG, "🔄 Resume: No partial download file found - starting fresh")
                clearResumeData()
                continueDownloadAfterInit(magnetLink, downloadPath, listener)
                return
            }
            
            val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            val savedSize = prefs.getLong("downloaded_size", 0L)
            val fileSize = downloadFile.length()
            
            Log.d(TAG, "🔄 Resume: Found partial file ${fileSize} bytes, saved progress was ${savedSize} bytes")
            
            // Use improved FrostWire approach for resume
            Log.d(TAG, "🚀 Resume: Using FrostWire-style metadata fetching...")
            
            // Extract tracker info for better resume reliability
            val trackerUrls = TorrentUtils.extractTrackersFromMagnet(magnetLink)
            Log.d(TAG, "📡 Resume: Found ${trackerUrls.size} trackers for resume")
            
            Thread {
                try {
                    handler.post {
                        listener.onDhtConnecting()
                    }
                    
                    // Skip DHT verification - start immediately for faster peer discovery
                    handler.post {
                        listener.onDhtDiagnostic("Starting P2P resume - DHT connecting automatically")
                    }
                    
                    // Get DHT status
                    val sessionStats = sessionManager?.stats()
                    val actualDhtNodes = sessionStats?.dhtNodes()?.toInt() ?: 0
                    val isDhtRunning = try {
                        sessionManager?.isDhtRunning() ?: false
                    } catch (e: Exception) {
                        false
                    }
                    
                    Log.d(TAG, "📊 Resume: DHT status: running=$isDhtRunning, nodes=$actualDhtNodes")
                    
                    handler.post {
                        listener.onDhtConnected(actualDhtNodes)
                        listener.onMetadataFetching()
                    }
                    
                    // Use FrostWire fetchMagnet for reliable resume
                    Log.d(TAG, "🎯 Resume: Fetching metadata with FrostWire approach...")
                    val metadata = TorrentUtils.fetchMetadataConcurrently(magnetLink, trackerUrls, listener, sessionManager, handler)
                    
                    if (metadata != null && metadata.isNotEmpty()) {
                        Log.d(TAG, "✅ Resume: Metadata fetched successfully (${metadata.size} bytes)")
                        
                        val torrentInfo = TorrentInfo.bdecode(metadata)
                        Log.d(TAG, "📊 Resume: Torrent info: ${torrentInfo.name()}, size: ${torrentInfo.totalSize()} bytes")
                        
                        handler.post {
                            listener.onMetadataComplete()
                        }
                        
                        // Download with existing partial file (jlibtorrent will auto-resume)
                        val downloadDir = File(downloadPath).parentFile
                        sessionManager?.download(torrentInfo, downloadDir)
                        
                        Thread.sleep(1000) // Give session time to process
                        currentTorrentHandle = sessionManager?.find(torrentInfo.infoHash())
                        
                        if (currentTorrentHandle?.isValid == true) {
                            Log.d(TAG, "🎯 Resume: Download resumed with metadata - monitoring progress...")
                            transitionToPhase(DownloadPhase.ACTIVE_DOWNLOADING, listener)
                            // Progress monitoring now handled by alerts
                        } else {
                            Log.e(TAG, "❌ Resume: Failed to get valid torrent handle")
                            clearResumeData()
                            continueDownloadAfterInit(magnetLink, downloadPath, listener)
                        }
                        
                    } else {
                        Log.e(TAG, "❌ Resume: Failed to fetch metadata - falling back to fresh download")
                        clearResumeData()
                        continueDownloadAfterInit(magnetLink, downloadPath, listener)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Resume: Error during resume attempt", e)
                    if (isDownloading) {
                        clearResumeData()
                        continueDownloadAfterInit(magnetLink, downloadPath, listener)
                    }
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "💥 Resume: Error during resume attempt - starting fresh download", e)
            clearResumeData()
            continueDownloadAfterInit(magnetLink, downloadPath, listener)
        }
    }
    
    private fun saveResumeData() {
        if (!isDownloading || currentTorrentHandle == null || !currentTorrentHandle!!.isValid) {
            return
        }
        
        if (pendingResumeDataSave) {
            Log.d(TAG, "Resume data save already pending")
            return
        }
        
        try {
            pendingResumeDataSave = true
            Log.d(TAG, "Saving torrent progress metadata...")
            
            // Instead of complex resume data, save current progress metadata
            // This will help us know if we should attempt resume
            val status = currentTorrentHandle!!.status()
            val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            
            prefs.edit().apply {
                putBoolean("has_resume_data", true)
                putString("download_path", currentDownloadPath)
                putLong("total_size", status.totalWanted())
                putLong("downloaded_size", status.totalWantedDone())
                putLong("last_save_time", System.currentTimeMillis())
                if (currentTorrentHandle?.isValid == true) {
                    putString("info_hash", currentTorrentHandle!!.infoHash().toString())
                }
                apply()
            }
            
            lastResumeDataSave = System.currentTimeMillis()
            
            val downloadedMB = status.totalWantedDone() / (1024 * 1024)
            val totalMB = status.totalWanted() / (1024 * 1024)
            val progress = if (status.totalWanted() > 0) {
                (status.totalWantedDone().toFloat() / status.totalWanted() * 100).toInt()
            } else 0
            
            Log.d(TAG, "Progress metadata saved: $progress% complete (${downloadedMB}MB/${totalMB}MB)")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving progress metadata", e)
        } finally {
            pendingResumeDataSave = false
        }
    }
    
    // This method is no longer needed with the simplified approach
    // jlibtorrent will auto-resume based on existing partial files
    
    private fun clearResumeData() {
        try {
            val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            
            Log.d(TAG, "Resume progress metadata cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing resume data", e)
        }
    }
    
    private fun continueDownloadAfterInit(magnetLink: String, downloadPath: String, listener: TorrentDownloadListener) {
        if (!isLibraryAvailable || sessionManager == null) {
            Log.d(TAG, "jlibtorrent not available - calling onError to trigger fallback")
            isDownloading = false
            listener.onError("P2P library not available")
            return
        }
        
        try {
            val downloadDir = File(downloadPath).parentFile
            if (downloadDir != null && !downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            Log.d(TAG, "🚀 Starting FrostWire-style metadata fetching for: $magnetLink")
            
            // Extract and validate tracker URLs
            val trackerUrls = TorrentUtils.extractTrackersFromMagnet(magnetLink)
            Log.d(TAG, "📡 Found ${trackerUrls.size} trackers: $trackerUrls")
            
            // Validate info hash
            val infoHash = TorrentUtils.extractInfoHashFromMagnet(magnetLink)
            if (infoHash.isEmpty()) {
                Log.e(TAG, "❌ Invalid magnet link - no info hash found")
                listener.onError("Invalid magnet link: no info hash")
                isDownloading = false
                return
            }
            Log.d(TAG, "🔍 Info hash: $infoHash")
            
            currentMagnetLink = magnetLink
            
            // Check network status for debugging
            Log.d(TAG, "🌐 Network status check:")
            Log.d(TAG, "  WiFi: ${networkManager.isOnWiFi()}")
            Log.d(TAG, "  Mobile data: ${networkManager.isOnMobileData()}")
            
            // Use FrostWire approach: fetchMagnet first, then download
            Thread {
                try {
                    Log.d(TAG, "🔗 Starting DHT connection verification...")
                    handler.post {
                        listener.onDhtConnecting()
                    }
                    
                    // Skip DHT verification - start immediately for faster peer discovery
                    handler.post {
                        listener.onDhtDiagnostic("Starting P2P download - DHT connecting in background")
                    }
                    
                    // Get DHT status with UI updates
                    var dhtRunning = false
                    var actualDhtNodes = 0
                    
                    try {
                        val sessionStats = sessionManager?.stats()
                        actualDhtNodes = sessionStats?.dhtNodes()?.toInt() ?: 0
                        dhtRunning = try {
                            sessionManager?.isDhtRunning() ?: false
                        } catch (e: Exception) {
                            false
                        }
                        
                        Log.d(TAG, "📊 DHT status: running=$dhtRunning, nodes=$actualDhtNodes")
                        
                        // Update UI with DHT status
                        handler.post {
                            listener.onDhtDiagnostic("DHT: $actualDhtNodes nodes connected, ${trackerUrls.size} trackers found")
                        }
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ DHT status check error: ${e.message}")
                        handler.post {
                            listener.onDhtDiagnostic("DHT: Error checking status - ${e.message}")
                        }
                    }
                    
                    handler.post {
                        listener.onDhtConnected(actualDhtNodes)
                        listener.onMetadataFetching()
                    }
                    
                    // Start concurrent DHT + tracker metadata resolution with UI updates
                    Log.d(TAG, "🔄 Starting concurrent DHT + tracker metadata resolution...")
                    
                    handler.post {
                        listener.onSessionDiagnostic("Starting metadata fetch: DHT($actualDhtNodes nodes) + Trackers(${trackerUrls.size})")
                    }
                    
                    // FIXED: Set waitingForMetadata flag before starting fetch
                    waitingForMetadata = true
                    Log.d(TAG, "🎯 Set waitingForMetadata = true for alert processing")
                    
                    val metadata = TorrentUtils.fetchMetadataConcurrently(magnetLink, trackerUrls, listener, sessionManager, handler)
                    
                    if (metadata != null && metadata.isNotEmpty()) {
                        Log.d(TAG, "✅ Metadata fetched successfully (${metadata.size} bytes)")
                        
                        // Parse metadata into TorrentInfo
                        val torrentInfo = TorrentInfo.bdecode(metadata)
                        Log.d(TAG, "📊 Torrent info: ${torrentInfo.name()}, size: ${torrentInfo.totalSize()} bytes")
                        
                        handler.post {
                            listener.onMetadataComplete()
                        }
                        
                        // Now download with metadata using FrostWire approach
                        sessionManager?.download(torrentInfo, downloadDir)
                        
                        // Give session time to process the torrent
                        Thread.sleep(1000)
                        currentTorrentHandle = sessionManager?.find(torrentInfo.infoHash())
                        
                        if (currentTorrentHandle?.isValid == true) {
                            Log.d(TAG, "🎯 Download started with metadata - monitoring progress...")
                            
                            // FIXED: Explicitly resume torrent (FrostWire pattern)
                            currentTorrentHandle?.resume()
                            Log.d(TAG, "✅ Torrent explicitly resumed for download")
                            
                            // FORCE REANNOUNCE: Force immediate tracker announces to find more peers
                            forceReannounceToTrackers(currentTorrentHandle, "after metadata fetch")
                            
                            // Check if this is a resume case
                            val downloadFile = File(downloadPath)
                            val isResuming = downloadFile.exists() && downloadFile.length() > 0
                            
                            if (isResuming) {
                                Log.d(TAG, "🔄 Resuming download - existing file: ${downloadFile.length()} bytes")
                            }
                            
                            // FIXED: Check for peer connections before transitioning to download
                            val status = currentTorrentHandle?.status()
                            val numPeers = status?.numPeers()?.toInt() ?: 0
                            val numSeeds = status?.numSeeds()?.toInt() ?: 0
                            
                            if (numPeers > 0 || numSeeds > 0) {
                                Log.d(TAG, "✅ Found $numPeers peers ($numSeeds seeds) - transitioning to active download")
                                transitionToPhase(DownloadPhase.ACTIVE_DOWNLOADING, listener)
                            } else {
                                Log.d(TAG, "⏳ Metadata ready but no peers yet - staying in peer discovery")
                                transitionToPhase(DownloadPhase.PEER_DISCOVERY, listener)
                            }
                            // Progress monitoring now handled by alerts
                        } else {
                            Log.e(TAG, "❌ Failed to get valid torrent handle after metadata fetch")
                            handler.post {
                                listener.onError("Failed to start download with metadata")
                            }
                            isDownloading = false
                        }
                        
                    } else {
                        Log.e(TAG, "❌ Failed to fetch metadata from both DHT and trackers")
                        handler.post {
                            listener.onTimeout()
                        }
                        isDownloading = false
                    }
                    
                } catch (e: InterruptedException) {
                    Log.d(TAG, "⏹️ Metadata fetch interrupted")
                    if (isDownloading) {
                        handler.post {
                            listener.onTimeout()
                        }
                        isDownloading = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Failed during FrostWire-style metadata fetch", e)
                    if (isDownloading) {
                        handler.post {
                            listener.onError("Metadata fetch failed: ${e.message}")
                        }
                        isDownloading = false
                    }
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "💥 Failed to start FrostWire-style P2P download", e)
            listener.onError("Failed to start P2P download: ${e.message}")
            isDownloading = false
        }
    }
    
    // DEPRECATED: DHT peer monitoring now handled by alerts
    private fun startDhtPeerMonitoring(listener: TorrentDownloadListener) {
        // Stop any existing DHT monitoring thread
        dhtMonitorThread?.interrupt()
        
        // Start DHT peer monitoring thread during metadata fetching
        dhtMonitorThread = Thread {
            Log.d(TAG, "[DHT MONITORING] Starting real peer discovery monitoring during metadata phase")
            var lastReportedPeers = -1
            val startTime = System.currentTimeMillis()
            
            while (isDownloading && (System.currentTimeMillis() - startTime) < 120000) { // Monitor for 120 seconds max
                try {
                    // Get DHT and session statistics for real peer discovery
                    val sessionStats = sessionManager?.stats()
                    val dhtNodes = sessionStats?.dhtNodes()?.toInt() ?: 0
                    val downloadRate = sessionStats?.downloadRate()?.toInt() ?: 0
                    val uploadRate = sessionStats?.uploadRate()?.toInt() ?: 0
                    
                    // Use DHT nodes as a proxy for potential peer connectivity
                    val potentialPeers = Math.min(dhtNodes, 50) // Cap at reasonable number
                    
                    // Only report if peer count changed significantly or every 5 seconds
                    val timeSinceStart = (System.currentTimeMillis() - startTime) / 1000
                    val shouldReport = (potentialPeers != lastReportedPeers) || (timeSinceStart.toInt() % 5 == 0)
                    
                    if (shouldReport && isDownloading) {
                        lastReportedPeers = potentialPeers
                        Log.d(TAG, "[DHT MONITORING] DHT nodes: $dhtNodes, DL: ${downloadRate}B/s, UL: ${uploadRate}B/s, reporting: $potentialPeers")
                        
                        // DHT status analysis
                        when {
                            dhtNodes == 0 && timeSinceStart > 15 -> {
                                Log.w(TAG, "[DHT MONITORING] DHT bootstrap appears to have failed after ${timeSinceStart}s - no DHT nodes")
                            }
                            dhtNodes > 0 && downloadRate == 0 && timeSinceStart > 45 -> {
                                Log.w(TAG, "[DHT MONITORING] DHT is working ($dhtNodes nodes) but no download activity after ${timeSinceStart}s")
                            }
                            dhtNodes > 0 && downloadRate > 0 -> {
                                Log.d(TAG, "[DHT MONITORING] Good: DHT working and download active at ${downloadRate}B/s")
                            }
                        }
                        
                        handler.post {
                            listener.onProgress(0, 0, 0, potentialPeers) // Report real DHT-based peer count
                        }
                    }
                    
                    Thread.sleep(1500) // Update every 1.5 seconds for responsiveness
                    
                } catch (e: Exception) {
                    Log.w(TAG, "[DHT MONITORING] Error getting DHT stats: ${e.message}")
                    // Fallback to showing 0 peers instead of fake count
                    if (isDownloading && lastReportedPeers != 0) {
                        lastReportedPeers = 0
                        handler.post {
                            listener.onProgress(0, 0, 0, 0)
                        }
                    }
                    Thread.sleep(2000)
                }
            }
            
            Log.d(TAG, "[DHT MONITORING] DHT peer monitoring completed - transitioning to torrent-based monitoring")
        }
        dhtMonitorThread?.start()
    }
    
    // DEPRECATED: Metadata and peer monitoring now handled by alerts
    private fun startMetadataAndPeerMonitoring(listener: TorrentDownloadListener) {
        progressMonitorThread = Thread {
            var hasMetadata = false
            var seedsReported = false
            var maxPeersObserved = 0
            var peerDropWarningTime = 0L
            
            while (isDownloading && currentTorrentHandle != null && currentTorrentHandle!!.isValid) {
                try {
                    val status = currentTorrentHandle!!.status()
                    val numPeers = status.numPeers().toInt()
                    val numSeeds = try { status.numSeeds() } catch (e: Exception) { 0 }
                    val hasMetadataFlag = status.hasMetadata()
                    val currentTime = System.currentTimeMillis()
                    
                    // Track maximum peers observed for connection stability analysis
                    if (numPeers > maxPeersObserved) {
                        maxPeersObserved = numPeers
                        Log.d(TAG, "[PEER TRACKING] New peak peer count: $maxPeersObserved")
                    }
                    
                    // Warn about significant peer drops (the main issue we're fixing)
                    if (maxPeersObserved >= 2 && numPeers < maxPeersObserved && currentTime - peerDropWarningTime > 30000) {
                        Log.w(TAG, "[PEER DROP DETECTED] Peers dropped from $maxPeersObserved to $numPeers - investigating cause")
                        Log.w(TAG, "[PEER DROP] Network transitioning: $isNetworkTransitioning, Session valid: ${sessionManager != null}")
                        peerDropWarningTime = currentTime
                    }
                    
                    Log.d(TAG, "[$downloadPhase] Status: peers=$numPeers, seeds=$numSeeds, hasMetadata=$hasMetadataFlag, state=${status.state()}, downloadRate=${status.downloadRate()}")
                    
                    // Report seeds found if we have them and haven't reported yet
                    if (!seedsReported && (numSeeds > 0 || numPeers > 0) && isDownloading) {
                        seedsReported = true
                        Log.d(TAG, "[PEER DISCOVERY] Found peers/seeds: $numSeeds seeds, $numPeers total peers")
                        Log.d(TAG, "[PEER DISCOVERY] Connection established successfully - monitoring for stability")
                        handler.post {
                            listener.onSeedsFound(numSeeds, numPeers)
                        }
                    }
                    
                    // Check for metadata timeout
                    if (checkTimeouts(0, listener)) {
                        break
                    }
                    
                    // Check if we got metadata
                    if (!hasMetadata && hasMetadataFlag && isDownloading) {
                        hasMetadata = true
                        Log.d(TAG, "Metadata received! Starting download monitoring...")
                        handler.post {
                            listener.onProgress(0, status.totalWanted(), 0, numPeers)
                        }
                    }
                    
                    // If we have metadata, switch to full progress monitoring
                    if (hasMetadata) {
                        Log.d(TAG, "Metadata acquired, total size: ${status.totalWanted()} bytes")
                        transitionToPhase(DownloadPhase.ACTIVE_DOWNLOADING, downloadListener!!)
                        // Progress monitoring now handled by alerts
                        break
                    }
                    
                    // Update progress even without metadata to show peer discovery
                    if (isDownloading) {
                        handler.post {
                            // Use 0 total to indicate we're still fetching metadata
                            listener.onProgress(0, 0, 0, numPeers)
                        }
                    }
                    
                    Thread.sleep(2000) // Check every 2 seconds for metadata/peers
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring metadata/peers", e)
                    if (isDownloading) {
                        handler.post {
                            listener.onError("Metadata monitoring error: ${e.message}")
                        }
                    }
                    break
                }
            }
        }
        progressMonitorThread?.start()
    }
    
    private fun startVerification(listener: TorrentDownloadListener) {
        currentTorrentHandle?.let { handle ->
            if (!handle.isValid) {
                listener.onError("Invalid torrent handle for verification")
                return
            }
            
            isVerifying = true
            verificationStartTime = System.currentTimeMillis()
            transitionToPhase(DownloadPhase.VERIFICATION, listener)
            Log.d(TAG, "Starting torrent verification...")
            
            // Force verification of pieces
            handle.forceRecheck()
            
            // Start verification monitoring
            progressMonitorThread = Thread {
                while (isVerifying && handle.isValid) {
                    try {
                        val status = handle.status()
                        val verifyProgress = status.progress()
                        val state = status.state()
                        val currentTime = System.currentTimeMillis()
                        
                        Log.d(TAG, "Verification progress: ${(verifyProgress * 100).toInt()}%, state: $state")
                        
                        // Check verification timeout
                        if (currentTime - verificationStartTime > VERIFICATION_TIMEOUT_MS) {
                            Log.w(TAG, "Verification timeout after ${VERIFICATION_TIMEOUT_MS}ms - forcing completion")
                            isVerifying = false
                            handler.post {
                                listener.onProgress(0, 0, 0, 0)
                            }
                            break
                        }
                        
                        // Update verification progress
                        handler.post {
                            listener.onVerifying(verifyProgress)
                        }
                        
                        // Check if verification is complete
                        if (state == TorrentStatus.State.DOWNLOADING || state == TorrentStatus.State.FINISHED) {
                            isVerifying = false
                            Log.d(TAG, "Verification completed, starting download monitoring...")
                            
                            // Start normal download monitoring
                            // Metadata and peer monitoring now handled by alerts
                            break
                        }
                        
                        Thread.sleep(1000) // Check every second during verification
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during verification", e)
                        isVerifying = false
                        handler.post {
                            listener.onError("Verification error: ${e.message}")
                        }
                        break
                    }
                }
            }
            progressMonitorThread?.start()
        } ?: run {
            listener.onError("No torrent handle available for verification")
        }
    }
    
    private fun startVerificationForSeeding(listener: TorrentDownloadListener? = null) {
        currentTorrentHandle?.let { handle ->
            if (!handle.isValid) {
                Log.e(TAG, "Invalid torrent handle for seeding verification")
                return
            }
            
            isVerifying = true
            verificationStartTime = System.currentTimeMillis()
            transitionToPhase(DownloadPhase.VERIFICATION, null)
            Log.d(TAG, "Starting torrent verification for seeding...")
            
            // Force verification of pieces
            handle.forceRecheck()
            
            // Start verification monitoring for seeding
            Thread {
                while (isVerifying && handle.isValid) {
                    try {
                        val status = handle.status()
                        val verifyProgress = status.progress()
                        val state = status.state()
                        val currentTime = System.currentTimeMillis()
                        
                        Log.d(TAG, "Seeding verification progress: ${(verifyProgress * 100).toInt()}%, state: $state")
                        
                        // Check verification timeout for seeding
                        if (currentTime - verificationStartTime > VERIFICATION_TIMEOUT_MS) {
                            Log.w(TAG, "Seeding verification timeout after ${VERIFICATION_TIMEOUT_MS}ms - assuming complete")
                            isVerifying = false
                            isSeeding = true
                            break
                        }
                        
                        // Update verification progress if listener is available
                        listener?.let {
                            handler.post {
                                it.onVerifying(verifyProgress)
                            }
                        }
                        
                        // Check if verification is complete
                        if (state == TorrentStatus.State.SEEDING || state == TorrentStatus.State.FINISHED) {
                            isVerifying = false
                            isSeeding = true
                            Log.d(TAG, "Verification for seeding completed - now seeding")
                            break
                        } else if (state == TorrentStatus.State.DOWNLOADING) {
                            isVerifying = false
                            Log.d(TAG, "Verification completed but file incomplete - starting download to complete")
                            break
                        }
                        
                        Thread.sleep(1000) // Check every second during verification
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during seeding verification", e)
                        isVerifying = false
                        break
                    }
                }
            }.start()
        } ?: run {
            Log.e(TAG, "No torrent handle available for seeding verification")
        }
    }
    
    // DEPRECATED: Progress monitoring now handled by alerts
    private fun startRealProgressMonitoring() {
        // Stop the metadata monitoring thread first
        progressMonitorThread?.interrupt()
        
        progressMonitorThread = Thread {
            var lastPeerLogTime = 0L
            while (isDownloading && currentTorrentHandle != null && currentTorrentHandle!!.isValid) {
                try {
                    val status = currentTorrentHandle!!.status()
                    val totalWanted = status.totalWanted()
                    val totalWantedDone = status.totalWantedDone()
                    val downloadRate = status.downloadRate()
                    val numPeers = status.numPeers().toInt()
                    val numSeeds = try { status.numSeeds() } catch (e: Exception) { 0 }
                    val progressPercent = if (totalWanted > 0) (totalWantedDone.toFloat() / totalWanted * 100).toInt() else 0
                    val currentTime = System.currentTimeMillis()
                    
                    // Enhanced peer connection logging every 10 seconds
                    if (currentTime - lastPeerLogTime > 10000) {
                        Log.d(TAG, "[PEER STATUS] Seeds: $numSeeds, Total Peers: $numPeers, Download Rate: ${downloadRate}B/s")
                        Log.d(TAG, "[PEER STATUS] State: ${status.state()}, Progress: $progressPercent%")
                        
                        // Log peer connection details
                        if (numPeers > 0) {
                            Log.d(TAG, "[PEER STABILITY] Maintaining $numPeers peer connections successfully")
                        } else {
                            Log.w(TAG, "[PEER WARNING] No peer connections - attempting reconnection")
                        }
                        lastPeerLogTime = currentTime
                    }
                    
                    Log.d(TAG, "[$downloadPhase] Progress: ${totalWantedDone}/${totalWanted} bytes ($progressPercent%), rate=${downloadRate}B/s, peers=$numPeers, seeds=$numSeeds")
                    
                    // Additional debug info for timeouts
                    when (downloadPhase) {
                        DownloadPhase.METADATA_FETCHING -> {
                            val metadataTime = System.currentTimeMillis() - metadataStartTime
                            val remaining = (METADATA_TIMEOUT_MS - metadataTime) / 1000
                            if (metadataTime > 20000) { // Log after 20 seconds
                                Log.d(TAG, "  Metadata fetching for ${metadataTime / 1000}s, timeout in ${remaining}s")
                            }
                        }
                        DownloadPhase.ACTIVE_DOWNLOADING -> {
                            val noProgressTime = System.currentTimeMillis() - lastProgressTime
                            val remaining = (NO_PROGRESS_TIMEOUT_MS - noProgressTime) / 1000
                            if (noProgressTime > 30000) { // Only log if no progress for >30s
                                Log.d(TAG, "  No progress for ${noProgressTime / 1000}s, timeout in ${remaining}s")
                            }
                        }
                        else -> { /* No timeout logging for other phases */ }
                    }
                    
                    // Check for download progress timeout only
                    if (checkTimeouts(totalWantedDone, downloadListener!!)) {
                        break
                    }
                    
                    // Enhanced completion detection
                    val state = status.state()
                    val isComplete = status.isFinished || 
                                   (totalWanted > 0 && totalWantedDone >= totalWanted) ||
                                   state == TorrentStatus.State.SEEDING ||
                                   (state == TorrentStatus.State.CHECKING_FILES && status.progress() >= 1.0f)
                    
                    Log.d(TAG, "Completion check: isFinished=${status.isFinished}, progress=${status.progress()}, state=$state, totalDone/Wanted=$totalWantedDone/$totalWanted")
                    
                    if (isComplete && isDownloading) {
                        Log.d(TAG, "Download completed - state: $state, progress: ${status.progress()}")
                        
                        // Clear resume data since download is complete
                        clearResumeData()
                        Log.d(TAG, "Resume data cleared after completion")
                        
                        handler.post {
                            downloadListener?.onCompleted(currentDownloadPath)
                        }
                        isDownloading = false
                        break
                    } else if (isComplete && !isDownloading) {
                        Log.d(TAG, "Download was already stopped, not reporting completion")
                        break
                    }
                    
                    // Handle verification phase progress
                    if (state == TorrentStatus.State.CHECKING_FILES && isDownloading) {
                        if (downloadPhase != DownloadPhase.VERIFICATION) {
                            transitionToPhase(DownloadPhase.VERIFICATION, downloadListener)
                        }
                        val verifyProgress = status.progress()
                        Log.d(TAG, "In verification phase: ${(verifyProgress * 100).toInt()}%")
                        handler.post {
                            downloadListener?.onVerifying(verifyProgress)
                        }
                    }
                    
                    // Basic error checking
                    if (!currentTorrentHandle!!.isValid) {
                        if (isDownloading) {
                            handler.post {
                                downloadListener?.onError("Torrent handle became invalid")
                            }
                            isDownloading = false
                        }
                        break
                    }
                    
                    // Update progress only if still downloading
                    if (isDownloading) {
                        handler.post {
                            downloadListener?.onProgress(totalWantedDone, totalWanted, downloadRate, numPeers)
                        }
                        
                        // Periodic resume data saving
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastResumeDataSave > RESUME_DATA_SAVE_INTERVAL_MS && !pendingResumeDataSave) {
                            Log.d(TAG, "Saving resume data (periodic)")
                            saveResumeData()
                        }
                    }
                    
                    // Enhanced progress logging with peer stability tracking
                    if (currentTime % 10000 < 1000) {
                        Log.d(TAG, "[DOWNLOAD PROGRESS] $progressPercent% - Seeds: $numSeeds, Peers: $numPeers - Rate: ${downloadRate}B/s")
                        
                        // Warn if peer count is dropping
                        if (numPeers > 0 && numPeers < 3) {
                            Log.w(TAG, "[PEER WARNING] Low peer count detected: $numPeers peers - connection may be unstable")
                        }
                    }
                    
                    Thread.sleep(1000)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error monitoring download progress", e)
                    if (isDownloading) {
                        handler.post {
                            downloadListener?.onError("Progress monitoring error: ${e.message}")
                        }
                    }
                    break
                }
            }
        }
        progressMonitorThread?.start()
    }
    
    private fun checkTimeouts(totalDone: Long, listener: TorrentDownloadListener): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // Update progress tracking - be more lenient about what constitutes progress
        val hasProgress = totalDone > lastBytesDownloaded || 
                         (currentTorrentHandle?.status()?.downloadRate() ?: 0) > 0
        if (hasProgress) {
            lastProgressTime = currentTime
            if (totalDone > lastBytesDownloaded) {
                val bytesGained = totalDone - lastBytesDownloaded
                Log.d(TAG, "Progress detected: $bytesGained bytes downloaded")
                lastBytesDownloaded = totalDone
            } else {
                Log.d(TAG, "Active download rate detected - resetting progress timer")
            }
        }
        
        // Check timeouts based on current phase
        return when (downloadPhase) {
            DownloadPhase.METADATA_FETCHING -> {
                // Timeout if metadata fetching takes too long
                val metadataTime = currentTime - metadataStartTime
                if (metadataTime > METADATA_TIMEOUT_MS) {
                    Log.d(TAG, "Metadata timeout: fetching took ${metadataTime}ms")
                    triggerTimeout(listener, "Metadata fetching timeout after ${metadataTime / 1000} seconds")
                    true
                } else {
                    val remainingTime = (METADATA_TIMEOUT_MS - metadataTime) / 1000
                    if (metadataTime > 30000) { // Log after 30 seconds
                        Log.d(TAG, "Metadata fetching for ${metadataTime / 1000}s, timeout in ${remainingTime}s")
                    }
                    false
                }
            }
            DownloadPhase.PEER_DISCOVERY -> {
                // Force reannounce periodically during peer discovery if no peers found
                val peerDiscoveryTime = currentTime - phaseStartTime
                val peerCount = currentTorrentHandle?.status()?.numPeers() ?: 0
                
                if (peerCount == 0 && peerDiscoveryTime > 30000 && (peerDiscoveryTime % 30000) < 1000) {
                    // Force reannounce every 30 seconds if no peers found
                    Log.d(TAG, "🔄 No peers found after ${peerDiscoveryTime/1000}s - force reannouncing")
                    forceReannounceToTrackers(currentTorrentHandle, "periodic during peer discovery")
                }
                
                // Don't timeout during peer discovery - give P2P time to connect
                false
            }
            DownloadPhase.ACTIVE_DOWNLOADING -> {
                // Only timeout if no download progress for specified time
                val noProgressTime = currentTime - lastProgressTime
                val downloadRate = currentTorrentHandle?.status()?.downloadRate() ?: 0
                val peerCount = currentTorrentHandle?.status()?.numPeers() ?: 0
                
                // Don't timeout if we have active download rate or recently had progress
                val shouldTimeout = noProgressTime > NO_PROGRESS_TIMEOUT_MS && 
                                  downloadRate == 0 && 
                                  peerCount == 0
                
                if (shouldTimeout) {
                    Log.d(TAG, "Progress timeout: no download progress for ${noProgressTime}ms, no peers, no download rate")
                    triggerTimeout(listener, "No download progress for ${noProgressTime / 1000} seconds with no active peers")
                    true
                } else {
                    val remainingTime = (NO_PROGRESS_TIMEOUT_MS - noProgressTime) / 1000
                    if (noProgressTime > 60000) { // Log every minute after first minute
                        val status = when {
                            downloadRate > 0 -> "downloading at ${downloadRate}B/s"
                            peerCount > 0 -> "connected to $peerCount peers"
                            else -> "no activity"
                        }
                        Log.d(TAG, "No progress for ${noProgressTime / 1000}s ($status), will timeout in ${remainingTime}s if no progress")
                    }
                    false
                }
            }
            DownloadPhase.VERIFICATION -> {
                // Don't timeout during verification
                false
            }
        }
    }
    
    private fun triggerTimeout(listener: TorrentDownloadListener, reason: String) {
        Log.w(TAG, "[TIMEOUT] Phase: $downloadPhase, Reason: $reason")
        Log.w(TAG, "[TIMEOUT] No progress time: ${System.currentTimeMillis() - lastProgressTime}ms")
        Log.w(TAG, "[TIMEOUT] Last bytes downloaded: $lastBytesDownloaded")
        Log.w(TAG, "[TIMEOUT] Explicitly stopping P2P download before HTTPS fallback")
        
        // Set flag to stop download immediately
        isDownloading = false
        
        // Notify listener for HTTPS fallback BEFORE cleaning up
        handler.post {
            listener.onTimeout()
        }
        
        // Then clean up P2P resources 
        handler.postDelayed({
            stopDownload()
        }, 100) // Small delay to ensure onTimeout is processed first
    }
    
    private fun transitionToPhase(newPhase: DownloadPhase, listener: TorrentDownloadListener?) {
        val oldPhase = downloadPhase
        downloadPhase = newPhase
        val currentTime = System.currentTimeMillis()
        
        // Determine timeout for this phase
        val timeoutSeconds = when (newPhase) {
            DownloadPhase.METADATA_FETCHING -> {
                metadataStartTime = currentTime
                60 // 60 seconds for metadata
            }
            DownloadPhase.PEER_DISCOVERY -> {
                phaseStartTime = currentTime // Track when peer discovery started
                0 // No timeout during peer discovery
            }
            DownloadPhase.ACTIVE_DOWNLOADING -> {
                lastProgressTime = currentTime
                240 // 4 minutes for no progress
            }
            DownloadPhase.VERIFICATION -> {
                0 // No timeout during verification
            }
        }
        
        Log.d(TAG, "[PHASE TRANSITION] $oldPhase -> $newPhase (${timeoutSeconds}s timeout)")
        
        // Notify listener of phase change
        listener?.let {
            handler.post {
                it.onPhaseChanged(newPhase, timeoutSeconds)
            }
        }
    }
    
    fun stopDownload() {
        // Save resume data before stopping if download was active
        if (isDownloading && currentTorrentHandle?.isValid == true) {
            Log.d(TAG, "Saving resume data before stopping download")
            saveResumeDataSync() // Synchronous save before cleanup
        }
        
        isDownloading = false
        isVerifying = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        progressMonitorThread?.interrupt()
        progressMonitorThread = null
        dhtMonitorThread?.interrupt()
        dhtMonitorThread = null
        
        // Reset metadata waiting state
        waitingForMetadata = false
        
        // Reset tracking variables
        lastProgressTime = 0
        lastBytesDownloaded = 0
        metadataStartTime = 0
        downloadPhase = DownloadPhase.METADATA_FETCHING
        pendingResumeDataSave = false
        
        // Remove torrent from session if it exists
        currentTorrentHandle?.let { handle ->
            if (handle.isValid) {
                sessionManager?.remove(handle)
            }
        }
        currentTorrentHandle = null
        downloadListener = null
    }
    
    private fun saveResumeDataSync() {
        if (currentTorrentHandle == null || !currentTorrentHandle!!.isValid) {
            return
        }
        
        try {
            Log.d(TAG, "Synchronous progress metadata save before stop")
            
            // Save final progress state
            val status = currentTorrentHandle!!.status()
            val prefs = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
            
            prefs.edit().apply {
                putBoolean("has_resume_data", true)
                putString("download_path", currentDownloadPath)
                putLong("total_size", status.totalWanted())
                putLong("downloaded_size", status.totalWantedDone())
                putLong("last_save_time", System.currentTimeMillis())
                if (currentTorrentHandle?.isValid == true) {
                    putString("info_hash", currentTorrentHandle!!.infoHash().toString())
                }
                apply()
            }
            
            val progress = if (status.totalWanted() > 0) {
                (status.totalWantedDone().toFloat() / status.totalWanted() * 100).toInt()
            } else 0
            
            Log.d(TAG, "Final progress saved before stop: $progress%")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error saving final progress state", e)
        }
    }
    
    fun seedFile(filePath: String, listener: TorrentDownloadListener? = null) {
        Log.d(TAG, "seedFile() called with: $filePath")
        Log.d(TAG, "Current state - isSeeding: $isSeeding, isNetworkTransitioning: $isNetworkTransitioning, isLibraryAvailable: $isLibraryAvailable")
        
        if (!shouldSeed()) {
            Log.d(TAG, "Seeding disabled by user preference")
            return
        }
        
        if (!File(filePath).exists()) {
            Log.e(TAG, "Torrent file does not exist: $filePath")
            return
        }
        
        if (!isLibraryAvailable || sessionManager == null) {
            Log.d(TAG, "jlibtorrent not available for seeding")
            return
        }
        
        try {
            val file = File(filePath)
            Log.d(TAG, "Starting seeding for: $filePath (size: ${file.length()} bytes)")
            
            // If we already have the torrent handle from download, configure it for seeding
            currentTorrentHandle?.let { handle ->
                if (handle.isValid) {
                    Log.d(TAG, "Found existing valid torrent handle")
                    
                    // Check the current state
                    val status = handle.status()
                    Log.d(TAG, "Torrent status: state=${status.state()}, finished=${status.isFinished}, seeding=${status.isSeeding}")
                    
                    // If download is complete, start seeding
                    if (status.isFinished || status.isSeeding || status.totalWanted() == status.totalWantedDone()) {
                        isSeeding = true
                        Log.d(TAG, "Torrent is complete - now seeding")
                        return
                    } else {
                        Log.d(TAG, "Torrent not yet complete for seeding: ${status.totalWantedDone()}/${status.totalWanted()}")
                        // Still mark as seeding since we have the handle and will seed when complete
                        isSeeding = true
                        return
                    }
                } else {
                    Log.w(TAG, "Current torrent handle is invalid")
                    currentTorrentHandle = null
                }
            }
            
            // No valid handle found - try to restore from stored magnet link with verification
            Log.d(TAG, "No valid torrent handle found - attempting to restore from stored magnet link")
            
            // Start seeding immediately since we have a valid file, then try verification in background
            isSeeding = true
            Log.d(TAG, "Seeding enabled immediately with valid file - verification will happen in background")
            
            // Notify listener immediately that seeding started
            listener?.let {
                handler.post {
                    Log.d(TAG, "Notifying listener that seeding started immediately")
                    // This should trigger MainActivity to clear isSeedingInitializing
                }
            }
            
            // Try metadata fetch with verification in background thread (non-blocking)
            Thread {
                try {
                    restoreSeedingFromMagnetLink(filePath, listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Background metadata fetch failed: ${e.message}")
                }
            }.start()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start seeding", e)
            isSeeding = false
        }
    }
    
    fun restoreSeedingFromMagnetLink(filePath: String, listener: TorrentDownloadListener? = null) {
        Log.d(TAG, "restoreSeedingFromMagnetLink() called with: $filePath")
        
        if (!shouldSeed()) {
            Log.d(TAG, "Seeding disabled by user preference")
            return
        }
        
        if (!isLibraryAvailable || sessionManager == null) {
            Log.d(TAG, "jlibtorrent not available for seeding restoration")
            return
        }
        
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        val magnetLink = prefs.getString("magnet_link", null)
        
        if (magnetLink == null) {
            Log.w(TAG, "No stored magnet link found - cannot restore seeding")
            return
        }
        
        if (!File(filePath).exists()) {
            Log.e(TAG, "File does not exist for seeding: $filePath")
            return
        }
        
        try {
            val downloadDir = File(filePath).parentFile
            Log.d(TAG, "Restoring seeding using magnet link: $magnetLink")
            
            // Use fetchMagnet approach for seeding restoration with shorter timeout
            Log.d(TAG, "Fetching magnet metadata for seeding restoration...")
            
            val timeout = if (isNetworkTransitioning) {
                Log.d(TAG, "Network transitioning - using reduced 20 second timeout")
                20
            } else {
                Log.d(TAG, "Stable network - using standard 60 second timeout")
                60
            }
            
            // Notify listener that we're starting the process
            listener?.let {
                handler.post {
                    it.onDhtConnecting()
                }
            }
            
            // Give DHT time to connect
            Thread.sleep(1000)
            
            // Get actual DHT status and node count and notify listener that we're fetching metadata
            val sessionStats = sessionManager?.stats()
            val actualDhtNodes = sessionStats?.dhtNodes()?.toInt() ?: 0
            val isDhtRunning = try {
                sessionManager?.isDhtRunning() ?: false
            } catch (e: Exception) {
                false
            }
            
            Log.d(TAG, "DHT status during seeding restoration: running=$isDhtRunning, nodes=$actualDhtNodes")
            
            listener?.let {
                handler.post {
                    it.onDhtConnected(actualDhtNodes)
                    it.onMetadataFetching()
                }
            }
            
            // Use improved FrostWire approach for seeding
            val trackerUrls = TorrentUtils.extractTrackersFromMagnet(magnetLink)
            Log.d(TAG, "🌱 Seeding: Found ${trackerUrls.size} trackers")
            
            val data = sessionManager?.fetchMagnet(magnetLink, timeout, false)
            Log.d(TAG, "🌱 Seeding: Magnet fetch result: ${if (data != null) "success (${data.size} bytes)" else "failed or null"}")
            
            if (data != null) {
                val torrentInfo = TorrentInfo.bdecode(data)
                Log.d(TAG, "🌱 Seeding: Magnet metadata downloaded, verifying file...")
                
                // Verify file matches torrent
                val expectedSize = torrentInfo.totalSize()
                val actualSize = File(filePath).length()
                Log.d(TAG, "🌱 Seeding: File size check - expected: $expectedSize, actual: $actualSize")
                
                // Notify metadata complete
                listener?.let {
                    handler.post {
                        it.onMetadataComplete()
                    }
                }
                
                // Add torrent to session for seeding (file should already exist)
                sessionManager?.download(torrentInfo, downloadDir)
                Thread.sleep(1000) // Give session time to process
                currentTorrentHandle = sessionManager?.find(torrentInfo.infoHash())
                
                // Notify ready to seed
                listener?.let {
                    handler.post {
                        it.onReadyToSeed()
                    }
                }
                
                currentTorrentHandle?.let { handle ->
                    if (handle.isValid) {
                        isSeeding = true
                        Log.d(TAG, "Successfully restored seeding - torrent complete")
                    } else {
                        Log.w(TAG, "Restored torrent handle is invalid")
                        currentTorrentHandle = null
                    }
                } ?: run {
                    Log.e(TAG, "Failed to get torrent handle after restoration")
                }
            } else {
                Log.w(TAG, "Failed to fetch magnet metadata for seeding restoration")
                if (!isSeeding) {
                    isSeeding = true
                    Log.d(TAG, "Seeding enabled without verification")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore seeding from magnet link: ${e.message}", e)
            Log.d(TAG, "Exception during magnet restoration - this is usually a network or timeout issue")
            // Don't set isSeeding = false here since it might already be true from seedFile()
            Log.d(TAG, "Keeping existing seeding state due to exception: isSeeding=$isSeeding")
        }
    }
    
    fun getPeerCount(): Int {
        return try {
            if (isSeeding && currentTorrentHandle?.isValid == true) {
                currentTorrentHandle!!.status().numPeers()
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    fun getUploadRate(): Int {
        return try {
            if (isSeeding && currentTorrentHandle?.isValid == true) {
                currentTorrentHandle!!.status().uploadRate()
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }
    
    fun isSeeding(): Boolean {
        Log.d(TAG, "isSeeding() called - returning: $isSeeding")
        return isSeeding
    }
    
    fun stopSeeding() {
        // CRITICAL: Never interfere with active downloads
        if (isDownloading) {
            Log.w(TAG, "stopSeeding() called during active download - COMPLETELY IGNORING to prevent peer disconnection")
            Log.w(TAG, "Download in progress: isDownloading=$isDownloading, currentTorrentHandle=${currentTorrentHandle?.isValid}")
            return
        }
        
        Log.d(TAG, "Stopping seeding - no active download detected")
        isSeeding = false
        
        // Remove torrent from session if it exists (only if not downloading)
        currentTorrentHandle?.let { handle ->
            if (handle.isValid) {
                Log.d(TAG, "Removing torrent handle for seeding stop")
                sessionManager?.remove(handle)
            }
        }
        currentTorrentHandle = null
    }
    
    private fun shouldSeed(): Boolean {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        val seedingEnabled = prefs.getBoolean("enable_seeding", true)
        val allowMobileDataSeeding = prefs.getBoolean("allow_mobile_data_seeding", false)
        
        if (!seedingEnabled) {
            Log.d(TAG, "Seeding disabled by user preference")
            return false
        }
        
        val isOnWiFi = networkManager.isOnWiFi()
        val isOnMobileData = networkManager.isOnMobileData()
        
        return when {
            isOnWiFi -> {
                Log.d(TAG, "On WiFi - seeding allowed")
                true
            }
            isOnMobileData && allowMobileDataSeeding -> {
                Log.d(TAG, "On mobile data but user allows mobile data seeding")
                true
            }
            isOnMobileData -> {
                Log.d(TAG, "On mobile data and mobile data seeding disabled - blocking seeding")
                false
            }
            else -> {
                Log.d(TAG, "Unknown network type - allowing seeding")
                true
            }
        }
    }
    
    fun setSeedingEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enable_seeding", enabled).apply()
        
        if (!enabled) {
            stopSeeding()
        }
    }
    
    fun setMobileDataSeedingEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("allow_mobile_data_seeding", enabled).apply()
        
        if (!enabled && networkManager.isOnMobileData()) {
            Log.d(TAG, "Mobile data seeding disabled and currently on mobile data - stopping seeding")
            stopSeeding()
        }
    }
    
    fun isMobileDataSeedingEnabled(): Boolean {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("allow_mobile_data_seeding", false)
    }
    
    fun isSeedingEnabled(): Boolean {
        val prefs = context.getSharedPreferences("torrent_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("enable_seeding", true)
    }
    
    fun isLibraryLoaded(): Boolean {
        return isLibraryAvailable
    }
    
    fun isNetworkTransitioning(): Boolean {
        val currentTime = System.currentTimeMillis()
        val inTransition = isNetworkTransitioning && (currentTime - networkTransitionStartTime) < NETWORK_TRANSITION_TIMEOUT_MS
        
        // Auto-clear the transition flag if timeout exceeded
        if (isNetworkTransitioning && (currentTime - networkTransitionStartTime) >= NETWORK_TRANSITION_TIMEOUT_MS) {
            Log.d(TAG, "Network transition timeout exceeded, clearing flag")
            isNetworkTransitioning = false
        }
        
        return inTransition
    }
    
    fun isActivelyDownloading(): Boolean {
        return isDownloading
    }
    
    fun getCurrentTorrentHandle(): TorrentHandle? {
        return currentTorrentHandle
    }
    
    fun getPeerConnectionHealth(): String {
        return try {
            if (currentTorrentHandle?.isValid == true) {
                val status = currentTorrentHandle!!.status()
                val peers = status.numPeers()
                val seeds = try { status.numSeeds() } catch (e: Exception) { 0 }
                val downloadRate = status.downloadRate()
                val state = status.state()
                
                "Peers: $peers, Seeds: $seeds, Rate: ${downloadRate}B/s, State: $state, Valid: true"
            } else {
                "No valid torrent handle available"
            }
        } catch (e: Exception) {
            "Error getting peer info: ${e.message}"
        }
    }
    
    fun getCurrentPort(): Int {
        return currentPort
    }
    
    private fun startNetworkMonitoring() {
        networkManager.startNetworkMonitoring { isOnWiFi ->
            Log.d(TAG, "Network change detected - WiFi: $isOnWiFi")
            
            // CRITICAL: Completely avoid any interference during active downloads
            if (isDownloading) {
                Log.w(TAG, "ACTIVE DOWNLOAD DETECTED - Ignoring all network change actions to prevent peer disconnection")
                Log.w(TAG, "Network monitoring will resume normal operation after download completes")
                return@startNetworkMonitoring
            }
            
            // Mark network transition period only for non-download operations
            isNetworkTransitioning = true
            networkTransitionStartTime = System.currentTimeMillis()
            
            // Handle network changes only when not downloading
            if (isSeeding && !isOnWiFi && networkManager.isOnMobileData() && !isMobileDataSeedingEnabled()) {
                Log.d(TAG, "Switched to mobile data and mobile data seeding is disabled - stopping seeding")
                stopSeeding()
            } else if (isOnWiFi && isSeedingEnabled()) {
                Log.d(TAG, "Switched to WiFi - checking seeding state and attempting resume")
                
                // Only reset seeding session if we're not downloading
                if (isSeeding) {
                    Log.d(TAG, "Already seeding but network changed - resetting session for stability")
                    stopSeeding()
                    isSeeding = false
                }
                
                // Wait for network to stabilize before attempting seeding
                Thread seedingRestartThread@{
                    try {
                        Thread.sleep(5000) // 5 second delay for network stabilization
                        
                        // Double-check we're still not downloading before proceeding
                        if (isDownloading) {
                            Log.w(TAG, "Download started during network transition - aborting seeding restart")
                            isNetworkTransitioning = false
                            return@seedingRestartThread
                        }
                        
                        // Check if we still have a valid file and are on WiFi
                        if (networkManager.isOnWiFi() && currentDownloadPath.isNotEmpty() && File(currentDownloadPath).exists()) {
                            Log.d(TAG, "Network stabilized, starting fresh seeding session")
                            seedFile(currentDownloadPath)
                        } else if (networkManager.isOnWiFi()) {
                            // Try to find the torrent file in internal files
                            val internalFile = File(context.filesDir, "pictures.tar.gz")
                            if (internalFile.exists()) {
                                Log.d(TAG, "Found torrent file in internal storage, starting seeding")
                                currentDownloadPath = internalFile.absolutePath
                                seedFile(internalFile.absolutePath)
                            }
                        }
                    } catch (e: InterruptedException) {
                        Log.d(TAG, "Network transition wait interrupted")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during network transition seeding restart", e)
                    } finally {
                        isNetworkTransitioning = false
                    }
                }.start()
            } else {
                // Clear transition flag after a short delay if no action needed
                Thread transitionCleanupThread@{
                    try {
                        Thread.sleep(3000) // Reduced from 5 to 3 seconds
                    } catch (e: InterruptedException) {
                        // Ignore
                    } finally {
                        isNetworkTransitioning = false
                    }
                }.start()
            }
        }
    }
    
    private fun startMetadataTimeoutMonitoring(listener: TorrentDownloadListener?) {
        // Cancel any existing timeout
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        
        timeoutRunnable = Runnable {
            if (waitingForMetadata && isDownloading) {
                Log.w(TAG, "Metadata fetch timeout after ${METADATA_TIMEOUT_MS}ms")
                waitingForMetadata = false
                handler.post {
                    listener?.onTimeout()
                }
                isDownloading = false
            }
        }
        
        handler.postDelayed(timeoutRunnable!!, METADATA_TIMEOUT_MS)
        Log.d(TAG, "Metadata timeout monitoring started (${METADATA_TIMEOUT_MS}ms)")
    }
    
    private fun startAlertProcessingLoop() {
        if (isAlertProcessingActive) {
            Log.d(TAG, "🔄 Alert processing already active")
            return
        }
        
        isAlertProcessingActive = true
        windowStartTime = System.currentTimeMillis()
        alertProcessingThread = Thread {
            Log.d(TAG, "🚀 Alert-based UI updates started (replacing polling)")
            
            while (isAlertProcessingActive && sessionManager != null) {
                try {
                    // Enhanced alert-based monitoring (keep existing pattern but add better metadata handling)
                    // Note: jlibtorrent 1.2.19 doesn't have popAlerts() - metadata detection handled by callbacks
                    
                    // Enhanced progress monitoring using existing working API
                    try {
                        // Update progress immediately when torrent handle is valid
                        if (currentTorrentHandle?.isValid == true) {
                            updateProgressFromAlert()
                        }
                    } catch (e: Exception) {
                        // Ignore progress update errors to avoid breaking the loop
                    }
                    
                    // Light periodic status check only for critical fallbacks
                    if (waitingForMetadata && currentTorrentHandle?.isValid == true) {
                        val status = currentTorrentHandle?.status()
                        if (status?.hasMetadata() == true) {
                            Log.d(TAG, "✅ Metadata detected (fallback check)")
                            handleMetadataDetected()
                        }
                    }
                    
                    // Sleep to avoid busy waiting
                    Thread.sleep(100)
                    
                } catch (e: InterruptedException) {
                    Log.d(TAG, "⏹️ Alert processing thread interrupted")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "💥 Error in alert processing: ${e.message}", e)
                    Thread.sleep(500)
                }
            }
            
            Log.d(TAG, "📴 Alert processing thread stopped")
        }
        alertProcessingThread?.start()
    }
    
    private fun processAlert(alert: Alert<*>) {
        try {
            when (alert.type()) {
                AlertType.METADATA_RECEIVED -> {
                    Log.d(TAG, "🎯 METADATA_RECEIVED alert - torrent metadata is ready!")
                    val metadataAlert = alert as? MetadataReceivedAlert
                    if (metadataAlert != null && waitingForMetadata) {
                        Log.d(TAG, "✅ Metadata received for torrent: ${metadataAlert.handle().infoHash().toString()}")
                        currentTorrentHandle = metadataAlert.handle()
                        handleMetadataDetected()
                    }
                }
                
                AlertType.ADD_TORRENT -> {
                    val addAlert = alert as? AddTorrentAlert
                    if (addAlert != null) {
                        Log.d(TAG, "🔗 ADD_TORRENT alert - torrent added: ${addAlert.handle().infoHash().toString()}")
                        if (currentTorrentHandle == null) {
                            currentTorrentHandle = addAlert.handle()
                        }
                        
                        // FIXED: Explicitly resume torrent when added (FrostWire pattern)
                        if (addAlert.handle().isValid) {
                            addAlert.handle().resume()
                            Log.d(TAG, "✅ Torrent resumed immediately after ADD_TORRENT alert")
                            
                            // FORCE REANNOUNCE: Immediately announce to trackers and DHT
                            forceReannounceToTrackers(addAlert.handle(), "after ADD_TORRENT alert")
                        }
                    }
                }
                
                AlertType.DHT_BOOTSTRAP -> {
                    Log.d(TAG, "🌐 DHT_BOOTSTRAP alert - DHT bootstrap completed")
                    // Update DHT status in UI
                    handler.post {
                        downloadListener?.onDhtConnected((sessionManager?.stats()?.dhtNodes() ?: 0).toInt())
                    }
                }
                
                AlertType.DHT_GET_PEERS -> {
                    val dhtAlert = alert as? DhtGetPeersAlert
                    if (dhtAlert != null) {
                        Log.d(TAG, "📊 DHT_GET_PEERS alert - found peers for: ${dhtAlert.infoHash().toString()}")
                        // Update peer discovery status
                        handler.post {
                            downloadListener?.onDiscoveringPeers()
                        }
                    }
                }
                
                AlertType.DHT_REPLY -> {
                    val dhtReplyAlert = alert as? DhtReplyAlert
                    if (dhtReplyAlert != null) {
                        val peerCount = dhtReplyAlert.numPeers().toInt()
                        Log.d(TAG, "📊 DHT_REPLY alert - received $peerCount peers from DHT")
                        // Update peer counts when DHT returns peer information
                        handler.post {
                            downloadListener?.onSeedsFound(0, peerCount) // Seeds unknown from DHT, will be determined later
                        }
                    }
                }
                
                AlertType.TRACKER_ANNOUNCE -> {
                    val announceAlert = alert as? TrackerAnnounceAlert
                    if (announceAlert != null) {
                        Log.d(TAG, "📡 TRACKER_ANNOUNCE alert - announcing to: ${announceAlert.trackerUrl()}")
                    }
                }
                
                AlertType.TRACKER_REPLY -> {
                    val replyAlert = alert as? TrackerReplyAlert
                    if (replyAlert != null) {
                        val peerCount = replyAlert.numPeers().toInt()
                        Log.d(TAG, "📡 TRACKER_REPLY alert - response from: ${replyAlert.trackerUrl()}, peers: $peerCount")
                        // Update peer counts when tracker returns peer information
                        handler.post {
                            downloadListener?.onSeedsFound(0, peerCount) // Seeds/leechers will be determined when connecting
                        }
                    }
                }
                
                AlertType.TRACKER_ERROR -> {
                    val errorAlert = alert as? TrackerErrorAlert
                    if (errorAlert != null) {
                        Log.w(TAG, "⚠️ TRACKER_ERROR alert - tracker: ${errorAlert.trackerUrl()}, error: ${errorAlert.error()}")
                    }
                }
                
                AlertType.PEER_CONNECT -> {
                    val peerAlert = alert as? PeerConnectAlert
                    if (peerAlert != null) {
                        Log.d(TAG, "🔗 PEER_CONNECT alert - connected to peer: ${peerAlert.endpoint()}")
                        // Update progress immediately when peer connects
                        updateProgressFromAlert()
                    }
                }
                
                AlertType.PEER_DISCONNECTED -> {
                    val peerAlert = alert as? PeerDisconnectedAlert
                    if (peerAlert != null) {
                        Log.d(TAG, "🔌 PEER_DISCONNECTED alert - peer disconnected: ${peerAlert.endpoint()}, reason: ${peerAlert.error()}")
                        // Update progress when peer disconnects
                        updateProgressFromAlert()
                    }
                }
                
                AlertType.PIECE_FINISHED -> {
                    val pieceAlert = alert as? PieceFinishedAlert
                    if (pieceAlert != null && isDownloading) {
                        Log.d(TAG, "🧩 PIECE_FINISHED alert - piece ${pieceAlert.pieceIndex()} completed")
                        updateProgressFromAlert()
                    }
                }
                
                AlertType.BLOCK_FINISHED -> {
                    val blockAlert = alert as? BlockFinishedAlert
                    if (blockAlert != null && isDownloading) {
                        // Update download rate calculation
                        updateDownloadRateFromBlocks()
                        // Update progress less frequently to avoid UI spam
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastProgressUpdateTime > 1000) { // Max 1 update per second
                            updateProgressFromAlert()
                            lastProgressUpdateTime = currentTime
                        }
                    }
                }
                
                AlertType.TORRENT_FINISHED -> {
                    val finishedAlert = alert as? TorrentFinishedAlert
                    if (finishedAlert != null && isDownloading) {
                        Log.d(TAG, "✅ TORRENT_FINISHED alert - download completed!")
                        handleTorrentCompleted()
                    }
                }
                
                AlertType.TORRENT_RESUMED -> {
                    val resumedAlert = alert as? TorrentResumedAlert
                    if (resumedAlert != null) {
                        Log.d(TAG, "▶️ TORRENT_RESUMED alert - torrent resumed")
                        if (downloadPhase != DownloadPhase.ACTIVE_DOWNLOADING) {
                            transitionToPhase(DownloadPhase.ACTIVE_DOWNLOADING, downloadListener)
                        }
                    }
                }
                
                AlertType.TORRENT_PAUSED -> {
                    val pausedAlert = alert as? TorrentPausedAlert
                    if (pausedAlert != null) {
                        Log.d(TAG, "⏸️ TORRENT_PAUSED alert - torrent paused")
                    }
                }
                
                AlertType.TORRENT_CHECKED -> {
                    val checkedAlert = alert as? TorrentCheckedAlert
                    if (checkedAlert != null) {
                        Log.d(TAG, "✓ TORRENT_CHECKED alert - file verification completed")
                        if (downloadPhase == DownloadPhase.VERIFICATION) {
                            transitionToPhase(DownloadPhase.ACTIVE_DOWNLOADING, downloadListener)
                        }
                    }
                }
                
                AlertType.FILE_COMPLETED -> {
                    val fileAlert = alert as? FileCompletedAlert
                    if (fileAlert != null) {
                        Log.d(TAG, "📁 FILE_COMPLETED alert - file completed")
                        updateProgressFromAlert()
                    }
                }
                
                AlertType.TORRENT_ERROR -> {
                    Log.e(TAG, "⚠️ TORRENT_ERROR alert received")
                    handler.post {
                        downloadListener?.onError("Torrent error occurred")
                    }
                }
                
                AlertType.FILE_ERROR -> {
                    Log.e(TAG, "📁⚠️ FILE_ERROR alert received")
                    handler.post {
                        downloadListener?.onError("File error occurred")
                    }
                }
                
                AlertType.METADATA_FAILED -> {
                    Log.e(TAG, "📦⚠️ METADATA_FAILED alert received")
                    handler.post {
                        downloadListener?.onError("Failed to fetch metadata")
                    }
                }
                
                AlertType.STATE_CHANGED -> {
                    val stateAlert = alert as? StateChangedAlert
                    if (stateAlert != null) {
                        val torrentState = stateAlert.state
                        Log.d(TAG, "🔄 STATE_CHANGED alert - torrent state: $torrentState")
                        
                        // Handle verification phase
                        if (torrentState == TorrentStatus.State.CHECKING_FILES && downloadPhase != DownloadPhase.VERIFICATION) {
                            transitionToPhase(DownloadPhase.VERIFICATION, downloadListener)
                            val status = currentTorrentHandle?.status()
                            if (status != null) {
                                handler.post {
                                    downloadListener?.onVerifying(status.progress())
                                }
                            }
                        }
                        
                        // Update progress for any state change
                        updateProgressFromAlert()
                    }
                }
                
                else -> {
                    // Log other alerts for debugging
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "💬 Alert: ${alert.type()} - ${alert.message()}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 Error processing alert: ${alert.type()}", e)
        }
    }
    
    private fun stopAlertProcessingLoop() {
        isAlertProcessingActive = false
        alertProcessingThread?.interrupt()
        alertProcessingThread = null
        Log.d(TAG, "Alert processing stopped")
    }
    
    private fun updateProgressFromAlert() {
        try {
            if (currentTorrentHandle?.isValid == true) {
                val status = currentTorrentHandle?.status()
                if (status != null) {
                    val numPeers = status.numPeers().toInt()
                    val numSeeds = status.numSeeds().toInt()
                    val downloadedBytes = status.totalDone()
                    val totalBytes = status.totalWanted()
                    val downloadRate = if (lastDownloadRate > 0) lastDownloadRate.toInt() else status.downloadRate().toInt()
                    
                    // Check for completion
                    val isComplete = status.isFinished || 
                                   (totalBytes > 0 && downloadedBytes >= totalBytes) ||
                                   status.state() == TorrentStatus.State.SEEDING
                    
                    if (isComplete && isDownloading) {
                        handleTorrentCompleted()
                        return
                    }
                    
                    // FIXED: Check for transition from PEER_DISCOVERY to ACTIVE_DOWNLOADING
                    if (downloadPhase == DownloadPhase.PEER_DISCOVERY && (numPeers > 0 || numSeeds > 0)) {
                        Log.d(TAG, "🎯 Peers found during discovery - transitioning to active download")
                        transitionToPhase(DownloadPhase.ACTIVE_DOWNLOADING, downloadListener)
                    }
                    
                    // Update UI with current progress
                    if (isDownloading) {
                        handler.post {
                            downloadListener?.onProgress(downloadedBytes, totalBytes, downloadRate, numPeers)
                        }
                        
                        // Update progress tracking for timeouts
                        if (downloadedBytes > lastBytesDownloaded) {
                            lastProgressTime = System.currentTimeMillis()
                            lastBytesDownloaded = downloadedBytes
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating progress from alert", e)
        }
    }
    
    private fun updateDownloadRateFromBlocks() {
        try {
            val currentTime = System.currentTimeMillis()
            if (currentTorrentHandle?.isValid == true) {
                val status = currentTorrentHandle?.status()
                if (status != null) {
                    val currentBytes = status.totalDone()
                    
                    // Reset window if needed
                    if (currentTime - windowStartTime > progressCalculationWindow) {
                        bytesDownloadedInWindow = 0
                        windowStartTime = currentTime
                    }
                    
                    // Calculate rate based on blocks completed in window
                    val timeDiff = currentTime - windowStartTime
                    if (timeDiff > 1000) { // At least 1 second of data
                        lastDownloadRate = (bytesDownloadedInWindow * 1000) / timeDiff
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating download rate from blocks", e)
        }
    }
    
    private fun handleTorrentCompleted() {
        try {
            Log.d(TAG, "Handling torrent completion via alert")
            
            // Clear resume data since download is complete
            clearResumeData()
            Log.d(TAG, "Resume data cleared after completion")
            
            handler.post {
                downloadListener?.onCompleted(currentDownloadPath)
            }
            isDownloading = false
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling torrent completion", e)
        }
    }
    
    private fun forceReannounceToTrackers(handle: TorrentHandle?, context: String) {
        try {
            if (handle?.isValid == true) {
                handle.forceReannounce()
                handle.forceDHTAnnounce()
                Log.d(TAG, "🔔 Force reannounce and DHT announce - $context")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Force reannounce failed ($context): ${e.message}")
        }
    }
    
    private fun updatePeerProgress() {
        try {
            if (currentTorrentHandle?.isValid == true) {
                val status = currentTorrentHandle?.status()
                if (status != null) {
                    val numPeers = status.numPeers().toInt()
                    val numSeeds = status.numSeeds().toInt()
                    val downloadedBytes = status.totalDone()
                    val totalBytes = status.totalWanted()
                    val downloadRate = status.downloadRate()
                    
                    Log.v(TAG, "📊 Peer update - Connected: $numPeers, Seeds: $numSeeds, Progress: $downloadedBytes/$totalBytes")
                    
                    // Update UI on main thread
                    handler.post {
                        downloadListener?.onProgress(downloadedBytes, totalBytes, downloadRate, numPeers)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating peer progress", e)
        }
    }
    
    private fun handleMetadataDetected() {
        try {
            if (!waitingForMetadata || currentTorrentHandle?.isValid != true) {
                return
            }
            
            val torrentInfo = currentTorrentHandle?.torrentFile()
            if (torrentInfo != null) {
                Log.d(TAG, "Metadata complete - file size: ${torrentInfo.totalSize()} bytes")
                
                waitingForMetadata = false
                
                // FORCE REANNOUNCE: Force immediate tracker and DHT announces after metadata detection
                forceReannounceToTrackers(currentTorrentHandle, "after metadata detection")
                
                // Cancel metadata timeout
                timeoutRunnable?.let { handler.removeCallbacks(it) }
                
                // Notify listener on main thread
                handler.post {
                    downloadListener?.onMetadataComplete()
                }
                
                // Check if this is a resume case by looking for existing partial file
                val downloadFile = File(currentDownloadPath)
                val isResuming = downloadFile.exists() && downloadFile.length() > 0
                
                if (isResuming) {
                    Log.d(TAG, "Metadata complete for resume - existing file: ${downloadFile.length()} bytes")
                    // jlibtorrent will automatically detect and resume from partial file
                    transitionToPhase(DownloadPhase.ACTIVE_DOWNLOADING, downloadListener)
                    // Progress monitoring now handled by alerts
                } else {
                    Log.d(TAG, "Metadata complete for fresh download")
                    // Transition to peer discovery phase for fresh download
                    transitionToPhase(DownloadPhase.PEER_DISCOVERY, downloadListener)
                    
                    handler.post {
                        downloadListener?.onDiscoveringPeers()
                    }
                    
                    // Start progress monitoring
                    // Metadata and peer monitoring now handled by alerts
                }
                
            } else {
                Log.e(TAG, "Metadata detected but torrent info is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling metadata detection", e)
            waitingForMetadata = false
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            handler.post {
                downloadListener?.onError("Error processing metadata: ${e.message}")
            }
        }
    }
    
    
    // DHT verification removed - using FrostWire approach of immediate start with alert-driven discovery
    
    
    fun shutdown() {
        stopDownload()
        stopSeeding()
        stopAlertProcessingLoop()
        networkManager.stopNetworkMonitoring()
        
        // Save session settings before shutdown for persistence like FrostWire
        sessionManager?.let { sm ->
            try {
                val currentSettings = sm.settings()
                saveSessionSettings(currentSettings)
                Log.d(TAG, "💾 Session settings saved during shutdown for state persistence")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Failed to save session settings during shutdown: ${e.message}")
            }
        }
        
        sessionManager?.stop()
        sessionManager = null
    }
    
    
}