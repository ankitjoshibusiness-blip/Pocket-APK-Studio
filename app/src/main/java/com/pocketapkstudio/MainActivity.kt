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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
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
    private lateinit var percentText: TextView
    private lateinit var logs: TextView
    private lateinit var installButton: Button
    private lateinit var shareButton: Button
    private lateinit var serverStatus: TextView
    private lateinit var previewIcon: ImageView
    private lateinit var previewName: TextView
    private lateinit var previewDetails: TextView
    private lateinit var previewPermissions: TextView
    private lateinit var previewInstallButton: Button
    private var previewApkFile: File? = null
    private var selectedUri: Uri? = null
    private var importedZipName: String? = null
    private var projectIsGradle = false
    private var projectIsFlutter = false
    private val buildResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
            if (intent?.action == "com.pocketapkstudio.SERVER_STATUS") {
                if (::serverStatus.isInitialized) serverStatus.text = prefs.getString("server_status", "Build server status unavailable.")
                return
            }
            status.text = prefs.getString("last_build_status", "Build finished. Check result.")
            buildProgress.visibility = View.VISIBLE
            buildProgress.isIndeterminate = false
            buildProgress.progress = prefs.getInt("last_build_percent", 0)
            percentText.text = "${buildProgress.progress}%"
            logs.text = prefs.getString("last_build_log", "No build logs yet.")
            val apk = prefs.getString("last_apk_path", null)?.let(::File)
            val available = apk?.isFile == true
            installButton.visibility = if (available) View.VISIBLE else View.GONE
            shareButton.visibility = if (available) View.VISIBLE else View.GONE
            if (available) loadApkPreview(apk!!)
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.pocketapkstudio.BUILD_RESULT").apply {
            addAction("com.pocketapkstudio.SERVER_STATUS")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(buildResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(buildResultReceiver, filter)
        refreshBuildResult()
    }

    override fun onStop() {
        try { unregisterReceiver(buildResultReceiver) } catch (_: Exception) { }
        super.onStop()
    }

    private fun refreshBuildResult() {
        if (!::status.isInitialized || !::buildProgress.isInitialized ||
            !::percentText.isInitialized || !::logs.isInitialized ||
            !::installButton.isInitialized || !::shareButton.isInitialized) return
        val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
        status.text = prefs.getString("last_build_status", "Waiting for a project.")
        val running = prefs.getBoolean("build_running", false)
        buildProgress.visibility = if (running) View.VISIBLE else View.GONE
        buildProgress.isIndeterminate = running
        val progress = prefs.getInt("last_build_percent", 0)
        if (!running) buildProgress.progress = progress
        percentText.text = if (running) "BUILD RUNNING" else "$progress%"
        logs.text = prefs.getString("last_build_log", "Build output will appear here.")
        val apk = prefs.getString("last_apk_path", null)?.let(::File)
        val available = apk?.isFile == true
        installButton.visibility = if (available) View.VISIBLE else View.GONE
        shareButton.visibility = if (available) View.VISIBLE else View.GONE
    }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            selectedUri = it
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            inspectZip(it)
        }
    }

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadApkPreview(it) }
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
        clearLegacyAiData()
        buildScreen()
    }

    private fun clearLegacyAiData() {
        getSharedPreferences("openai_secure_settings", MODE_PRIVATE).edit().clear().apply()
        try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias("pocket_apk_studio_openai_key")) {
                keyStore.deleteEntry("pocket_apk_studio_openai_key")
            }
        } catch (_: Exception) { }
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            setBackgroundColor(0xFF10131B.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(label("POCKET APK STUDIO", 13, 0xFF9AA7FF.toInt(), true))
        content.addView(label("Build APKs on your phone", 24, 0xFFFFFFFF.toInt(), true).apply {
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

        val serverCard = card()
        serverCard.addView(label("3. LOCAL BUILD SERVER", 12, 0xFF9AA7FF.toInt(), true))
        serverCard.addView(space(8))
        serverStatus = label(getSharedPreferences("build_state", MODE_PRIVATE).getString("server_status", "Not checked yet.") ?: "Not checked yet.", 13, 0xFFB6BBC9.toInt(), false)
        serverCard.addView(serverStatus)
        serverCard.addView(space(8))
        serverCard.addView(actionButton("Check Termux + Ubuntu server") { checkBuildServer() })
        content.addView(serverCard)
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
        percentText = label("0%", 11, 0xFF9AA7FF.toInt(), true)
        buildCard.addView(percentText)
        buildCard.addView(space(8))
        status = label("Waiting for a project.", 13, 0xFFB6BBC9.toInt(), false)
        buildCard.addView(status)
        installButton = actionButton("Install APK") { installBuiltApk() }.apply { visibility = View.GONE }
        shareButton = actionButton("Share APK") { shareBuiltApk() }.apply { visibility = View.GONE }
        buildCard.addView(space(6)); buildCard.addView(installButton)
        buildCard.addView(space(6)); buildCard.addView(shareButton)
        content.addView(buildCard)
        content.addView(space(12))
        val logCard = card()
        logCard.addView(label("BUILD LOGS", 12, 0xFF9AA7FF.toInt(), true))
        logs = label("Build output will appear here.", 11, 0xFFB6BBC9.toInt(), false).apply {
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF0B0E15.toInt()); cornerRadius = dp(10).toFloat() }
        }
        val logScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(180)) }
        logScroll.addView(logs)
        logCard.addView(logScroll)
        logCard.addView(space(6))
        logCard.addView(actionButton("Copy build logs") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Build logs", logs.text))
            Toast.makeText(this, "Logs copied.", Toast.LENGTH_SHORT).show()
        })
        content.addView(logCard)
        content.addView(space(18))

        val setupCard = card()
        setupCard.addView(label("LOCAL BUILD REQUIREMENTS", 12, 0xFF9AA7FF.toInt(), true))
        setupCard.addView(label("Builds run in the background through the local Termux + Ubuntu engine. You do not need to open the Termux terminal. Termux, Termux:RunCommand, Java 17, Gradle and Android SDK must remain installed and configured.", 13, 0xFFB6BBC9.toInt(), false))
        setupCard.addView(space(8))
        setupCard.addView(actionButton("Analyze existing APK") {
            pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        })
        setupCard.addView(space(6))
        setupCard.addView(actionButton("Device & build information") { showDeviceInfo() })
        content.addView(setupCard)
        content.addView(space(18))
        content.addView(label("Version 1.2 • Background local build", 12, 0xFF737B8D.toInt(), false))

        val previewContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        previewContent.addView(label("APK PREVIEW", 13, 0xFF9AA7FF.toInt(), true))
        previewContent.addView(space(8))
        previewContent.addView(label("Check an APK before installing", 22, 0xFFFFFFFF.toInt(), true))
        previewContent.addView(label("View its app icon, package, version, SDK details and requested permissions.", 14, 0xFFB6BBC9.toInt(), false))
        previewContent.addView(space(16))
        previewContent.addView(actionButton("Choose APK to preview", true) {
            pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        })
        previewContent.addView(space(8))
        previewContent.addView(actionButton("Preview latest built APK") {
            val path = getSharedPreferences("build_state", MODE_PRIVATE).getString("last_apk_path", null)
            val file = path?.let(::File)
            if (file != null && file.isFile) loadApkPreview(file) else Toast.makeText(this, "No built APK available yet.", Toast.LENGTH_SHORT).show()
        })
        previewContent.addView(space(16))
        val previewCard = card()
        val previewHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        previewIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(android.R.drawable.sym_def_app_icon)
        }
        previewHeader.addView(previewIcon, LinearLayout.LayoutParams(dp(64), dp(64)))
        previewName = label("No APK selected", 18, 0xFFFFFFFF.toInt(), true)
        previewHeader.addView(previewName, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        previewCard.addView(previewHeader)
        previewCard.addView(space(12))
        previewDetails = label("Select an APK to see its details.", 13, 0xFFB6BBC9.toInt(), false)
        previewCard.addView(previewDetails)
        previewCard.addView(space(12))
        previewCard.addView(label("REQUESTED PERMISSIONS", 11, 0xFF9AA7FF.toInt(), true))
        previewPermissions = label("No APK loaded.", 12, 0xFFB6BBC9.toInt(), false)
        previewPermissions.setTextIsSelectable(true)
        previewCard.addView(previewPermissions)
        previewCard.addView(space(12))
        previewInstallButton = actionButton("Install this APK") { installPreviewApk() }.apply { visibility = View.GONE }
        previewCard.addView(previewInstallButton)
        previewContent.addView(previewCard)

        val previewScroll = ScrollView(this).apply { visibility = View.GONE }
        previewScroll.addView(previewContent)
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val buildTab = actionButton("Build", true) {
            scroll.visibility = View.VISIBLE; previewScroll.visibility = View.GONE
        }
        val previewTab = actionButton("Preview") {
            scroll.visibility = View.GONE; previewScroll.visibility = View.VISIBLE
        }
        listOf(buildTab, previewTab).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            }
        }
        tabBar.addView(buildTab)
        tabBar.addView(previewTab)
        root.addView(tabBar)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(previewScroll, LinearLayout.LayoutParams(-1, 0, 1f))
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

    private fun checkBuildServer() {
        serverStatus.text = "Checking Termux + Ubuntu + Java + Android SDK…"
        val command = "command -v proot-distro >/dev/null 2>&1 && proot-distro login ubuntu -- bash -lc \"command -v java >/dev/null 2>&1 && test -f /root/Android/Sdk/platforms/android-35/android.jar && echo BUILD_SERVER_READY=1\""
        val resultIntent = Intent(this, TermuxResultService::class.java).apply {
            putExtra("task_type", "server_check")
        }
        val requestCode = (SystemClock.uptimeMillis() and 0x7fffffff).toInt()
        val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val callback = PendingIntent.getService(this, requestCode, resultIntent, flags)
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        }
        try { startService(intent) } catch (e: Exception) {
            serverStatus.text = "Could not start Termux: " + (e.message ?: "check Termux permissions")
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
        buildProgress.isIndeterminate = true
        percentText.text = "BUILD RUNNING"
        installButton.visibility = View.GONE
        shareButton.visibility = View.GONE
        status.text = "Preparing project…"
        logs.text = "Preparing project…"
        getSharedPreferences("build_state", MODE_PRIVATE).edit()
            .putBoolean("build_running", true).putInt("last_build_percent", 0)
            .putString("last_build_log", "Preparing project…").apply()
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
            buildProgress.isIndeterminate = true
            percentText.text = "BUILD RUNNING"
            status.text = "Build running in background. Gradle task logs will be shown when the build finishes."
        } catch (e: Exception) {
            buildProgress.visibility = View.GONE
            status.text = "Could not start background build: ${e.message ?: "Termux permission or service error"}"
        }
    }

    private fun installBuiltApk() {
        val path = getSharedPreferences("build_state", MODE_PRIVATE).getString("last_apk_path", null)
        val apk = path?.let(::File)
        if (apk == null || !apk.isFile) { status.text = "APK file not found. Build again."; return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { status.text = "Could not open installer: ${e.message}" }
    }

    private fun shareBuiltApk() {
        val path = getSharedPreferences("build_state", MODE_PRIVATE).getString("last_apk_path", null)
        val apk = path?.let(::File) ?: return
        if (!apk.isFile) return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share APK"))
    }

    @Suppress("DEPRECATION")
    private fun loadApkPreview(uri: Uri) {
        lifecycleScope.launch {
            val temp = File(cacheDir, "apk-preview-${System.currentTimeMillis()}.apk")
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                        ?: error("Cannot read selected APK")
                }
                loadApkPreview(temp)
            } catch (e: Exception) {
                previewName.text = "Preview failed"
                previewDetails.text = e.message ?: "Could not read APK."
                previewPermissions.text = "No permissions available."
                previewInstallButton.visibility = View.GONE
                temp.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadApkPreview(file: File) {
        try {
            val info = packageManager.getPackageArchiveInfo(file.absolutePath, android.content.pm.PackageManager.GET_PERMISSIONS)
                ?: error("Invalid APK or metadata unavailable")
            val appInfo = info.applicationInfo ?: error("App information unavailable")
            appInfo.sourceDir = file.absolutePath
            appInfo.publicSourceDir = file.absolutePath
            previewApkFile = file
            previewName.text = appInfo.loadLabel(packageManager)?.toString() ?: "Unknown app"
            previewIcon.setImageDrawable(appInfo.loadIcon(packageManager))
            previewDetails.text = buildString {
                append("Package: ${info.packageName}\n")
                append("Version: ${info.versionName ?: "Unknown"}\n")
                append("Version code: ${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()}\n")
                append("Min SDK: ${appInfo.minSdkVersion}\n")
                append("Target SDK: ${appInfo.targetSdkVersion}\n")
                append("APK size: ${file.length() / 1024} KB")
            }
            previewPermissions.text = info.requestedPermissions?.joinToString("\n") ?: "None listed"
            previewInstallButton.visibility = View.VISIBLE
        } catch (e: Exception) {
            previewName.text = "Preview failed"
            previewDetails.text = e.message ?: "Could not analyze APK."
            previewPermissions.text = "No permissions available."
            previewInstallButton.visibility = View.GONE
        }
    }

    private fun installPreviewApk() {
        val apk = previewApkFile
        if (apk == null || !apk.isFile) {
            Toast.makeText(this, "Please select an APK again.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open installer: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showDeviceInfo() {
        val details = "Device: ${Build.MANUFACTURER} ${Build.MODEL}\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nCPU ABI: ${Build.SUPPORTED_ABIS.joinToString()}\nBuild engine: Termux + Ubuntu\nOutput: Downloads/PocketAPKStudio/Builds"
        android.app.AlertDialog.Builder(this).setTitle("Device & Build Information")
            .setMessage(details).setPositiveButton("OK", null).show()
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1B202C.toInt())
            cornerRadius = dp(16).toFloat()
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
        layoutParams = LinearLayout.LayoutParams(-1, dp(46))
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
