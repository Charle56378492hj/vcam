package com.vcam.utils

  import android.util.Log
  import com.topjohnwu.superuser.Shell
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import java.io.File

  object RootManager {

      private const val TAG = "RootManager"

      init {
          Shell.enableVerboseLogging = false
          Shell.setDefaultBuilder(
              Shell.Builder.create()
                  .setFlags(Shell.FLAG_REDIRECT_STDERR)
                  .setTimeout(15)
          )
      }

      /** Try every available root method; returns true if ANY succeeds */
      suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
          DiagnosticLog.info(TAG, "Root permission check started")
          // Method 1: libsu Shell.getShell()
          try {
              DiagnosticLog.info(TAG, "ROOT CHECK > Shell.getShell()")
              val shell = Shell.getShell()
              val rootGranted = shell.isRoot
              DiagnosticLog.info(TAG, "ROOT CHECK < root=$rootGranted via Shell.getShell()")
              if (rootGranted) {
                  Log.d(TAG, "Root OK via libsu Shell.getShell()")
                  DiagnosticLog.info(TAG, "ROOT CHECK < root=true via Shell.getShell()")
                  return@withContext true
              }
          } catch (e: Exception) {
              Log.w(TAG, "libsu getShell failed: ${e.message}")
              DiagnosticLog.warn(TAG, "libsu getShell failed: ${e.message}")
          }

          // Method 2: Shell.cmd("id")
          try {
              DiagnosticLog.info(TAG, "ROOT CHECK > Shell.cmd(id)")
              val result = Shell.cmd("id").exec()
              val out = result.out.joinToString(" ")
              if (out.contains("uid=0")) {
                  Log.d(TAG, "Root OK via Shell.cmd(id)")
                  DiagnosticLog.info(TAG, "ROOT CHECK < root=true; output=${out.take(500)}")
                  return@withContext true
              }
              DiagnosticLog.info(TAG, "ROOT CHECK < root=false; output=${out.take(500)}")
          } catch (e: Exception) {
              Log.w(TAG, "Shell.cmd id failed: ${e.message}")
              DiagnosticLog.warn(TAG, "Shell.cmd(id) failed: ${e.message}")
          }

          // Method 3: Runtime.exec su -c id
          try {
              DiagnosticLog.info(TAG, "ROOT CHECK > su -c id")
              val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
              val out = proc.inputStream.bufferedReader().readText()
              val exitCode = proc.waitFor()
              if (out.contains("uid=0")) {
                  Log.d(TAG, "Root OK via Runtime su -c id")
                  DiagnosticLog.info(TAG, "ROOT CHECK < root=true; exit=$exitCode; output=${out.take(500)}")
                  return@withContext true
              }
              DiagnosticLog.info(TAG, "ROOT CHECK < root=false; exit=$exitCode; output=${out.take(500)}")
          } catch (e: Exception) {
              Log.w(TAG, "Runtime su failed: ${e.message}")
              DiagnosticLog.warn(TAG, "su -c id failed: ${e.message}")
          }

          // Method 4: Check su binary presence
          val suPaths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su",
                               "/vendor/bin/su", "/su/bin/su", "/magisk/.core/bin/su")
          val hasSu = suPaths.any { File(it).exists() }
          if (hasSu) {
              Log.d(TAG, "Root OK via su binary found")
              DiagnosticLog.info(TAG, "ROOT CHECK < su binary exists; path=${suPaths.first { File(it).exists() }}")
              return@withContext true
          }

          Log.e(TAG, "Root NOT available — all methods failed")
          DiagnosticLog.warn(TAG, "Root permission check failed using all methods")
          false
      }

      fun isRooted(): Boolean {
          // Use Shell.isAppGrantedRoot() — may be null if not yet determined
          val granted = Shell.isAppGrantedRoot()
          DiagnosticLog.info(TAG, "Shell.isAppGrantedRoot()=$granted")
          if (granted == true) return true
          // Fallback: try a quick shell command
          return try {
              DiagnosticLog.info(TAG, "ROOT CHECK > Shell.cmd(id)")
              val result = Shell.cmd("id").exec()
              val out = result.out.joinToString(" ")
              val rooted = out.contains("uid=0")
              DiagnosticLog.info(TAG, "ROOT CHECK < root=$rooted; output=${out.take(500)}")
              rooted
          } catch (e: Exception) {
              DiagnosticLog.error(TAG, "Root status fallback failed", e)
              false
          }
      }

      fun runCommand(command: String): ShellResult {
          DiagnosticLog.info(TAG, "SHELL > ${DiagnosticLog.sanitize(command)}")
          return try {
              val result = Shell.cmd(command).exec()
              val shellResult = ShellResult(
                  success = result.isSuccess,
                  output = result.out.joinToString("\n"),
                  error = result.err.joinToString("\n")
              )
              DiagnosticLog.info(TAG, "SHELL < success=${shellResult.success}; stdout=${shellResult.output.take(2500)}; stderr=${shellResult.error.take(1500)}")
              shellResult
          } catch (e: Exception) {
              DiagnosticLog.error(TAG, "SHELL failed: ${DiagnosticLog.sanitize(command)}", e)
              ShellResult(success = false, output = "", error = e.message ?: "Unknown error")
          }
      }

      fun runCommands(vararg commands: String): Boolean {
          DiagnosticLog.info(TAG, "SHELL batch > ${commands.joinToString(" ; ") { DiagnosticLog.sanitize(it) }}")
          return try {
              val result = Shell.cmd(*commands).exec()
              DiagnosticLog.info(TAG, "SHELL batch < success=${result.isSuccess}; stdout=${result.out.joinToString("\n").take(2500)}; stderr=${result.err.joinToString("\n").take(1500)}")
              result.isSuccess
          } catch (e: Exception) {
              DiagnosticLog.error(TAG, "SHELL batch failed", e)
              false
          }
      }

      suspend fun runCommandAsync(command: String): ShellResult = withContext(Dispatchers.IO) {
          runCommand(command)
      }

      fun checkV4L2Loopback(): Boolean {
          val result = runCommand("ls /dev/video* 2>/dev/null")
          return result.success && result.output.isNotBlank()
      }

      fun getVideoDevices(): List<String> {
          val result = runCommand("ls /dev/video* 2>/dev/null")
          return if (result.success && result.output.isNotBlank()) {
              result.output.trim().split("\n").filter { it.isNotBlank() }
          } else {
              emptyList()
          }
      }

      fun setSystemProp(key: String, value: String): Boolean {
          val result = runCommand("setprop $key $value")
          return result.success
      }

      fun grantPermission(packageName: String, permission: String): Boolean {
          runCommand("pm grant $packageName $permission 2>/dev/null || appops set $packageName CAMERA allow")
          return true
      }

      fun killApp(packageName: String) {
          runCommand("am force-stop $packageName")
      }

      data class ShellResult(
          val success: Boolean,
          val output: String,
          val error: String
      )
  }
