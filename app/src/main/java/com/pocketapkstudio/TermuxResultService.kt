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
            val outputPath = Regex("RECOVERY_SAVED=([^\\r\\n]+)").findAll(stdout).lastOrNull()?.groupValues?.get(1)?.trim()
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
                .putString("zip_path", if (successful) outputFile!!.absolutePath else null)
                .putString("log", combined.takeLast(30000))
                .apply()
            sendBroadcast(Intent("com.pocketapkstudio.RECOVERY_RESULT").setPackage(packageName))
            Log.i("PocketAPKStudio", "JADX decompilation finished. exit=$exitCode err=$errCode")
            return
        }
        if (intent.getStringExtra("task_type") == "server_check") {
            val successful = exitCode == 0 && errCode == -1
            val ready = successful && stdout.contains("BUILD_SERVER_READY=1")
            val failure = Regex("BUILD_SERVER_FAILED=([^\\r\\n]+)").find(stdout)?.groupValues?.get(1)
            val detail = failure ?: errMessage.ifBlank { stderr.ifBlank { "Check Termux and Ubuntu setup." } }
            val message = if (ready) "Build server READY - Termux + Ubuntu + Java + Android SDK connected." else "Build server NOT READY. " + detail
            getSharedPreferences("build_state", MODE_PRIVATE).edit().putString("server_status", message).apply()
            sendBroadcast(Intent("com.pocketapkstudio.SERVER_STATUS").setPackage(packageName))
            return
        }
        val apkPath = Regex("APK_SAVED=([^\\r\\n]+)").findAll(stdout).lastOrNull()?.groupValues?.get(1)?.trim()
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
