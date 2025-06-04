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
import java.io.FileOutputStream
import java.net.URL

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
    
    private var torrentManager: TorrentManager? = null
    private var peerUpdateHandler: Handler = Handler(Looper.getMainLooper())
    private var peerUpdateRunnable: Runnable? = null
    private var backgroundDownloadJob: Job? = null
    private var isBackgroundDownloading = false
    private var isSeedingInitializing = false

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
        
        // Initialize TorrentManager for seeding (after UI is set up)
        android.util.Log.d("MainActivity", "Initializing TorrentManager...")
        torrentManager = TorrentManager.getInstance(this)
        android.util.Log.d("MainActivity", "TorrentManager initialized, library loaded: ${torrentManager?.isLibraryLoaded()}")
        
        // Check for existing torrent file and start seeding/background download
        val downloadedFile = File(cacheDir, "pictures.tar.gz")
        android.util.Log.d("MainActivity", "Checking for torrent file: ${downloadedFile.absolutePath}, exists: ${downloadedFile.exists()}")
        
        if (downloadedFile.exists() && downloadedFile.length() > 0) {
            android.util.Log.d("MainActivity", "Found existing torrent file (${downloadedFile.length()} bytes), starting seeding")
            peerCountTextView.text = "Torrent: Found archive file, starting seeding..."
            // Use the enhanced seedFile method that can restore from stored magnet link
            try {
                isSeedingInitializing = true
                peerCountTextView.text = "Torrent: Initializing seeding..."
                android.util.Log.d("MainActivity", "Starting async seeding initiation")
                
                // Run seeding initialization in background thread
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        torrentManager!!.seedFile(downloadedFile.absolutePath)
                        android.util.Log.d("MainActivity", "Seeding initiation completed")
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "Seeding initiation failed: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            isSeedingInitializing = false
                            peerCountTextView.text = "Torrent: Seeding failed - ${e.message}"
                        }
                    }
                }
                
                // Set multiple status checks - quick check and final timeout
                Handler(Looper.getMainLooper()).postDelayed({
                    // Quick status check after 5 seconds
                    if (isSeedingInitializing) {
                        val isSeeding = torrentManager?.isSeeding() ?: false
                        if (isSeeding) {
                            android.util.Log.d("MainActivity", "Quick check: Seeding started successfully")
                            checkSeedingInitialization()
                        } else {
                            peerCountTextView.text = "Torrent: Still initializing... (fetching metadata)"
                        }
                    }
                }, 5000) // 5 second quick check
                
                Handler(Looper.getMainLooper()).postDelayed({
                    checkSeedingInitialization()
                }, 15000) // 15 second final timeout
                
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to start seeding: ${e.message}", e)
                isSeedingInitializing = false
                peerCountTextView.text = "Torrent: Failed to start seeding - ${e.message}"
            }
        } else {
            android.util.Log.d("MainActivity", "No torrent file found, starting background download")
            // Pictures are extracted but no archive for seeding - start background torrent download
            startBackgroundTorrentDownload()
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
        backgroundDownloadJob?.cancel()
        torrentManager?.stopDownload()
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
                peerUpdateHandler.postDelayed(this, 5000) // Update every 5 seconds
            }
        }
        peerUpdateHandler.post(peerUpdateRunnable!!)
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
                    // Don't override seeding initialization display
                    return
                }
                isSeeding && peerCount > 0 -> {
                    val uploadKB = uploadRate / 1024
                    val mode = if (isLibraryLoaded) "P2P" else "Simulated"
                    peerCountTextView.text = "Torrent: Sharing with $peerCount peers (${uploadKB}KB/s) [$mode]"
                }
                isSeeding -> {
                    val mode = if (isLibraryLoaded) "P2P" else "Simulated"
                    peerCountTextView.text = "Torrent: Seeding (0 peers) [$mode]"
                }
                !isSeedingEnabled -> {
                    peerCountTextView.text = "Torrent: Seeding disabled"
                }
                !isLibraryLoaded -> {
                    peerCountTextView.text = "Torrent: Library not available"
                }
                File(cacheDir, "pictures.tar.gz").exists() -> {
                    peerCountTextView.text = "Torrent: File exists, initializing seeding..."
                }
                else -> {
                    peerCountTextView.text = "Torrent: No archive file - downloading in background"
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
        
        android.util.Log.d("MainActivity", "Starting background P2P download for seeding")
        peerCountTextView.text = "Torrent: Starting background download..."
        isBackgroundDownloading = true
        
        val downloadFile = File(cacheDir, "pictures.tar.gz")
        
        // Check if file already exists (race condition protection)
        if (downloadFile.exists() && downloadFile.length() > 0) {
            android.util.Log.d("MainActivity", "Archive file already exists (${downloadFile.length()} bytes), starting seeding")
            torrentManager?.seedFile(downloadFile.absolutePath)
            isBackgroundDownloading = false
            peerCountTextView.text = "Torrent: File found, starting seeding..."
            return
        }
        
        // Check if LibTorrent is available before attempting P2P
        if (torrentManager?.isLibraryLoaded() != true) {
            android.util.Log.d("MainActivity", "LibTorrent not available, falling back to HTTPS")
            peerCountTextView.text = "Torrent: P2P unavailable, using HTTPS..."
            fallbackToHttpsDownload()
            return
        }
        
        // Try P2P download first
        val magnetLink = getString(R.string.pictures_magnet_link)
        android.util.Log.d("MainActivity", "Starting P2P download with magnet: ${magnetLink.take(50)}...")
        peerCountTextView.text = "Torrent: Connecting to P2P network..."
        torrentManager?.downloadFile(magnetLink, downloadFile.absolutePath, this)
    }
    
    private fun fallbackToHttpsDownload() {
        android.util.Log.d("MainActivity", "Starting background HTTPS download as fallback")
        
        backgroundDownloadJob = GlobalScope.launch(Dispatchers.IO) {
            try {
                val downloadFile = File(cacheDir, "pictures.tar.gz")
                
                // Check if file already exists (race condition protection)
                if (downloadFile.exists()) {
                    android.util.Log.d("MainActivity", "Archive file already exists, starting seeding")
                    withContext(Dispatchers.Main) {
                        torrentManager?.seedFile(downloadFile.absolutePath)
                        isBackgroundDownloading = false
                    }
                    return@launch
                }
                
                val url = URL("https://unjumble.au/files/pictures.tar.gz")
                android.util.Log.d("MainActivity", "Background downloading: ${url}")
                
                val connection = url.openConnection()
                val fileLength = connection.contentLength.toLong()
                val inputStream = connection.getInputStream()
                val outputStream = FileOutputStream(downloadFile)
                
                val buffer = ByteArray(8192)
                var count: Int
                var totalBytes = 0L
                var lastUpdateTime = System.currentTimeMillis()
                
                while (inputStream.read(buffer).also { count = it } != -1) {
                    outputStream.write(buffer, 0, count)
                    totalBytes += count
                    
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime >= 2000) { // Update every 2 seconds
                        val progress = if (fileLength > 0) (totalBytes.toFloat() / fileLength * 100).toInt() else 0
                        val downloadedMB = totalBytes / (1024 * 1024)
                        val totalMB = fileLength / (1024 * 1024)
                        
                        withContext(Dispatchers.Main) {
                            if (fileLength > 0) {
                                peerCountTextView.text = "Background Download: $progress% (${downloadedMB}MB/${totalMB}MB) - HTTPS"
                            } else {
                                peerCountTextView.text = "Background Download: ${downloadedMB}MB downloaded - HTTPS"
                            }
                        }
                        
                        lastUpdateTime = currentTime
                    }
                }
                
                outputStream.close()
                inputStream.close()
                
                android.util.Log.d("MainActivity", "Background HTTPS download completed: ${totalBytes} bytes")
                
                // Start seeding the downloaded file
                withContext(Dispatchers.Main) {
                    peerCountTextView.text = "Background Download: Complete (HTTPS) - Starting seeding..."
                    torrentManager?.seedFile(downloadFile.absolutePath)
                    isBackgroundDownloading = false
                    android.util.Log.d("MainActivity", "Started seeding background downloaded file")
                }
                
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Background HTTPS download failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    peerCountTextView.text = "Background Download: HTTPS failed - ${e.message}"
                    isBackgroundDownloading = false
                }
            }
        }
    }
    
    // TorrentDownloadListener implementation for background download
    override fun onProgress(downloaded: Long, total: Long, downloadRate: Int, peers: Int) {
        val progress = if (total > 0) (downloaded.toFloat() / total * 100).toInt() else 0
        val downloadSpeedKB = downloadRate / 1024
        val downloadedMB = downloaded / (1024 * 1024)
        val totalMB = total / (1024 * 1024)
        
        val statusText = if (total > 0) {
            "Background Download: $progress% (${downloadedMB}MB/${totalMB}MB) - $peers peers - ${downloadSpeedKB}KB/s"
        } else {
            "Background Download: Connecting... ($peers peers)"
        }
        
        peerCountTextView.text = statusText
        android.util.Log.d("MainActivity", "Background download progress: $statusText")
    }
    
    override fun onCompleted(filePath: String) {
        android.util.Log.d("MainActivity", "Background P2P download completed: $filePath")
        isBackgroundDownloading = false
        
        // Start seeding the downloaded file
        torrentManager?.seedFile(filePath)
        
        peerCountTextView.text = "Background Download: Complete - Starting seeding..."
    }
    
    override fun onError(error: String) {
        android.util.Log.w("MainActivity", "Background P2P download failed: $error")
        isBackgroundDownloading = false
        
        peerCountTextView.text = "Background Download: P2P failed - Trying HTTPS..."
        
        // Fallback to HTTPS download
        Handler(Looper.getMainLooper()).postDelayed({
            fallbackToHttpsDownload()
        }, 2000) // Wait 2 seconds before fallback
    }
    
    override fun onTimeout() {
        android.util.Log.w("MainActivity", "Background P2P download timed out")
        isBackgroundDownloading = false
        
        peerCountTextView.text = "Background Download: P2P timeout - Trying HTTPS..."
        
        // Fallback to HTTPS download
        Handler(Looper.getMainLooper()).postDelayed({
            fallbackToHttpsDownload()
        }, 2000) // Wait 2 seconds before fallback
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
                // Start background download as fallback
                startBackgroundTorrentDownload()
            }
            else -> {
                android.util.Log.w("MainActivity", "Seeding initialization timed out - trying background download")
                isSeedingInitializing = false
                peerCountTextView.text = "Torrent: Seeding timeout - starting background download"
                // Delete the potentially corrupted file and restart download
                val downloadFile = File(cacheDir, "pictures.tar.gz")
                if (downloadFile.exists()) {
                    downloadFile.delete()
                    android.util.Log.d("MainActivity", "Deleted potentially corrupted torrent file")
                }
                startBackgroundTorrentDownload()
            }
        }
    }
}