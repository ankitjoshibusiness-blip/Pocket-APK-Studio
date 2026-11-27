package com.pocketapkstudio

import android.app.IntentService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

internal object TermuxResultHandler {

    private fun isValidOutputFile(file: File, expectedExtension: String): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return !expectedExtension.equals("apk", ignoreCase = true) || ApkFileValidator.isValidApk(file)
    }

    private fun materializeOutputFile(
        context: Context,
        subDirName: String,
        expectedExtension: String,
        pendingUriText: String?,
        pendingName: String?,
        reportedPath: String?
    ): File? {
        val reportedFile = reportedPath?.let(::File)
        val fileNamePattern = Regex("^[A-Za-z0-9._-]+\\.${Regex.escape(expectedExtension)}$", RegexOption.IGNORE_CASE)
        val fileName = pendingName?.takeIf { fileNamePattern.matches(it) }
            ?: reportedFile?.name?.takeIf { fileNamePattern.matches(it) }
            ?: return null

        val uri = pendingUriText?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri != null) {
            val targetDir = File(context.filesDir, subDirName)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                Log.e("PocketAPKStudio", "Could not create private $subDirName directory")
                return null
            }
            val target = File(targetDir, fileName)
            val temporary = File(targetDir, ".$fileName.part")
            return try {
                val input = if (uri.scheme == "file") {
                    File(uri.path ?: error("Invalid file URI")).inputStream()
                } else {
                    context.contentResolver.openInputStream(uri)
                        ?: error("Android could not open the reserved Downloads output")
                }
                input.use { source ->
                    temporary.outputStream().buffered().use { output -> source.copyTo(output) }
                }
                if (!isValidOutputFile(temporary, expectedExtension)) {
                    error(
                        if (expectedExtension.equals("apk", ignoreCase = true))
                            "The reserved output does not contain a valid APK ZIP with a non-empty AndroidManifest.xml"
                        else
                            "The reserved output file is empty"
                    )
                }
                if (target.exists() && !target.delete()) {
                    error("Could not replace the previous private output copy")
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                target.takeIf { isValidOutputFile(it, expectedExtension) }
                    ?: error("The private output copy failed verification")
            } catch (error: Exception) {
                temporary.delete()
                Log.e("PocketAPKStudio", "Could not materialize output file from URI", error)
                reportedFile?.takeIf { isValidOutputFile(it, expectedExtension) }
            }
        }

        // Compatibility fallback for older builds that reported a direct readable path.
        return reportedFile?.takeIf { isValidOutputFile(it, expectedExtension) }
    }

    private fun deletePendingUri(context: Context, uriText: String?) {
        if (uriText.isNullOrBlank()) return
        runCatching {
            val uri = Uri.parse(uriText)
            if (uri.scheme == "file") {
                uri.path?.let { File(it).delete() }
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    fun handle(context: Context, intent: Intent) {
        val result = intent.getBundleExtra("result")
        val stdout = result?.getString("stdout").orEmpty()
        val stderr = result?.getString("stderr").orEmpty()
        val exitCode = result?.getInt("exitCode", -1) ?: -1
        val errCode = result?.getInt("err", -1) ?: -1
        val errMessage = result?.getString("errmsg").orEmpty()

        if (intent.getStringExtra("task_type") == "apk_decompile") {
            val recoveryPrefs = context.getSharedPreferences("recovery_state", Context.MODE_PRIVATE)
            val pendingZipUri = recoveryPrefs.getString("pending_recovery_zip_uri", null)
            val pendingZipName = recoveryPrefs.getString("pending_recovery_zip_name", null)
            val outputPath = Regex("RECOVERY_SAVED=([^\\r\n]+)").findAll(stdout).lastOrNull()?.groupValues?.get(1)?.trim()
            val outputFile = materializeOutputFile(
                context = context,
                subDirName = "recovered-zips",
                expectedExtension = "zip",
                pendingUriText = pendingZipUri,
                pendingName = pendingZipName,
                reportedPath = outputPath
            )
            val successful = exitCode == 0 && errCode == -1 && outputFile != null && stdout.contains("JADX_DECOMPILE_SUCCESS=1")
            val partial = exitCode == 0 && errCode == -1 && outputFile != null && stdout.contains("JADX_PARTIAL=1")
            val hasRecovery = successful || partial
            if (!hasRecovery) deletePendingUri(context, pendingZipUri)
            val combined = buildString {
                append(stdout)
                if (stderr.isNotBlank()) append("\n\nERROR OUTPUT:\n").append(stderr)
                if (errMessage.isNotBlank()) append("\n\nTERMUX ERROR:\n").append(errMessage)
            }
            val status = when {
                successful -> "JADX decompilation completed. Recovered ZIP: " + outputFile!!.name
                partial -> "Partial recovery created. JADX reported errors, but generated files were saved in: " + outputFile!!.name
                errCode != -1 -> "Termux could not run JADX: $errMessage"
                else -> "Decompilation failed (exit code $exitCode).\n" + combined.takeLast(2500)
            }
            recoveryPrefs.edit()
                .putString("status", status)
                .putBoolean("running", false)
                .putBoolean("failed", !hasRecovery)
                .putBoolean("partial", partial)
                .putInt("percent", if (hasRecovery) 100 else 0)
                .putString("stage", when {
                    successful -> "Decompilation successful"
                    partial -> "Partial recovery ZIP created"
                    else -> "Decompilation failed — exit code $exitCode"
                })
                .putString("zip_path", if (hasRecovery) outputFile!!.absolutePath else null)
                .putString("log", combined.takeLast(30000))
                .putString("live_log", combined.takeLast(12000))
                .remove("pending_recovery_zip_uri")
                .remove("pending_recovery_zip_name")
                .apply()
            context.sendBroadcast(Intent("com.pocketapkstudio.RECOVERY_RESULT").setPackage(context.packageName))
            Log.i("PocketAPKStudio", "JADX decompilation finished. exit=$exitCode err=$errCode")
            return
        }

        if (intent.getStringExtra("task_type") == "environment_setup") {
            val successful = exitCode == 0 && errCode == -1 && stdout.contains("BUILD_SERVER_READY=1")
            val combined = buildString {
                append(stdout)
                if (stderr.isNotBlank()) append("\n\nERROR OUTPUT:\n").append(stderr)
                if (errMessage.isNotBlank()) append("\n\nTERMUX ERROR:\n").append(errMessage)
            }
            val message = if (successful) {
                "Build environment READY — Termux, Ubuntu, Java 17 and Android SDK are configured."
            } else {
                "Automatic setup did not finish. Check Termux permission, allow-external-apps setting and internet connection.\n" + combined.takeLast(1800)
            }
            val scanSummary = listOf(
                "Termux" to "SCAN_TERMUX",
                "Ubuntu" to "SCAN_UBUNTU",
                "Java 17" to "SCAN_JAVA17",
                "Android SDK" to "SCAN_ANDROID_SDK",
                "Platform Tools" to "SCAN_PLATFORM_TOOLS",
                "Build Tools" to "SCAN_BUILD_TOOLS"
            ).joinToString("\n") { item ->
                item.first + ": " + if (stdout.contains(item.second + "=INSTALLED")) "INSTALLED" else "MISSING / NOT VERIFIED"
            }
            context.getSharedPreferences("build_state", Context.MODE_PRIVATE).edit()
                .putBoolean("environment_setup_running", false)
                .putString("server_status", message)
                .putString("environment_setup_result", scanSummary + if (successful) "\n\nAll components are ready." else "\n\nSetup incomplete. Review the error and retry.")
                .putBoolean("environment_setup_complete", successful)
                .apply()
            context.sendBroadcast(Intent("com.pocketapkstudio.SERVER_STATUS").setPackage(context.packageName))
            Log.i("PocketAPKStudio", "Environment setup finished. exit=$exitCode err=$errCode")
            return
        }

        if (intent.getStringExtra("task_type") == "server_check") {
            val successful = exitCode == 0 && errCode == -1
            val ready = successful && stdout.contains("BUILD_SERVER_READY=1")
            val failure = Regex("BUILD_SERVER_FAILED=([^\\r\n]+)").find(stdout)?.groupValues?.get(1)
            val detail = failure ?: errMessage.ifBlank { stderr.ifBlank { "Check Termux and Ubuntu setup." } }
            val message = if (ready) "Build server READY - Termux + Ubuntu + Java + Android SDK connected." else "Build server NOT READY. " + detail
            context.getSharedPreferences("build_state", Context.MODE_PRIVATE).edit().putString("server_status", message).apply()
            context.sendBroadcast(Intent("com.pocketapkstudio.SERVER_STATUS").setPackage(context.packageName))
            return
        }

        val buildPrefs = context.getSharedPreferences("build_state", Context.MODE_PRIVATE)
        val pendingApkUri = buildPrefs.getString("pending_build_apk_uri", null)
        val pendingApkName = buildPrefs.getString("pending_build_apk_name", null)
        // RUN_COMMAND callback output can be empty or partial on some Android/Termux versions.
        // Read the persisted transcript before parsing APK_SAVED so the callback can recover it.
        val liveLogUriText = buildPrefs.getString("live_build_log_uri", null)
        val liveLogFallback = liveLogUriText?.let { text ->
            runCatching {
                val uri = Uri.parse(text)
                val stream = if (uri.scheme == "file") {
                    File(uri.path ?: return@runCatching "").inputStream()
                } else {
                    context.contentResolver.openInputStream(uri)
                }
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
        }.orEmpty()
        val primaryBuildLog = BuildApkCallbackParser.preferredTranscript(
            callbackStdout = stdout,
            liveLog = liveLogFallback,
            expectedFileName = pendingApkName
        )
        val apkPath = BuildApkCallbackParser.findSavedApkPath(primaryBuildLog, pendingApkName)
        val verifiedApk = materializeOutputFile(
            context = context,
            subDirName = "built-apks",
            expectedExtension = "apk",
            pendingUriText = pendingApkUri,
            pendingName = pendingApkName,
            reportedPath = apkPath
        )
        val successful = exitCode == 0 && errCode == -1 && apkPath != null && verifiedApk != null
        val combined = buildString {
            append(primaryBuildLog)
            if (stderr.isNotBlank()) append("\n\nERROR OUTPUT:\n").append(stderr)
            if (errMessage.isNotBlank()) append("\n\nTERMUX ERROR:\n").append(errMessage)
            if (isBlank()) append("No build output was returned by Termux. Check Download/PocketAPKStudio/build-live.log for the device-side transcript.")
        }
        val status = when {
            successful -> "BUILD SUCCESS! APK verified and ready to install/share: " + verifiedApk!!.name
            exitCode == 0 && errCode == -1 && verifiedApk == null -> "BUILD FAILED: Gradle returned success, but no valid APK file was found at the reported path."
            errCode != -1 -> "Termux could not run the build: $errMessage"
            else -> "BUILD FAILED (exit code $exitCode).\n${combined.takeLast(2200)}"
        }
        if (!successful) deletePendingUri(context, pendingApkUri)
        buildPrefs.edit()
            .putString("last_build_status", status)
            .putString("last_build_log", combined.takeLast(40000))
            .putBoolean("build_running", false)
            .putInt("last_build_percent", if (successful) 100 else 0)
            .putString("last_apk_path", if (successful) verifiedApk!!.absolutePath else null)
            .remove("pending_build_apk_uri")
            .remove("pending_build_apk_name")
            .apply()
        context.sendBroadcast(Intent("com.pocketapkstudio.BUILD_RESULT").setPackage(context.packageName))
        Log.i("PocketAPKStudio", "Termux build finished. exit=$exitCode err=$errCode")
    }
}

@Suppress("DEPRECATION")
class TermuxResultService : IntentService("TermuxResultService") {
    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) return
        TermuxResultHandler.handle(applicationContext, intent)
    }
}
