package com.wbpxre150.unjumbleapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt
import java.io.File
import android.view.animation.AnimationUtils
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.io.FileInputStream

class MainActivity : Activity(), TorrentDownloadListener {

    private lateinit var imageView: ImageView
    private lateinit var textBox: TextView
    private lateinit var letterContainer: LinearLayout
    private lateinit var clearButton: Button
    private lateinit var backspaceButton: Button
    private lateinit var shuffleButton: Button
    private lateinit var nextWordButton: Button
    private lateinit var hintButton: Button
    private lateinit var checkButton: Button
    private lateinit var scoreTextView: TextView
    private lateinit var levelTextView: TextView
    private lateinit var peerCountTextView: TextView

    private var currentPictureIndex = 0
    private lateinit var pictureFiles: List<String>
    private lateinit var currentWord: String
    private var correctLetters = ""

    private lateinit var sharedPreferences: SharedPreferences
    private var score = 0
    private var possibleScore = 0
    private var level = 1
    private var totalLevels = 4364

    private lateinit var timerTextView: TextView
    private var totalPlayTimeMillis: Long = 0
    private var currentWordStartTime: Long = 0
    private var timerHandler: Handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var isTimerRunning = false
    
    private var torrentManager: SimpleTorrentManager? = null
    private var peerUpdateHandler: Handler = Handler(Looper.getMainLooper())
    private var peerUpdateRunnable: Runnable? = null
    private var isBackgroundDownloading = false
    private var isSeedingInitializing = false
    private var seedingInitStartTime: Long = 0
    private var downloadRetryCount = 0
    private var totalPeersFound = 0
    private var connectedPeers = 0
    private val networkManager by lazy { NetworkManager.getInstance(this) }
    
    companion object {
        // Expected SHA-256 hash of the correct pictures.tar.gz file
        private const val EXPECTED_FILE_HASH = "ca09ad71a399a6b3b1dde84be3b0d3c16e7f92e71f5548660d852e9f18df3dbb"
        private const val MAX_DOWNLOAD_RETRIES = 3
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("AppState", Context.MODE_PRIVATE)

        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        textBox = findViewById(R.id.textBox)
        letterContainer = findViewById(R.id.letterContainer)
        clearButton = findViewById(R.id.clearButton)
        backspaceButton = findViewById(R.id.backspaceButton)
        shuffleButton = findViewById(R.id.shuffleButton)
        nextWordButton = findViewById(R.id.nextWordButton)
        hintButton = findViewById(R.id.hintButton)
        checkButton = findViewById(R.id.checkButton)
        scoreTextView = findViewById(R.id.scoreTextView)
        levelTextView = findViewById(R.id.levelTextView)
        timerTextView = findViewById(R.id.timerTextView)
        peerCountTextView = findViewById(R.id.peerCountTextView)

        pictureFiles = getPictureFiles()
        if (pictureFiles.isEmpty()) {
            // Log the error and show a message to the user
            android.util.Log.e("MainActivity", "No picture files found in ${getPicturesDir().absolutePath}")
            textBox.text = "No pictures found. Please restart the app."
            return
        }

        if (sharedPreferences.getBoolean("isFirstRun", true)) {
            pictureFiles = pictureFiles.shuffled()
            saveShuffledOrder(pictureFiles)
            sharedPreferences.edit().putBoolean("isFirstRun", false).apply()
            totalPlayTimeMillis = sharedPreferences.getLong("totalPlayTimeMillis", 0)
            updateTimerDisplay()
        } else {
            pictureFiles = loadShuffledOrder()
        }

        currentPictureIndex = sharedPreferences.getInt("currentPictureIndex", 0)
        score = sharedPreferences.getInt("score", 0)
        possibleScore = sharedPreferences.getInt("possibleScore", 0)
        level = sharedPreferences.getInt("level", 1)
        totalPlayTimeMillis = sharedPreferences.getLong("totalPlayTimeMillis", 0)
        
        // Initialize SimpleTorrentManager for seeding (after UI is set up)
        android.util.Log.d("MainActivity", "Initializing SimpleTorrentManager...")
        torrentManager = SimpleTorrentManager.getInstance(this)
        android.util.Log.d("MainActivity", "SimpleTorrentManager initialized, library loaded: ${torrentManager?.isLibraryLoaded()}")
        
        // Migrate existing cache file to internal storage if it exists
        migrateCacheFileToInternalStorage()
        
        // Check for existing torrent file and verify its integrity with hash verification
        val downloadedFile = File(filesDir, "pictures.tar.gz")
        android.util.Log.d("MainActivity", "Checking for torrent file: ${downloadedFile.absolutePath}, exists: ${downloadedFile.exists()}")
        
        if (verifyFileHash(downloadedFile)) {
            android.util.Log.d("MainActivity", "Found valid torrent file (${downloadedFile.length()} bytes), hash verified, starting seeding")
            peerCountTextView.text = "Torrent: Valid archive file found, starting seeding..."
            // Use SimpleTorrentManager's streamlined seedFile method
            try {
                isSeedingInitializing = true
                seedingInitStartTime = System.currentTimeMillis()
                peerCountTextView.text = "Torrent: Initializing seeding..."
                android.util.Log.d("MainActivity", "Starting async seeding initiation")
                
                // Run seeding initialization in background thread
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        torrentManager!!.seedFile(downloadedFile.absolutePath, this@MainActivity)
                        android.util.Log.d("MainActivity", "Seeding initiation completed")
                        
                        // Check immediately after seedFile completes
                        withContext(Dispatchers.Main) {
                            delay(2000) // Give it a moment to process
                            val isSeeding = torrentManager?.isSeeding() ?: false
                            android.util.Log.d("MainActivity", "Immediate seeding check after seedFile: $isSeeding")
                            
                            // Always clear the initializing flag after a reasonable delay
                            // This prevents getting permanently stuck
                            isSeedingInitializing = false
                            
                            if (isSeeding) {
                                android.util.Log.d("MainActivity", "Seeding started successfully immediately")
                                peerCountTextView.text = "Torrent: Seeding started successfully"
                            } else {
                                android.util.Log.d("MainActivity", "Seeding not active after initialization")
                                peerCountTextView.text = "Torrent: Seeding initialization completed"
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Seeding initiation failed: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            isSeedingInitializing = false
                            peerCountTextView.text = "Torrent: Seeding failed - ${e.message}"
                        }
                    }
                }
                
                // Set multiple status checks with progressive timeouts
                Handler(Looper.getMainLooper()).postDelayed({
                    // Quick status check after 3 seconds
                    if (isSeedingInitializing) {
                        val isSeeding = torrentManager?.isSeeding() ?: false
                        android.util.Log.d("MainActivity", "Quick check (3s): isSeedingInitializing=$isSeedingInitializing, isSeeding=$isSeeding")
                        if (isSeeding) {
                            android.util.Log.d("MainActivity", "Quick check: Seeding started successfully")
                            isSeedingInitializing = false
                            peerCountTextView.text = "Torrent: Seeding active"
                        } else {
                            peerCountTextView.text = "Torrent: Fetching magnet metadata..."
                        }
                    }
                }, 3000) // 3 second quick check
                
                Handler(Looper.getMainLooper()).postDelayed({
                    // Medium check after 8 seconds - force clear if still stuck
                    if (isSeedingInitializing) {
                        val isSeeding = torrentManager?.isSeeding() ?: false
                        android.util.Log.d("MainActivity", "Medium check (8s): Forcing clear of initialization flag")
                        isSeedingInitializing = false // Force clear
                        if (isSeeding) {
                            peerCountTextView.text = "Torrent: Seeding active"
                        } else {
                            peerCountTextView.text = "Torrent: Initialization completed"
                        }
                    }
                }, 8000) // 8 second medium check
                
                Handler(Looper.getMainLooper()).postDelayed({
                    // Final check after 15 seconds
                    if (isSeedingInitializing) {
                        val isSeeding = torrentManager?.isSeeding() ?: false
                        android.util.Log.d("MainActivity", "Extended check (15s): isSeedingInitializing=$isSeedingInitializing, isSeeding=$isSeeding")
                        if (isSeeding) {
                            android.util.Log.d("MainActivity", "Extended check: Seeding started successfully")
                            checkSeedingInitialization()
                        } else {
                            peerCountTextView.text = "Torrent: Still fetching metadata, please wait..."
                        }
                    }
                }, 15000) // 15 second extended check
                
                Handler(Looper.getMainLooper()).postDelayed({
                    checkSeedingInitialization()
                }, 20000) // 20 second timeout - reduced for better responsiveness during network transitions
                
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to start seeding: ${e.message}", e)
                isSeedingInitializing = false
                peerCountTextView.text = "Torrent: Failed to start seeding - ${e.message}"
            }
        } else {
            if (downloadedFile.exists()) {
                android.util.Log.w("MainActivity", "Torrent file exists but hash verification failed - deleting corrupted file")
                downloadedFile.delete()
                peerCountTextView.text = "Torrent: Corrupted file detected, re-downloading..."
            } else {
                android.util.Log.d("MainActivity", "No torrent file found, starting background download")
                peerCountTextView.text = "Torrent: No archive file, downloading..."
            }
            // Pictures are extracted but no valid archive for seeding - start background torrent download only if on WiFi
            if (networkManager.isOnWiFi()) {
                startBackgroundTorrentDownload()
            } else {
                android.util.Log.d("MainActivity", "Not on WiFi - skipping background torrent download")
                peerCountTextView.text = "Torrent: Connect to WiFi for P2P downloads"
            }
        }
        
