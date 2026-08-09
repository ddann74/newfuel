package com.newfuel.fuelalert

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.newfuel.fuelalert.databinding.ActivityMainBinding

/**
 * Scaffold only (PROGRESS.md milestone 1) - the settings screen (milestone
 * 6) replaces this placeholder body once trip start/stop, fuel type,
 * search mode, and threshold controls exist to put on it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}
