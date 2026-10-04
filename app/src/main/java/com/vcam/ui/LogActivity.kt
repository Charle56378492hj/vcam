package com.vcam.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.vcam.R
import com.vcam.utils.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogActivity : AppCompatActivity() {
    private lateinit var logText: TextView
    private var lastText = ""
    private var lastRevision = Long.MIN_VALUE
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLog()
            handler.postDelayed(this, 1200L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        DiagnosticLog.initialize(this)

        logText = findViewById(R.id.tv_log_content)
        findViewById<MaterialButton>(R.id.btn_log_back).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btn_log_copy).setOnClickListener { copyLog() }
        findViewById<MaterialButton>(R.id.btn_log_clear).setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun refreshLog() {
        lifecycleScope.launch {
            val revision = withContext(Dispatchers.IO) { DiagnosticLog.revision() }
            if (revision != lastRevision) {
                val text = withContext(Dispatchers.IO) { DiagnosticLog.readAll() }
                lastRevision = revision
                if (text != lastText) {
                    lastText = text
                    logText.text = text.ifBlank { getString(R.string.logs_empty) }
                    logText.post {
                        val parent = logText.parent as? android.widget.ScrollView
                        parent?.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
        }
    }

    private fun copyLog() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { DiagnosticLog.readAll() }
            if (text.isBlank()) {
                Toast.makeText(this@LogActivity, R.string.logs_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VCam diagnostic log", text))
            Toast.makeText(this@LogActivity, R.string.logs_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.logs_clear_title)
            .setMessage(R.string.logs_clear_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.logs_clear) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { DiagnosticLog.clear() }
                    lastText = ""
                    lastRevision = Long.MIN_VALUE
                    Toast.makeText(this@LogActivity, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
