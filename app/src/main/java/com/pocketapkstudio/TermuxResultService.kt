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
        val successful = exitCode == 0 && errCode == -1
        val apkPath = Regex("APK_SAVED=([^\\r\\n]+)").findAll(stdout).lastOrNull()?.groupValues?.get(1)?.trim()
        val taskCount = Regex("(?m)^> Task ").findAll(stdout).count()
        val combined = buildString {
            append(stdout)
            if (stderr.isNotBlank()) append("\n\nERROR OUTPUT:\n").append(stderr)
            if (errMessage.isNotBlank()) append("\n\nTERMUX ERROR:\n").append(errMessage)
        }
        val status = when {
            successful -> "BUILD SUCCESS! APK saved in Downloads/PocketAPKStudio/Builds."
            errCode != -1 -> "Termux could not run the build: $errMessage"
            else -> "BUILD FAILED (exit code $exitCode).\n${combined.takeLast(2200)}"
        }
        getSharedPreferences("build_state", MODE_PRIVATE).edit()
            .putString("last_build_status", status)
            .putString("last_build_log", combined.takeLast(40000))
            .putBoolean("build_running", false)
            .putInt("last_build_percent", if (successful) 100 else 0)
            .putString("last_apk_path", if (successful) apkPath else null)
            .apply()
        sendBroadcast(Intent("com.pocketapkstudio.BUILD_RESULT").setPackage(packageName))
        Log.i("PocketAPKStudio", "Termux build finished. exit=$exitCode err=$errCode")
    }
}
