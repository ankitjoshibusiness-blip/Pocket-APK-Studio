package com.pocketapkstudio

import android.app.IntentService
import android.content.Intent
import android.util.Log

class TermuxResultService : IntentService("TermuxResultService") {
    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) return
        val result = intent.getBundleExtra("result")
        val stdout = result?.getString("stdout").orEmpty()
        val stderr = result?.getString("stderr").orEmpty()
        val exitCode = result?.getInt("exitCode", -1) ?: -1
        val errCode = result?.getInt("err", -1) ?: -1
        val errMessage = result?.getString("errmsg").orEmpty()
        if (intent.getStringExtra("task_type") == "apk_decompile") {
            val outputPath = Regex("RECOVERY_SAVED=([^\\r\n]+)").findAll(stdout).lastOrNull()?.groupValues?.get(1)?.trim()
            val outputFile = outputPath?.let { java.io.File(it) }?.takeIf { it.isFile && it.length() > 0L }
            val successful = exitCode == 0 && errCode == -1 && outputFile != null
            val combined = buildString {
                append(stdout)
                if (stderr.isNotBlank()) append("\n\nERROR OUTPUT:\n").append(stderr)
                if (errMessage.isNotBlank()) append("\n\nTERMUX ERROR:\n").append(errMessage)
            }
            val status = when {
                successful -> "JADX decompilation completed. Recovered ZIP: " + outputFile!!.name
                errCode != -1 -> "Termux could not run JADX: $errMessage"
                else -> "Decompilation failed (exit code $exitCode).\n" + combined.takeLast(2500)
            }
            getSharedPreferences("recovery_state", MODE_PRIVATE).edit()
                .putString("status", status)
                .putBoolean("running", false)
                .putBoolean("failed", !successful)
                .putInt("percent", if (successful) 100 else 0)
                .putString("stage", if (successful) "Decompilation successful" else "Decompilation failed — exit code $exitCode")
                .putString("zip_path", if (successful) outputFile!!.absolutePath else null)
                .putString("log", combined.takeLast(30000))
                .putString("live_log", combined.takeLast(12000))
                .apply()
            sendBroadcast(Intent("com.pocketapkstudio.RECOVERY_RESULT").setPackage(packageName))
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
            getSharedPreferences("build_state", MODE_PRIVATE).edit()
                .putString("server_status", message)
                .putString("environment_setup_result", scanSummary + if (successful) "\n\nAll components are ready." else "\n\nSetup incomplete. Review the error and retry.")
                .putBoolean("environment_setup_complete", successful)
                .apply()
            sendBroadcast(Intent("com.pocketapkstudio.SERVER_STATUS").setPackage(packageName))
            Log.i("PocketAPKStudio", "Environment setup finished. exit=$exitCode err=$errCode")
            return
        }
        if (intent.getStringExtra("task_type") == "server_check") {
            val successful = exitCode == 0 && errCode == -1
            val ready = successful && stdout.contains("BUILD_SERVER_READY=1")
            val failure = Regex("BUILD_SERVER_FAILED=([^\\r\n]+)").find(stdout)?.groupValues?.get(1)
            val detail = failure ?: errMessage.ifBlank { stderr.ifBlank { "Check Termux and Ubuntu setup." } }
            val message = if (ready) "Build server READY - Termux + Ubuntu + Java + Android SDK connected." else "Build server NOT READY. " + detail
            getSharedPreferences("build_state", MODE_PRIVATE).edit().putString("server_status", message).apply()
            sendBroadcast(Intent("com.pocketapkstudio.SERVER_STATUS").setPackage(packageName))
            return
        }
        val apkPath = Regex("APK_SAVED=([^\\r\n]+)").findAll(stdout).lastOrNull()?.groupValues?.get(1)?.trim()
        val verifiedApk = apkPath?.let { java.io.File(it) }?.takeIf { it.isFile && it.length() > 0L }
        val successful = exitCode == 0 && errCode == -1 && verifiedApk != null
        val combined = buildString {
            append(stdout)
            if (stderr.isNotBlank()) append("\n\nERROR OUTPUT:\n").append(stderr)
            if (errMessage.isNotBlank()) append("\n\nTERMUX ERROR:\n").append(errMessage)
        }
        val status = when {
            successful -> "BUILD SUCCESS! APK verified: " + verifiedApk!!.name
            exitCode == 0 && errCode == -1 && verifiedApk == null -> "BUILD FAILED: Gradle returned success, but no valid APK file was found at the reported path."
            errCode != -1 -> "Termux could not run the build: $errMessage"
            else -> "BUILD FAILED (exit code $exitCode).\n${combined.takeLast(2200)}"
        }
        getSharedPreferences("build_state", MODE_PRIVATE).edit()
            .putString("last_build_status", status)
            .putString("last_build_log", combined.takeLast(40000))
            .putBoolean("build_running", false)
            .putInt("last_build_percent", if (successful) 100 else 0)
            .putString("last_apk_path", if (successful) verifiedApk!!.absolutePath else null)
            .apply()
        sendBroadcast(Intent("com.pocketapkstudio.BUILD_RESULT").setPackage(packageName))
        Log.i("PocketAPKStudio", "Termux build finished. exit=$exitCode err=$errCode")
    }
}
