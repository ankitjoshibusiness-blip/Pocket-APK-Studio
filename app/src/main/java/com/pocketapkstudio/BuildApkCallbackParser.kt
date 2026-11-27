package com.pocketapkstudio

/**
 * Parses the saved APK path from a Termux build transcript.
 *
 * RUN_COMMAND stdout may be incomplete. A persisted live transcript is used as
 * a fallback, but its APK marker must match the output filename reserved for
 * the current build so an old transcript cannot report a previous APK as new.
 */
internal object BuildApkCallbackParser {
    private val savedApkPattern = Regex("""APK_SAVED=([^\\r\\n]+)""")

    fun preferredTranscript(
        callbackStdout: String,
        liveLog: String,
        expectedFileName: String? = null
    ): String {
        val callbackHasCurrentApk = findSavedApkPath(callbackStdout, expectedFileName) != null
        val liveLogHasCurrentApk = findSavedApkPath(liveLog, expectedFileName) != null
        return when {
            liveLogHasCurrentApk && !callbackHasCurrentApk -> liveLog
            callbackHasCurrentApk && !liveLogHasCurrentApk -> callbackStdout
            liveLog.length > callbackStdout.length -> liveLog
            else -> callbackStdout
        }
    }

    fun findSavedApkPath(transcript: String, expectedFileName: String? = null): String? {
        val path = savedApkPattern.findAll(transcript)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val expected = expectedFileName?.takeIf { it.isNotBlank() }
        if (expected != null && path.substringAfterLast('/') != expected) return null
        return path
    }
}
