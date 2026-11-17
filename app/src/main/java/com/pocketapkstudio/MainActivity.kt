package com.pocketapkstudio

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
    private var selectedUri: Uri? = null
    private var importedZipName: String? = null
    private var projectIsGradle = false
    private var projectIsFlutter = false

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
            projectType.text = "Folder selected. ZIP import is currently required for the Termux build bridge."
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
        status = label("Waiting for a project.", 13, 0xFFB6BBC9.toInt(), false)
        buildCard.addView(status)
        content.addView(buildCard)
        content.addView(space(18))

        val setupCard = card()
        setupCard.addView(label("LOCAL BUILD REQUIREMENTS", 12, 0xFF9AA7FF.toInt(), true))
        setupCard.addView(label("This build bridge uses Termux + Ubuntu on your phone. Install the Termux:RunCommand add-on, enable external commands in Termux, and install Java 17, Gradle and Android SDK Build Tools inside Ubuntu.", 13, 0xFFB6BBC9.toInt(), false))
        content.addView(setupCard)
        content.addView(space(18))
        content.addView(label("Version 1.1 • Local build bridge groundwork", 12, 0xFF737B8D.toInt(), false))

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
                                val name = entry.name.replace('\\\\', '/')
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

        status.text = "Copying project ZIP to shared Downloads…"
        lifecycleScope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) { copyZipToDownloads(source) }
                importedZipName = fileName
                launchTermuxBuild(fileName)
            } catch (e: Exception) {
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
            echo 'Pocket APK Studio: starting local Android build'
            command -v proot-distro >/dev/null || { echo 'ERROR: proot-distro is missing in Termux'; exit 10; }
            proot-distro login ubuntu -- bash -lc '
              set -e
              command -v java >/dev/null || { echo "ERROR: Java is missing in Ubuntu"; exit 11; }
              command -v gradle >/dev/null || { echo "ERROR: Gradle is missing in Ubuntu"; exit 12; }
              test -n "$ANDROID_HOME" || { echo "ERROR: ANDROID_HOME is not configured in Ubuntu"; exit 13; }
              PROJECT="$HOME/pocket-apk-studio/project"
              mkdir -p "$PROJECT"
              rm -rf "$PROJECT"/*
              unzip -q -o "$sharedZip" -d "$PROJECT"
              SETTINGS=$(find "$PROJECT" -type f \( -name settings.gradle -o -name settings.gradle.kts \) -print -quit)
              test -n "$SETTINGS" || { echo "ERROR: settings.gradle(.kts) not found in ZIP"; exit 14; }
              cd "$(dirname "$SETTINGS")"
              gradle assembleDebug
              echo "BUILD SUCCESS. APK files:"
              find . -path "*/build/outputs/apk/*" -type f -name "*.apk" -print
            '
        """.trimIndent()
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
        }
        try {
            startService(intent)
            status.text = "Build command sent to Termux. Open Termux to watch output. APK is not confirmed until Gradle reports BUILD SUCCESS."
        } catch (e: Exception) {
            status.text = "Termux build bridge unavailable. Install Termux and Termux:RunCommand, then enable allow-external-apps in Termux."
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
