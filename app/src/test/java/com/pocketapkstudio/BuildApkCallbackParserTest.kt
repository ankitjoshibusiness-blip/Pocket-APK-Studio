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

    @Test
    fun rejectsSavedPathFromPreviousBuildWhenReservedNameDiffers() {
        val previousBuild = "APK_SAVED=/storage/emulated/0/Download/OldBuild.apk\n"
        assertNull(BuildApkCallbackParser.findSavedApkPath(previousBuild, "CurrentBuild.apk"))
    }

    @Test
    fun prefersCurrentCallbackOverLongerLiveLogContainingOnlyPreviousBuild() {
        val callback = "APK_SAVED=/storage/emulated/0/Download/CurrentBuild.apk\n"
        val staleLiveLog = "Older Gradle output and details\n".repeat(8) +
            "APK_SAVED=/storage/emulated/0/Download/OldBuild.apk\n"
        assertEquals(
            callback,
            BuildApkCallbackParser.preferredTranscript(callback, staleLiveLog, "CurrentBuild.apk")
        )
    }

    @Test
    fun prefersCurrentLiveLogOverCallbackContainingOnlyPreviousBuild() {
        val staleCallback = "APK_SAVED=/storage/emulated/0/Download/OldBuild.apk\n"
        val liveLog = "Gradle output\nAPK_SAVED=/storage/emulated/0/Download/CurrentBuild.apk\n"
        assertEquals(
            liveLog,
            BuildApkCallbackParser.preferredTranscript(staleCallback, liveLog, "CurrentBuild.apk")
        )
    }

    @Test
    fun acceptsOnlyTheReservedOutputNameWhenSeveralMarkersExist() {
        val transcript = """
            APK_SAVED=/storage/emulated/0/Download/OldBuild.apk
            APK_SAVED=/storage/emulated/0/Download/CurrentBuild.apk
        """.trimIndent()
        assertEquals(
            "/storage/emulated/0/Download/CurrentBuild.apk",
            BuildApkCallbackParser.findSavedApkPath(transcript, "CurrentBuild.apk")
        )
    }
}
