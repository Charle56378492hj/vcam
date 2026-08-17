package com.vcam.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.vcam.databinding.ActivitySourceModeBinding

/**
 * First screen after authentication.
 *
 * The existing local media workflow remains in MainActivity unchanged. This
 * screen only lets the user choose which source they want to use.
 */
class SourceModeActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySourceModeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceModeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLocalUpload.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.btnObsBridge.setOnClickListener {
            startActivity(Intent(this, ObsBridgeActivity::class.java))
            finish()
        }
    }

    override fun onBackPressed() {
        // Keep the authenticated flow predictable: going back returns to login
        // instead of exposing a half-configured source screen.
        super.onBackPressed()
    }
}