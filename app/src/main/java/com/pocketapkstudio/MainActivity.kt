package com.pocketapkstudio

import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.PendingIntent
import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

class MainActivity : androidx.activity.ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var projectName: TextView
    private lateinit var projectType: TextView
    private lateinit var buildProgress: ProgressBar
    private var selectedUri: Uri? = null
    private var importedZipName: String? = null
    private var projectIsGradle = false
    private var projectIsFlutter = false
    private val buildResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
            status.text = prefs.getString("last_build_status", "Build finished. Check result.")
            buildProgress.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.pocketapkstudio.BUILD_RESULT")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(buildResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(buildResultReceiver, filter)
        val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
        val saved = prefs.getString("last_build_status", null)
        if (saved != null && ::status.isInitialized) {
            status.text = saved
            buildProgress.visibility = if (saved.startsWith("Build running")) View.VISIBLE else View.GONE
        }
    }

    override fun onStop() {
        try { unregisterReceiver(buildResultReceiver) } catch (_: Exception) { }
        super.onStop()
    }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            selectedUri = it
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            inspectZip(it)
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            selectedUri = it
            importedZipName = null
            projectName.text = it.lastPathSegment ?: "Selected project folder"
            projectType.text = "Folder selected. ZIP import is currently required for local builds."
            status.text = "Folder access saved. For this version, export the project as a ZIP to build it."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            setBackgroundColor(0xFF10131B.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(label("POCKET APK STUDIO", 13, 0xFF9AA7FF.toInt(), true))
        content.addView(label("Build APKs on your phone", 27, 0xFFFFFFFF.toInt(), true).apply {
            setPadding(0, dp(10), 0, dp(6))
        })
        content.addView(label("Import Android projects and prepare local builds without GitHub Actions.", 14, 0xFFB6BBC9.toInt(), false))
        content.addView(space(22))

        val importCard = card()
        importCard.addView(label("1. IMPORT PROJECT", 12, 0xFF9AA7FF.toInt(), true))
        importCard.addView(space(10))
        importCard.addView(actionButton("Choose ZIP project") { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) })
        importCard.addView(space(8))
        importCard.addView(actionButton("Choose project folder") { pickFolder.launch(null) })
        content.addView(importCard)
        content.addView(space(14))

        val projectCard = card()
        projectCard.addView(label("2. PROJECT DETAILS", 12, 0xFF9AA7FF.toInt(), true))
        projectName = label("No project selected", 18, 0xFFFFFFFF.toInt(), true)
        projectCard.addView(projectName)
        projectType = label("Targets: Android Java/Kotlin first; Flutter later", 14, 0xFFB6BBC9.toInt(), false)
        projectCard.addView(projectType)
        content.addView(projectCard)
        content.addView(space(14))

        val buildCard = card()
        buildCard.addView(label("3. BUILD", 12, 0xFF9AA7FF.toInt(), true))
        buildCard.addView(space(8))
        buildCard.addView(actionButton("Build Android APK", true) { startLocalBuild() })
        buildCard.addView(space(8))
        buildProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF7184FF.toInt())
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF7184FF.toInt())
        }
        buildCard.addView(buildProgress, LinearLayout.LayoutParams(-1, dp(6)))
        buildCard.addView(space(8))
        status = label("Waiting for a project.", 13, 0xFFB6BBC9.toInt(), false)
        buildCard.addView(status)
        content.addView(buildCard)
        content.addView(space(18))

        val setupCard = card()
        setupCard.addView(label("LOCAL BUILD REQUIREMENTS", 12, 0xFF9AA7FF.toInt(), true))
        setupCard.addView(label("Builds run in the background through the local Termux + Ubuntu engine. You do not need to open the Termux terminal. Termux, Termux:RunCommand, Java 17, Gradle and Android SDK must remain installed and configured.", 13, 0xFFB6BBC9.toInt(), false))
        content.addView(setupCard)
        content.addView(space(18))
        content.addView(label("Version 1.2 • Background local build", 12, 0xFF737B8D.toInt(), false))

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun inspectZip(uri: Uri) {
        status.text = "Scanning project ZIP…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    var hasGradle = false
                    var hasSettings = false
                    var hasFlutter = false
                    var hasPubspec = false
                    var firstName = "Imported ZIP project"
                    contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(input).use { zip ->
                            var count = 0
                            var entry = zip.nextEntry
                            while (entry != null && count < 30000) {
                                val name = entry.name.replace('\\', '/')
                                if (count == 0) firstName = name.substringBefore('/').ifBlank { firstName }
                                if (name.endsWith("build.gradle") || name.endsWith("build.gradle.kts")) hasGradle = true
                                if (name.endsWith("settings.gradle") || name.endsWith("settings.gradle.kts")) hasSettings = true
                                if (name.endsWith("pubspec.yaml")) hasPubspec = true
                                if (name.contains(".dart_tool/")) hasFlutter = true
                                zip.closeEntry()
                                entry = zip.nextEntry
                                count++
                            }
                        }
                    } ?: error("Could not open ZIP file")
                    val flutter = hasPubspec || hasFlutter
                    val type = when {
                        flutter -> "Flutter project detected (build support coming later)"
                        hasGradle || hasSettings -> "Android Gradle project detected (Java/Kotlin)"
                        else -> "Project type not recognized — ZIP must contain Gradle project files"
                    }
                    Triple(Triple(firstName, hasGradle || hasSettings, flutter), type, "ZIP scanned successfully.")
                } catch (e: Exception) {
                    Triple(Triple("Imported project", false, false), "Could not inspect ZIP", e.message ?: "Unknown error")
                }
            }
            projectName.text = result.first.first
            projectIsGradle = result.first.second
            projectIsFlutter = result.first.third
            projectType.text = result.second
            status.text = result.third
        }
    }

    private fun startLocalBuild() {
        val source = selectedUri
        if (source == null) {
            Toast.makeText(this, "Please import a project ZIP first.", Toast.LENGTH_SHORT).show()
            return
        }
        if (projectIsFlutter) {
            status.text = "Flutter project detected. Flutter SDK build integration is planned for a later version."
            return
        }
        if (!projectIsGradle) {
            status.text = "No Gradle settings/build file found. Select a valid Android Studio project ZIP."
            return
        }

        buildProgress.visibility = View.VISIBLE
        status.text = "Preparing project…"
        lifecycleScope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) { copyZipToDownloads(source) }
                importedZipName = fileName
                launchTermuxBuild(fileName)
            } catch (e: Exception) {
                buildProgress.visibility = View.GONE
                status.text = "Could not prepare ZIP: ${e.message ?: "storage error"}"
            }
        }
    }

    private fun copyZipToDownloads(uri: Uri): String {
        val original = queryDisplayName(uri) ?: "android-project.zip"
        val safeName = original.replace(Regex("[^A-Za-z0-9._-]"), "_").let {
            if (it.endsWith(".zip", true)) it else "$it.zip"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PocketAPKStudio")
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val outputUri = contentResolver.insert(collection, values) ?: error("Could not create ZIP in Downloads")
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read selected ZIP" }
                contentResolver.openOutputStream(outputUri).use { output ->
                    requireNotNull(output) { "Cannot write ZIP to Downloads" }
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            contentResolver.delete(outputUri, null, null)
            throw e
        }
        return safeName
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun launchTermuxBuild(zipName: String) {
        val sharedZip = "/sdcard/Download/PocketAPKStudio/$zipName"
        val command = """
            set -e
            echo "Pocket APK Studio: background Android build started"
            command -v proot-distro >/dev/null || { echo 'ERROR: proot-distro is missing in Termux'; exit 10; }
            proot-distro login ubuntu -- bash -lc '
              set -e
              command -v java >/dev/null || { echo "ERROR: Java is missing in Ubuntu"; exit 11; }
              test -n "${'$'}ANDROID_HOME" || { echo "ERROR: ANDROID_HOME is not configured in Ubuntu"; exit 13; }
              test -f "${'$'}ANDROID_HOME/platforms/android-35/android.jar" || { echo "ERROR: Android 35 SDK platform is missing"; exit 15; }
              PROJECT="${'$'}HOME/pocket-apk-studio/project"
              mkdir -p "${'$'}PROJECT"
              rm -rf "${'$'}PROJECT"/*
              unzip -q -o "$sharedZip" -d "${'$'}PROJECT"
              SETTINGS=${'$'}(find "${'$'}PROJECT" -type f \( -name settings.gradle -o -name settings.gradle.kts \) -print -quit)
              test -n "${'$'}SETTINGS" || { echo "ERROR: settings.gradle(.kts) not found in ZIP"; exit 14; }
              cd "${'$'}(dirname "${'$'}SETTINGS")"
              if [ -f ./gradlew ]; then chmod +x ./gradlew; ./gradlew --no-daemon --max-workers=2 assembleDebug; else command -v gradle >/dev/null || { echo "ERROR: Gradle is missing in Ubuntu"; exit 12; }; gradle --no-daemon --max-workers=2 assembleDebug; fi
              mkdir -p /sdcard/Download/PocketAPKStudio/Builds
              APK_COUNT=0
              while IFS= read -r APK; do
                [ -n "${'$'}APK" ] || continue
                cp "${'$'}APK" /sdcard/Download/PocketAPKStudio/Builds/
                echo "APK_SAVED=/sdcard/Download/PocketAPKStudio/Builds/${'$'}(basename "${'$'}APK")"
                APK_COUNT=${'$'}((APK_COUNT + 1))
              done < <(find . -path "*/build/outputs/apk/debug/*.apk" -type f)
              test "${'$'}APK_COUNT" -gt 0 || { echo "ERROR: Build completed but no debug APK was found"; exit 16; }
              echo "BUILD SUCCESS: ${'$'}APK_COUNT APK file(s) copied to Downloads/PocketAPKStudio/Builds"
            '
        """.trimIndent()

        val resultIntent = Intent(this, TermuxResultService::class.java)
        val requestCode = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val pendingFlags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val resultPendingIntent = PendingIntent.getService(this, requestCode, resultIntent, pendingFlags)

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPendingIntent)
        }
        try {
            getSharedPreferences("build_state", MODE_PRIVATE).edit()
                .putString("last_build_status", "Build running in background… You can stay in Pocket APK Studio.")
                .apply()
            startService(intent)
            buildProgress.visibility = View.VISIBLE
            status.text = "Build running… Please keep Pocket APK Studio available. The progress bar is indeterminate while Gradle works in the background."
        } catch (e: Exception) {
            buildProgress.visibility = View.GONE
            status.text = "Could not start background build: ${e.message ?: "Termux permission or service error"}"
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1B202C.toInt())
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun actionButton(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFFE4E7F2.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (primary) 0xFF596BEB.toInt() else 0xFF30394D.toInt())
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52))
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
