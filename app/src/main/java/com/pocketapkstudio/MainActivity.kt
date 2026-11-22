package com.pocketapkstudio

import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.PendingIntent
import android.app.AlertDialog
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
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
import androidx.documentfile.provider.DocumentFile
import java.io.File
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : androidx.activity.ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var projectName: TextView
    private lateinit var projectType: TextView
    private lateinit var buildProgress: ProgressBar
    private lateinit var percentText: TextView
    private lateinit var logs: TextView
    private lateinit var installButton: Button
    private lateinit var shareButton: Button
    private lateinit var runtimePreviewHint: TextView
    private lateinit var runPreviewButton: Button
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
    private lateinit var analysisComponentsTitle: TextView
    private lateinit var analysisPermissionsTitle: TextView
    private lateinit var recoveryStatus: TextView
    private lateinit var recoveryProgress: ProgressBar
    private lateinit var recoveryPercentText: TextView
    private lateinit var recoveryStageText: TextView
    private lateinit var recoveryLogText: TextView
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private val buildLogHandler = Handler(Looper.getMainLooper())
    private val buildLogPollTask = object : Runnable {
        override fun run() {
            readBuildLiveLog()
            if (getSharedPreferences("build_state", MODE_PRIVATE).getBoolean("build_running", false)) {
                buildLogHandler.postDelayed(this, 1000)
            }
        }
    }
    private val recoveryPollTask = object : Runnable {
        override fun run() {
            readRecoveryProgress()
            if (getSharedPreferences("recovery_state", MODE_PRIVATE).getBoolean("running", false)) recoveryHandler.postDelayed(this, 1000)
        }
    }
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
    private var navigateToWorkflow: (() -> Unit)? = null
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
            buildProgress.visibility = if (prefs.getBoolean("build_running", false)) View.VISIBLE else View.GONE
            buildProgress.isIndeterminate = prefs.getBoolean("build_running", false)
            buildProgress.progress = prefs.getInt("last_build_percent", 0)
            percentText.text = "${buildProgress.progress}%"
            logs.text = prefs.getString("last_build_log", "No build logs yet.")
            val apk = prefs.getString("last_apk_path", null)?.let(::File)
            val available = apk?.isFile == true
            installButton.visibility = if (available) View.VISIBLE else View.GONE
            shareButton.visibility = if (available) View.VISIBLE else View.GONE
            val showRuntimePreview = available
            if (::runtimePreviewHint.isInitialized) runtimePreviewHint.visibility = if (showRuntimePreview) View.VISIBLE else View.GONE
            if (::runPreviewButton.isInitialized) runPreviewButton.visibility = if (showRuntimePreview) View.VISIBLE else View.GONE
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
        if (getSharedPreferences("build_state", MODE_PRIVATE).getBoolean("build_running", false)) {
            buildLogHandler.removeCallbacks(buildLogPollTask)
            buildLogHandler.post(buildLogPollTask)
        }
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
        recoveryHandler.removeCallbacks(recoveryPollTask)
        buildLogHandler.removeCallbacks(buildLogPollTask)
        try { unregisterReceiver(buildResultReceiver) } catch (_: Exception) { }
        super.onStop()
    }

    private fun refreshBuildResult() {
        if (!::status.isInitialized || !::buildProgress.isInitialized ||
            !::percentText.isInitialized || !::logs.isInitialized ||
            !::installButton.isInitialized || !::shareButton.isInitialized ||
            !::runtimePreviewHint.isInitialized || !::runPreviewButton.isInitialized) return
        val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
        val running = prefs.getBoolean("build_running", false)
        status.text = if (running) "Build running — check live logs below." else
            (prefs.getString("last_build_status", "Waiting for a project.") ?: "Waiting for a project.")
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
        val showRuntimePreview = available
        runtimePreviewHint.visibility = if (showRuntimePreview) View.VISIBLE else View.GONE
        runPreviewButton.visibility = if (showRuntimePreview) View.VISIBLE else View.GONE
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
        uri?.let { showDecompileModePopup(it) }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { treeUri ->
            try { contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            status.text = "Scanning selected project folder…"
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) { zipDocumentTree(treeUri) }
                    val zipUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", result.first)
                    selectedUri = zipUri
                    importedZipName = null
                    projectName.text = result.first.name.removeSuffix(".zip")
                    projectIsGradle = result.second
                    projectIsFlutter = result.third
                    projectType.text = when {
                        result.third -> "Flutter project detected — Flutter build support is not available yet."
                        result.second -> "Android Gradle project folder ready for editing and local build."
                        else -> "Folder scanned, but no Gradle settings/build files were found."
                    }
                    status.text = if (result.second && !result.third) "Project folder imported successfully." else "Folder scan finished."
                    if (result.second && !result.third) openProjectEditor(zipUri)
                } catch (error: Exception) {
                    projectIsGradle = false
                    projectIsFlutter = false
                    status.text = "Could not import folder: " + (error.message ?: "folder access error")
                    Toast.makeText(this@MainActivity, status.text, Toast.LENGTH_LONG).show()
                }
            }
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
            if (result.data?.getBooleanExtra("runtime_preview_build", false) == true) {
                navigateToWorkflow?.invoke()
                status.text = "Preparing a real device build from the edited project…"
                startLocalBuild(runtimePreview = true)
            }
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
            if ! command -v proot-distro >/dev/null 2>&1; then
                pkg update -y
                pkg install -y proot-distro
            fi
            ROOTFS="@D@PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
            if proot-distro login ubuntu -- true >/dev/null 2>&1; then
                echo "SCAN_UBUNTU=INSTALLED"
            elif [ -d "@D@ROOTFS" ]; then
                echo "ERROR_UBUNTU_EXISTS_BUT_LOGIN_FAILED"
                exit 1
            else
                proot-distro install ubuntu
                proot-distro login ubuntu -- true
                echo "SCAN_UBUNTU=INSTALLED"
            fi
            proot-distro login ubuntu -- bash -lc '
                set -e
                export DEBIAN_FRONTEND=noninteractive
                NEED_APT=0
                if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q "17"; then NEED_APT=1; fi
                if ! command -v curl >/dev/null 2>&1 || ! command -v wget >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1 || ! command -v zip >/dev/null 2>&1; then NEED_APT=1; fi
                if [ "@D@NEED_APT" = "1" ]; then apt-get update; fi
                if ! command -v java >/dev/null 2>&1 || ! java -version 2>&1 | grep -q "17"; then
                    apt-get install -y openjdk-17-jdk
                fi
                java -version 2>&1 | grep -q "17"
                echo "SCAN_JAVA17=INSTALLED"
                for package in curl wget unzip zip; do
                    if ! command -v "@D@package" >/dev/null 2>&1; then apt-get install -y "@D@package"; fi
                done
                mkdir -p /root/Android/cmdline-tools
                if [ ! -x /root/Android/cmdline-tools/latest/bin/sdkmanager ]; then
                    if [ "@D@NEED_APT" = "0" ] && ! command -v wget >/dev/null 2>&1; then apt-get update; fi
                    apt-get install -y wget unzip
                    cd /tmp
                    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
                    rm -rf /tmp/cmdline-tools
                    mkdir -p /tmp/cmdline-tools
                    unzip -q cmdline-tools.zip -d /tmp/cmdline-tools
                    rm -rf /root/Android/cmdline-tools/latest
                    mv /tmp/cmdline-tools/cmdline-tools /root/Android/cmdline-tools/latest
                fi
                echo "SCAN_ANDROID_SDK=INSTALLED"
                NEED_SDK=0
                [ -f /root/Android/Sdk/platforms/android-35/android.jar ] || NEED_SDK=1
                [ -x /root/Android/Sdk/platform-tools/adb ] || NEED_SDK=1
                [ -x /root/Android/Sdk/build-tools/35.0.0/aapt ] || NEED_SDK=1
                if [ "@D@NEED_SDK" = "1" ]; then
                    yes | /root/Android/cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/Android/Sdk --licenses >/dev/null || true
                    /root/Android/cmdline-tools/latest/bin/sdkmanager --sdk_root=/root/Android/Sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"
                fi
                test -f /root/Android/Sdk/platforms/android-35/android.jar
                echo "SCAN_ANDROID_SDK=INSTALLED"
                test -x /root/Android/Sdk/platform-tools/adb
                echo "SCAN_PLATFORM_TOOLS=INSTALLED"
                test -x /root/Android/Sdk/build-tools/35.0.0/aapt
                echo "SCAN_BUILD_TOOLS=INSTALLED"
                echo BUILD_SERVER_READY=1
            '
        """.trimIndent().replace("@D@", "$").replace("@D@", "@D@")
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
            getSharedPreferences("build_state", MODE_PRIVATE).edit()
                .putString("last_build_status", "Termux command sent. Waiting for build output…")
                .putString("last_build_log", "Termux command sent.\nIf no output appears, check Termux RUN_COMMAND access and allow-external-apps.")
                .apply()
            Handler(Looper.getMainLooper()).postDelayed({
                val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
                if (prefs.getBoolean("build_running", false)) {
                    val live = prefs.getString("last_build_log", "").orEmpty()
                    if (live.isBlank() || live.contains("Build starting…") || live.contains("Build starting...")) {
                        val message = "Termux did not return build output. Open Termux > Settings > allow external apps, enable it, then retry. Also confirm Termux has storage permission and Ubuntu is installed."
                        prefs.edit()
                            .putBoolean("build_running", false)
                            .putString("last_build_status", message)
                            .putString("last_build_log", message)
                            .putInt("last_build_percent", 0)
                            .putString("last_apk_path", null)
                            .apply()
                        buildLogHandler.removeCallbacks(buildLogPollTask)
                        if (::buildProgress.isInitialized) buildProgress.visibility = View.GONE
                        if (::status.isInitialized) status.text = message
                        if (::logs.isInitialized) logs.text = message
                        if (::percentText.isInitialized) percentText.text = "BUILD NOT STARTED"
                    }
                }
            }, 30000L)
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
        val workspaceHero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFF1F6F8.toInt())
            ).apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), 0xFFD9E5E9.toInt())
            }
            elevation = dp(5).toFloat()
        }
        workspaceHero.addView(label("WORKSPACE", 29, 0xFF354F5D.toInt(), true).apply {
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.045f
        })
        workspaceHero.addView(label("Manage your Android projects", 11, 0xFF778B96.toInt(), false).apply {
            gravity = android.view.Gravity.CENTER
        })
        workspaceHero.addView(space(5))
        val workspaceActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        fun heroActionArrow(): TextView {
            return label("→", 25, 0xFFFFFFFF.toInt(), true).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), dp(4), dp(12), dp(4))
                minWidth = dp(52)
                minHeight = dp(42)
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF3E8790.toInt(), 0xFF24566A.toInt())
                ).apply {
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), 0xFF75AEB1.toInt())
                }
                elevation = dp(4).toFloat()
                contentDescription = "Open Workspace"
                isClickable = true
                isFocusable = true
                setOnClickListener { openPage?.invoke(1) }
            }
        }
        fun heroText(text: String): TextView {
            return label(text, 16, 0xFF315E70.toInt(), false).apply {
                setPadding(dp(5), dp(3), dp(5), dp(3))
                letterSpacing = 0.01f
            }
        }
        workspaceActions.addView(heroText("Manage"))
        workspaceActions.addView(heroText("·"))
        workspaceActions.addView(heroText("Create"))
        workspaceActions.addView(heroText("·"))
        workspaceActions.addView(heroText("Edit"))
        workspaceActions.addView(space(4))
        workspaceActions.addView(heroActionArrow())
        workspaceHero.addView(workspaceActions)
        homeContent.addView(workspaceHero)
        homeContent.addView(space(10))
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
                .setMessage("Create project: start a Kotlin or Java Android project.\n\nOpen existing project: import an Android Gradle project ZIP.\n\nWorkspace: edit project files and save the updated ZIP.\n\nBuild APK: configure Termux, Ubuntu, Java and Android SDK, then run a local build.\n\nAPK Tools: inspect APK details and recover source files with JADX.")
                .setPositiveButton("Got it", null).show()
        })
        homeContent.addView(welcomeMenuItem("⚙", "Preferences") { openSettingsPage?.invoke() })
        homeContent.addView(space(12))
        homeContent.addView(label("PROJECT & BUILD WORKSPACE", 12, 0xFF91B5B5.toInt(), true))
        homeContent.addView(label("Build status, recent activity and build logs remain available below.", 12, 0xFFB4C0C8.toInt(), false))
        homeContent.addView(space(8))
        // Workspace sections use an Android-Studio-inspired accordion for a clean mobile layout.
        fun workspaceSection(title: String, subtitle: String, content: View, initiallyExpanded: Boolean = false) {
            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = premiumGradient(0xFF263640.toInt(), 0xFF22313B.toInt(), 14)
                setPadding(dp(10), dp(8), dp(10), dp(9))
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            heading.addView(label(title, 14, 0xFFE6F0F3.toInt(), true))
            heading.addView(label(subtitle, 10, 0xFFA8BAC3.toInt(), false))
            header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
            val arrow = label(if (initiallyExpanded) "⌃" else "⌄", 23, 0xFF9CC5C3.toInt(), true).apply {
                gravity = android.view.Gravity.CENTER
                minWidth = dp(38)
            }
            header.addView(arrow)
            section.addView(header)
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
                alpha = if (initiallyExpanded) 1f else 0f
            }
            body.addView(space(6))
            body.addView(content, LinearLayout.LayoutParams(-1, -2))
            section.addView(body)
            header.setOnClickListener {
                val expand = body.visibility != View.VISIBLE
                if (expand) {
                    body.visibility = View.VISIBLE
                    body.alpha = 0f
                    body.translationY = -dp(5).toFloat()
                    body.animate().alpha(1f).translationY(0f).setDuration(180L).
                        setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                } else {
                    body.animate().alpha(0f).translationY(-dp(5).toFloat()).setDuration(130L).
                        withEndAction { body.visibility = View.GONE; body.alpha = 1f; body.translationY = 0f }.start()
                }
                arrow.text = if (expand) "⌃" else "⌄"
            }
            editorContent.addView(section, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }

        val workspaceIntro = card().apply {
            background = premiumGradient(0xFF344F5D.toInt(), 0xFF263B48.toInt(), 16)
            addView(label("ANDROID PROJECT WORKSPACE", 11, 0xFF9CC5C3.toInt(), true))
            addView(label("Your project, source code, build controls and logs — organized in expandable panels.", 13, 0xFFE1EBEF.toInt(), false))
        }
        editorContent.addView(workspaceIntro)
        editorContent.addView(space(8))

        val importCard = card()
        importCard.addView(label("1. PROJECT ACTIONS", 12, 0xFF9AA7FF.toInt(), true))
        importCard.addView(space(10))
        importCard.addView(actionButton("＋  Create Project", true) { showQuickStart() })
        importCard.addView(space(8))
        importCard.addView(actionButton("▣  Open Existing Project (ZIP)") {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream"))
        })
        importCard.addView(space(8))
        importCard.addView(actionButton("Open Existing Project (Folder)") { pickFolder.launch(null) })
        workspaceSection("01  PROJECT ACTIONS", "Create a project or open an existing Android project", importCard, true)

        val projectCard = card()
        projectCard.addView(label("2. PROJECT DETAILS", 12, 0xFF9AA7FF.toInt(), true))
        projectName = label("No project selected", 18, 0xFFFFFFFF.toInt(), true)
        projectCard.addView(projectName)
        projectType = label("Targets: Android Java/Kotlin first; Flutter later", 14, 0xFFB6BBC9.toInt(), false)
        projectCard.addView(projectType)
        projectCard.addView(space(8))
        projectCard.addView(actionButton("Open Selected Project in Editor", true) {
            val uri = selectedUri
            if (uri == null) Toast.makeText(this, "Import a project ZIP first.", Toast.LENGTH_SHORT).show()
            else openProjectEditor(uri)
        })
        workspaceSection("02  PROJECT DETAILS", "Selected project information and editor entry", projectCard)

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
        runtimePreviewHint = label(
            "Install the APK above first. Then return here and open the real compiled app preview.",
            11, 0xFFFFC98D.toInt(), false
        ).apply { visibility = View.GONE }
        runPreviewButton = actionButton("▶  Run Exact Device Preview", true) { openBuiltRuntimePreview() }.apply {
            visibility = View.GONE
        }
        buildCard.addView(space(6)); buildCard.addView(installButton)
        buildCard.addView(space(6)); buildCard.addView(shareButton)
        buildCard.addView(space(6)); buildCard.addView(runtimePreviewHint)
        buildCard.addView(space(4)); buildCard.addView(runPreviewButton)
        workspaceSection("03  BUILD APK", "Build progress, status, install and share", buildCard, true)
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
        workspaceSection("04  BUILD LOGS", "Build output, diagnostics and copy logs", logCard, true)
        homeContent.addView(space(18))

        val setupCard = card()
        setupCard.addView(label("LOCAL BUILD REQUIREMENTS", 12, 0xFF9AA7FF.toInt(), true))
        setupCard.addView(label("Builds run in the background through the local Termux + Ubuntu engine. You do not need to open the Termux terminal. Termux, Termux:RunCommand, Java 17, Gradle and Android SDK must remain installed and configured.", 13, 0xFFB6BBC9.toInt(), false))
        setupCard.addView(space(8))
        setupCard.addView(actionButton("Device & build information") { showDeviceInfo() })
        settingsContent.addView(setupCard)
        val toolsIntro = card().apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = premiumGradient(0xFF405968.toInt(), 0xFF3A5260.toInt(), 2)
            elevation = dp(0).toFloat()
        }
        val toolsHeading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val toolsBack = TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFF405968.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFF4F6F7.toInt())
                setStroke(dp(1), 0xFFD6DDE1.toInt())
            }
        }
        toolsHeading.addView(toolsBack, LinearLayout.LayoutParams(dp(44), dp(44)))
        toolsHeading.addView(label("  Apk Tool", 22, 0xFFFFFFFF.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        val toolsSettingsButton = android.widget.ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFF2F5F6.toInt())
            scaleType = android.widget.ImageView.ScaleType.CENTER
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x334F5F6F)
            }
            contentDescription = "APK Tools settings"
            isClickable = true
            isFocusable = true
            setOnClickListener { openSettingsPage?.invoke() }
        }
        toolsHeading.addView(toolsSettingsButton, LinearLayout.LayoutParams(dp(42), dp(42)))
        toolsBack.setOnClickListener { openPage?.invoke(0) }
        toolsIntro.addView(toolsHeading)
        toolsIntro.addView(space(14))
        val toolsMenuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = premiumGradient(0xFFBFCBD2.toInt(), 0xFFB7C4CC.toInt(), 18)
        }
        val menuHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        menuHeader.addView(label("•  Live Process Logs", 15, 0xFF111820.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        val menuToggle = TextView(this).apply {
            text = "⌄"
            textSize = 25f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = premiumGradient(0xFF405968.toInt(), 0xFF405968.toInt(), 24)
            minWidth = dp(52)
            minHeight = dp(36)
            isClickable = true
            isFocusable = true
            contentDescription = "Show or hide APK actions"
        }
        menuHeader.addView(menuToggle)
        toolsMenuPanel.addView(menuHeader)
        val toolActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = premiumGradient(0xFF405968.toInt(), 0xFF405968.toInt(), 16)
        }
        val analyzeAction = actionButton("⌕   Analyze Apk", false) {
            pickAnalyzeApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        analyzeAction.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        analyzeAction.background = premiumGradient(0xFF405968.toInt(), 0xFF405968.toInt(), 12)
        toolActions.addView(analyzeAction)
        toolActions.addView(space(8))
        val decompileAction = actionButton("⟳   Decompile Apk", false) {
            pickDecompileApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
        }
        decompileAction.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        decompileAction.background = premiumGradient(0xFF405968.toInt(), 0xFF405968.toInt(), 12)
        toolActions.addView(decompileAction)
        toolsMenuPanel.addView(toolActions)
        var menuExpanded = true
        toolActions.visibility = View.VISIBLE
        menuToggle.setOnClickListener {
            menuExpanded = !menuExpanded
            if (menuExpanded) {
                toolActions.visibility = View.VISIBLE
                toolActions.alpha = 0f
                toolActions.translationY = -dp(8).toFloat()
                toolActions.animate().alpha(1f).translationY(0f).setDuration(210L).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else {
                toolActions.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(150L).withEndAction {
                    toolActions.visibility = View.GONE
                    toolActions.alpha = 1f
                    toolActions.translationY = 0f
                }.start()
            }
            menuToggle.text = if (menuExpanded) "⌃" else "⌄"
        }
        toolsIntro.addView(toolsMenuPanel)
        toolsContent.addView(toolsIntro)
        toolsContent.addView(space(10))
        analysisCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = premiumGradient(0xFFBFCBD2.toInt(), 0xFFB7C4CC.toInt(), 18)
            visibility = View.GONE
        }
        analysisCard.addView(label("◈  APK ANALYSIS REPORT", 13, 0xFF405968.toInt(), true))
        analysisCard.addView(space(5))
        analysisStatus = label("Select an APK to begin analysis.", 13, 0xFF25343D.toInt(), false)
        analysisCard.addView(analysisStatus)
        analysisDetails = label("", 12, 0xFF17232B.toInt(), false).apply { setTextIsSelectable(true) }
        analysisCard.addView(analysisDetails)
        analysisComponentsTitle = label("APK CONTENTS", 11, 0xFF405968.toInt(), true).apply { visibility = View.GONE }
        analysisCard.addView(analysisComponentsTitle)
        analysisComponents = label("", 12, 0xFF25343D.toInt(), false).apply { setTextIsSelectable(true); visibility = View.GONE }
        analysisCard.addView(analysisComponents)
        analysisPermissionsTitle = label("REQUESTED PERMISSIONS", 11, 0xFF405968.toInt(), true).apply { visibility = View.GONE }
        analysisCard.addView(analysisPermissionsTitle)
        analysisPermissions = label("", 12, 0xFF25343D.toInt(), false).apply { setTextIsSelectable(true); visibility = View.GONE }
        analysisCard.addView(analysisPermissions)
        analysisCard.addView(space(8))
        analysisCard.addView(label("LIVE PROCESS LOGS", 13, 0xFF17232B.toInt(), true))
        val logToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        logToolbar.addView(label("Current operation output", 11, 0xFF405968.toInt(), false), LinearLayout.LayoutParams(0, -2, 1f))
        logToolbar.addView(actionButton("Copy Logs  ▢", false) {
            val prefs = getSharedPreferences("recovery_state", MODE_PRIVATE)
            val statusText = prefs.getString("status", "").orEmpty()
            val stageText = prefs.getString("stage", "").orEmpty()
            val fullLog = prefs.getString("log", "").orEmpty()
            val liveLog = prefs.getString("live_log", "").orEmpty()
            val report = buildString {
                append("POCKET APK STUDIO — JADX DECOMPILE LOGS\n")
                append("Status: ").append(statusText).append("\n")
                append("Stage: ").append(stageText).append("\n")
                append("Progress: ").append(prefs.getInt("percent", 0)).append("%\n\n")
                append("FULL TERMUX OUTPUT:\n").append(fullLog.ifBlank { "No final output captured." })
                append("\n\nLIVE PROCESS LOG:\n").append(liveLog.ifBlank { recoveryLogText.text })
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("JADX Decompile Logs", report))
            Toast.makeText(this, "Decompile logs copied.", Toast.LENGTH_SHORT).show()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(-2, dp(38))
            textSize = 12f
            setTextColor(0xFF17232B.toInt())
            background = premiumGradient(0xFFAEBBC3.toInt(), 0xFFAEBBC3.toInt(), 10)
        })
        analysisCard.addView(logToolbar)
        recoveryLogText = label("No process logs yet.", 11, 0xFF17232B.toInt(), false).apply {
            setTextIsSelectable(false)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val recoveryLogScroll = ScrollView(this).apply {
            addView(recoveryLogText)
            layoutParams = LinearLayout.LayoutParams(-1, dp(270))
            isFillViewport = false
            isSmoothScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(0xFFBFCBD2.toInt())
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN,
                    android.view.MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        analysisCard.addView(recoveryLogScroll)
        analysisCard.addView(space(8))
        recoveryStatus = label("Decompilation has not started.", 12, 0xFF25343D.toInt(), false)
        analysisCard.addView(recoveryStatus)
        recoveryStageText = label("Waiting for decompilation…", 11, 0xFF405968.toInt(), false)
        analysisCard.addView(recoveryStageText)
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        recoveryProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            visibility = View.VISIBLE
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF3D8064.toInt())
        }
        progressRow.addView(recoveryProgress, LinearLayout.LayoutParams(0, dp(7), 1f))
        recoveryPercentText = label("0%", 15, 0xFF405968.toInt(), true)
        progressRow.addView(recoveryPercentText)
        analysisCard.addView(progressRow)
        exportRecoveryButton = actionButton("⇧   Export Recovery File", true) { shareRecoveredProject() }.apply {
            visibility = View.GONE
            background = premiumGradient(0xFFAEBBC3.toInt(), 0xFFAEBBC3.toInt(), 24)
            setTextColor(0xFF17232B.toInt())
        }
        analysisCard.addView(space(8))
        analysisCard.addView(exportRecoveryButton)
        analysisCard.addView(space(6))
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
        navigateToWorkflow = { showPage(0) }
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
        homeTab = navButton("⌂", "Workspace") { showPage(0) }
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
        root.addView(tabBar, LinearLayout.LayoutParams(-1, dp(62)))
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

    private fun startLocalBuild(runtimePreview: Boolean = false) {
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
        runtimePreviewHint.visibility = View.GONE
        runPreviewButton.visibility = View.GONE
        val buildPrefs = getSharedPreferences("build_state", MODE_PRIVATE)
        buildPrefs.edit()
            .putBoolean("runtime_preview_requested", runtimePreview)
            .putBoolean("build_running", true)
            .putString("last_build_status", "Build running — preparing project…")
            .putInt("last_build_percent", 0)
            .putString("last_build_log", "Preparing project…")
            .putString("last_apk_path", null)
            .apply()
        runCatching { prepareBuildLiveLog() }.onFailure { error ->
            buildPrefs.edit().putString("last_build_log", "Preparing project…\\nLive log file unavailable: ${error.message}").apply()
        }
        buildLogHandler.removeCallbacks(buildLogPollTask)
        buildLogHandler.post(buildLogPollTask)
        lifecycleScope.launch {
            try {
                status.text = "Copying project ZIP to shared Downloads…"
                logs.text = "Preparing project…\nCopying the selected ZIP into Downloads/PocketAPKStudio."
                buildPrefs.edit()
                    .putString("last_build_status", "Copying project ZIP to shared Downloads…")
                    .putString("last_build_log", "Preparing project…\nCopying the selected ZIP into Downloads/PocketAPKStudio.")
                    .apply()
                val fileName = withContext(Dispatchers.IO) { copyZipToDownloads(source) }
                importedZipName = fileName
                val copied = "Project ZIP copied: $fileName\nStarting Termux build service…"
                status.text = "Starting Termux build…"
                logs.text = copied
                buildPrefs.edit()
                    .putString("last_build_status", "Starting Termux build…")
                    .putString("last_build_log", copied)
                    .apply()
                launchTermuxBuild(fileName)
            } catch (e: Exception) {
                val message = "Could not prepare project ZIP: ${e.message ?: "storage error"}"
                getSharedPreferences("build_state", MODE_PRIVATE).edit()
                    .putBoolean("build_running", false)
                    .putString("last_build_status", message)
                    .putString("last_build_log", message)
                    .putInt("last_build_percent", 0)
                    .putString("last_apk_path", null)
                    .apply()
                buildLogHandler.removeCallbacks(buildLogPollTask)
                buildProgress.visibility = View.GONE
                status.text = message
                logs.text = message
            }
        }
    }

    private fun prepareBuildLiveLog() {
        val folder = Environment.DIRECTORY_DOWNLOADS + "/PocketAPKStudio/"
        contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("build-live.log", folder)
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "build-live.log")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create live build log")
        contentResolver.openOutputStream(uri)?.use { it.write("Build starting…\\n".toByteArray()) }
            ?: error("Could not initialize live build log")
        getSharedPreferences("build_state", MODE_PRIVATE).edit()
            .putString("live_build_log_uri", uri.toString()).apply()
    }

    private fun readBuildLiveLog() {
        if (!::logs.isInitialized) return
        val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
        val uriText = prefs.getString("live_build_log_uri", null) ?: return
        try {
            val text = contentResolver.openInputStream(Uri.parse(uriText))
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (text.isNotBlank()) {
                val visible = text.takeLast(40000)
                logs.text = visible
                prefs.edit().putString("last_build_log", visible).apply()
            }
        } catch (_: Exception) {
            // Final Termux callback still supplies stdout/stderr if live access is unavailable.
        }
    }

    private fun zipDocumentTree(treeUri: Uri): Triple<File, Boolean, Boolean> {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: error("Cannot access selected folder")
        require(root.isDirectory) { "Selected item is not a folder" }
        val output = File(cacheDir, "folder-import-${System.currentTimeMillis()}.zip")
        var hasGradle = false
        var hasFlutter = false
        var count = 0
        ZipOutputStream(output.outputStream().buffered()).use { zip ->
            fun addFolder(folder: DocumentFile, prefix: String) {
                folder.listFiles().forEach { child ->
                    val name = child.name ?: return@forEach
                    if (name in setOf(".gradle", "build", ".git", "node_modules", ".idea")) return@forEach
                    val relative = if (prefix.isBlank()) name else "$prefix/$name"
                    if (child.isDirectory) {
                        addFolder(child, relative)
                    } else if (child.isFile) {
                        if (relative.endsWith("settings.gradle") || relative.endsWith("settings.gradle.kts") ||
                            relative.endsWith("build.gradle") || relative.endsWith("build.gradle.kts")) hasGradle = true
                        if (relative.endsWith("pubspec.yaml") || relative.contains(".dart_tool/")) hasFlutter = true
                        zip.putNextEntry(ZipEntry(relative))
                        contentResolver.openInputStream(child.uri)?.use { input -> input.copyTo(zip) }
                            ?: error("Cannot read project file: $relative")
                        zip.closeEntry()
                        count++
                        require(count <= 30000) { "Folder has more than 30,000 files; import it as a ZIP instead." }
                    }
                }
            }
            addFolder(root, "")
        }
        check(output.isFile && output.length() > 0L) { "Selected folder is empty." }
        return Triple(output, hasGradle, hasFlutter)
    }

    private fun copyZipToDownloads(uri: Uri): String {
        val original = queryDisplayName(uri) ?: "android-project.zip"
        val normalizedName = original.replace(Regex("[^A-Za-z0-9._-]"), "_").let {
            if (it.endsWith(".zip", true)) it else "$it.zip"
        }
        // A unique name prevents MediaStore auto-renaming the file while the
        // Termux command still tries to read the original name.
        val safeName = "project-${System.currentTimeMillis()}-$normalizedName"
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
        val sharedZip = "/mnt/shared/Download/PocketAPKStudio/$zipName"
        val command = """
            set -e
            SHARED="/storage/emulated/0"
            LOG_DIR="@D@SHARED/Download/PocketAPKStudio"
            LIVE_LOG="@D@LOG_DIR/build-live.log"
            mkdir -p "@D@LOG_DIR"
            : > "@D@LIVE_LOG"
            exec > >(tee -a "@D@LIVE_LOG") 2>&1
            echo "Pocket APK Studio: background Android build started"
            if [ ! -d "@D@SHARED/Download" ]; then
                echo "ERROR: Termux shared storage is not ready. Open Termux, run termux-setup-storage, allow storage access, then retry."
                exit 24
            fi
            if [ ! -r "@D@SHARED/Download/PocketAPKStudio/$zipName" ]; then
                echo "ERROR: Imported project ZIP is not readable in Termux shared storage: $zipName"
                exit 25
            fi
            command -v proot-distro >/dev/null || { echo 'ERROR: proot-distro is missing in Termux'; exit 10; }
            proot-distro login ubuntu --bind "@D@SHARED:/mnt/shared" -- bash -lc '
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
                unzip -q -o "/mnt/shared/Download/PocketAPKStudio/$zipName" -d "@D@PROJECT"
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
                mkdir -p /mnt/shared/Download/PocketAPKStudio/Builds
                APK_COUNT=0
                while IFS= read -r APK; do
                    [ -n "@D@APK" ] || continue
                    cp "@D@APK" /mnt/shared/Download/PocketAPKStudio/Builds/
                    echo "APK_SAVED=/storage/emulated/0/Download/PocketAPKStudio/Builds/@D@(basename "@D@APK")"
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
            val message = "Could not start Termux build: " + (e.message ?: "unknown error")
            getSharedPreferences("build_state", MODE_PRIVATE).edit()
                .putBoolean("build_running", false)
                .putString("last_build_status", message)
                .putString("last_build_log", message)
                .putInt("last_build_percent", 0)
                .putString("last_apk_path", null)
                .apply()
            if (::buildProgress.isInitialized) buildProgress.visibility = View.GONE
            if (::status.isInitialized) status.text = message
            if (::logs.isInitialized) logs.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Opens the compiled project app after the user has installed the latest APK.
     * This is the genuine Compose/runtime path; the in-pane source renderer remains approximate.
     */
    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openBuiltRuntimePreview() {
        val prefs = getSharedPreferences("build_state", MODE_PRIVATE)
        val apk = prefs.getString("last_apk_path", null)?.let(::File)
        if (apk == null || !apk.isFile || apk.length() <= 0L) {
            status.text = "Preview APK not found. Build the project again."
            return
        }
        @Suppress("DEPRECATION")
        val archive = runCatching { packageManager.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull()
        val targetPackage = archive?.packageName?.takeIf { it.isNotBlank() }
        if (targetPackage == null) {
            status.text = "Could not read the package name from the built APK."
            return
        }
        if (targetPackage == packageName) {
            status.text = "The project uses Pocket APK Studio's own applicationId. Change it to a separate package before running a preview."
            return
        }
        @Suppress("DEPRECATION")
        val installed = runCatching { packageManager.getPackageInfo(targetPackage, 0) }.getOrNull()
        val installedApkPath = installed?.applicationInfo?.sourceDir
        val installedMatches = installedApkPath != null &&
            runCatching { sha256(File(installedApkPath)) == sha256(apk) }.getOrDefault(false)
        if (!installedMatches) {
            status.text = "The latest build is not installed. Opening Android's installer…"
            Toast.makeText(this, "Install this build, then return and tap Run Exact Device Preview.", Toast.LENGTH_LONG).show()
            installBuiltApk()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            status.text = "The installed project has no visible launcher activity. Check for an exported MAIN/LAUNCHER activity in AndroidManifest.xml."
            Toast.makeText(this, "No launcher activity found in the installed preview app.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            status.text = "Could not open preview app: ${error.message ?: error.javaClass.simpleName}"
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
        val savedStatus = prefs.getString("status", "Decompilation has not started.") ?: "Decompilation has not started."
        if (savedStatus != "Decompilation has not started." || prefs.getBoolean("running", false)) {
            analysisCard.visibility = View.VISIBLE
        }
        recoveryStatus.text = savedStatus
        val running = prefs.getBoolean("running", false)
        val failed = prefs.getBoolean("failed", false)
        recoveryStatus.setTextColor(when {
            running -> 0xFF78A5A8.toInt()
            prefs.getBoolean("partial", false) -> 0xFFFFC16B.toInt()
            failed -> 0xFFFF7777.toInt()
            prefs.getString("zip_path", null) != null -> 0xFF72C995.toInt()
            else -> 0xFFB6BBC9.toInt()
        })
        recoveryProgress.visibility = if (running) View.VISIBLE else View.GONE
        recoveryProgress.progress = prefs.getInt("percent", 0)
        recoveryPercentText.text = "${recoveryProgress.progress}%"
        recoveryStageText.text = prefs.getString("stage", if (running) "Working…" else "Waiting for decompilation…")
        recoveryLogText.text = prefs.getString("live_log", prefs.getString("log", "No process logs yet.")) ?: "No process logs yet."
        recoveryFilePath = prefs.getString("zip_path", null)
        if (running) startRecoveryProgressPolling() else recoveryHandler.removeCallbacks(recoveryPollTask)
        val file = recoveryFilePath?.let(::File)
        exportRecoveryButton.visibility = if (file?.isFile == true && file.length() > 0L) View.VISIBLE else View.GONE
    }

    private fun showDecompileModePopup(uri: Uri) {
        val labels = arrayOf(
            "Balanced — Full source + resources",
            "Fast — Skip resource decoding",
            "Full Recovery — Maximum ZIP compression",
            "Maximum Speed — Fastest processing"
        )
        val values = arrayOf("balanced", "fast", "full", "max_speed")
        val prefs = getSharedPreferences("recovery_state", MODE_PRIVATE)
        val saved = prefs.getString("decompile_mode", "balanced") ?: "balanced"
        var selected = values.indexOf(saved).coerceAtLeast(0)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Choose Decompile Mode")
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start Decompile") { _, _ ->
                prefs.edit().putString("decompile_mode", values[selected]).apply()
                startApkDecompilation(uri)
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF405968.toInt())
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF405968.toInt())
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    private fun startApkDecompilation(uri: Uri) {
        // The JADX progress UI lives inside analysisCard; reveal it when decompilation starts.
        analysisCard.visibility = View.VISIBLE
        recoveryStatus.text = "Preparing selected APK…"
        recoveryPercentText.text = "1%"
        recoveryStageText.text = "Preparing selected APK…"
        recoveryLogText.text = "Starting decompilation…"
        recoveryProgress.progress = 1
        recoveryProgress.visibility = View.VISIBLE
        exportRecoveryButton.visibility = View.GONE
        getSharedPreferences("recovery_state", MODE_PRIVATE).edit().clear()
            .putString("status", "Preparing selected APK…")
            .putBoolean("running", true)
            .apply()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { copyApkToRecoveryFolder(uri) }
                createRecoveryProgressFile()
                recoveryStatus.text = "APK copied. Starting JADX in Ubuntu…"
                getSharedPreferences("recovery_state", MODE_PRIVATE).edit()
                    .putString("status", "APK copied. Starting JADX in Ubuntu…")
                    .putInt("percent", 5).putString("stage", "APK copied. Connecting to Ubuntu…").apply()
                launchTermuxDecompile()
            } catch (e: Exception) {
                val message = "Could not prepare APK: ${e.message ?: "storage error"}"
                recoveryStatus.text = message
                recoveryProgress.visibility = View.GONE
                getSharedPreferences("recovery_state", MODE_PRIVATE).edit()
                    .putString("status", message).putString("stage", "Failed while preparing APK")
                    .putBoolean("running", false).putBoolean("failed", true).apply()
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

    private fun createRecoveryProgressFile() {
        val folder = Environment.DIRECTORY_DOWNLOADS + "/PocketAPKStudio/Recovery"
        val name = "decompile_progress.txt"
        contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, folder + "/"))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create live decompilation progress file")
        contentResolver.openOutputStream(uri).use { output ->
            requireNotNull(output).write("P|1|Starting decompilation…\n".toByteArray())
        }
        getSharedPreferences("recovery_state", MODE_PRIVATE).edit()
            .putString("progress_uri", uri.toString()).putInt("percent", 1)
            .putString("stage", "Starting decompilation…").putString("live_log", "Progress tracking initialized.")
            .apply()
    }

    private fun startRecoveryProgressPolling() {
        recoveryHandler.removeCallbacks(recoveryPollTask)
        recoveryHandler.post(recoveryPollTask)
    }

    private fun readRecoveryProgress() {
        if (!::recoveryLogText.isInitialized) return
        val prefs = getSharedPreferences("recovery_state", MODE_PRIVATE)
        val uriText = prefs.getString("progress_uri", null) ?: return
        try {
            val content = contentResolver.openInputStream(Uri.parse(uriText))?.bufferedReader()?.use { it.readText() }.orEmpty()
            var percent = prefs.getInt("percent", 1)
            var stage = prefs.getString("stage", "Working…") ?: "Working…"
            val logLines = mutableListOf<String>()
            content.lineSequence().forEach { line ->
                when {
                    line.startsWith("P|") -> {
                        val parts = line.split("|", limit = 3)
                        if (parts.size == 3) {
                            percent = parts[1].toIntOrNull()?.coerceIn(0, 100) ?: percent
                            stage = parts[2]
                        }
                    }
                    line.startsWith("L|") -> logLines.add(line.removePrefix("L|"))
                    line.isNotBlank() -> logLines.add(line)
                }
            }
            val log = logLines.takeLast(100).joinToString("\n").ifBlank { "Waiting for JADX output…" }
            recoveryProgress.progress = percent
            recoveryPercentText.text = "$percent%"
            recoveryStageText.text = stage
            recoveryLogText.text = log
            prefs.edit().putInt("percent", percent).putString("stage", stage).putString("live_log", log).apply()
        } catch (_: Exception) {
            recoveryStageText.text = "Reading live progress…"
        }
    }

    private fun launchTermuxDecompile() {
        val selectedMode = getSharedPreferences("recovery_state", MODE_PRIVATE).getString("decompile_mode", "balanced") ?: "balanced"
        val modeArgs = if (selectedMode == "fast" || selectedMode == "max_speed") "--no-res" else ""
        val zipLevel = when (selectedMode) { "fast", "max_speed" -> "-0"; "full" -> "-9"; else -> "-1" }
        val command = """
            set -e
            SHARED="/storage/emulated/0"
            RECOVERY="@D@SHARED/Download/PocketAPKStudio/Recovery"
            if [ ! -d "@D@RECOVERY" ] || [ ! -r "@D@RECOVERY/customer-manager.apk" ]; then
              echo "ERROR: Termux cannot access shared Downloads. Open Termux and run: termux-setup-storage, then allow storage permission and retry."
              exit 24
            fi
            proot-distro login --bind "@D@SHARED:/mnt/shared" ubuntu -- bash -lc '
              set -e
              PROGRESS="/mnt/shared/Download/PocketAPKStudio/Recovery/decompile_progress.txt"
              progress() { printf "P|%s|%s\n" "@D@1" "@D@2" >> "@D@PROGRESS"; }
              logline() { printf "L|%s\n" "@D@*" >> "@D@PROGRESS"; }
              progress 5 "Connecting to Ubuntu"
              logline "Ubuntu session started."
              command -v java >/dev/null || { logline "ERROR: Java is required in Ubuntu"; exit 21; }
              progress 10 "Checking JADX and required tools"
              for tool in curl unzip zip; do
                if ! command -v "@D@tool" >/dev/null 2>&1; then
                  logline "Installing missing tool: @D@tool"
                  apt-get update
                  apt-get install -y "@D@tool"
                fi
              done
              JADXD="@D@HOME/.local/share/jadx-1.5.1"
              if [ ! -x "@D@JADXD/bin/jadx" ]; then
                progress 18 "Downloading JADX 1.5.1"
                logline "Downloading JADX from official GitHub release."
                mkdir -p "@D@JADXD"
                curl -L --fail --show-error "https://github.com/skylot/jadx/releases/download/v1.5.1/jadx-1.5.1.zip" -o /tmp/jadx-1.5.1.zip
                progress 28 "Extracting JADX tools"
                unzip -q -o /tmp/jadx-1.5.1.zip -d "@D@JADXD"
              else
                progress 28 "JADX already installed"
                logline "Using existing JADX installation."
              fi
              INPUT="/mnt/shared/Download/PocketAPKStudio/Recovery/customer-manager.apk"
              test -s "@D@INPUT" || { logline "ERROR: selected APK not found"; exit 22; }
              progress 35 "Validating selected APK"
              logline "APK input validated."
              OUT="@D@HOME/pocket-apk-studio/recovered_customer_manager"
              rm -rf "@D@OUT"
              mkdir -p "@D@OUT"
              CPU_THREADS=@D@(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)
              MEM_AVAILABLE_KB=@D@(grep MemAvailable /proc/meminfo 2>/dev/null | tr -s " " | cut -d " " -f 2)
              MEM_AVAILABLE_MB=@D@((MEM_AVAILABLE_KB / 1024))
              if [ "@D@MEM_AVAILABLE_MB" -lt 2048 ]; then
                THREADS=1
              elif [ "@D@MEM_AVAILABLE_MB" -lt 4096 ]; then
                THREADS=2
              elif [ "${selectedMode}" = "max_speed" ]; then
                THREADS=@D@CPU_THREADS
                [ "@D@THREADS" -gt 6 ] && THREADS=6
              else
                THREADS=@D@((CPU_THREADS / 2))
                [ "@D@THREADS" -lt 1 ] && THREADS=1
                [ "@D@THREADS" -gt 4 ] && THREADS=4
              fi
              logline "Performance profile: @D@THREADS JADX threads; @D@MEM_AVAILABLE_MB MB available RAM; @D@CPU_THREADS CPU threads detected."
              progress 42 "Decompiling DEX and resources with JADX"
              logline "JADX started. Live tool output and resource monitoring follow."
              rm -f /tmp/pocket-jadx.log
              logline "Selected mode: ${selectedMode}; fast mode skips resource decoding."
              "@D@JADXD/bin/jadx" --show-bad-code --threads-count "@D@THREADS" ${modeArgs} -d "@D@OUT" "@D@INPUT" > /tmp/pocket-jadx.log 2>&1 &
              JAD_PID=@D@!
              tail -n 0 -f /tmp/pocket-jadx.log >> "@D@PROGRESS" &
              TAIL_PID=@D@!
              (
                while kill -0 "@D@JAD_PID" 2>/dev/null; do
                  sleep 15
                  kill -0 "@D@JAD_PID" 2>/dev/null || break
                  FILE_COUNT=@D@(find "@D@OUT" -type f 2>/dev/null | wc -l | tr -d " ")
                  MEM_AVAILABLE_KB=@D@(grep MemAvailable /proc/meminfo 2>/dev/null | tr -s " " | cut -d " " -f 2)
                  MEM_AVAILABLE_MB=@D@((MEM_AVAILABLE_KB / 1024))
                  LOAD_AVG=@D@(tr " " "\n" < /proc/loadavg 2>/dev/null | head -n 3 | paste -sd "," - || echo unavailable)
                  logline "Live monitor: @D@FILE_COUNT files generated; @D@MEM_AVAILABLE_MB MB RAM available; CPU load @D@LOAD_AVG."
                done
              ) &
              MON_PID=@D@!
              set +e
              wait "@D@JAD_PID"
              JAD_EXIT=@D@?
              kill "@D@TAIL_PID" "@D@MON_PID" >/dev/null 2>&1
              set -e
              PARTIAL=0
              if [ "@D@JAD_EXIT" -ne 0 ]; then
                GENERATED_COUNT=@D@(find "@D@OUT" -type f 2>/dev/null | wc -l | tr -d " ")
                logline "JADX returned exit code @D@JAD_EXIT after processing. Generated files: @D@GENERATED_COUNT."
                if [ "@D@GENERATED_COUNT" -gt 0 ]; then
                  PARTIAL=1
                  logline "WARNING: Continuing with partial recovery because JADX produced usable files."
                else
                  logline "ERROR: JADX produced no recoverable files."
                  cat /tmp/pocket-jadx.log
                  exit "@D@JAD_EXIT"
                fi
              else
                logline "JADX finished successfully."
              fi
              progress 82 "Decompilation finished. Preparing ZIP archive"
              OUTPUT="/mnt/shared/Download/PocketAPKStudio/Recovery/CustomerManager-Recovered.zip"
              rm -f "@D@OUTPUT"
              cd "@D@(dirname "@D@OUT")"
              zip ${zipLevel} -qr "@D@OUTPUT" "@D@(basename "@D@OUT")"
              progress 95 "Verifying recovered ZIP"
              test -s "@D@OUTPUT" || { logline "ERROR: recovered ZIP was not created"; exit 23; }
              logline "Recovered ZIP verified."
              echo "RECOVERY_SAVED=/storage/emulated/0/Download/PocketAPKStudio/Recovery/CustomerManager-Recovered.zip"
              if [ "@D@PARTIAL" -eq 1 ]; then
                echo "JADX_PARTIAL=1"
                progress 100 "Partial recovery ZIP created"
                logline "PARTIAL RECOVERY: ZIP created with available files; inspect JADX errors in the logs."
              else
                echo "JADX_DECOMPILE_SUCCESS=1"
                progress 100 "Decompilation successful"
                logline "SUCCESS: recovered source ZIP is ready."
              fi
            '
        """.trimIndent().replace("@D@", "$")
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
                .putString("status", "JADX is running in Ubuntu. Live progress is being tracked.")
                .putString("stage", "Connecting to Ubuntu…").putInt("percent", 1)
                .putBoolean("running", true).putBoolean("failed", false).apply()
            startService(intent)
            recoveryStatus.text = "JADX is running in Ubuntu. Live progress is being tracked."
            recoveryProgress.visibility = View.VISIBLE
            startRecoveryProgressPolling()
        } catch (e: Exception) {
            val message = "Could not start JADX: ${e.message ?: "Termux permission or service error"}"
            recoveryStatus.text = message
            recoveryProgress.visibility = View.GONE
            getSharedPreferences("recovery_state", MODE_PRIVATE).edit()
                .putString("status", message).putString("stage", "Failed to start JADX")
                .putBoolean("running", false).putBoolean("failed", true).apply()
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
        analysisComponentsTitle.visibility = View.GONE
        analysisComponents.visibility = View.GONE
        analysisPermissionsTitle.visibility = View.GONE
        analysisPermissions.visibility = View.GONE
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
                analysisComponentsTitle.visibility = View.VISIBLE
                analysisComponents.visibility = View.VISIBLE
                analysisPermissionsTitle.visibility = View.VISIBLE
                analysisPermissions.visibility = View.VISIBLE
                analysisStatus.text = "Analysis completed successfully."
            } catch (e: Exception) {
                analysisStatus.text = "Analysis failed."
                analysisDetails.text = e.message ?: "Could not analyze APK."
                analysisComponents.text = "No APK content data available."
                analysisPermissions.text = "No permissions available."
                analysisComponentsTitle.visibility = View.VISIBLE
                analysisComponents.visibility = View.VISIBLE
                analysisPermissionsTitle.visibility = View.VISIBLE
                analysisPermissions.visibility = View.VISIBLE
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
