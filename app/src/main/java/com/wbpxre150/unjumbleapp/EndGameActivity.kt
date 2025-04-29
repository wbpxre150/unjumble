package com.wbpxre150.unjumbleapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import kotlin.math.roundToInt

class EndGameActivity : Activity() {

    private lateinit var scoreTextView: TextView
    private lateinit var levelsCompletedTextView: TextView
    private lateinit var totalTimeTextView: TextView
    private lateinit var playAgainButton: Button
    private lateinit var shareResultsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_end_game)

        // Initialize views
        scoreTextView = findViewById(R.id.scoreTextView)
        levelsCompletedTextView = findViewById(R.id.levelsCompletedTextView)
        totalTimeTextView = findViewById(R.id.totalTimeTextView)
        playAgainButton = findViewById(R.id.playAgainButton)
        shareResultsButton = findViewById(R.id.shareResultsButton)

        // Get stats from intent
        val score = intent.getIntExtra("score", 0)
        val possibleScore = intent.getIntExtra("possibleScore", 0)
        val levelsCompleted = intent.getIntExtra("levels", 0)
        val totalLevels = intent.getIntExtra("totalLevels", 4364)
        val totalPlayTimeMillis = intent.getLongExtra("totalPlayTimeMillis", 0)

        // Calculate percentages
        val scorePercentage = if (possibleScore > 0) {
            (score.toDouble() / possibleScore * 100).roundToInt()
        } else {
            0
        }
        
        val levelPercentage = if (totalLevels > 0) {
            (levelsCompleted.toDouble() / totalLevels * 100).roundToInt()
        } else {
            0
        }

        // Format play time
        val seconds = (totalPlayTimeMillis / 1000).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60
        val timeString = String.format("%02d:%02d:%02d", hours, minutes % 60, seconds % 60)

        // Update UI
        scoreTextView.text = "Score: $score/$possibleScore ($scorePercentage%)"
        levelsCompletedTextView.text = "Levels Completed: $levelsCompleted/$totalLevels ($levelPercentage%)"
        totalTimeTextView.text = "Total Play Time: $timeString"

        // Set up click listeners
        playAgainButton.setOnClickListener {
            resetGame()
            startMainActivity()
        }

        shareResultsButton.setOnClickListener {
            shareResults(score, possibleScore, scorePercentage, levelsCompleted, totalLevels, timeString)
        }
    }

    private fun resetGame() {
        val sharedPreferences = getSharedPreferences("AppState", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        
        // Reset game state, but keep filesDownloaded flag
        editor.putInt("currentPictureIndex", 0)
        editor.putInt("score", 0)
        editor.putInt("possibleScore", 0)
        editor.putInt("level", 1)
        editor.putLong("totalPlayTimeMillis", 0)
        editor.putBoolean("isFirstRun", true)
        
        editor.apply()
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(intent)
        finish()
    }

    private fun shareResults(
        score: Int,
        possibleScore: Int,
        scorePercentage: Int,
        levelsCompleted: Int,
        totalLevels: Int,
        timeString: String
    ) {
        val shareText = """
            I just completed Unjumble!
            
            📊 Score: $score/$possibleScore ($scorePercentage%)
            🔢 Levels: $levelsCompleted/$totalLevels completed
            ⏱️ Total Time: $timeString
            
            Download and play Unjumble now!
        """.trimIndent()

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            type = "text/plain"
        }
        
        startActivity(Intent.createChooser(shareIntent, "Share your results"))
    }
}