        updateScoreAndLevel()
        loadCurrentPicture()
        startPeerCountUpdates()

        clearButton.setOnClickListener { clearIncorrectLetters() }
        backspaceButton.setOnClickListener { backspace() }
        shuffleButton.setOnClickListener { shuffleLetters() }
        nextWordButton.setOnClickListener {
            loadNextPicture()
            saveAppState()
        }
        hintButton.setOnClickListener { showHint() }
        checkButton.setOnClickListener { checkWord() }
        
        // Debug: Long press on the title to launch end game screen (for testing)
        findViewById<TextView>(R.id.titleTextView).setOnLongClickListener {
            launchEndGameScreen()
            true
        }
    }

    private fun getPicturesDir(): File {
        return File(filesDir, "pictures/pictures")
    }

    override fun onPause() {
        super.onPause()
        if (isTimerRunning) {
            stopTimer()
            savePlayTime()
        }
        stopPeerCountUpdates()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Full shutdown with session state persistence like FrostWire
        torrentManager?.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (isTimerRunning) {
            startTimer()
        }
        updateTimerDisplay()
        startPeerCountUpdates()
    }

    private fun startTimer() {
        if (!isTimerRunning) {
            currentWordStartTime = System.currentTimeMillis()
            timerRunnable = object : Runnable {
                override fun run() {
                    val currentTimeMillis = System.currentTimeMillis()
                    val sessionTimeMillis = currentTimeMillis - currentWordStartTime
                    val totalTimeMillis = totalPlayTimeMillis + sessionTimeMillis
                    updateTimerDisplay(totalTimeMillis)
                    timerHandler.postDelayed(this, 500)
                }
            }
            timerHandler.post(timerRunnable!!)
            isTimerRunning = true
        }
    }

    private fun stopTimer() {
        if (isTimerRunning) {
            timerRunnable?.let { timerHandler.removeCallbacks(it) }
            savePlayTime()
            isTimerRunning = false
        }
    }

    private fun updateTimerDisplay(totalMillis: Long = totalPlayTimeMillis) {
        val seconds = (totalMillis / 1000).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60
        timerTextView.text = String.format("Time: %02d:%02d:%02d", hours, minutes % 60, seconds % 60)
    }

    private fun savePlayTime() {
        if (isTimerRunning) {
            val currentTimeMillis = System.currentTimeMillis()
            val sessionTimeMillis = currentTimeMillis - currentWordStartTime
            totalPlayTimeMillis += sessionTimeMillis
            currentWordStartTime = currentTimeMillis
        }

        val editor = sharedPreferences.edit()
        editor.putLong("totalPlayTimeMillis", totalPlayTimeMillis)
        editor.apply()
    }

    private fun updateScoreAndLevel() {
        var scorePercentage = 0
        var levelPercentage = 0
        if (level != 1) {
            scorePercentage = (score.toDouble() / possibleScore * 100).roundToInt()
            levelPercentage = (level.toDouble() / totalLevels * 100).roundToInt()
        }
        scoreTextView.text = "Score: $score/$possibleScore ($scorePercentage%)"
        levelTextView.text = "Level: $level/$totalLevels ($levelPercentage%)"
    }

    private fun getPictureFiles(): List<String> {
        val picturesDir = getPicturesDir()
        android.util.Log.d("MainActivity", "Searching for pictures in: ${picturesDir.absolutePath}")

        if (!picturesDir.exists()) {
            android.util.Log.e("MainActivity", "Pictures directory does not exist")
            return emptyList()
        }

        if (!picturesDir.isDirectory) {
            android.util.Log.e("MainActivity", "Pictures path is not a directory")
            return emptyList()
        }

        val files = picturesDir.listFiles()
        if (files == null) {
            android.util.Log.e("MainActivity", "Failed to list files in pictures directory")
            return emptyList()
        }

        android.util.Log.d("MainActivity", "Total files in directory: ${files.size}")

        // Log details about each file
        files.forEach { file ->
            android.util.Log.d("MainActivity", "File: ${file.name}, isFile: ${file.isFile}, isDirectory: ${file.isDirectory}, length: ${file.length()} bytes")
        }

        val pictureFiles = files.filter {
            it.isFile && (it.name.endsWith(".png", ignoreCase = true) ||
                    it.name.endsWith(".jpg", ignoreCase = true) ||
                    it.name.endsWith(".jpeg", ignoreCase = true))
        }.map { it.name }

        android.util.Log.d("MainActivity", "Found ${pictureFiles.size} picture files")
        pictureFiles.forEach { fileName ->
            android.util.Log.d("MainActivity", "Picture file: $fileName")
        }

        return pictureFiles
    }

    private fun saveShuffledOrder(shuffledList: List<String>) {
        val editor = sharedPreferences.edit()
        editor.putInt("pictureFilesSize", shuffledList.size)
        shuffledList.forEachIndexed { index, fileName ->
            editor.putString("pictureFile_$index", fileName)
        }
        editor.apply()
    }

    private fun loadShuffledOrder(): List<String> {
        val size = sharedPreferences.getInt("pictureFilesSize", 0)
        return (0 until size).mapNotNull { index ->
            sharedPreferences.getString("pictureFile_$index", null)
        }
    }

    private fun saveAppState() {
        val editor = sharedPreferences.edit()
        editor.putInt("currentPictureIndex", currentPictureIndex)
        editor.putInt("score", score)
        editor.putInt("possibleScore", possibleScore)
        editor.putInt("level", level)
        editor.putLong("totalPlayTimeMillis", totalPlayTimeMillis)
        editor.apply()
    }

    private fun loadCurrentPicture() {
        if (pictureFiles.isEmpty()) {
            textBox.text = "No pictures found"
            return
        }

        if (currentPictureIndex >= pictureFiles.size) {
            currentPictureIndex = 0
        }

        val pictureFile = pictureFiles[currentPictureIndex]
        val file = File(getPicturesDir(), pictureFile)
        android.util.Log.d("MainActivity", "Attempting to load picture: ${file.absolutePath}")

        if (!file.exists()) {
            android.util.Log.e("MainActivity", "File does not exist: ${file.absolutePath}")
            textBox.text = "Error loading picture"
            return
        }

        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap == null) {
            android.util.Log.e("MainActivity", "Failed to decode bitmap: ${file.absolutePath}")
            textBox.text = "Error loading picture"
            return
        }
        imageView.setImageBitmap(bitmap)

        currentWord = pictureFile.substringBeforeLast('.')
        createLetterButtons()

        textBox.text = ""
        correctLetters = ""
        // Hide next word button as we'll auto-progress
        nextWordButton.visibility = View.GONE
        isTimerRunning = false
        updateTimerDisplay()
        enableAllLetterButtons()
        setHintButtonEnabled(true)
        updateScoreAndLevel()
    }

    private fun loadNextPicture() {
        currentPictureIndex++
        
        // Check if we've completed all levels
        if (level >= totalLevels) {
            launchEndGameScreen()
            return
        }
        
        if (currentPictureIndex >= pictureFiles.size) {
            currentPictureIndex = 0
        }
        loadCurrentPicture()
    }
    
    private fun launchEndGameScreen() {
        // Stop timer if running
        if (isTimerRunning) {
            stopTimer()
            savePlayTime()
        }
        
        // Create intent and pass all stats
        val intent = Intent(this, EndGameActivity::class.java).apply {
            putExtra("score", score)
            putExtra("possibleScore", possibleScore)
            putExtra("levels", level)
            putExtra("totalLevels", totalLevels)
            putExtra("totalPlayTimeMillis", totalPlayTimeMillis)
        }
        
        startActivity(intent)
        finish()
    }

    private fun createLetterButtons() {
        letterContainer.removeAllViews()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        
        // Determine buttons per row based on screen width
        // On smaller screens, we'll have fewer buttons per row
        val buttonsPerRow = when {
            screenWidth < 480 * density -> 4 // Small phones
            screenWidth < 600 * density -> 5 // Medium phones
            else -> 6 // Large phones and tablets
        }
        
        // Calculate button size based on available width
        val buttonMargin = (4 * density).toInt()
        val buttonWidth = ((screenWidth - 32 * density) / buttonsPerRow).toInt() - (buttonMargin * 2)
        
        // Adjust button height based on screen size to avoid overflow
        val buttonHeight = (48 * density).toInt()
        
        android.util.Log.d("MainActivity", "Screen: ${screenWidth}x${screenHeight}, Density: $density, " +
            "Buttons per row: $buttonsPerRow, Button size: ${buttonWidth}x${buttonHeight}")
        
        var currentRow: LinearLayout? = null
        var buttonsInCurrentRow = 0

        var shuffledWord: List<Char>
        do {
            shuffledWord = currentWord.toList().shuffled()
        } while (shuffledWord.joinToString("") == currentWord)

        shuffledWord.forEach { letter ->
            if (currentRow == null || buttonsInCurrentRow >= buttonsPerRow) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    setPadding(buttonMargin, buttonMargin, buttonMargin, buttonMargin)
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
                letterContainer.addView(currentRow)
                buttonsInCurrentRow = 0
            }

            val button = Button(this).apply {
                text = letter.toString().uppercase()  // Convert to uppercase for better readability
                setOnClickListener {
                    if (!isTimerRunning) {
                        startTimer()
                    }
                    
                    // Get the lowercase letter
                    val letterToAdd = this.text.toString().lowercase()
                    
                    // Directly update the TextView
                    textBox.text = textBox.text.toString() + letterToAdd
                    
                    // Add a quick feedback animation to the textBox
                    try {
                        val scaleAnim = AnimationUtils.loadAnimation(context, android.R.anim.fade_in)
                        scaleAnim.duration = 200
                        textBox.startAnimation(scaleAnim)
                    } catch (e: Exception) {
                        // Ignore animation errors to ensure functionality
                    }
                    
                    // Disable this button
                    isEnabled = false

                    //Check if the word is complete and correct
                    if (textBox.text.toString() == currentWord) {
                        // Word is correct, automatically trigger checkWord
                        checkWord()
                    } else {
                        // Check if all letters are used (all buttons disabled)
                        var allLettersUsed = true
                        forEachLetterButton { btn ->
                            if (btn.isEnabled) {
                                allLettersUsed = false
                                return@forEachLetterButton
                            }
                        }
                        
                        // If all letters are used and word is incorrect, flash red and clear
                        if (allLettersUsed && textBox.text.toString() != currentWord) {
                            flashTextBoxRed()
                        }
                    }
                }
                layoutParams = LinearLayout.LayoutParams(buttonWidth, buttonHeight).apply {
                    setMargins(buttonMargin, buttonMargin, buttonMargin, buttonMargin)
                }
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.letter_button_background_selector)
                setTextColor(ContextCompat.getColorStateList(context, R.color.button_text_selector))
                
                // Adjust text size based on screen density
                val textSizeSp = when {
                    density <= 1.0 -> 18f // ldpi
                    density <= 1.5 -> 20f // mdpi
                    density <= 2.0 -> 22f // hdpi
                    else -> 24f           // xhdpi and above
                }
                textSize = textSizeSp
                
                elevation = 4f
                setPadding(0, 0, 0, 0)
                isAllCaps = true  // Force uppercase for the button text display
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                minimumWidth = 0
                minimumHeight = 0
                
                // Create rounded corners for the button
                stateListAnimator = null  // Remove button shadow animation
            }

            currentRow?.addView(button)
            buttonsInCurrentRow++
        }

        if (buttonsInCurrentRow < buttonsPerRow) {
            currentRow?.gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
    }

    private fun clearIncorrectLetters() {
        // Clear the textbox and re-enable all letter buttons
        textBox.text = ""
        enableAllLetterButtons()
        
        // Restore the correct letters that were already found
        if (correctLetters.isNotEmpty()) {
            // Set the correct letters in the textbox (they'll display as uppercase due to textAllCaps="true")
            textBox.text = correctLetters
            
            // Disable the corresponding letter buttons
            correctLetters.forEach { letter ->
                disableLetterButton(letter.toString())
            }
        }
        
        // Check the word status
        checkWord()
    }

    private fun backspace() {
        val text = textBox.text.toString()
        if (text.isNotEmpty() && text.length > correctLetters.length) {
            // Get the last letter (which will be uppercase in the display)
            val removedLetter = text.last().toString().lowercase()
            
            // Remove the last character
            textBox.text = text.substring(0, text.length - 1)
            
            // Re-enable the matching button (which will have uppercase text)
            enableFirstDisabledButton(removedLetter)
        }
    }

    private fun enableFirstDisabledButton(letter: String) {
        val uppercaseLetter = letter.uppercase()
        var enabled = false
        forEachLetterButton { button ->
            if (!enabled && button.text.toString() == uppercaseLetter && !button.isEnabled) {
                button.isEnabled = true
                enabled = true
            }
        }
    }

    private fun enableAllLetterButtons() {
        forEachLetterButton { button ->
            button.isEnabled = true
        }
    }

    private fun disableLetterButton(letter: String) {
        val uppercaseLetter = letter.uppercase()
        forEachLetterButton { button ->
            if (button.text.toString() == uppercaseLetter && button.isEnabled) {
                button.isEnabled = false
                return@forEachLetterButton
            }
        }
    }

    private fun forEachLetterButton(action: (Button) -> Unit) {
        for (i in 0 until letterContainer.childCount) {
            val row = letterContainer.getChildAt(i) as LinearLayout
            for (j in 0 until row.childCount) {
                val button = row.getChildAt(j) as Button
                action(button)
            }
        }
    }

    private fun showHint() {
        if (textBox.text.isEmpty() || textBox.text[0] != currentWord[0]) {
            textBox.text = currentWord[0].toString()
        }

        setHintButtonEnabled(false)
        checkWord(isHint = true)
        score -= 1
        updateScoreAndLevel()
        startTimer()
    }

    private fun checkWord(isHint: Boolean = false) {
        val enteredText = textBox.text.toString()
        val newCorrectLetters = enteredText.commonPrefixWith(currentWord)

        // Add points for newly correct letters
        score += (newCorrectLetters.length - correctLetters.length)
        possibleScore += (newCorrectLetters.length - correctLetters.length)

        correctLetters = newCorrectLetters
        textBox.text = correctLetters

        enableAllLetterButtons()

        // Create a map of lowercase letter counts from correctLetters
        val letterCounts = correctLetters.groupingBy { it }.eachCount().toMutableMap()
        
        // Now handle the buttons which have uppercase text
        forEachLetterButton { button ->
            // Get the letter from the button but convert to lowercase for comparison
            val buttonLetter = button.text.toString()[0].lowercaseChar()
            if (buttonLetter in letterCounts && letterCounts[buttonLetter]!! > 0) {
                button.isEnabled = false
                letterCounts[buttonLetter] = letterCounts[buttonLetter]!! - 1
            }
        }

        if (correctLetters == currentWord) {
            stopTimer()
            // Flash the textbox green
            flashTextBoxGreen()
            level++
        } else if (!isHint) {
            score -= 1  // Deduct a point for using Check button
        }

        if (correctLetters.isNotEmpty()) {
            setHintButtonEnabled(false)
        }

        updateScoreAndLevel()
        saveAppState()
    }

    private fun setHintButtonEnabled(enabled: Boolean) {
        hintButton.isEnabled = enabled
        hintButton.backgroundTintList = ContextCompat.getColorStateList(this, R.color.button_background_selector)
        hintButton.setTextColor(ContextCompat.getColorStateList(this, R.color.button_text_selector))
    }
    
    private fun flashTextBoxRed() {
        // Save original background color
        val originalBackground = textBox.background
        
        // Set text box background to red
        textBox.setBackgroundColor(ContextCompat.getColor(this, R.color.error))
        
        // Deduct a point for incorrect word
        score -= 1
        updateScoreAndLevel()
        
        // Schedule restoration of original color and clearing after 1 second
        Handler(Looper.getMainLooper()).postDelayed({
            // Restore original background
            textBox.background = originalBackground
            
            // Call checkWord instead of clearIncorrectLetters to preserve game flow
            checkWord(isHint = false)
        }, 1000) // 1 second delay
    }
    
    private fun flashTextBoxGreen() {
        // Save original background color
        val originalBackground = textBox.background
        
        // Set text box background to green (using app's secondary color)
        textBox.setBackgroundColor(ContextCompat.getColor(this, R.color.secondary))
        
        // Schedule restoration of original color and move to next picture after 1 second
        Handler(Looper.getMainLooper()).postDelayed({
            // Restore original background
            textBox.background = originalBackground
            
            // Move to next picture
            loadNextPicture()
            saveAppState()
        }, 1000) // 1 second delay
    }
    
    private fun shuffleLetters() {
        // Start the timer if it's not running
        if (!isTimerRunning) {
            startTimer()
        }
        
        try {
            // Get all letter buttons currently enabled
            val activeButtons = ArrayList<Button>()
            val activeLetters = ArrayList<String>()
            
            // First pass to collect all buttons and their letters (which will already be uppercase)
            forEachLetterButton { button ->
                if (button.isEnabled) {
                    activeButtons.add(button)
                    activeLetters.add(button.text.toString())
                }
            }
            
            // If we don't have at least 2 letters to shuffle, nothing to do
            if (activeButtons.size < 2) {
                return
            }
            
            // Shuffle the letters until the order is different
            var shuffledLetters: List<String>
            var attempts = 0
            val maxAttempts = 5
            do {
                shuffledLetters = activeLetters.shuffled()
                attempts++
            } while (shuffledLetters == activeLetters && attempts < maxAttempts)
            
            // If we couldn't get a different order after several attempts, just return
            if (shuffledLetters == activeLetters) {
                return
            }
            
            // Update each button with its new letter
            for (i in activeButtons.indices) {
                if (i < shuffledLetters.size) {
                    activeButtons[i].text = shuffledLetters[i]
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Shuffle error: ${e.message}", e)
        }
    }
    
    private fun startPeerCountUpdates() {
        peerUpdateRunnable = object : Runnable {
            override fun run() {
                updatePeerCount()
                
                // Watchdog: Check for stuck states every update
                checkForStuckStates()
                
                peerUpdateHandler.postDelayed(this, 5000) // Update every 5 seconds
            }
        }
        peerUpdateHandler.post(peerUpdateRunnable!!)
    }
    
    private fun checkForStuckStates() {
        try {
            // CRITICAL: Never interfere with active downloads
            if (isBackgroundDownloading || torrentManager?.isActivelyDownloading() == true) {
                android.util.Log.d("MainActivity", "Watchdog: Download in progress, skipping all interference")
                return
            }
            
            // Check if we've been initializing seeding for too long (only if NOT downloading)
            if (isSeedingInitializing) {
                val currentTime = System.currentTimeMillis()
                val initDuration = currentTime - seedingInitStartTime
                
                if (initDuration > 20000) { // 20 second watchdog
                    android.util.Log.w("MainActivity", "Watchdog: Seeding stuck for ${initDuration}ms - clearing flag only")
                    isSeedingInitializing = false
                    
                    // Only clear the flag, don't interfere with torrent manager during downloads
                    android.util.Log.d("MainActivity", "Watchdog: Only clearing isSeedingInitializing flag, not touching torrent session")
                    peerCountTextView.text = "Torrent: Initialization timeout cleared"
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error in watchdog check", e)
        }
    }
    
    private fun stopPeerCountUpdates() {
        peerUpdateRunnable?.let { peerUpdateHandler.removeCallbacks(it) }
    }
    
    private fun updatePeerCount() {
        try {
            // TorrentManager should already be initialized in onCreate
            if (torrentManager == null) {
                android.util.Log.w("MainActivity", "TorrentManager not initialized")
                peerCountTextView.text = "Torrent: Not initialized"
                return
            }
            
            val peerCount = torrentManager!!.getPeerCount()
            val isSeeding = torrentManager!!.isSeeding()
            val uploadRate = torrentManager!!.getUploadRate()
            val isLibraryLoaded = torrentManager!!.isLibraryLoaded()
            val isSeedingEnabled = torrentManager!!.isSeedingEnabled()
            
            android.util.Log.d("MainActivity", "Torrent status - seeding: $isSeeding, peers: $peerCount, uploadRate: $uploadRate, libraryLoaded: $isLibraryLoaded, seedingEnabled: $isSeedingEnabled, downloading: $isBackgroundDownloading")
            
            when {
                isBackgroundDownloading -> {
                    // Don't override download progress display
                    return
                }
                isSeedingInitializing -> {
                    // CRITICAL: Never interfere with active downloads
                    if (isBackgroundDownloading || torrentManager?.isActivelyDownloading() == true) {
                        android.util.Log.d("MainActivity", "Download in progress - not interfering with seeding initialization")
                        return
                    }
                    
                    // Check if seeding initialization has been stuck too long (only if NOT downloading)
                    val currentTime = System.currentTimeMillis()
                    val initDuration = currentTime - seedingInitStartTime
                    
                    if (initDuration > 15000) { // Reduced to 15 seconds for faster recovery
                        android.util.Log.w("MainActivity", "Seeding initialization stuck for ${initDuration}ms, clearing flag only")
                        isSeedingInitializing = false
                        
                        // Only clear the flag during non-download periods
                        android.util.Log.d("MainActivity", "Clearing isSeedingInitializing flag without touching torrent session")
                        peerCountTextView.text = "Torrent: Initialization timeout cleared"
                        return
                    }
                    // Don't override seeding initialization display if still within timeout
                    return
                }
                isSeeding && peerCount > 0 -> {
                    val uploadKB = uploadRate / 1024
                    val mode = if (isLibraryLoaded) "P2P" else "Simulated"
                    val port = torrentManager?.getCurrentPort() ?: 0
                    val portStr = when {
                        port > 0 -> ":$port"
                        port == 0 && (torrentManager?.isLibraryLoaded() == true) -> ":random"
                        else -> ":6881" // Default when library not loaded or uninitialized
                    }
                    
                    // Try to get seed count from torrent handle
                    val seedCount = try {
                        torrentManager?.getCurrentTorrentHandle()?.status()?.numSeeds() ?: 0
                    } catch (e: Exception) {
                        0
                    }
                    val leecherCount = maxOf(0, peerCount - seedCount)
                    
                    if (seedCount > 0) {
                        peerCountTextView.text = "Torrent: Sharing with $seedCount seeds, $leecherCount leechers (${uploadKB}KB/s) [$mode$portStr]"
                    } else {
                        peerCountTextView.text = "Torrent: Sharing with $peerCount peers (${uploadKB}KB/s) [$mode$portStr]"
                    }
                }
                isSeeding -> {
                    val mode = if (isLibraryLoaded) "P2P" else "Simulated"
                    val port = torrentManager?.getCurrentPort() ?: 0
                    val portStr = when {
                        port > 0 -> ":$port"
                        port == 0 && (torrentManager?.isLibraryLoaded() == true) -> ":random"
                        else -> ":6881" // Default when library not loaded or uninitialized
                    }
                    peerCountTextView.text = "Torrent: Seeding (0 peers) [$mode$portStr]"
                }
                !isSeedingEnabled -> {
                    peerCountTextView.text = "Torrent: Seeding disabled"
                }
                !isLibraryLoaded -> {
                    peerCountTextView.text = "Torrent: Library not available"
                }
                networkManager.isOnMobileData() -> {
                    peerCountTextView.text = "Torrent: Mobile data - P2P disabled"
                }
                File(filesDir, "pictures.tar.gz").exists() -> {
                    val downloadFile = File(filesDir, "pictures.tar.gz")
                    if (verifyFileHash(downloadFile)) {
                        // Check if we're in a network transition - show appropriate message
                        if (torrentManager?.isNetworkTransitioning() == true) {
                            peerCountTextView.text = "Torrent: Network transitioning, seeding will resume..."
                        } else {
                            peerCountTextView.text = "Torrent: Valid file exists, initializing seeding..."
                        }
                    } else {
                        peerCountTextView.text = "Torrent: File corrupted, will re-download when on WiFi"
                    }
                }
                else -> {
                    peerCountTextView.text = "Torrent: No archive file - will download on WiFi"
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error updating peer count", e)
            peerCountTextView.text = "Torrent: Status error"
        }
    }
    
    private fun startBackgroundTorrentDownload() {
        // Only start if not already downloading
        if (isBackgroundDownloading) {
            android.util.Log.d("MainActivity", "Background download already in progress")
            return
        }
        
        // Check if on mobile data - if so, don't start torrent download
        if (networkManager.isOnMobileData()) {
            android.util.Log.d("MainActivity", "On mobile data - DHT discovery doesn't work, skipping torrent download")
            peerCountTextView.text = "Torrent: Mobile data detected - P2P downloads disabled"
            return
        }
        
        android.util.Log.d("MainActivity", "Starting background P2P download for seeding")
        peerCountTextView.text = "Torrent: Starting background download..."
        isBackgroundDownloading = true
        
        val downloadFile = File(filesDir, "pictures.tar.gz")
        
        // Check if valid file already exists (race condition protection)
        if (verifyFileHash(downloadFile)) {
            android.util.Log.d("MainActivity", "Valid archive file already exists (${downloadFile.length()} bytes), starting seeding")
            torrentManager?.seedFile(downloadFile.absolutePath, this)
            isBackgroundDownloading = false
            peerCountTextView.text = "Torrent: Valid file found, starting seeding..."
            return
        } else if (downloadFile.exists()) {
            android.util.Log.w("MainActivity", "Archive file exists but hash verification failed - deleting corrupted file")
            downloadFile.delete()
            peerCountTextView.text = "Torrent: Removing corrupted file..."
        }
        
        // Check if LibTorrent is available before attempting P2P
        if (torrentManager?.isLibraryLoaded() != true) {
            android.util.Log.d("MainActivity", "LibTorrent not available, cannot download")
            peerCountTextView.text = "Torrent: P2P library not available"
            isBackgroundDownloading = false
            return
        }
        
        // Try P2P download first
        val magnetLink = getString(R.string.pictures_magnet_link)
        android.util.Log.d("MainActivity", "Starting P2P download with magnet: ${magnetLink.take(50)}...")
        android.util.Log.d("MainActivity", "Setting isBackgroundDownloading=true to protect from interference")
        peerCountTextView.text = "Torrent: Connecting to P2P network..."
        torrentManager?.downloadFile(magnetLink, downloadFile.absolutePath, this)
    }
    
    
    // TorrentDownloadListener implementation for background download
    override fun onProgress(downloaded: Long, total: Long, downloadRate: Int, peers: Int) {
        connectedPeers = peers // Connected peers are those actively transferring data
        val progress = if (total > 0) (downloaded.toFloat() / total * 100).toInt() else 0
        val downloadSpeedKB = downloadRate / 1024
        val downloadedMB = downloaded / (1024 * 1024)
        val totalMB = total / (1024 * 1024)
        val port = torrentManager?.getCurrentPort() ?: 0
        val portStr = when {
            port > 0 -> " [P2P:$port]"
            port == 0 && (torrentManager?.isLibraryLoaded() == true) -> " [P2P:random]"
            else -> " [P2P:6881]" // Default when library not loaded or uninitialized
        }
        
        val statusText = if (total > 0) {
            "Background Download: $progress% (${downloadedMB}MB/${totalMB}MB) - $peers peers - ${downloadSpeedKB}KB/s$portStr"
        } else {
            "Background Download: Connecting... ($peers peers)$portStr"
        }
        
        peerCountTextView.text = statusText
        android.util.Log.d("MainActivity", "Background download progress: $statusText")
    }
    
    override fun onCompleted(filePath: String) {
        android.util.Log.d("MainActivity", "Background P2P download completed: $filePath")
        android.util.Log.d("MainActivity", "Setting isBackgroundDownloading=false - interference protection disabled")
        isBackgroundDownloading = false
        downloadRetryCount = 0 // Reset retry counter on success
        totalPeersFound = 0
        connectedPeers = 0
        
        val downloadedFile = File(filePath)
        if (verifyFileHash(downloadedFile)) {
            android.util.Log.d("MainActivity", "P2P downloaded file hash verified successfully")
            peerCountTextView.text = "Background Download: Complete (P2P) - Hash verified, starting seeding..."
            // Start seeding the verified downloaded file
            torrentManager?.seedFile(filePath, this)
        } else {
            android.util.Log.e("MainActivity", "P2P downloaded file hash verification failed - deleting corrupted file")
            peerCountTextView.text = "Background Download: Hash verification failed - file corrupted"
            downloadedFile.delete()
            // Don't retry - file was corrupted
            android.util.Log.w("MainActivity", "P2P downloaded file was corrupted - not retrying")
        }
    }
    
    override fun onError(error: String) {
        android.util.Log.w("MainActivity", "Background P2P download failed: $error (attempt ${downloadRetryCount + 1}/$MAX_DOWNLOAD_RETRIES)")
        android.util.Log.d("MainActivity", "Setting isBackgroundDownloading=false due to error - interference protection disabled")
        isBackgroundDownloading = false
        totalPeersFound = 0
        connectedPeers = 0
        
        if (downloadRetryCount < MAX_DOWNLOAD_RETRIES) {
            downloadRetryCount++
            peerCountTextView.text = "Torrent: Download error, retrying... (${downloadRetryCount}/$MAX_DOWNLOAD_RETRIES)"
            
            // Retry after 10 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                android.util.Log.d("MainActivity", "Retrying P2P download after error (attempt $downloadRetryCount)")
                startBackgroundTorrentDownload()
            }, 10000)
        } else {
            android.util.Log.w("MainActivity", "P2P download failed after $MAX_DOWNLOAD_RETRIES attempts")
            peerCountTextView.text = "Torrent: Download failed - $error"
            downloadRetryCount = 0 // Reset for future attempts
        }
    }
    
    override fun onTimeout() {
        android.util.Log.w("MainActivity", "Background P2P download timed out (attempt ${downloadRetryCount + 1}/$MAX_DOWNLOAD_RETRIES)")
        android.util.Log.d("MainActivity", "Setting isBackgroundDownloading=false due to timeout - interference protection disabled")
        isBackgroundDownloading = false
        totalPeersFound = 0
        connectedPeers = 0
        
        if (downloadRetryCount < MAX_DOWNLOAD_RETRIES) {
            downloadRetryCount++
            peerCountTextView.text = "Torrent: No peers found, retrying... (${downloadRetryCount}/$MAX_DOWNLOAD_RETRIES)"
            
            // Retry after 10 seconds
            Handler(Looper.getMainLooper()).postDelayed({
                android.util.Log.d("MainActivity", "Retrying P2P download (attempt $downloadRetryCount)")
                startBackgroundTorrentDownload()
            }, 10000)
        } else {
            android.util.Log.w("MainActivity", "P2P download failed after $MAX_DOWNLOAD_RETRIES attempts")
            peerCountTextView.text = "Torrent: Download failed - no peers available"
            downloadRetryCount = 0 // Reset for future attempts
        }
    }

    override fun onVerifying(progress: Float) {
        val progressPercent = (progress * 100).toInt()
        if (progress == 0.0f) {
            peerCountTextView.text = if (totalPeersFound > 0) {
                "Torrent: Fetching metadata ($connectedPeers/$totalPeersFound peers)..."
            } else {
                "Torrent: Fetching metadata..."
            }
        } else {
            peerCountTextView.text = "Torrent: Verifying $progressPercent%"
        }
    }

    // Enhanced status callbacks for better torrent lifecycle tracking
    override fun onDhtConnecting() {
        android.util.Log.d("MainActivity", "DHT connecting...")
        peerCountTextView.text = "Torrent: Connecting to DHT network..."
    }

    override fun onDhtConnected(nodeCount: Int) {
        android.util.Log.d("MainActivity", "DHT connected with $nodeCount nodes")
        peerCountTextView.text = if (nodeCount > 0) {
            "Torrent: DHT connected ($nodeCount nodes)"
        } else {
            "Torrent: DHT network connected"
        }
    }

    override fun onDiscoveringPeers() {
        android.util.Log.d("MainActivity", "Discovering peers...")
        peerCountTextView.text = "Torrent: Searching for peers..."
    }

    override fun onSeedsFound(seedCount: Int, peerCount: Int) {
        android.util.Log.d("MainActivity", "Found $seedCount seeds, $peerCount total peers")
        totalPeersFound = peerCount
        val leecherCount = peerCount - seedCount
        peerCountTextView.text = "Torrent: Found $seedCount seeds, $leecherCount leechers"
    }

    override fun onMetadataFetching() {
        android.util.Log.d("MainActivity", "Fetching metadata...")
        peerCountTextView.text = if (totalPeersFound > 0) {
            "Torrent: Fetching metadata ($connectedPeers/$totalPeersFound peers)..."
        } else {
            "Torrent: Fetching file metadata..."
        }
    }

    override fun onMetadataComplete() {
        android.util.Log.d("MainActivity", "Metadata complete")
        peerCountTextView.text = "Torrent: Metadata received, preparing file..."
    }

    override fun onReadyToSeed() {
        android.util.Log.d("MainActivity", "Ready to seed")
        peerCountTextView.text = "Torrent: File ready, starting seeding..."
        // Clear the seeding initialization flag since we're ready
        isSeedingInitializing = false
    }
    
    override fun onPhaseChanged(phase: DownloadPhase, timeoutSeconds: Int) {
        // MainActivity doesn't need to do anything special with phase changes
        // since it only uses TorrentManager for seeding, not downloading
        android.util.Log.d("MainActivity", "Torrent phase changed to $phase with ${timeoutSeconds}s timeout")
    }

    // DHT diagnostics callbacks - not needed for MainActivity but required by interface
    override fun onDhtDiagnostic(message: String) {
        android.util.Log.d("MainActivity", "DHT Diagnostic: $message")
    }

    override fun onSessionDiagnostic(message: String) {
        android.util.Log.d("MainActivity", "Session Diagnostic: $message")
    }
    
    private fun checkSeedingInitialization() {
        if (!isSeedingInitializing) {
            return // Already completed or failed
        }
        
        val isSeeding = torrentManager?.isSeeding() ?: false
        val isLibraryLoaded = torrentManager?.isLibraryLoaded() ?: false
        val peerCount = torrentManager?.getPeerCount() ?: 0
        
        android.util.Log.d("MainActivity", "Seeding initialization check: isSeeding=$isSeeding, libraryLoaded=$isLibraryLoaded, peers=$peerCount")
        
        when {
            isSeeding -> {
                android.util.Log.d("MainActivity", "Seeding initialization successful")
                isSeedingInitializing = false
                peerCountTextView.text = "Torrent: Seeding active (checking for peers...)"
            }
            !isLibraryLoaded -> {
                android.util.Log.w("MainActivity", "Seeding initialization failed - library not loaded")
                isSeedingInitializing = false
                peerCountTextView.text = "Torrent: P2P library not available"
                // Don't re-download if we have a valid file, just note that seeding isn't working
                val downloadFile = File(filesDir, "pictures.tar.gz")
                if (!verifyFileHash(downloadFile)) {
                    android.util.Log.d("MainActivity", "No valid file and no P2P library - cannot download")
                    peerCountTextView.text = "Torrent: No file, P2P unavailable"
                }
            }
            else -> {
                android.util.Log.w("MainActivity", "Seeding initialization timed out - checking file validity")
                isSeedingInitializing = false
                val downloadFile = File(filesDir, "pictures.tar.gz")
                
                if (verifyFileHash(downloadFile)) {
                    android.util.Log.w("MainActivity", "File is valid but seeding failed - accepting gracefully")
                    peerCountTextView.text = "Torrent: File valid, seeding unavailable (P2P issues)"
                    // File is valid, so don't delete it or re-download
                    // Accept that seeding might not work due to network/peer issues
                    // This is not a critical failure - the app can function without seeding
                } else {
                    android.util.Log.w("MainActivity", "Seeding timeout and file is corrupted - deleting corrupted file")
                    peerCountTextView.text = "Torrent: File corrupted, deleted"
                    if (downloadFile.exists()) {
                        downloadFile.delete()
                        android.util.Log.d("MainActivity", "Deleted corrupted torrent file")
                    }
                    // Don't re-download - stay torrent-only
                }
            }
        }
    }
    
    /**
     * Calculate SHA-256 hash of a file
     */
    private fun calculateFileHash(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val fis = FileInputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            fis.close()
            
            // Convert to hex string
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to calculate file hash: ${e.message}", e)
            null
        }
    }
    
    /**
     * Verify if the downloaded file has the correct hash
     */
    private fun verifyFileHash(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) {
            android.util.Log.d("MainActivity", "File verification failed: file doesn't exist or is empty")
            return false
        }
        
        val actualHash = calculateFileHash(file)
        if (actualHash == null) {
            android.util.Log.w("MainActivity", "File verification failed: could not calculate hash")
            return false
        }
        
        val isValid = actualHash.equals(EXPECTED_FILE_HASH, ignoreCase = true)
        android.util.Log.d("MainActivity", "File hash verification: expected=$EXPECTED_FILE_HASH, actual=$actualHash, valid=$isValid")
        
        return isValid
    }
    
    /**
     * Migrate existing torrent file from cache directory to internal storage
     */
    private fun migrateCacheFileToInternalStorage() {
        val cacheFile = File(cacheDir, "pictures.tar.gz")
        val internalFile = File(filesDir, "pictures.tar.gz")
        
        // Only migrate if cache file exists and internal file doesn't
        if (cacheFile.exists() && !internalFile.exists()) {
            try {
                android.util.Log.d("MainActivity", "Migrating torrent file from cache to internal storage")
                
                // Verify the cache file before migrating
                if (verifyFileHash(cacheFile)) {
                    android.util.Log.d("MainActivity", "Cache file is valid, copying to internal storage")
                    cacheFile.copyTo(internalFile, overwrite = false)
                    android.util.Log.d("MainActivity", "Migration successful, deleting cache file")
                    cacheFile.delete()
                    android.util.Log.d("MainActivity", "Torrent file migrated successfully (${internalFile.length()} bytes)")
                } else {
                    android.util.Log.w("MainActivity", "Cache file is corrupted, deleting instead of migrating")
                    cacheFile.delete()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to migrate torrent file: ${e.message}", e)
                // Clean up on error
                try {
                    if (internalFile.exists()) internalFile.delete()
                } catch (cleanupError: Exception) {
                    android.util.Log.e("MainActivity", "Failed to clean up after migration error", cleanupError)
                }
            }
        } else if (cacheFile.exists() && internalFile.exists()) {
            // Both files exist - remove the cache file since we now use internal storage
            android.util.Log.d("MainActivity", "Both cache and internal torrent files exist, removing cache file")
            cacheFile.delete()
        }
    }
}