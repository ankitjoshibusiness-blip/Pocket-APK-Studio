package com.pocketapkstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuildApkCallbackParserTest {

    @Test
    fun usesLiveLogWhenCallbackStdoutIsEmpty() {
        val liveLog = "Gradle finished\nAPK_SAVED=/storage/emulated/0/Download/My Build.apk\n"
        assertEquals(liveLog, BuildApkCallbackParser.preferredTranscript("", liveLog))
    }

    @Test
    fun usesLiveLogWhenOnlyItContainsTheSavedPathMarker() {
        val callbackStdout = "Termux returned partial status without the final output marker."
        val liveLog = "APK_SAVED=/storage/emulated/0/Download/Recovered Build.apk\n"
        assertEquals(liveLog, BuildApkCallbackParser.preferredTranscript(callbackStdout, liveLog))
    }

    @Test
    fun keepsCallbackWhenOnlyCallbackContainsTheSavedPathMarker() {
        val callbackStdout = "APK_SAVED=/storage/emulated/0/Download/Callback Build.apk\n"
        val longerLiveLog = "A longer live transcript without the final saved APK path marker."
        assertEquals(callbackStdout, BuildApkCallbackParser.preferredTranscript(callbackStdout, longerLiveLog))
    }

    @Test
    fun choosesLongerTranscriptWhenBothContainSavedPathMarker() {
        val callbackStdout = "APK_SAVED=/tmp/a.apk\n"
        val liveLog = "Gradle task output and copy verification\nAPK_SAVED=/storage/emulated/0/Download/Full Build.apk\n"
        assertEquals(liveLog, BuildApkCallbackParser.preferredTranscript(callbackStdout, liveLog))
    }

    @Test
    fun extractsLastSavedApkPathAndPreservesSpaces() {
        val transcript = """
            APK_SAVED=/tmp/older.apk
            BUILD_SUCCESS=1
            APK_SAVED=/storage/emulated/0/Download/Pocket APK Studio.apk   
        """.trimIndent()
        assertEquals(
            "/storage/emulated/0/Download/Pocket APK Studio.apk",
            BuildApkCallbackParser.findSavedApkPath(transcript)
        )
    }

    @Test
    fun returnsNullWhenSavedPathIsMissingOrBlank() {
        assertNull(BuildApkCallbackParser.findSavedApkPath("BUILD_SUCCESS=1"))
        assertNull(BuildApkCallbackParser.findSavedApkPath("APK_SAVED=   \n"))
    }
}
