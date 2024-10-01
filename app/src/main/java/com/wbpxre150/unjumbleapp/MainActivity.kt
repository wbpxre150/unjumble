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

class MainActivity : Activity() {

    private lateinit var imageView: ImageView
    private lateinit var textBox: TextView
    private lateinit var letterContainer: LinearLayout
    private lateinit var clearButton: Button
    private lateinit var backspaceButton: Button
    private lateinit var nextWordButton: Button
    private lateinit var hintButton: Button
    private lateinit var checkButton: Button
    private lateinit var scoreTextView: TextView
    private lateinit var levelTextView: TextView

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("AppState", Context.MODE_PRIVATE)

        // Check if files have been downloaded
        if (!sharedPreferences.getBoolean("filesDownloaded", false)) {
            startActivity(Intent(this, DownloadActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        imageView = findViewById(R.id.imageView)
        textBox = findViewById(R.id.textBox)
        letterContainer = findViewById(R.id.letterContainer)
        clearButton = findViewById(R.id.clearButton)
        backspaceButton = findViewById(R.id.backspaceButton)
        nextWordButton = findViewById(R.id.nextWordButton)
        hintButton = findViewById(R.id.hintButton)
        checkButton = findViewById(R.id.checkButton)
        scoreTextView = findViewById(R.id.scoreTextView)
        levelTextView = findViewById(R.id.levelTextView)
        timerTextView = findViewById(R.id.timerTextView)

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

        updateScoreAndLevel()
        loadCurrentPicture()

        clearButton.setOnClickListener { clearIncorrectLetters() }
        backspaceButton.setOnClickListener { backspace() }
        nextWordButton.setOnClickListener {
            loadNextPicture()
            saveAppState()
        }
        hintButton.setOnClickListener { showHint() }
        checkButton.setOnClickListener { checkWord() }
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
    }

    override fun onResume() {
        super.onResume()
        if (isTimerRunning) {
            startTimer()
        }
        updateTimerDisplay()
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
        nextWordButton.visibility = View.GONE
        isTimerRunning = false
        updateTimerDisplay()
        enableAllLetterButtons()
        setHintButtonEnabled(true)
        updateScoreAndLevel()
    }

    private fun loadNextPicture() {
        currentPictureIndex++
        if (currentPictureIndex >= pictureFiles.size) {
            currentPictureIndex = 0
        }
        loadCurrentPicture()
    }

    private fun createLetterButtons() {
        letterContainer.removeAllViews()
        val screenWidth = resources.displayMetrics.widthPixels
        val buttonMargin = 8
        val buttonsPerRow = 6
        val buttonWidth = ((screenWidth - (buttonsPerRow + 1) * buttonMargin) / buttonsPerRow) - 20

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
                }
                letterContainer.addView(currentRow)
                buttonsInCurrentRow = 0
            }

            val button = Button(this).apply {
                text = letter.toString()
                setOnClickListener {
                    if (!isTimerRunning) {
                        startTimer()
                    }
                    textBox.append(letter.toString())
                    isEnabled = false
                }
                layoutParams = LinearLayout.LayoutParams(buttonWidth, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(buttonMargin / 2, 0, buttonMargin / 2, buttonMargin)
                }
                backgroundTintList = ContextCompat.getColorStateList(context, R.color.letter_button_background_selector)
                setTextColor(ContextCompat.getColorStateList(context, R.color.button_text_selector))
                textSize = 18f * 1.5f
            }

            currentRow?.addView(button)
            buttonsInCurrentRow++
        }

        if (buttonsInCurrentRow < buttonsPerRow) {
            currentRow?.gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
    }

    private fun clearIncorrectLetters() {
        textBox.text = ""
        enableAllLetterButtons()
        correctLetters.forEach { letter ->
            textBox.append(letter.toString())
            disableLetterButton(letter.toString())
        }
        checkWord()
    }

    private fun backspace() {
        val text = textBox.text
        if (text.isNotEmpty() && text.length > correctLetters.length) {
            val removedLetter = text.last().toString()
            textBox.text = text.substring(0, text.length - 1)
            enableFirstDisabledButton(removedLetter)
        }
    }

    private fun enableFirstDisabledButton(letter: String) {
        var enabled = false
        forEachLetterButton { button ->
            if (!enabled && button.text.toString() == letter && !button.isEnabled) {
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
        forEachLetterButton { button ->
            if (button.text.toString() == letter && button.isEnabled) {
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

        val letterCounts = correctLetters.groupingBy { it }.eachCount().toMutableMap()

        forEachLetterButton { button ->
            val letter = button.text.toString()[0]
            if (letter in letterCounts && letterCounts[letter]!! > 0) {
                button.isEnabled = false
                letterCounts[letter] = letterCounts[letter]!! - 1
            }
        }

        if (correctLetters == currentWord) {
            stopTimer()
            nextWordButton.visibility = View.VISIBLE
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
}