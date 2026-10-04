package com.vcam.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local, app-private diagnostics for VCam and camera-related Android events. */
object DiagnosticLog {
    private const val TAG = "DiagnosticLog"
    private const val LOG_NAME = "vcam-diagnostics.log"
    private const val BACKUP_NAME = "vcam-diagnostics.log.1"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val MAX_MESSAGE_CHARS = 3000

    private val lock = Any()
    @Volatile private var appContext: Context? = null
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private val assignmentSecret = Regex(
        "(?i)(\\b(?:access[_-]?token|refresh[_-]?token|token|password|passwd|secret|api[_-]?key|anon[_-]?key)\\b\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;&]+)"
    )
    private val bearerSecret = Regex("(?i)\\bBearer\\s+[^\\s,;]+")

    fun initialize(context: Context) {
        appContext = context.applicationContext
        info(
            "App",
            "Diagnostics ready; Android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), device=${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    fun info(tag: String, message: String) = write("I", tag, message)
    fun warn(tag: String, message: String) = write("W", tag, message)
    fun error(tag: String, message: String, error: Throwable? = null) {
        val details = if (error == null) message else "$message\n${Log.getStackTraceString(error)}"
        write("E", tag, details)
    }

    fun sanitize(value: String): String = value
        .replace(bearerSecret, "Bearer <redacted>")
        .replace(assignmentSecret, "$1<redacted>")

    fun readAll(): String = synchronized(lock) {
        val dir = appContext?.filesDir ?: return@synchronized ""
        val backup = File(dir, BACKUP_NAME)
        val current = File(dir, LOG_NAME)
        buildString {
            if (backup.exists()) append(backup.readText(StandardCharsets.UTF_8))
            if (current.exists()) append(current.readText(StandardCharsets.UTF_8))
        }
    }

    fun revision(): Long = synchronized(lock) {
        val dir = appContext?.filesDir ?: return@synchronized 0L
        val current = File(dir, LOG_NAME)
        val backup = File(dir, BACKUP_NAME)
        (current.lastModified() * 31L + current.length()) xor
            (backup.lastModified() * 17L + backup.length())
    }

    fun clear() {
        synchronized(lock) {
            val dir = appContext?.filesDir ?: return
            File(dir, LOG_NAME).delete()
            File(dir, BACKUP_NAME).delete()
            File(dir, LOG_NAME).createNewFile()
        }
        info("Logs", "Log history cleared by user")
    }

    private fun write(level: String, tag: String, message: String) {
        val dir = appContext?.filesDir ?: return
        val safeMessage = sanitize(message).take(MAX_MESSAGE_CHARS)
        val time = timestampFormat.get()!!.format(Date())
        val prefix = "$time ${Thread.currentThread().name} $level/$tag: "
        val lines = safeMessage.split('\n')
        synchronized(lock) {
            try {
                val current = File(dir, LOG_NAME)
                val backup = File(dir, BACKUP_NAME)
                if (current.exists() && current.length() >= MAX_FILE_BYTES) {
                    backup.delete()
                    current.renameTo(backup)
                }
                FileOutputStream(current, true).bufferedWriter(StandardCharsets.UTF_8).use { out ->
                    lines.forEach { line ->
                        out.append(prefix).append(line).append('\n')
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to save diagnostic log", e)
            }
        }
    }
}

/** Captures only VCam and Android camera-service log tags, not unrelated app logs. */
object CameraSystemLogCollector {
    private const val TAG = "CameraSystemLogCollector"
    private const val FILTER = "exec logcat -v threadtime -T 1 " +
        "CameraService:V CameraServiceProxy:V CameraProviderManager:V " +
        "CameraDeviceClient:V Camera3-Device:V Camera:V cameraserver:V " +
        "VCamInject:V VCamNative:V VcplaxEngine:V CameraInjector:V " +
        "VCamService:V RootManager:V '*:S'"

    @Volatile private var process: Process? = null
    @Volatile private var reader: Thread? = null

    @Synchronized
    fun start() {
        if (process?.isAlive == true) {
            DiagnosticLog.info(TAG, "Filtered camera logcat collector already running")
            return
        }
        try {
            DiagnosticLog.info(TAG, "ROOT SHELL > su -c '$FILTER'")
            val launched = ProcessBuilder("su", "-c", FILTER)
                .redirectErrorStream(true)
                .start()
            process = launched
            DiagnosticLog.info(TAG, "Started filtered camera/system logcat capture")
            reader = Thread({
                try {
                    launched.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (process !== launched) return@forEach
                            val level = if (line.contains("permission denied", true) ||
                                line.contains("not found", true) || line.contains("error", true)) "E" else "I"
                            when (level) {
                                "E" -> DiagnosticLog.error("AndroidLogcat", line)
                                else -> DiagnosticLog.info("AndroidLogcat", line)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (process === launched) DiagnosticLog.error(TAG, "Camera logcat reader failed", e)
                } finally {
                    val exit = try { launched.waitFor() } catch (_: Exception) { -1 }
                    if (process === launched) {
                        process = null
                        DiagnosticLog.warn(TAG, "Camera logcat process ended (exit=$exit)")
                    }
                }
            }, "vcam-camera-logcat").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            process = null
            DiagnosticLog.error(TAG, "Could not start root camera logcat capture", e)
        }
    }

    @Synchronized
    fun stop() {
        val current = process ?: return
        process = null
        try { current.destroy() } catch (_: Exception) {}
        DiagnosticLog.info(TAG, "Stopped filtered camera/system logcat capture")
    }
}
