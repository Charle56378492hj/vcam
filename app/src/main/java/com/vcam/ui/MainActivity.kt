package com.vcam.ui

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.vcam.R
import com.vcam.data.AuthManager
import com.vcam.data.SubscriptionManager
import com.vcam.databinding.ActivityMainBinding
import com.vcam.service.ConnectServer
import com.vcam.service.VCamService
import com.vcam.ui.account.AccountActivity
import com.vcam.ui.auth.LoginActivity
import com.vcam.utils.MediaSlotManager
import com.vcam.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var pendingSlot = 1

    private var livePreviewReceiver: BroadcastReceiver? = null
    private var lastFrameTime = 0L

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val slot    = pendingSlot
        val isVideo = slot >= 5
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                MediaSlotManager.setSlot(this@MainActivity, slot, uri, isVideo)
            }
            refreshSlotUI(slot)
            binding.btnStartStop.isEnabled = MediaSlotManager.isSlotSet(this@MainActivity, 1)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        lifecycleScope.launch {
            viewModel.initRoot()
            if (!allGranted) showSnack(getString(R.string.permissions_required))
        }
    }

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupObservers()
        setupSlotPickers()
        setupDeleteButtons()
        setupRotateButtons()
        setupStartStop()
        setupLinkSwitch()
        setupLivePreview()
        requestPermissions()
        (1..8).forEach { refreshSlotUI(it) }
        binding.btnStartStop.isEnabled = MediaSlotManager.isSlotSet(this, 1)

        // My Account button
        binding.btnMyAccount.setOnClickListener {
            startActivity(Intent(this, AccountActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Check auth
        if (!AuthManager.isLoggedIn()) {
            goToLogin()
            return
        }
        refreshLinkUI()
        refreshLivePreviewUI()
        registerLivePreviewReceiver()
    }

    override fun onPause() {
        super.onPause()
        unregisterLivePreviewReceiver()
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    // ── Observers ─────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.rootStatus.observe(this) { hasRoot ->
            binding.tvRootStatus.text = if (hasRoot) getString(R.string.root_granted) else getString(R.string.root_denied)
            binding.tvRootStatus.setTextColor(
                getColor(if (hasRoot) R.color.color_root_ok else R.color.color_root_fail)
            )
        }
        viewModel.isServiceRunning.observe(this) { running ->
            binding.btnStartStop.text = if (running) getString(R.string.stop_vcam) else {
                if (ConnectServer.isEnabled(this)) getString(R.string.start_bridge_mode)
                else getString(R.string.start_vcam)
            }
            binding.btnStartStop.backgroundTintList = ContextCompat.getColorStateList(
                this, if (running) R.color.color_stop else R.color.color_start
            )
        }
    }

    // ── Slot Pickers ──────────────────────────────────────────────────────

    private fun setupSlotPickers() {
        for (slot in 1..8) {
            val cardId = resources.getIdentifier("card_slot_$slot", "id", packageName)
            val placeholderId = resources.getIdentifier("placeholder_slot_$slot", "id", packageName)
            val card = findViewById<View>(cardId) ?: continue
            val placeholder = findViewById<View>(placeholderId) ?: continue
            listOf(card, placeholder).forEach { v ->
                v.setOnClickListener {
                    pendingSlot = slot
                    val isVideo = slot >= 5
                    pickMedia.launch(if (isVideo) "video/*" else "image/*")
                }
            }
        }
    }

    private fun setupDeleteButtons() {
        for (slot in 1..8) {
            val btnId = resources.getIdentifier("btn_delete_slot_$slot", "id", packageName)
            val btn = findViewById<ImageButton>(btnId) ?: continue
            btn.setOnClickListener {
                MediaSlotManager.clearSlot(this, slot)
                refreshSlotUI(slot)
                binding.btnStartStop.isEnabled = MediaSlotManager.isSlotSet(this, 1)
            }
        }
    }

    private fun setupRotateButtons() {
        for (slot in 1..8) {
            val btnId = resources.getIdentifier("btn_rotate_slot_$slot", "id", packageName)
            val btn = findViewById<ImageButton>(btnId) ?: continue
            btn.setOnClickListener {
                val newRot = (MediaSlotManager.getSlotRotation(this, slot) + 90) % 360
                MediaSlotManager.setSlotRotation(this, slot, newRot)
                refreshSlotUI(slot)
            }
        }
    }

    private fun refreshSlotUI(slot: Int) {
        val ivId     = resources.getIdentifier("iv_slot_$slot",          "id", packageName)
        val phId     = resources.getIdentifier("placeholder_slot_$slot",  "id", packageName)
        val delBtnId = resources.getIdentifier("btn_delete_slot_$slot",   "id", packageName)
        val rotBtnId = resources.getIdentifier("btn_rotate_slot_$slot",   "id", packageName)

        val iv     = findViewById<ImageView>(ivId)     ?: return
        val ph     = findViewById<View>(phId)          ?: return
        val delBtn = findViewById<ImageButton>(delBtnId)
        val rotBtn = findViewById<ImageButton>(rotBtnId)

        val isSet  = MediaSlotManager.isSlotSet(this, slot)
        if (isSet) {
            val path = MediaSlotManager.getSlotPath(this, slot) ?: return
            val isVideo = MediaSlotManager.isSlotVideo(this, slot)
            val bmp: Bitmap? = if (isVideo) {
                try {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(path)
                    val frame = mmr.getFrameAtTime(0)
                    mmr.release()
                    frame
                } catch (_: Exception) { null }
            } else {
                try { android.graphics.BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
            }
            val rot = MediaSlotManager.getSlotRotation(this, slot)
            if (bmp != null) {
                val matrix = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                iv.setImageBitmap(rotated)
            }
            iv.visibility = View.VISIBLE
            ph.visibility = View.GONE
        } else {
            iv.setImageBitmap(null)
            iv.visibility = View.GONE
            ph.visibility = View.VISIBLE
        }
        delBtn?.visibility = if (isSet) View.VISIBLE else View.GONE
        rotBtn?.visibility = if (isSet) View.VISIBLE else View.GONE
    }

    // ── Start / Stop ──────────────────────────────────────────────────────

    private fun setupStartStop() {
        binding.btnStartStop.setOnClickListener {
            val running = viewModel.isServiceRunning.value ?: false
            if (running) {
                stopVCamService()
            } else {
                // Check subscription before allowing start
                lifecycleScope.launch {
                    val user = AuthManager.currentUser()
                    if (user == null) {
                        val refreshed = AuthManager.refreshSession()
                        val refreshedUser = AuthManager.currentUser()
                        if (!refreshed || refreshedUser == null) { goToLogin(); return@launch }
                        val hasSub = SubscriptionManager.hasActiveSubscription(refreshedUser.id)
                        runOnUiThread {
                            if (hasSub) tryStartVCamService() else showSubscriptionRequired()
                        }
                    } else {
                        val hasSub = SubscriptionManager.hasActiveSubscription(user.id)
                        runOnUiThread {
                            if (hasSub) {
                                tryStartVCamService()
                            } else {
                                showSubscriptionRequired()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showSubscriptionRequired() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.subscription_required))
            .setMessage(getString(R.string.subscription_required_msg))
            .setPositiveButton(getString(R.string.go_to_account)) { _, _ ->
                startActivity(Intent(this, AccountActivity::class.java))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun tryStartVCamService() {
        val bridgeEnabled = ConnectServer.isEnabled(this)
        val hasLocalMedia = MediaSlotManager.isSlotSet(this, 1)

        // Bridge mode: if the link is enabled, start injection directly from
        // the live Bridg stream — no local media required.
        if (bridgeEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(getString(R.string.overlay_permission_title))
                    .setMessage(getString(R.string.overlay_permission_msg))
                    .setPositiveButton(getString(R.string.grant)) { _, _ ->
                        overlayPermLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName"))
                        )
                    }
                    .setNegativeButton(R.string.skip) { _, _ -> doStartBridgeService() }
                    .show()
            } else {
                doStartBridgeService()
            }
            return
        }

        // Local mode: fall back to the original local-media workflow.
        if (!hasLocalMedia) {
            showSnack(getString(R.string.select_media_first))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.overlay_permission_title))
                .setMessage(getString(R.string.overlay_permission_msg))
                .setPositiveButton(getString(R.string.grant)) { _, _ ->
                    overlayPermLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(R.string.skip) { _, _ -> doStartService() }
                .show()
        } else {
            doStartService()
        }
    }

    private fun doStartService() {
        val slot1Path = MediaSlotManager.getSlotPath(this, 1) ?: return
        val intent = Intent(this, VCamService::class.java).apply {
            action = VCamService.ACTION_START
            putExtra(VCamService.EXTRA_MEDIA_PATH, slot1Path)
            putExtra(VCamService.EXTRA_IS_VIDEO, false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        viewModel.setServiceRunning(true)
        showSnack(getString(R.string.injection_active))
    }

    private fun doStartBridgeService() {
        val intent = Intent(this, VCamService::class.java).apply {
            action = VCamService.ACTION_START_BRIDGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        viewModel.setServiceRunning(true)
        showSnack(getString(R.string.bridge_injection_active))
    }

    private fun stopVCamService() {
        startService(Intent(this, VCamService::class.java).apply { action = VCamService.ACTION_STOP })
        viewModel.setServiceRunning(false)
    }

    // ── Link Switch ───────────────────────────────────────────────────────

    private fun setupLinkSwitch() {
        refreshLinkUI()
        binding.switchLink.setOnCheckedChangeListener { _, isChecked ->
            ConnectServer.setEnabled(this, isChecked)
            if (isChecked) {
                val intent = Intent(this, VCamService::class.java).apply {
                    action = VCamService.ACTION_ENABLE_LINK
                }
                startService(intent)
                showSnack(getString(R.string.link_enabled_msg))
            } else {
                val intent = Intent(this, VCamService::class.java).apply {
                    action = VCamService.ACTION_DISABLE_LINK
                }
                startService(intent)
                showSnack(getString(R.string.link_disabled_msg))
            }
            refreshLinkUI()
            refreshLivePreviewUI()
        }
    }

    private fun refreshLinkUI() {
        val enabled = ConnectServer.isEnabled(this)
        binding.switchLink.isChecked = enabled
        if (enabled) {
            val token = ConnectServer.getToken(this)
            binding.tvLinkToken.text = token
            binding.tvLinkPort.text = getString(R.string.link_port, ConnectServer.PORT)
        } else {
            binding.tvLinkToken.text = "——"
            binding.tvLinkPort.text = ""
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────

    private fun requestPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(android.Manifest.permission.CAMERA)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
        else lifecycleScope.launch { viewModel.initRoot() }
    }

    private fun showSnack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()

    // ── Live Preview (Bridg stream) ──────────────────────────────────────

    private fun setupLivePreview() {
        refreshLivePreviewUI()
    }

    private fun refreshLivePreviewUI() {
        val bridgeEnabled = ConnectServer.isEnabled(this)
        val placeholder = findViewById<LinearLayout>(R.id.layout_preview_placeholder)
        val statusText  = findViewById<TextView>(R.id.tv_live_status)
        val dot         = findViewById<View>(R.id.view_live_dot)

        if (bridgeEnabled) {
            placeholder?.visibility = View.GONE
            statusText?.text = getString(R.string.live_preview_waiting)
            statusText?.setTextColor(getColor(R.color.color_warning))
            dot?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.color_warning)
        } else {
            placeholder?.visibility = View.VISIBLE
            findViewById<ImageView>(R.id.iv_live_preview)?.setImageBitmap(null)
            statusText?.text = getString(R.string.live_preview_idle)
            statusText?.setTextColor(getColor(R.color.color_text_hint))
            dot?.backgroundTintList = ContextCompat.getColorStateList(this, R.color.color_text_hint)
        }
    }

    private fun registerLivePreviewReceiver() {
        if (livePreviewReceiver != null) return
        livePreviewReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == VCamService.ACTION_BRIDGE_FRAME) {
                    val path = intent.getStringExtra(VCamService.EXTRA_FRAME_PATH) ?: return
                    val now = System.currentTimeMillis()
                    // Throttle UI updates to ~15 FPS to avoid jank on the main thread.
                    if (now - lastFrameTime < 66) return
                    lastFrameTime = now
                    lifecycleScope.launch(Dispatchers.IO) {
                        val bmp = try { BitmapFactory.decodeFile(path) } catch (_: Exception) { null }
                        if (bmp != null) {
                            runOnUiThread {
                                findViewById<ImageView>(R.id.iv_live_preview)?.setImageBitmap(bmp)
                                findViewById<LinearLayout>(R.id.layout_preview_placeholder)
                                    ?.visibility = View.GONE
                                val status = findViewById<TextView>(R.id.tv_live_status)
                                status?.text = getString(R.string.live_preview_active)
                                status?.setTextColor(getColor(R.color.color_success))
                                findViewById<View>(R.id.view_live_dot)
                                    ?.backgroundTintList = ContextCompat.getColorStateList(
                                        this@MainActivity, R.color.color_success)
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(VCamService.ACTION_BRIDGE_FRAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(livePreviewReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(livePreviewReceiver, filter)
        }
    }

    private fun unregisterLivePreviewReceiver() {
        livePreviewReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            livePreviewReceiver = null
        }
    }
}
