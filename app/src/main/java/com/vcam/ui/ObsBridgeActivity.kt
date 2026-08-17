package com.vcam.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.vcam.R
import com.vcam.databinding.ActivityObsBridgeBinding
import com.vcam.service.ConnectServer
import com.vcam.service.VCamService

/**
 * OBS/Bridg setup screen.
 *
 * The Android side continues to use the existing authenticated ConnectServer
 * on port 7979. Bridg sends the live JPEG frames over the same USB-forwarded
 * socket; the service then feeds them through the existing injector pipeline.
 */
class ObsBridgeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityObsBridgeBinding
    private var linkEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityObsBridgeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etBridgeHost.setText("localhost")
        binding.etBridgePort.setText(ConnectServer.PORT.toString())
        binding.etBridgeToken.setText(ConnectServer.getToken(this))

        binding.btnBackToSource.setOnClickListener {
            startActivity(Intent(this, SourceModeActivity::class.java))
            finish()
        }
        binding.btnStartBridge.setOnClickListener { enableBridge() }
        binding.btnStopBridge.setOnClickListener { disableBridge() }
        binding.btnCopyToken.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText(
                    getString(R.string.bridge_token_label),
                    binding.etBridgeToken.text.toString()
                )
            )
            Toast.makeText(this, getString(R.string.bridge_token_copied), Toast.LENGTH_SHORT).show()
        }

        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun enableBridge() {
        if (binding.etBridgeToken.text.toString().trim().isEmpty()) {
            binding.etBridgeToken.error = getString(R.string.bridge_token_required)
            return
        }

        startService(Intent(this, VCamService::class.java).apply {
            action = VCamService.ACTION_ENABLE_LINK
        })
        linkEnabled = true
        refreshState()
        Toast.makeText(this, getString(R.string.bridge_ready), Toast.LENGTH_LONG).show()
    }

    private fun disableBridge() {
        startService(Intent(this, VCamService::class.java).apply {
            action = VCamService.ACTION_DISABLE_LINK
        })
        linkEnabled = false
        refreshState()
    }

    private fun refreshState() {
        linkEnabled = ConnectServer.isEnabled(this)
        binding.tvBridgeStatus.text = if (linkEnabled) {
            getString(R.string.bridge_status_ready)
        } else {
            getString(R.string.bridge_status_stopped)
        }
        binding.tvBridgeStatus.setTextColor(
            getColor(if (linkEnabled) R.color.color_success else R.color.color_text_hint)
        )
        binding.btnStartBridge.isEnabled = !linkEnabled
        binding.btnStopBridge.isEnabled = linkEnabled
    }
}