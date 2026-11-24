package com.pocketapkstudio

/**
 * Parses the saved APK path from a Termux build transcript.
 *
 * The RUN_COMMAND result bundle is not guaranteed to contain complete stdout.
 * The app therefore persists a separate live transcript in Downloads and uses
 * this parser to choose whichever source contains the APK_SAVED marker.
 */
internal object BuildApkCallbackParser {
    private val savedApkPattern = Regex("""APK_SAVED=([^\r\n]+)""")

    fun preferredTranscript(callbackStdout: String, liveLog: String): String {
        val callbackHasApkPath = callbackStdout.contains("APK_SAVED=")
        val liveLogHasApkPath = liveLog.contains("APK_SAVED=")
        return when {
            liveLogHasApkPath && !callbackHasApkPath -> liveLog
            callbackHasApkPath && !liveLogHasApkPath -> callbackStdout
            liveLog.length > callbackStdout.length -> liveLog
            else -> callbackStdout
        }
    }

    fun findSavedApkPath(transcript: String): String? =
        savedApkPattern.findAll(transcript)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
}
