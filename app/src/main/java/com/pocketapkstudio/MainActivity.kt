package com.pocketapkstudio

import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.PendingIntent
import android.app.AlertDialog
import android.os.SystemClock
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
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
    private lateinit var analysisCard: LinearLayout
    private lateinit var analysisStatus: TextView
    private lateinit var analysisDetails: TextView
    private lateinit var analysisComponents: TextView
    private lateinit var analysisPermissions: TextView
    private lateinit var recoveryStatus: TextView
    private lateinit var recoveryProgress: ProgressBar
    private lateinit var setupScreen: ScrollView
    private lateinit var setupStatus: TextView
    private lateinit var setupResults: TextView
    private lateinit var setupProgress: ProgressBar
    private lateinit var setupNextButton: Button
    private lateinit var exportRecoveryButton: Button
    private var recoveryFilePath: String? = null
    private var analysisReportText: String = ""
    private var previewApkFile: File? = null
    private var selectedUri: Uri? = null
    private var importedZipName: String? = null
    private var projectIsGradle = false
    private var projectIsFlutter = false
    private val buildResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
            if (intent?.action == "com.pocketapkstudio.RECOVERY_RESULT") {
                refreshRecoveryResult()
                return
            }
            if (intent?.action == "com.pocketapkstudio.SERVER_STATUS") {
                val message = prefs.getString("server_status", "Build server status unavailable.") ?: "Build server status unavailable."
                if (::serverStatus.isInitialized) serverStatus.text = message
                if (::setupScreen.isInitialized && setupScreen.visibility == View.VISIBLE) {
                    setupProgress.visibility = View.GONE
                    setupStatus.text = message
                    setupResults.text = prefs.getString("environment_setup_result", message) ?: message
                    setupNextButton.isEnabled = prefs.getBoolean("environment_setup_complete", false)
                    setupNextButton.alpha = if (setupNextButton.isEnabled) 1f else 0.45f
                }
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
            addAction("com.pocketapkstudio.RECOVERY_RESULT")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(buildResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(buildResultReceiver, filter)
        refreshBuildResult()
        refreshRecoveryResult()
        refreshEnvironmentSetupResult()
    }

    private fun refreshEnvironmentSetupResult() {
        if (!::setupScreen.isInitialized || setupScreen.visibility != View.VISIBLE) return
        val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
        val result = prefs.getString("environment_setup_result", null) ?: return
        val ready = prefs.getBoolean("environment_setup_complete", false)
        setupProgress.visibility = View.GONE
        setupResults.text = result
        setupStatus.text = prefs.getString("server_status", if (ready) "Environment setup complete." else "Setup needs attention.")
        setupNextButton.isEnabled = ready
        setupNextButton.alpha = if (ready) 1f else 0.45f
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
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            openProjectEditor(it)
        }
    }

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadApkPreview(it) }
    }

    private val pickAnalyzeApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { analyzeExistingApk(it) }
    }

    private val pickDecompileApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { startApkDecompilation(it) }
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

    private val editProjectResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            selectedUri = result.data!!.data
            projectIsGradle = true
            projectIsFlutter = false
            projectName.text = "Edited project ZIP"
            projectType.text = "Changes saved. Build will use the edited project."
            status.text = "Edited project ready to build."
        }
    }

    private val termuxPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startAutomaticEnvironmentSetup()
        else {
            setupStatus.text = "Termux command permission was denied."
            setupResults.text = "Allow the Termux RUN_COMMAND permission in Android settings, then tap Scan & Setup again."
            setupProgress.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clearLegacyAiData()
        buildScreen()
        val done = getSharedPreferences("build_state", MODE_PRIVATE).getBoolean("environment_setup_complete", false)
        if (done) {
            setupScreen.visibility = View.GONE
            startAutomaticEnvironmentCheck()
        } else {
            setupScreen.visibility = View.VISIBLE
            updateTermuxInstallStatus()
            if (packageManager.getLaunchIntentForPackage("com.termux") != null) beginEnvironmentSetup()
        }
    }

    private fun showEnvironmentSetupWizard() {
        if (::setupScreen.isInitialized) {
            setupScreen.visibility = View.VISIBLE
            updateTermuxInstallStatus()
        }
    }

    private fun updateTermuxInstallStatus() {
        val installed = packageManager.getLaunchIntentForPackage("com.termux") != null
        setupStatus.text = if (installed) "Termux detected. Ready to scan the build environment."
            else "Termux is not installed. Install it first, then return here."
        setupResults.text = if (installed) {
            "Termux: INSTALLED\nUbuntu: Waiting for scan\nJava 17: Waiting for scan\nAndroid SDK: Waiting for scan\nPlatform Tools: Waiting for scan\nBuild Tools: Waiting for scan"
        } else {
            "Termux: NOT INSTALLED\nUbuntu: Not checked\nJava 17: Not checked\nAndroid SDK: Not checked\nPlatform Tools: Not checked\nBuild Tools: Not checked"
        }
    }

    private fun beginEnvironmentSetup() {
        if (packageManager.getLaunchIntentForPackage("com.termux") == null) {
            updateTermuxInstallStatus()
            return
        }
        val permission = "com.termux.permission.RUN_COMMAND"
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            setupStatus.text = "Waiting for Termux command permission…"
            termuxPermissionLauncher.launch(permission)
        } else startAutomaticEnvironmentSetup()
    }

    private fun openTermuxDownloadPage() {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))) }
        catch (_: Exception) { Toast.makeText(this, "Could not open Termux download page.", Toast.LENGTH_LONG).show() }
    }

    private fun startAutomaticEnvironmentSetup() {
        if (packageManager.getLaunchIntentForPackage("com.termux") == null) {
            updateTermuxInstallStatus()
            return
        }
        setupStatus.text = "Scanning and preparing Termux, Ubuntu and Android build tools…"
        setupResults.text = "Termux: INSTALLED\nUbuntu: Scanning…\nJava 17: Scanning…\nAndroid SDK: Scanning…\nPlatform Tools: Scanning…\nBuild Tools: Scanning…"
        setupProgress.visibility = View.VISIBLE
        setupNextButton.isEnabled = false
        setupNextButton.alpha = 0.45f
        getSharedPreferences("build_state", MODE_PRIVATE).edit()
            .putBoolean("environment_setup_complete", false)
            .remove("environment_setup_result")
            .apply()
        serverStatus.text = "Checking and preparing Termux, Ubuntu, Java 17 and Android SDK…"
        val command = """
            set -e
            echo "SCAN_TERMUX=INSTALLED"
            export DEBIAN_FRONTEND=noninteractive
            ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
            if [ ! -d "$ROOTFS" ]; then
                pkg update -y
                pkg install -y proot-distro
                if [ ! -d "$ROOTFS" ]; then
                    proot-distro install ubuntu
                fi
            fi
            [ -d "$ROOTFS" ]
            echo "SCAN_UBUNTU=INSTALLED"
            proot-distro login ubuntu -- bash -lc '
                set -e
                export DEBIAN_FRONTEND=noninteractive
                apt-get update
                if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q "17"; then
                    apt-get install -y openjdk-17-jdk
                fi
                java -version 2>&1 | grep -q "17"
                echo "SCAN_JAVA17=INSTALLED"
                apt-get install -y git curl wget unzip zip
                mkdir -p /root/Android/cmdline-tools
                if [ ! -x /root/Android/cmdline-tools/latest/bin/sdkmanager ]; then
                    cd /tmp
                    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
                    rm -rf /tmp/cmdline-tools
                    mkdir -p /tmp/cmdline-tools
                    unzip -q cmdline-tools.zip -d /tmp/cmdline-tools
                    rm -rf /root/Android/cmdline-tools/latest
                    mv /tmp/cmdline-tools/cmdline-tools /root/Android/cmdline-tools/latest
                fi
                echo "SCAN_ANDROID_SDK=INSTALLED"
                yes | /root/Android/cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/Android/Sdk --licenses >/dev/null || true
                /root/Android/cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/Android/Sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"
                test -f /root/Android/Sdk/platforms/android-35/android.jar
                echo "SCAN_ANDROID_SDK=INSTALLED"
                test -x /root/Android/Sdk/platform-tools/adb
                echo "SCAN_PLATFORM_TOOLS=INSTALLED"
                test -x /root/Android/Sdk/build-tools/35.0.0/aapt
                echo "SCAN_BUILD_TOOLS=INSTALLED"
                echo BUILD_SERVER_READY=1
            '
        """.trimIndent()
        val resultIntent = Intent(this, TermuxResultService::class.java).apply {
            putExtra("task_type", "environment_setup")
        }
        val requestCode = (SystemClock.uptimeMillis() and 0x7fffffff).toInt()
        val flags = PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val callback = PendingIntent.getService(this, requestCode, resultIntent, flags)
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            serverStatus.text = "Could not start Termux setup: " +
                (e.message ?: "check Termux permissions and allow-external-apps setting")
        }
    }

    private fun startAutomaticEnvironmentCheck() {
        if (packageManager.getLaunchIntentForPackage("com.termux") == null) {
            serverStatus.text = "Termux is not installed. Reinstall it to connect the build server."
            return
        }
        val command = "proot-distro login ubuntu -- bash -lc 'if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q 17 && test -x /root/Android/cmdline-tools/latest/bin/sdkmanager && test -f /root/Android/Sdk/platforms/android-35/android.jar && test -x /root/Android/Sdk/platform-tools/adb && test -x /root/Android/Sdk/build-tools/35.0.0/aapt; then echo BUILD_SERVER_READY=1; else echo BUILD_SERVER_FAILED=Ubuntu or Android SDK components are missing; exit 1; fi'"
        val callbackIntent = Intent(this, TermuxResultService::class.java).apply { putExtra("task_type", "server_check") }
        val requestCode = (SystemClock.uptimeMillis() and 0x7fffffff).toInt()
        val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val callback = PendingIntent.getService(this, requestCode, callbackIntent, flags)
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
        }
        try { serverStatus.text = "Connecting to Termux + Ubuntu…"; startService(intent) }
        catch (e: Exception) { serverStatus.text = "Automatic connection failed: " + (e.message ?: "Check Termux settings.") }
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
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFF202A33.toInt())
        }
        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        val appLogo = TextView(this).apply {
            text = "🚀"
            textSize = 23f
            gravity = android.view.Gravity.CENTER
            background = premiumGradient(0xFF568B91.toInt(), 0xFF456F7A.toInt(), 18)
        }
        appBar.addView(appLogo, LinearLayout.LayoutParams(dp(42), dp(42)))
        val appTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        appTitle.addView(label("Pocket APK Studio", 17, 0xFFFFFFFF.toInt(), true))
        appTitle.addView(label("BUILD  •  ANALYZE  •  RECOVER", 9, 0xFF9AA7D5.toInt(), true))
        appBar.addView(appTitle, LinearLayout.LayoutParams(0, -2, 1f))
        val proBadge = label("✦ PRO", 11, 0xFFFFD36A.toInt(), true).apply {
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = premiumGradient(0xFF806B3F.toInt(), 0xFF9A8050.toInt(), 30)
        }
        appBar.addView(proBadge)
        root.addView(appBar)
        fun page(): Pair<ScrollView, LinearLayout> {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(4), dp(2), dp(18)) }
            return ScrollView(this).apply { addView(body) } to body
        }
        val (homeScroll, homeContent) = page()
        val (editorScroll, editorContent) = page()
        val (toolsScroll, toolsContent) = page()
        val (settingsScroll, settingsContent) = page()
        editorContent.addView(label("Import and edit your Android project files", 12, 0xFF9EADD0.toInt(), false))
        editorContent.addView(space(12))
        settingsContent.addView(label("Build environment and device information", 12, 0xFF9EADD0.toInt(), false))
        settingsContent.addView(space(12))

        val welcomeHeader = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(18), dp(12), dp(20))
        }
        val welcomeLogo = TextView(this).apply {
            text = "⌘"
            textSize = 34f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF456F70.toInt())
            background = premiumGradient(0xFFDCE9E8.toInt(), 0xFFE8EFED.toInt(), 22)
        }
        welcomeHeader.addView(welcomeLogo, LinearLayout.LayoutParams(dp(76), dp(76)))
        welcomeHeader.addView(space(12))
        welcomeHeader.addView(label("Get started", 23, 0xFFE2E8ED.toInt(), true).apply { gravity = android.view.Gravity.CENTER })
        welcomeHeader.addView(label("Start your next awesome project!", 13, 0xFFB4C0C8.toInt(), false).apply { gravity = android.view.Gravity.CENTER })
        homeContent.addView(welcomeHeader)

        var openSettingsPage: (() -> Unit)? = null
        var openPage: ((Int) -> Unit)? = null
        fun welcomeMenuItem(symbol: String, title: String, action: () -> Unit): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(14), dp(8))
                background = premiumGradient(0xFF2B3742.toInt(), 0xFF2B3742.toInt(), 14)
                isClickable = true
                isFocusable = true
                val icon = label(symbol, 21, 0xFF91B5B5.toInt(), true).apply {
                    gravity = android.view.Gravity.CENTER
                }
                addView(icon, LinearLayout.LayoutParams(dp(38), -1))
                addView(label(title, 15, 0xFFE2E8ED.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
                addView(label("›", 24, 0xFF91A5B0.toInt(), false))
                layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply {
                    bottomMargin = dp(9)
                    leftMargin = dp(8)
                    rightMargin = dp(8)
                }
                setOnClickListener { animate().alpha(0.88f).setDuration(70L).withEndAction {
                    animate().alpha(1f).setDuration(100L).start()
                    action()
                }.start() }
            }
        }
        homeContent.addView(welcomeMenuItem("＋", "Create project") { showQuickStart() })
        homeContent.addView(welcomeMenuItem("▣", "Open existing project") {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream"))
        })
        homeContent.addView(welcomeMenuItem("</>", "Workspace") { openPage?.invoke(1) })
        homeContent.addView(welcomeMenuItem("◉", "App Preview") { openPage?.invoke(3) })
        homeContent.addView(welcomeMenuItem("⑂", "Clone git repository") {
            val repoUrl = EditText(this).apply {
                hint = "https://github.com/owner/repository"
                setSingleLine(true)
            }
            AlertDialog.Builder(this).setTitle("Clone Git Repository")
                .setMessage("Open the repository on GitHub. You can download its ZIP, then import it using Open existing project.")
                .setView(repoUrl)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open GitHub") { _, _ ->
                    val raw = repoUrl.text.toString().trim()
                    if (raw.startsWith("https://") || raw.startsWith("http://")) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw))) }
                        catch (_: Exception) { Toast.makeText(this, "Could not open repository URL.", Toast.LENGTH_SHORT).show() }
                    } else Toast.makeText(this, "Enter a valid repository URL.", Toast.LENGTH_SHORT).show()
                }.show()
        })
        homeContent.addView(welcomeMenuItem("›_", "Terminal") {
            val terminal = packageManager.getLaunchIntentForPackage("com.termux")
            if (terminal != null) startActivity(terminal)
            else {
                Toast.makeText(this, "Please install Termux first.", Toast.LENGTH_LONG).show()
                checkBuildServer()
            }
        })
        homeContent.addView(welcomeMenuItem("⬡", "APK Tools") { openPage?.invoke(2) })
        homeContent.addView(welcomeMenuItem("▤", "Documentation") {
            AlertDialog.Builder(this).setTitle("Pocket APK Studio Documentation")
                .setMessage("Create project: start a Kotlin or Java Android project.\\n\\nOpen existing project: import an Android Gradle project ZIP.\\n\\nWorkspace: edit project files and save the updated ZIP.\\n\\nBuild APK: configure Termux, Ubuntu, Java and Android SDK, then run a local build.\\n\\nAPK Tools: inspect APK details and recover source files with JADX.")
                .setPositiveButton("Got it", null).show()
        })
        homeContent.addView(welcomeMenuItem("⚙", "Preferences") { openSettingsPage?.invoke() })
        homeContent.addView(space(12))
        homeContent.addView(label("PROJECT & BUILD WORKSPACE", 12, 0xFF91B5B5.toInt(), true))
        homeContent.addView(label("Build status, recent activity and build logs remain available below.", 12, 0xFFB4C0C8.toInt(), false))
        homeContent.addView(space(8))
        val importCard = card()
        importCard.addView(label("1. IMPORT PROJECT", 12, 0xFF9AA7FF.toInt(), true))
        importCard.addView(space(10))
        importCard.addView(actionButton("Choose ZIP project") { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) })
        importCard.addView(space(8))
        importCard.addView(actionButton("Choose project folder") { pickFolder.launch(null) })
        editorContent.addView(importCard)
        editorContent.addView(space(14))

        val projectCard = card()
        projectCard.addView(label("2. PROJECT DETAILS", 12, 0xFF9AA7FF.toInt(), true))
        projectName = label("No project selected", 18, 0xFFFFFFFF.toInt(), true)
        projectCard.addView(projectName)
        projectType = label("Targets: Android Java/Kotlin first; Flutter later", 14, 0xFFB6BBC9.toInt(), false)
        projectCard.addView(projectType)
        projectCard.addView(space(8))
        projectCard.addView(actionButton("Open ZIP in Workspace", true) {
            val uri = selectedUri
            if (uri == null) Toast.makeText(this, "Import a project ZIP first.", Toast.LENGTH_SHORT).show()
            else openProjectEditor(uri)
        })
        editorContent.addView(projectCard)
        editorContent.addView(space(14))

        val serverCard = card()
        serverCard.addView(label("3. LOCAL BUILD SERVER", 12, 0xFF9AA7FF.toInt(), true))
        serverCard.addView(space(8))
        serverStatus = label(getSharedPreferences("build_state", MODE_PRIVATE).getString("server_status", "Not checked yet.") ?: "Not checked yet.", 13, 0xFFB6BBC9.toInt(), false)
        serverCard.addView(serverStatus)
        serverCard.addView(space(8))
        serverCard.addView(actionButton("Check Termux + Ubuntu server") { checkBuildServer() })
        settingsContent.addView(serverCard)
        editorContent.addView(space(14))

        val buildCard = card()
        buildCard.addView(label("✓  BUILD STATUS", 12, 0xFF9AA7FF.toInt(), true))
        buildCard.addView(space(8))
        buildCard.addView(actionButton("Build Android APK", true) { startLocalBuild() })
        buildCard.addView(space(8))
        buildProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF78A5A8.toInt())
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF78A5A8.toInt())
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
        editorContent.addView(buildCard)
        toolsContent.addView(space(12))
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
        editorContent.addView(logCard)
        homeContent.addView(space(18))

        val setupCard = card()
        setupCard.addView(label("LOCAL BUILD REQUIREMENTS", 12, 0xFF9AA7FF.toInt(), true))
        setupCard.addView(label("Builds run in the background through the local Termux + Ubuntu engine. You do not need to open the Termux terminal. Termux, Termux:RunCommand, Java 17, Gradle and Android SDK must remain installed and configured.", 13, 0xFFB6BBC9.toInt(), false))
        setupCard.addView(space(8))
        setupCard.addView(actionButton("Device & build information") { showDeviceInfo() })
        settingsContent.addView(setupCard)
        val toolsIntro = card()
        toolsIntro.addView(space(8))
        toolsIntro.addView(label("Inspect an APK, recover readable code with JADX, and export the recovered files.", 13, 0xFFB6BBC9.toInt(), false))
        toolsIntro.addView(space(10))
        val analyzeAction = actionButton("⌕   Analyze APK     ›", true) {
            pickAnalyzeApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        analyzeAction.background = premiumGradient(0xFF587F9D.toInt(), 0xFF4C718D.toInt(), 16)
        toolsIntro.addView(analyzeAction)
        toolsIntro.addView(space(8))
        val decompileAction = actionButton("⟳   Decompile APK with JADX     ›") {
            pickDecompileApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        decompileAction.background = premiumGradient(0xFF568B78.toInt(), 0xFF4D806E.toInt(), 16)
        toolsIntro.addView(decompileAction)
        toolsIntro.addView(space(8))
        val exportAction = actionButton("⇧   Export Recovered ZIP     ›") { shareRecoveredProject() }
        exportAction.background = premiumGradient(0xFF8A7860.toInt(), 0xFF796B58.toInt(), 16)
        toolsIntro.addView(exportAction)
        toolsContent.addView(toolsIntro)
        toolsContent.addView(space(12))
        analysisCard = card().apply { visibility = View.GONE }
        analysisCard.addView(label("◈  APK ANALYSIS REPORT", 13, 0xFF9AA7FF.toInt(), true))
        analysisStatus = label("Select an APK to begin analysis.", 14, 0xFFB6BBC9.toInt(), false)
        analysisCard.addView(analysisStatus)
        analysisCard.addView(space(8))
        analysisDetails = label("", 13, 0xFFFFFFFF.toInt(), false).apply { setTextIsSelectable(true) }
        analysisCard.addView(analysisDetails)
        analysisCard.addView(space(10))
        analysisCard.addView(label("APK CONTENTS", 11, 0xFF9AA7FF.toInt(), true))
        analysisComponents = label("", 12, 0xFFB6BBC9.toInt(), false).apply { setTextIsSelectable(true) }
        analysisCard.addView(analysisComponents)
        analysisCard.addView(space(10))
        analysisCard.addView(label("REQUESTED PERMISSIONS", 11, 0xFF9AA7FF.toInt(), true))
        analysisPermissions = label("", 12, 0xFFB6BBC9.toInt(), false).apply { setTextIsSelectable(true) }
        analysisCard.addView(analysisPermissions)
        analysisCard.addView(space(10))
        analysisCard.addView(label("SOURCE CODE RECOVERY", 11, 0xFF9AA7FF.toInt(), true))
        analysisCard.addView(label("APK analysis does not decompile DEX bytecode. JADX decompilation is a separate step and has not been run.", 12, 0xFFB6BBC9.toInt(), false))
        analysisCard.addView(space(8))
        analysisCard.addView(actionButton("Decompile APK with JADX", true) {
            pickDecompileApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        })
        analysisCard.addView(space(8))
        recoveryStatus = label("Decompilation has not started.", 12, 0xFFB6BBC9.toInt(), false)
        analysisCard.addView(recoveryStatus)
        recoveryProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF78A5A8.toInt())
        }
        analysisCard.addView(recoveryProgress, LinearLayout.LayoutParams(-1, dp(6)))
        exportRecoveryButton = actionButton("Export recovered project ZIP") { shareRecoveredProject() }.apply { visibility = View.GONE }
        analysisCard.addView(space(6))
        analysisCard.addView(exportRecoveryButton)
        analysisCard.addView(space(8))
        analysisCard.addView(actionButton("Copy analysis report") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("APK Analysis Report", analysisReportText))
            Toast.makeText(this, "Analysis report copied.", Toast.LENGTH_SHORT).show()
        })
        toolsContent.addView(analysisCard)
        homeContent.addView(space(18))
        homeContent.addView(label("Version 1.4 • APK analysis + JADX recovery", 12, 0xFF737B8D.toInt(), false))

        val previewContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(16))
        }
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
        val pages = listOf(homeScroll, editorScroll, toolsScroll, previewScroll, settingsScroll)
        lateinit var homeTab: Button
        lateinit var editorTab: Button
        lateinit var toolsTab: Button
        lateinit var previewTab: Button
        lateinit var settingsTab: Button
        var selectedPage = 0
        var pageTransitionRunning = false
        fun showPage(index: Int) {
            if (index !in pages.indices || index == selectedPage || pageTransitionRunning) return
            pageTransitionRunning = true
            val previous = pages[selectedPage]
            val next = pages[index]
            pages.forEach { page ->
                page.animate().cancel()
                if (page !== previous && page !== next) {
                    page.visibility = View.GONE
                    page.alpha = 1f
                    page.translationX = 0f
                    page.scaleX = 1f
                    page.scaleY = 1f
                }
            }
            // Reverse the layer-slide direction when navigating to an earlier tab.
            // Later tab: incoming from right, outgoing to left.
            // Earlier tab: incoming from left, outgoing to right.
            val direction = if (index > selectedPage) 1f else -1f
            val slideDistance = dp(42).toFloat() * direction
            val exitDistance = dp(24).toFloat() * direction
            next.visibility = View.VISIBLE
            next.bringToFront()
            next.alpha = 0f
            next.translationX = slideDistance
            next.scaleX = 0.965f
            next.scaleY = 0.965f
            previous.visibility = View.VISIBLE
            previous.alpha = 1f
            previous.translationX = 0f
            previous.scaleX = 1f
            previous.scaleY = 1f
            selectedPage = index
            val interpolator = android.view.animation.DecelerateInterpolator(1.7f)
            previous.animate()
                .translationX(-exitDistance)
                .scaleX(0.94f).scaleY(0.94f).alpha(0f)
                .setDuration(125L).setInterpolator(interpolator)
                .withEndAction {
                    previous.visibility = View.GONE
                    previous.alpha = 1f
                    previous.translationX = 0f
                    previous.scaleX = 1f
                    previous.scaleY = 1f
                }.start()
            next.animate()
                .translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(245L).setInterpolator(interpolator)
                .withEndAction {
                    previous.visibility = View.GONE
                    previous.alpha = 1f
                    previous.translationX = 0f
                    previous.scaleX = 1f
                    previous.scaleY = 1f
                    next.alpha = 1f
                    next.translationX = 0f
                    next.scaleX = 1f
                    next.scaleY = 1f
                    pageTransitionRunning = false
                }.start()
            val buttons = listOf(homeTab, editorTab, toolsTab, previewTab, settingsTab)
            buttons.forEachIndexed { i, button ->
                button.animate().cancel()
                button.animate().alpha(if (i == index) 1f else 0.72f).setDuration(180L).start()
                button.background = if (i == index) premiumGradient(0xFF568B91.toInt(), 0xFF4D7F85.toInt(), 13) else premiumGradient(0xFF344653.toInt(), 0xFF2B3B47.toInt(), 13)
                if (i == index) button.animate().scaleX(1.035f).scaleY(1.035f).setDuration(150L).withEndAction {
                    button.animate().scaleX(1f).scaleY(1f).setDuration(130L).start()
                }.start()
            }
        }
        openPage = { pageIndex -> showPage(pageIndex) }
        openSettingsPage = { showPage(4) }

        fun addPageBackHeader(target: LinearLayout, title: String, home: Boolean = false) {
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(4), dp(2), dp(12))
            }
            val back = actionButton("‹") {
                if (home) finish() else showPage(0)
            }.apply {
                textSize = 27f
                setPadding(dp(10), 0, dp(10), 0)
                minWidth = dp(46)
                background = premiumGradient(0xFF344653.toInt(), 0xFF2B3B47.toInt(), 12)
                contentDescription = if (home) "Exit app" else "Back to Home"
            }
            header.addView(back, LinearLayout.LayoutParams(dp(48), dp(44)))
            header.addView(label(title, 17, 0xFFE2E8ED.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
            target.addView(header, 0)
        }
        addPageBackHeader(editorContent, "Workspace")
        addPageBackHeader(toolsContent, "APK Tools")
        addPageBackHeader(previewContent, "App Preview")
        addPageBackHeader(settingsContent, "Settings")
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = premiumGradient(0xFF273640.toInt(), 0xFF202A33.toInt(), 18)
        }
        homeTab = navButton("⌂", "Home") { showPage(0) }
        editorTab = navButton("</>", "Editor") { showPage(1) }
        toolsTab = navButton("⬡", "Tools") { showPage(2) }
        previewTab = navButton("◉", "Preview") { showPage(3) }
        settingsTab = navButton("⚙", "Settings") { showPage(4) }
        listOf(homeTab, editorTab, toolsTab, previewTab, settingsTab).forEach {
            it.layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            }
        }
        val pageContainer = FrameLayout(this).apply {
            clipChildren = true
            clipToPadding = true
        }
        pages.forEach { page ->
            pageContainer.addView(page, FrameLayout.LayoutParams(-1, -1))
        }
        setupScreen = buildEnvironmentSetupPage()
        pageContainer.addView(setupScreen, FrameLayout.LayoutParams(-1, -1))
        root.addView(pageContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        // Bottom navigation removed; navigation is available from the Welcome screen and page back controls.
        pages.forEachIndexed { index, view -> view.visibility = if (index == 0) View.VISIBLE else View.GONE }
        homeTab.background = premiumGradient(0xFF568B91.toInt(), 0xFF4D7F85.toInt(), 13)
        setContentView(root)
    }

    private fun buildEnvironmentSetupPage(): ScrollView {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(24))
            background = premiumGradient(0xFF202A33.toInt(), 0xFF18232C.toInt(), 0)
        }
        body.addView(label("⚙", 36, 0xFF9CC5C3.toInt(), true).apply { gravity = android.view.Gravity.CENTER })
        body.addView(label("Environment Setup", 24, 0xFFFFFFFF.toInt(), true).apply { gravity = android.view.Gravity.CENTER })
        body.addView(label("Prepare your local Android build engine", 13, 0xFFB6C5CE.toInt(), false).apply { gravity = android.view.Gravity.CENTER })
        body.addView(space(14))
        val scanCard = card()
        scanCard.addView(label("AUTOMATIC DEVICE SCAN", 12, 0xFF9AA7FF.toInt(), true))
        setupStatus = label("Checking Termux…", 14, 0xFFE2E8ED.toInt(), true)
        scanCard.addView(setupStatus)
        setupProgress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        scanCard.addView(setupProgress, LinearLayout.LayoutParams(-1, dp(5)))
        scanCard.addView(space(8))
        setupResults = label("Termux: Waiting\nUbuntu: Waiting\nJava 17: Waiting\nAndroid SDK: Waiting\nPlatform Tools: Waiting\nBuild Tools: Waiting", 13, 0xFFB6C5CE.toInt(), false).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = premiumGradient(0xFF1A252D.toInt(), 0xFF1A252D.toInt(), 12)
            setTextIsSelectable(true)
        }
        scanCard.addView(setupResults)
        body.addView(scanCard)
        body.addView(space(10))
        val info = card()
        info.addView(label("ONE-TIME INSTALLATION", 12, 0xFF9AA7FF.toInt(), true))
        info.addView(label("Missing Ubuntu, Java 17 and Android SDK components will be installed automatically. Existing components will be reused. Internet connection is required.", 13, 0xFFB6C5CE.toInt(), false))
        info.addView(space(8))
        info.addView(actionButton("Install Termux") { openTermuxDownloadPage() })
        body.addView(info)
        body.addView(space(10))
        body.addView(actionButton("Scan & Setup", true) { beginEnvironmentSetup() })
        body.addView(space(8))
        setupNextButton = actionButton("Next  →", true) {
            setupScreen.visibility = View.GONE
            Toast.makeText(this, "Welcome to Pocket APK Studio.", Toast.LENGTH_SHORT).show()
        }.apply { isEnabled = false; alpha = 0.45f }
        body.addView(setupNextButton)
        body.addView(space(10))
        body.addView(label("Android requires manual Termux installation and permission approval. Enable allow-external-apps in Termux settings.", 11, 0xFF8999A3.toInt(), false))
        return ScrollView(this).apply { isFillViewport = true; addView(body); visibility = View.GONE }
    }

    private fun openProjectEditor(uri: Uri) {
        val editorIntent = Intent(this, ProjectEditorActivity::class.java)
            .putExtra("zip_uri", uri.toString())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        editProjectResult.launch(editorIntent)
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

    private fun checkBuildServer() { startAutomaticEnvironmentSetup() }

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
                export ANDROID_HOME=/root/Android/Sdk
                export ANDROID_SDK_ROOT=/root/Android/Sdk
                export PATH=/root/Android/cmdline-tools/latest/bin:/root/Android/Sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                command -v java >/dev/null || { echo "ERROR: Java is missing in Ubuntu"; exit 11; }
                test -f /root/Android/Sdk/platforms/android-35/android.jar || { echo "ERROR: Android 35 SDK platform is missing"; exit 15; }
                AAPT2_DIR="/root/.local/bin"
                AAPT2="/root/.local/bin/aapt2"
                mkdir -p "@D@AAPT2_DIR"
                if [ ! -x "@D@AAPT2" ] || ! "@D@AAPT2" version >/dev/null 2>&1; then
                    echo "Installing ARM64 AAPT2..."
                    if command -v curl >/dev/null 2>&1; then
                        curl -L --fail --silent --show-error "https://github.com/ReVanced/aapt2/releases/download/v1.1.0/aapt2-arm64-v8a" -o "@D@AAPT2"
                    elif command -v wget >/dev/null 2>&1; then
                        wget -q "https://github.com/ReVanced/aapt2/releases/download/v1.1.0/aapt2-arm64-v8a" -O "@D@AAPT2"
                    else
                        echo "ERROR: curl or wget is required to install ARM64 AAPT2"
                        exit 17
                    fi
                fi
                echo "7e5ae2e1f62fc24cab14072555ffd0a1a7e1ce27e82cc1008967b835b6d8df5b  @D@AAPT2" | sha256sum -c - || { echo "ERROR: ARM64 AAPT2 checksum verification failed"; exit 18; }
                chmod 755 "@D@AAPT2"
                "@D@AAPT2" version
                mkdir -p "@D@HOME/.gradle"
                touch "@D@HOME/.gradle/gradle.properties"
                if grep -q '^android.aapt2FromMavenOverride=' "@D@HOME/.gradle/gradle.properties"; then
                    sed -i "s|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=@D@AAPT2|" "@D@HOME/.gradle/gradle.properties"
                else
                    echo "android.aapt2FromMavenOverride=@D@AAPT2" >> "@D@HOME/.gradle/gradle.properties"
                fi
                PROJECT="@D@HOME/pocket-apk-studio/project"
                mkdir -p "@D@PROJECT"
                rm -rf "@D@PROJECT"/*
                unzip -q -o "@ZIP@" -d "@D@PROJECT"
                SETTINGS=@D@(find "@D@PROJECT" -type f \( -name settings.gradle -o -name settings.gradle.kts \) -print -quit)
                test -n "@D@SETTINGS" || { echo "ERROR: settings.gradle(.kts) not found in ZIP"; exit 14; }
                cd "@D@(dirname "@D@SETTINGS")"
                if [ -f ./gradlew ]; then
                    chmod +x ./gradlew
                    ./gradlew --no-daemon --max-workers=2 assembleDebug
                else
                    command -v gradle >/dev/null || { echo "ERROR: Gradle is missing in Ubuntu"; exit 12; }
                    gradle --no-daemon --max-workers=2 assembleDebug
                fi
                mkdir -p /sdcard/Download/PocketAPKStudio/Builds
                APK_COUNT=0
                while IFS= read -r APK; do
                    [ -n "@D@APK" ] || continue
                    cp "@D@APK" /sdcard/Download/PocketAPKStudio/Builds/
                    echo "APK_SAVED=/sdcard/Download/PocketAPKStudio/Builds/@D@(basename "@D@APK")"
                    APK_COUNT=@D@((APK_COUNT + 1))
                done < <(find . -path "*/build/outputs/apk/debug/*.apk" -type f)
                test "@D@APK_COUNT" -gt 0 || { echo "ERROR: Build completed but no debug APK was found"; exit 16; }
                echo "BUILD SUCCESS: @D@APK_COUNT APK file(s) copied to Downloads/PocketAPKStudio/Builds"
            '
        """.trimIndent().replace("@D@", "$").replace("@ZIP@", sharedZip)

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
            startService(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start Termux build: " + (e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
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


    private fun refreshRecoveryResult() {
        if (!::recoveryStatus.isInitialized || !::exportRecoveryButton.isInitialized) return
        val prefs = getSharedPreferences("recovery_state", MODE_PRIVATE)
        recoveryStatus.text = prefs.getString("status", "Decompilation has not started.") ?: "Decompilation has not started."
        val running = prefs.getBoolean("running", false)
        recoveryProgress.visibility = if (running) View.VISIBLE else View.GONE
        recoveryFilePath = prefs.getString("zip_path", null)
        val file = recoveryFilePath?.let(::File)
        exportRecoveryButton.visibility = if (file?.isFile == true && file.length() > 0L) View.VISIBLE else View.GONE
    }

    private fun startApkDecompilation(uri: Uri) {
        recoveryStatus.text = "Preparing selected APK…"
        recoveryProgress.visibility = View.VISIBLE
        exportRecoveryButton.visibility = View.GONE
        getSharedPreferences("recovery_state", MODE_PRIVATE).edit().clear()
            .putString("status", "Preparing selected APK…")
            .putBoolean("running", true)
            .apply()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { copyApkToRecoveryFolder(uri) }
                recoveryStatus.text = "APK copied. Starting JADX in Ubuntu…"
                getSharedPreferences("recovery_state", MODE_PRIVATE).edit()
                    .putString("status", "APK copied. Starting JADX in Ubuntu…").apply()
                launchTermuxDecompile()
            } catch (e: Exception) {
                val message = "Could not prepare APK: ${e.message ?: "storage error"}"
                recoveryStatus.text = message
                recoveryProgress.visibility = View.GONE
                getSharedPreferences("recovery_state", MODE_PRIVATE).edit()
                    .putString("status", message).putBoolean("running", false).apply()
            }
        }
    }

    private fun copyApkToRecoveryFolder(uri: Uri) {
        val folder = Environment.DIRECTORY_DOWNLOADS + "/PocketAPKStudio/Recovery"
        if (Build.VERSION.SDK_INT >= 29) {
            contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf("customer-manager.apk", folder + "/"))
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "customer-manager.apk")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        }
        val outputUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create APK in Downloads/PocketAPKStudio/Recovery")
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read selected APK" }
                contentResolver.openOutputStream(outputUri).use { output ->
                    requireNotNull(output) { "Cannot write APK to Recovery folder" }
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            contentResolver.delete(outputUri, null, null)
            throw e
        }
    }

    private fun launchTermuxDecompile() {
        val command = """
            set -e
            proot-distro login ubuntu -- bash -lc '
              set -e
              command -v java >/dev/null || { echo "ERROR: Java 17 is required in Ubuntu"; exit 21; }
              if ! command -v curl >/dev/null 2>&1; then apt-get update; apt-get install -y curl; fi
              if ! command -v unzip >/dev/null 2>&1; then apt-get install -y unzip; fi
              if ! command -v zip >/dev/null 2>&1; then apt-get install -y zip; fi
              JADXD="${'$'}HOME/.local/share/jadx-1.5.1"
              if [ ! -x "${'$'}JADXD/bin/jadx" ]; then
                mkdir -p "${'$'}JADXD"
                curl -L --fail --silent --show-error "https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip" -o /tmp/jadx-1.5.1.zip
                unzip -q -o /tmp/jadx-1.5.1.zip -d "${'$'}JADXD"
              fi
              INPUT="/sdcard/Download/PocketAPKStudio/Recovery/customer-manager.apk"
              test -s "${'$'}INPUT" || { echo "ERROR: selected APK not found in Recovery folder"; exit 22; }
              OUT="${'$'}HOME/pocket-apk-studio/recovered_customer_manager"
              rm -rf "${'$'}OUT"
              mkdir -p "${'$'}OUT"
              "${'$'}JADXD/bin/jadx" --show-bad-code -d "${'$'}OUT" "${'$'}INPUT"
              OUTPUT="/sdcard/Download/PocketAPKStudio/Recovery/CustomerManager-Recovered.zip"
              rm -f "${'$'}OUTPUT"
              cd "$(dirname "${'$'}OUT")"
              zip -qr "${'$'}OUTPUT" "$(basename "${'$'}OUT")"
              test -s "${'$'}OUTPUT" || { echo "ERROR: recovered ZIP was not created"; exit 23; }
              echo "RECOVERY_SAVED=${'$'}OUTPUT"
              echo "JADX_DECOMPILE_SUCCESS=1"
            '
        """.trimIndent()
        val resultIntent = Intent(this, TermuxResultService::class.java).putExtra("task_type", "apk_decompile")
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
            getSharedPreferences("recovery_state", MODE_PRIVATE).edit()
                .putString("status", "JADX is running in Ubuntu. Downloading/checking JADX, decompiling DEX files and preparing ZIP…")
                .putBoolean("running", true)
                .apply()
            startService(intent)
            recoveryStatus.text = "JADX is running in Ubuntu. Decompiling DEX files and preparing ZIP…"
            recoveryProgress.visibility = View.VISIBLE
        } catch (e: Exception) {
            val message = "Could not start JADX: ${e.message ?: "Termux permission or service error"}"
            recoveryStatus.text = message
            recoveryProgress.visibility = View.GONE
            getSharedPreferences("recovery_state", MODE_PRIVATE).edit()
                .putString("status", message).putBoolean("running", false).apply()
        }
    }

    private fun shareRecoveredProject() {
        val file = recoveryFilePath?.let(::File)
        if (file == null || !file.isFile || file.length() <= 0L) {
            recoveryStatus.text = "Recovered ZIP not found. Run Decompile APK again."
            exportRecoveryButton.visibility = View.GONE
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Export recovered project"
        ))
    }

    private fun analyzeExistingApk(uri: Uri) {
        analysisCard.visibility = View.VISIBLE
        analysisStatus.text = "Analyzing APK…"
        analysisDetails.text = "Reading package metadata and APK contents."
        analysisComponents.text = ""
        analysisPermissions.text = ""
        lifecycleScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val temp = File(cacheDir, "apk-analysis-" + System.currentTimeMillis() + ".apk")
                    try {
                        contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                            ?: error("Cannot read selected APK")
                        @Suppress("DEPRECATION")
                        val info = packageManager.getPackageArchiveInfo(temp.absolutePath,
                            android.content.pm.PackageManager.GET_PERMISSIONS or
                            android.content.pm.PackageManager.GET_ACTIVITIES or
                            android.content.pm.PackageManager.GET_SERVICES or
                            android.content.pm.PackageManager.GET_RECEIVERS or
                            android.content.pm.PackageManager.GET_PROVIDERS) ?: error("Invalid APK or metadata unavailable")
                        val appInfo = info.applicationInfo ?: error("Application information unavailable")
                        appInfo.sourceDir = temp.absolutePath
                        appInfo.publicSourceDir = temp.absolutePath
                        val dexNames = mutableListOf<String>()
                        var resourceCount = 0
                        var assetCount = 0
                        java.util.zip.ZipFile(temp).use { zip ->
                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val entryName = entries.nextElement().name
                                val name = entryName.substringAfterLast('/')
                                if (name.endsWith(".dex", ignoreCase = true)) dexNames.add(entryName)
                                if (entryName.startsWith("res/") && !entryName.endsWith("/")) resourceCount++
                                if (entryName.startsWith("assets/") && !entryName.endsWith("/")) assetCount++
                            }
                        }
                        dexNames.sort()
                        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                        val appName = appInfo.loadLabel(packageManager)?.toString() ?: "Unknown app"
                        val details = "App: " + appName + "\nPackage: " + info.packageName +
                            "\nVersion: " + (info.versionName ?: "Unknown") + " (code " + versionCode + ")" +
                            "\nMin SDK: " + appInfo.minSdkVersion + "\nTarget SDK: " + appInfo.targetSdkVersion +
                            "\nAPK size: " + (temp.length() / 1024) + " KB\nDEX files: " + dexNames.size
                        val components = "DEX files: " + dexNames.joinToString().ifBlank { "None found" } +
                            "\nResources: " + resourceCount + "\nAssets: " + assetCount +
                            "\nActivities: " + (info.activities?.size ?: 0) +
                            "\nServices: " + (info.services?.size ?: 0) +
                            "\nReceivers: " + (info.receivers?.size ?: 0) +
                            "\nProviders: " + (info.providers?.size ?: 0)
                        val permissions = info.requestedPermissions?.joinToString("\n") ?: "None listed"
                        details + "\n\n" + components + "\n\nRequested permissions:\n" + permissions
                    } finally { temp.delete() }
                }
                analysisReportText = report
                val parts = report.split("\n\nRequested permissions:\n", limit = 2)
                val mainParts = parts[0].split("\n\n", limit = 2)
                analysisDetails.text = mainParts[0]
                analysisComponents.text = if (mainParts.size > 1) mainParts[1] else "No content details"
                analysisPermissions.text = if (parts.size > 1) parts[1] else "None listed"
                analysisStatus.text = "Analysis completed successfully."
            } catch (e: Exception) {
                analysisStatus.text = "Analysis failed."
                analysisDetails.text = e.message ?: "Could not analyze APK."
                analysisComponents.text = "No APK content data available."
                analysisPermissions.text = "No permissions available."
                analysisReportText = "APK analysis failed: " + (e.message ?: "Unknown error")
            }
        }
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

    private fun premiumGradient(start: Int, end: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(start, end)
        ).apply { cornerRadius = dp(radius).toFloat() }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = premiumGradient(0xFF2B3742.toInt(), 0xFF2B3742.toInt(), 18)
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(7)
            bottomMargin = dp(7)
        }
    }

    private fun navButton(symbol: String, caption: String, action: () -> Unit) = Button(this).apply {
        text = "$symbol\n$caption"
        isAllCaps = false
        textSize = 11f
        setLineSpacing(0f, 0.9f)
        setTextColor(0xFFE2E8ED.toInt())
        gravity = android.view.Gravity.CENTER
        background = premiumGradient(0xFF344653.toInt(), 0xFF2B3B47.toInt(), 13)
        setOnClickListener { action() }
    }

    private fun showQuickStart() {
        val box=LinearLayout(this).apply{orientation=1;setPadding(dp(16),dp(8),dp(16),dp(8))}
        val scroll=ScrollView(this).apply{addView(box)}
        fun title(t:String){box.addView(label(t,12,0xFF9AA7FF.toInt(),true))}
        val name=EditText(this).apply{hint="My First App";setSingleLine(true)}
        val pkg=EditText(this).apply{setText("com.example.myapp");setSingleLine(true)}
        val ver=EditText(this).apply{setText("1.0");setSingleLine(true)}
        fun spin(a:List<String>)=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,a)}
        val lang=spin(listOf("Kotlin","Java"))
        val template=spin(listOf("Empty Activity","Basic Activity","Material UI App","WebView App","Login & Signup App","Navigation Drawer App","Bottom Navigation App","Custom Template"))
        val sdk=spin(listOf("24","26","28","29","30","31","33","35")).apply{setSelection(1)}
        box.addView(label("CREATE NEW ANDROID PROJECT",18,0xFFFFFFFF.toInt(),true))
        box.addView(label("Ready-to-edit Gradle project",12,0xFFB6C5E8.toInt(),false))
        title("PROJECT NAME");box.addView(name)
        title("PACKAGE NAME");box.addView(pkg)
        title("LANGUAGE");box.addView(lang)
        title("TEMPLATE");box.addView(template)
        title("MINIMUM SDK");box.addView(sdk)
        title("APP VERSION");box.addView(ver)
        val dlg=AlertDialog.Builder(this).setTitle("Quick Start").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Create Project",null).create()
        dlg.setOnShowListener{dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val n=name.text.toString().trim();val p=pkg.text.toString().trim();val v=ver.text.toString().trim()
            if(n.isBlank()){name.error="Required";return@setOnClickListener}
            if(!p.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))){pkg.error="Example: com.example.myapp";return@setOnClickListener}
            if(!v.matches(Regex("[0-9]+(\\.[0-9]+){0,2}"))){ver.error="Example: 1.0";return@setOnClickListener}
            dlg.dismiss();createNewProject(n,p,lang.selectedItem.toString(),template.selectedItem.toString(),sdk.selectedItem.toString().toInt(),v)
        }}
        dlg.show()
    }

    private fun createNewProject(appName:String,pkg:String,language:String,template:String,minSdk:Int,version:String){
        try{
            val dir=File(cacheDir,"quick-start-project").apply{deleteRecursively();mkdirs()}
            fun write(path:String,data:String){val f=File(dir,path);f.parentFile?.mkdirs();f.writeText(data.trimIndent()+"\n")}
            val kotlin=language=="Kotlin"
            write("settings.gradle.kts","""
                pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
                dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
                rootProject.name = "$appName"
                include(":app")
            """)
            write("build.gradle.kts","""
                plugins {
                    id("com.android.application") version "8.7.3" apply false
                    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
                }
            """)
            write("gradle.properties","""
                org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
                android.useAndroidX=true
                kotlin.code.style=official
            """)
            val kotlinPlugin=if(kotlin) "id(\"org.jetbrains.kotlin.android\")" else ""
            write("app/build.gradle.kts","""
                plugins { id("com.android.application"); $kotlinPlugin }
                android {
                    namespace = "$pkg"
                    compileSdk = 35
                    defaultConfig { applicationId = "$pkg"; minSdk = $minSdk; targetSdk = 35; versionCode = 1; versionName = "$version" }
                }
            """)
            val permission=if(template=="WebView App") "<uses-permission android:name=\"android.permission.INTERNET\"/>" else ""
            val safeName=appName.replace("&","&amp;").replace("\"","&quot;")
            write("app/src/main/AndroidManifest.xml","""
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    $permission
                    <application android:label="$safeName" android:theme="@style/AppTheme" android:allowBackup="true">
                        <activity android:name=".MainActivity" android:exported="true">
                            <intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
                        </activity>
                    </application>
                </manifest>
            """)
            write("app/src/main/res/values/styles.xml","""
                <resources><style name="AppTheme" parent="@android:style/Theme.Material.Light.NoActionBar">
                    <item name="android:colorAccent">#536DFE</item><item name="android:statusBarColor">#10172C</item>
                    <item name="android:navigationBarColor">#10172C</item>
                </style></resources>
            """)
            val source=starterSource(pkg,appName,template,kotlin)
            val ext=if(kotlin) "kt" else "java"
            write("app/src/main/java/"+pkg.replace(".","/")+"/MainActivity."+ext,source)
            write("README.md","# $appName\nPocket APK Studio starter project.\nTemplate: $template\nLanguage: $language\nMinimum SDK: $minSdk\n")
            val zip=File(cacheDir,"quick-start-"+System.currentTimeMillis()+".zip")
            java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use{z->dir.walkTopDown().filter{it.isFile}.forEach{f->
                z.putNextEntry(java.util.zip.ZipEntry(f.relativeTo(dir).invariantSeparatorsPath));f.inputStream().buffered().use{it.copyTo(z)};z.closeEntry()
            }}
            val uri=FileProvider.getUriForFile(this,"$packageName.fileprovider",zip)
            editProjectResult.launch(Intent(this,ProjectEditorActivity::class.java).putExtra("zip_uri",uri.toString()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            Toast.makeText(this,"$template project created.",Toast.LENGTH_SHORT).show()
        }catch(e:Exception){Toast.makeText(this,"Project creation failed: \${e.message}",Toast.LENGTH_LONG).show()}
    }

    private fun starterSource(pkg:String,appName:String,template:String,kotlin:Boolean):String{
        val app=appName.replace("\"","\\\"")
        val body=when(template){
            "Empty Activity" -> "// Start coding here."
            "WebView App" -> if(kotlin) """val web=WebView(this);web.settings.javaScriptEnabled=true;web.loadUrl("https://example.com");root.addView(web,LinearLayout.LayoutParams(-1,0,1f))""" else """WebView web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.loadUrl("https://example.com");root.addView(web,new LinearLayout.LayoutParams(-1,0,1f));"""
            "Login & Signup App" -> if(kotlin) """val email=EditText(this).apply{hint="Email"};val pass=EditText(this).apply{hint="Password";inputType=129};root.addView(email);root.addView(pass);root.addView(Button(this).apply{text="Login"});root.addView(Button(this).apply{text="Sign up"})""" else """EditText email=new EditText(this);email.setHint("Email");root.addView(email);EditText pass=new EditText(this);pass.setHint("Password");root.addView(pass);Button login=new Button(this);login.setText("Login");root.addView(login);"""
            "Navigation Drawer App" -> if(kotlin) """root.addView(Button(this).apply{text="☰ Open menu";setOnClickListener{android.app.AlertDialog.Builder(this@MainActivity).setItems(arrayOf("Home","Profile","Settings")){_,i->heading.text=arrayOf("Home","Profile","Settings")[i]}.show()}})""" else """Button menu=new Button(this);menu.setText("Open menu");menu.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setItems(new String[]{"Home","Profile","Settings"},(d,i)->heading.setText(new String[]{"Home","Profile","Settings"}[i])).show());root.addView(menu);"""
            "Bottom Navigation App" -> if(kotlin) """val content=TextView(this).apply{text="Home screen";gravity=Gravity.CENTER};root.addView(content,LinearLayout.LayoutParams(-1,0,1f));val nav=LinearLayout(this);listOf("Home","Search","Profile").forEach{item->nav.addView(Button(this).apply{text=item;setOnClickListener{content.text=item+" screen"}},LinearLayout.LayoutParams(0,52,1f))};root.addView(nav)""" else """TextView content=new TextView(this);content.setText("Home screen");content.setGravity(Gravity.CENTER);root.addView(content,new LinearLayout.LayoutParams(-1,0,1f));LinearLayout nav=new LinearLayout(this);for(String item:new String[]{"Home","Search","Profile"}){Button b=new Button(this);b.setText(item);b.setOnClickListener(v->content.setText(item+" screen"));nav.addView(b,new LinearLayout.LayoutParams(0,52,1f));}root.addView(nav);"""
            "Material UI App" -> if(kotlin) """val card=TextView(this).apply{text="Welcome to your app";textSize=20f;setPadding(28,28,28,28);setBackgroundColor(0xFFE8EAF6.toInt())};root.addView(card);root.addView(Button(this).apply{text="Get Started"})""" else """TextView card=new TextView(this);card.setText("Welcome to your app");card.setTextSize(20);card.setPadding(28,28,28,28);card.setBackgroundColor(0xFFE8EAF6);root.addView(card);"""
            "Custom Template" -> if(kotlin) """root.addView(TextView(this).apply{text="Your custom app starts here."})""" else """TextView note=new TextView(this);note.setText("Your custom app starts here.");root.addView(note);"""
            else -> if(kotlin) """val message=TextView(this).apply{text="Welcome to $app";textSize=20f};root.addView(message);root.addView(Button(this).apply{text="Get Started";setOnClickListener{message.text="Ready!"}})""" else """TextView message=new TextView(this);message.setText("Welcome to $app");root.addView(message);"""
        }
        val webImport=if(template=="WebView App") "import android.webkit.WebView\n" else ""
        return if(kotlin) """
            package $pkg
            import android.app.Activity
            import android.os.Bundle
            import android.view.Gravity
            import android.widget.*
            $webImport
            class MainActivity:Activity(){
                override fun onCreate(savedInstanceState:Bundle?){
                    super.onCreate(savedInstanceState)
                    val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;setPadding(24,24,24,24);setBackgroundColor(0xFFF5F7FF.toInt())}
                    val heading=TextView(this).apply{text="$app";textSize=24f;setTextColor(0xFF18264A.toInt());setPadding(0,0,0,20)}
                    root.addView(heading)
                    $body
                    setContentView(root)
                }
            }
        """.trimIndent() else """
            package $pkg;
            import android.app.Activity;import android.os.Bundle;import android.view.Gravity;import android.widget.*;
            $webImport
            public class MainActivity extends Activity{
                @Override public void onCreate(Bundle savedInstanceState){
                    super.onCreate(savedInstanceState);
                    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER_VERTICAL);root.setPadding(24,24,24,24);root.setBackgroundColor(0xFFF5F7FF);
                    TextView heading=new TextView(this);heading.setText("$app");heading.setTextSize(24);root.addView(heading);
                    $body
                    setContentView(root);
                }
            }
        """.trimIndent()
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
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFFDCE5E9.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (primary) 0xFF568B91.toInt() else 0xFF344653.toInt())
            cornerRadius = dp(12).toFloat()
        }
        isFocusable = true
        setOnClickListener { action() }
        setOnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.975f).scaleY(0.975f).setDuration(80L).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }
            false
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(46))
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
