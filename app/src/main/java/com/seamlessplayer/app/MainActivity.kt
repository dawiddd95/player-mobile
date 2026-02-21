package com.seamlessplayer.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Ekran startowy - odpowiednik Player.bat
 * Wyświetla 3 przyciski do wyboru wariantu odtwarzacza:
 * 1. Odtwarzacz standardowy (sortowanie naturalne: 1, 2, 10)
 * 2. Odtwarzacz z sortowaniem jak w Windows (1, 10, 2)
 * 3. Wideo bez dźwięku (sortowanie jak w Windows)
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Przycisk 1: Odtwarzacz standardowy (sortowanie naturalne)
        findViewById<MaterialButton>(R.id.btnStandard).setOnClickListener {
            launchPlayer(
                sortMode = PlayerActivity.SORT_NATURAL,
                muted = false
            )
        }

        // Przycisk 2: Odtwarzacz z sortowaniem jak w Windows
        findViewById<MaterialButton>(R.id.btnWindowsSort).setOnClickListener {
            launchPlayer(
                sortMode = PlayerActivity.SORT_LOCALE,
                muted = false
            )
        }

        // Przycisk 3: Wideo bez dźwięku (sortowanie jak w Windows)
        findViewById<MaterialButton>(R.id.btnMuted).setOnClickListener {
            launchPlayer(
                sortMode = PlayerActivity.SORT_LOCALE,
                muted = true
            )
        }
    }

    private fun launchPlayer(sortMode: Int, muted: Boolean) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_SORT_MODE, sortMode)
            putExtra(PlayerActivity.EXTRA_MUTED, muted)
        }
        startActivity(intent)
    }
}

