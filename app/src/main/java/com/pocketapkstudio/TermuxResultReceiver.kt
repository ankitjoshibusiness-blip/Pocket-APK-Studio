package com.pocketapkstudio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors

/**
 * Receives Termux RUN_COMMAND callbacks via PendingIntent.getBroadcast so results are
 * delivered reliably on Android 8–15 even while Pocket APK Studio is backgrounded.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            try {
                TermuxResultHandler.handle(appContext, intent)
            } catch (error: Exception) {
                Log.e("PocketAPKStudio", "Failed handling Termux broadcast callback", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
