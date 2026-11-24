package com.pocketapkstudio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.FileProvider
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Approximate source inspector plus a build-and-run handoff for exact on-device runtime preview. */
class LivePreviewActivity : Activity() {
    private lateinit var projectRoot: File
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var previewScroll: ScrollView
    private lateinit var previewHorizontal: HorizontalScrollView
    private lateinit var deviceViewport: FrameLayout
    private lateinit var deviceContentScroll: ScrollView
    private var previewWidthDp = 360
    private var previewHeightDp = 800
    private var selectedPreviewPath: String? = null
    private var previewDarkThemeOverride: Boolean? = null
    private lateinit var themeButton: Button
    private var webPreviewView: android.webkit.WebView? = null
    private var layouts: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewWidthDp = (savedInstanceState?.getInt("preview_width_dp", 360) ?: 360).coerceIn(240, 1200)
        previewHeightDp = (savedInstanceState?.getInt("preview_height_dp", 800) ?: 800).coerceIn(320, 2400)
        selectedPreviewPath = savedInstanceState?.getString("preview_source_path")
        previewDarkThemeOverride = when (savedInstanceState?.getInt("preview_theme_mode", 0) ?: 0) {
            1 -> true
            2 -> false
            else -> null
        }
        projectRoot = File(intent.getStringExtra("project_root") ?: "")
        if (!projectRoot.isDirectory) { Toast.makeText(this, "Project folder unavailable.", Toast.LENGTH_LONG).show(); finish(); return }
        buildScreen()

        val restoredFile = selectedPreviewPath?.let { relative ->
            runCatching {
                File(projectRoot, relative).canonicalFile.takeIf { candidate ->
                    candidate.path.startsWith(projectRoot.canonicalPath + File.separator) && candidate.isFile
                }
            }.getOrNull()
        }
        when (restoredFile?.extension?.lowercase()) {
            "xml" -> {
                layouts = projectRoot.walkTopDown().maxDepth(10)
                    .filter { it.isFile && it.extension.equals("xml", true) && it.parentFile?.name == "layout" &&
                        it.path.split(File.separator).none { part -> part in setOf(".gradle", "build", ".git", "node_modules") } }
                    .take(200).sortedBy { it.relativeTo(projectRoot).invariantSeparatorsPath }.toList()
                render(restoredFile)
            }
            "kt" -> renderComposeFile(restoredFile)
            else -> if (!renderWebPreviewIfAvailable()) findLayouts()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("preview_width_dp", previewWidthDp)
        outState.putInt("preview_height_dp", previewHeightDp)
        outState.putString("preview_source_path", selectedPreviewPath)
        outState.putInt("preview_theme_mode", when (previewDarkThemeOverride) {
            true -> 1
            false -> 2
            null -> 0
        })
        super.onSaveInstanceState(outState)
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xFF121922.toInt()) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply { text = "‹ Back"; isAllCaps = false; setOnClickListener { finish() } }, LinearLayout.LayoutParams(-2, dp(44)))
        title = TextView(this).apply { text = "SOURCE PREVIEW"; textSize = 17f; setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0) }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "☷"; contentDescription = "Choose screen"; setOnClickListener { chooseLayout() } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        header.addView(Button(this).apply { text = "↻"; contentDescription = "Refresh preview"; setOnClickListener { refreshCurrentPreview() } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)
        root.addView(TextView(this).apply { text = "Approximate source view only. Build & Run creates the APK; install it and launch it for exact Android rendering."; textSize = 11f; setTextColor(0xFFFFD28A.toInt()); setPadding(dp(4), dp(8), dp(4), dp(6)) })
        val deviceControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun deviceButton(label: String, width: Int, height: Int, rotate: Boolean = false) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                if (rotate) {
                    val oldWidth = previewWidthDp
                    previewWidthDp = previewHeightDp
                    previewHeightDp = oldWidth
                } else {
                    previewWidthDp = width
                    previewHeightDp = height
                }
                applyDeviceProfile()
            }
        }
        deviceControls.addView(deviceButton("Compact", 320, 740), LinearLayout.LayoutParams(0, dp(42), 1f))
        deviceControls.addView(deviceButton("Phone", 360, 800), LinearLayout.LayoutParams(0, dp(42), 1f))
        deviceControls.addView(deviceButton("Tablet", 600, 900), LinearLayout.LayoutParams(0, dp(42), 1f))
        deviceControls.addView(deviceButton("Rotate", 0, 0, true), LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(deviceControls, LinearLayout.LayoutParams(-1, -2))
        val previewOptions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previewOptions.addView(Button(this).apply {
            text = "Custom size"
            isAllCaps = false
            contentDescription = "Set a custom device preview width and height in dp"
            setOnClickListener { showCustomDeviceDialog() }
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(4) })
        themeButton = Button(this).apply {
            text = previewThemeLabel()
            isAllCaps = false
            contentDescription = "Cycle between system, dark and light Compose preview themes"
            setOnClickListener {
                previewDarkThemeOverride = when (previewDarkThemeOverride) {
                    null -> true
                    true -> false
                    false -> null
                }
                text = previewThemeLabel()
                if (selectedPreviewPath?.endsWith(".kt", ignoreCase = true) == true) {
                    refreshCurrentPreview()
                } else {
                    Toast.makeText(this@LivePreviewActivity, "Theme mode will apply to Compose source previews.", Toast.LENGTH_SHORT).show()
                }
            }
        }
        previewOptions.addView(themeButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(4) })
        root.addView(previewOptions, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(3)
            bottomMargin = dp(4)
        })
        val buildRunButton = Button(this).apply {
            text = "Build & Run · Exact Device Preview"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2563EB.toInt())
            setOnClickListener { exportProjectForDeviceRun() }
        }
        root.addView(buildRunButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7); bottomMargin = dp(8) })
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFFE0E5EC.toInt()) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        deviceContentScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        deviceViewport = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0xFF8795A7.toInt())
            }
            clipToOutline = true
            addView(deviceContentScroll, FrameLayout.LayoutParams(-1, -1))
        }
        previewHorizontal = HorizontalScrollView(this).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(deviceViewport, FrameLayout.LayoutParams(dp(previewWidthDp), dp(previewHeightDp)))
        }
        previewScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(previewHorizontal, FrameLayout.LayoutParams(-1, -2))
        }
        frame.addView(previewScroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        applyDeviceProfile()
    }

    /**
     * A true source preview must compile and run the imported app. Export the edited
     * project to the existing Termux build pipeline rather than pretending that
     * statically rendered Android Views execute the user's Compose code.
     */
    private fun exportProjectForDeviceRun() {
        try {
            val output = File(cacheDir, "runtime-preview-project.zip")
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                projectRoot.walkTopDown()
                    .filter { file ->
                        file.isFile && file.path.split(File.separator).none {
                            it in setOf(".gradle", "build", ".git", "node_modules")
                        }
                    }
                    .forEach { file ->
                        val entryName = file.relativeTo(projectRoot).invariantSeparatorsPath
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().buffered().use { input -> input.copyTo(zip) }
                        zip.closeEntry()
                    }
            }
            check(output.isFile && output.length() > 0L) { "Project ZIP is empty." }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", output)
            setResult(RESULT_OK, android.content.Intent().setData(uri)
                .putExtra("runtime_preview_build", true)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            Toast.makeText(this, "Project sent to the real Android build pipeline.", Toast.LENGTH_LONG).show()
            finish()
        } catch (error: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Could not prepare runtime build")
                .setMessage(error.message ?: "Unknown ZIP export error")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showCustomDeviceDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(4))
        }
        val widthInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "Width: 240–1200 dp"
            setText(previewWidthDp.toString())
        }
        val heightInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            hint = "Height: 320–2400 dp"
            setText(previewHeightDp.toString())
        }
        form.addView(TextView(this).apply { text = "Width (dp)"; textSize = 12f })
        form.addView(widthInput, LinearLayout.LayoutParams(-1, dp(48)))
        form.addView(TextView(this).apply { text = "Height (dp)"; textSize = 12f; setPadding(0, dp(8), 0, 0) })
        form.addView(heightInput, LinearLayout.LayoutParams(-1, dp(48)))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Custom device viewport")
            .setView(form)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val width = widthInput.text.toString().toIntOrNull()
                val height = heightInput.text.toString().toIntOrNull()
                if (width == null || width !in 240..1200) {
                    widthInput.error = "Enter 240–1200 dp"
                    return@setOnClickListener
                }
                if (height == null || height !in 320..2400) {
                    heightInput.error = "Enter 320–2400 dp"
                    return@setOnClickListener
                }
                previewWidthDp = width
                previewHeightDp = height
                applyDeviceProfile()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun previewThemeLabel(): String = "Theme: " + when (previewDarkThemeOverride) {
        true -> "Dark"
        false -> "Light"
        null -> "System"
    }

    private fun applyDeviceProfile() {
        if (!::deviceViewport.isInitialized) return
        deviceViewport.layoutParams = (deviceViewport.layoutParams ?: FrameLayout.LayoutParams(
            dp(previewWidthDp), dp(previewHeightDp)
        )).apply {
            width = dp(previewWidthDp)
            height = dp(previewHeightDp)
        }
        content.layoutParams = (content.layoutParams ?: FrameLayout.LayoutParams(-1, -2)).apply {
            width = -1
            height = -2
        }
        content.requestLayout()
        deviceViewport.requestLayout()
        title.text = "LIVE UI PREVIEW · ${previewWidthDp}×${previewHeightDp} dp"
        deviceContentScroll.post { deviceContentScroll.scrollTo(0, 0) }
        previewHorizontal.post { previewHorizontal.scrollTo(0, 0) }
        previewScroll.post { previewScroll.scrollTo(0, 0) }
    }

    private fun clearPreviewContent() {
        content.removeAllViews()
        val previousWebView = webPreviewView
        webPreviewView = null
        if (previousWebView != null) {
            runCatching {
                previousWebView.stopLoading()
                previousWebView.webChromeClient = null
                previousWebView.webViewClient = android.webkit.WebViewClient()
                previousWebView.removeAllViews()
                previousWebView.destroy()
            }
        }
    }

    /**
     * Render bundled web applications with Android WebView instead of trying to
     * interpret their HTML/React UI as native XML or Kotlin source.
     */
    private fun renderWebPreviewIfAvailable(): Boolean {
        val candidates = listOf(
            File(projectRoot, "app/src/main/assets/index.html"),
            File(projectRoot, "src/main/assets/index.html"),
            File(projectRoot, "index.html"),
            File(projectRoot, "web-source/index.html")
        )
        val entry = candidates.firstOrNull { it.isFile } ?: return false
        clearPreviewContent()
        selectedPreviewPath = entry.relativeTo(projectRoot).invariantSeparatorsPath
        title.text = "LIVE WEB UI PREVIEW · " + entry.name
        val baseDirectory = entry.parentFile ?: projectRoot
        val relativeDirectory = baseDirectory.relativeTo(projectRoot).invariantSeparatorsPath
        val baseUrl = "https://appassets.androidplatform.net/project/" +
            (if (relativeDirectory.isBlank() || relativeDirectory == ".") "" else "$relativeDirectory/")

        val web = android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setBackgroundColor(Color.WHITE)
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(
                    view: android.webkit.WebView,
                    request: android.webkit.WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    val uri = request.url
                    if (uri.host != "appassets.androidplatform.net" || !uri.path.orEmpty().startsWith("/project/")) {
                        return super.shouldInterceptRequest(view, request)
                    }
                    return try {
                        val relative = uri.path.orEmpty().removePrefix("/project/").substringBefore('?')
                        val target = File(projectRoot, relative).canonicalFile
                        if (!target.path.startsWith(projectRoot.canonicalPath + File.separator) || !target.isFile) {
                            android.webkit.WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                                mapOf("Access-Control-Allow-Origin" to "*"), "Preview asset not found".byteInputStream())
                        } else {
                            val mime = when (target.extension.lowercase()) {
                                "js", "mjs" -> "text/javascript"
                                "css" -> "text/css"
                                "html", "htm" -> "text/html"
                                "json", "map" -> "application/json"
                                "svg" -> "image/svg+xml"
                                "png" -> "image/png"
                                "jpg", "jpeg" -> "image/jpeg"
                                "webp" -> "image/webp"
                                "gif" -> "image/gif"
                                "woff" -> "font/woff"
                                "woff2" -> "font/woff2"
                                "ttf" -> "font/ttf"
                                "otf" -> "font/otf"
                                else -> "application/octet-stream"
                            }
                            android.webkit.WebResourceResponse(mime, null, target.inputStream())
                        }
                    } catch (_: Exception) {
                        null
                    }
                }

                override fun onReceivedError(
                    view: android.webkit.WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError
                ) {
                    if (request.isForMainFrame) runOnUiThread {
                        showMessage("Web preview could not load: " + error.description +
                            "\\nCheck that the project's bundled HTML and asset files are present.")
                    }
                }
            }
            webChromeClient = android.webkit.WebChromeClient()
            layoutParams = LinearLayout.LayoutParams(-1, dp(previewHeightDp))
        }
        content.addView(web)
        webPreviewView = web
        return try {
            web.loadDataWithBaseURL(baseUrl, entry.readText(), "text/html", "UTF-8", null)
            true
        } catch (error: Exception) {
            showMessage("Could not open web preview: " + (error.message ?: error.javaClass.simpleName))
            true
        }
    }

    private fun findLayouts() {
        if (renderWebPreviewIfAvailable()) return

        // Prefer the actual launcher Activity's Compose entry point over unrelated XML
        // layouts that may remain in projects after a migration from Views to Compose.
        val composeEntry = findLauncherComposeSource()
        if (composeEntry != null) {
            layouts = emptyList()
            renderComposeFile(composeEntry)
            return
        }

        layouts = projectRoot.walkTopDown().maxDepth(10)
            .filter { it.isFile && it.extension.equals("xml", true) && it.parentFile?.name == "layout" &&
                it.path.split(File.separator).none { part -> part in setOf(".gradle", "build", ".git", "node_modules") } }
            .take(200).sortedBy { it.relativeTo(projectRoot).invariantSeparatorsPath }.toList()
        if (layouts.isEmpty()) { renderSourcePreview(); return }
        val preferred = findLauncherLayout()
        val first = preferred ?: layouts.firstOrNull { it.name == "activity_main.xml" }
            ?: layouts.firstOrNull { it.name == "main.xml" } ?: layouts.first()
        render(first)
    }

    private fun findLauncherComposeSource(): File? {
        val manifests = projectRoot.walkTopDown().maxDepth(8)
            .filter { it.isFile && it.name == "AndroidManifest.xml" &&
                it.path.split(File.separator).none { part -> part in setOf(".gradle", "build", ".git") } }
            .take(10).toList()
        for (manifest in manifests) {
            val xml = runCatching { manifest.readText() }.getOrNull() ?: continue
            val launcher = Regex("""<activity\b([^>]*)>([\s\S]*?)</activity>""")
                .findAll(xml).firstOrNull {
                    it.groupValues[2].contains("android.intent.action.MAIN") &&
                    it.groupValues[2].contains("android.intent.category.LAUNCHER")
                } ?: continue
            val activityName = Regex("""android:name=["']([^"']+)["']""")
                .find(launcher.groupValues[1])?.groupValues?.get(1) ?: continue
            val simpleName = activityName.substringAfterLast('.').removePrefix(".")
            val sourceFiles = projectRoot.walkTopDown().maxDepth(12)
                .filter { it.isFile && it.extension == "kt" && it.nameWithoutExtension == simpleName &&
                    it.path.split(File.separator).none { part -> part in setOf(".gradle", "build", ".git", "node_modules") } }
                .take(5).toList()
            for (source in sourceFiles) {
                val code = runCatching { source.readText() }.getOrNull() ?: continue
                if (Regex("""\bsetContent\s*\(""").containsMatchIn(code) || code.contains("ComposeView(")) {
                    return source
                }
            }
        }
        return null
    }

    private fun findLauncherLayout(): File? {
        val manifests = projectRoot.walkTopDown().maxDepth(8)
            .filter { it.isFile && it.name == "AndroidManifest.xml" &&
                it.path.split(File.separator).none { p -> p in setOf(".gradle", "build", ".git") } }
            .take(10).toList()
        for (manifest in manifests) {
            val xml = runCatching { manifest.readText() }.getOrNull() ?: continue
            val launcher = Regex("""<activity\b([^>]*)>([\s\S]*?)</activity>""")
                .findAll(xml).firstOrNull {
                    it.groupValues[2].contains("android.intent.action.MAIN") &&
                    it.groupValues[2].contains("android.intent.category.LAUNCHER")
                } ?: continue
            val activityName = Regex("""android:name=["']([^"']+)["']""")
                .find(launcher.groupValues[1])?.groupValues?.get(1) ?: continue
            val simpleName = activityName.substringAfterLast('.').removePrefix(".")
            val sourceFiles = projectRoot.walkTopDown().maxDepth(12)
                .filter { it.isFile && it.extension == "kt" && it.nameWithoutExtension == simpleName &&
                    it.path.split(File.separator).none { p -> p in setOf(".gradle", "build", ".git", "node_modules") } }
                .take(5).toList()
            for (source in sourceFiles) {
                val code = runCatching { source.readText() }.getOrNull() ?: continue
                val layoutName = Regex("""setContentView\s*\(\s*R\.layout\.(\w+)\s*\)""")
                    .find(code)?.groupValues?.get(1)
                if (layoutName != null) return layouts.firstOrNull { it.nameWithoutExtension == layoutName }
                if (code.contains("setContent") || code.contains("@Composable")) return null
            }
        }
        return null
    }

    private fun refreshCurrentPreview() {
        val selected = selectedPreviewPath?.let { relative ->
            runCatching {
                File(projectRoot, relative).canonicalFile.takeIf { candidate ->
                    candidate.path.startsWith(projectRoot.canonicalPath + File.separator) && candidate.isFile
                }
            }.getOrNull()
        }
        clearPreviewContent()
        when (selected?.extension?.lowercase()) {
            "xml" -> render(selected)
            "kt" -> renderComposeFile(selected)
            "html", "htm" -> if (!renderWebPreviewIfAvailable()) findLayouts()
            else -> if (!renderWebPreviewIfAvailable()) findLayouts()
        }
    }

    /** Static Compose source preview. It approximates common composables without executing app code. */
    private fun renderSourcePreview() {
        clearPreviewContent()
        val sources = projectRoot.walkTopDown().maxDepth(10).filter {
            it.isFile && it.extension == "kt" && it.length() < 2_000_000L &&
                it.path.split(File.separator).none { part -> part in setOf(".gradle", "build", ".git", "node_modules", "generated") }
        }.toList()
        val source = sources.firstOrNull { it.name == "MainActivity.kt" && runCatching { it.readText().contains("setContent") }.getOrDefault(false) }
            ?: sources.firstOrNull { runCatching { it.readText().contains("@Composable") }.getOrDefault(false) }
        if (source == null) {
            title.text = "SOURCE PREVIEW · APPROXIMATE"
            showMessage("No Compose Kotlin source found. Add a MainActivity or @Composable screen.")
            return
        }
        renderComposeFile(source)
    }

    /**
     * Follow composable function calls from the selected launcher/source file.
     * This lets the approximate preview see screens split into App.kt, HomeScreen.kt,
     * and other Kotlin files instead of rendering only the Activity wrapper.
     */
    private fun resolveComposePreviewSources(entry: File): List<File> {
        val candidateFiles = projectRoot.walkTopDown().maxDepth(12)
            .filter { file ->
                file.isFile && file.extension == "kt" && file.length() <= 2_000_000L &&
                    file.path.split(File.separator).none { part ->
                        part in setOf(".gradle", "build", ".git", "node_modules", ".idea", "generated", ".dart_tool")
                    }
            }
            .take(250)
            .sortedBy { it.relativeTo(projectRoot).invariantSeparatorsPath }
            .toList()

        val sourceText = LinkedHashMap<File, String>()
        var readBudget = 16L * 1024L * 1024L
        for (candidate in candidateFiles) {
            val size = candidate.length()
            if (size > readBudget) continue
            val text = runCatching { candidate.readText() }.getOrNull() ?: continue
            sourceText[candidate.canonicalFile] = text
            readBudget -= size
        }
        val entryFile = entry.canonicalFile
        if (!sourceText.containsKey(entryFile)) {
            sourceText[entryFile] = entry.readText()
        }

        val composableDeclaration = Regex("""@Composable[\s\S]{0,240}?\bfun\s+([A-Za-z_][A-Za-z0-9_]*)""")
        val declarations = sourceText.entries.flatMap { (file, text) ->
            composableDeclaration.findAll(text).map { match ->
                match.groupValues[1] to file
            }.toList()
        }.distinctBy { it.first }

        val included = linkedSetOf(entryFile)
        for (pass in 0 until 8) {
            val combined = included.mapNotNull { sourceText[it] }.joinToString("\n\n")
            var added = false
            for ((functionName, declarationFile) in declarations) {
                if (declarationFile in included || included.size >= 30) continue
                val callPattern = Regex("""\b""" + Regex.escape(functionName) + """\s*\(""")
                if (callPattern.containsMatchIn(combined)) {
                    included.add(declarationFile)
                    added = true
                }
            }
            if (!added || included.size >= 30) break
        }
        return included.mapNotNull { file ->
            if (file.isFile) file else null
        }
    }

    private fun renderComposeFile(source: File) {
        clearPreviewContent()
        selectedPreviewPath = source.relativeTo(projectRoot).invariantSeparatorsPath
        title.text = "SOURCE PREVIEW · " + source.name
        try {
            val sourceFiles = resolveComposePreviewSources(source)
            val code = sourceFiles.joinToString("\n\n") { it.readText() }
            // Preserve source offsets while blanking strings and comments. This prevents
            // example code inside comments or quoted text from becoming fake preview nodes.
            fun maskKotlinStructure(sourceText: String): String {
                val chars = sourceText.toCharArray()
                var index = 0
                var inString = false
                var inRawString = false
                var inCharacter = false
                var escaped = false
                var lineComment = false
                var blockCommentDepth = 0
                while (index < sourceText.length) {
                    val ch = sourceText[index]
                    when {
                        lineComment -> {
                            if (ch != '\n' && ch != '\r') chars[index] = ' '
                            if (ch == '\n') lineComment = false
                            index++
                        }
                        blockCommentDepth > 0 -> {
                            when {
                                sourceText.startsWith("/*", index) -> {
                                    chars[index] = ' '
                                    chars[index + 1] = ' '
                                    blockCommentDepth++
                                    index += 2
                                }
                                sourceText.startsWith("*/", index) -> {
                                    chars[index] = ' '
                                    chars[index + 1] = ' '
                                    blockCommentDepth--
                                    index += 2
                                }
                                else -> {
                                    if (ch != '\n' && ch != '\r') chars[index] = ' '
                                    index++
                                }
                            }
                        }
                        inRawString -> {
                            if (sourceText.startsWith("\"\"\"", index)) {
                                chars[index] = ' '
                                chars[index + 1] = ' '
                                chars[index + 2] = ' '
                                inRawString = false
                                index += 3
                            } else {
                                if (ch != '\n' && ch != '\r') chars[index] = ' '
                                index++
                            }
                        }
                        inString -> {
                            if (escaped) {
                                if (ch != '\n' && ch != '\r') chars[index] = ' '
                                escaped = false
                                index++
                            } else when (ch) {
                                '\\' -> {
                                    chars[index] = ' '
                                    escaped = true
                                    index++
                                }
                                '"' -> {
                                    chars[index] = ' '
                                    inString = false
                                    index++
                                }
                                else -> {
                                    if (ch != '\n' && ch != '\r') chars[index] = ' '
                                    index++
                                }
                            }
                        }
                        inCharacter -> {
                            if (escaped) {
                                if (ch != '\n' && ch != '\r') chars[index] = ' '
                                escaped = false
                                index++
                            } else when (ch) {
                                '\\' -> {
                                    chars[index] = ' '
                                    escaped = true
                                    index++
                                }
                                '\'' -> {
                                    chars[index] = ' '
                                    inCharacter = false
                                    index++
                                }
                                else -> {
                                    if (ch != '\n' && ch != '\r') chars[index] = ' '
                                    index++
                                }
                            }
                        }
                        sourceText.startsWith("//", index) -> {
                            chars[index] = ' '
                            chars[index + 1] = ' '
                            lineComment = true
                            index += 2
                        }
                        sourceText.startsWith("/*", index) -> {
                            chars[index] = ' '
                            chars[index + 1] = ' '
                            blockCommentDepth = 1
                            index += 2
                        }
                        sourceText.startsWith("\"\"\"", index) -> {
                            chars[index] = ' '
                            chars[index + 1] = ' '
                            chars[index + 2] = ' '
                            inRawString = true
                            index += 3
                        }
                        ch == '"' -> {
                            chars[index] = ' '
                            inString = true
                            index++
                        }
                        ch == '\'' -> {
                            chars[index] = ' '
                            inCharacter = true
                            index++
                        }
                        else -> index++
                    }
                }
                return String(chars)
            }
            val structuralCode = maskKotlinStructure(code)
            val screen = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setBackgroundColor(0xFFF5F7FF.toInt())
            }
            val colorMap = HashMap<String, Int>()
            Regex("""(?:private\s+)?val\s+(\w+)\s*=\s*Color\(\s*0x([0-9A-Fa-f]{8})[Ll]?\s*\)""").findAll(structuralCode).forEach {
                runCatching {
                    colorMap[it.groupValues[1]] = android.graphics.Color.parseColor("#" + it.groupValues[2])
                }
            }
            fun findClosing(text: String, open: Int, left: Char, right: Char): Int {
                var depth = 0
                var quoted = false
                var escaped = false
                for (i in open until text.length) {
                    val ch = text[i]
                    if (quoted) {
                        if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
                    } else {
                        if (ch == '"') quoted = true
                        else if (ch == left) depth++
                        else if (ch == right && --depth == 0) return i
                    }
                }
                return -1
            }
            fun schemeColors(functionName: String): Map<String, Int> {
                val match = Regex("""\b""" + Regex.escape(functionName) + """\s*\(""")
                    .find(structuralCode) ?: return emptyMap()
                val open = structuralCode.indexOf('(', match.range.first)
                val close = findClosing(structuralCode, open, '(', ')')
                if (close <= open) return emptyMap()
                val arguments = code.substring(open + 1, close)
                val colors = LinkedHashMap<String, Int>()
                val parameter = Regex(
                    """\b([A-Za-z_]\w*)\s*=\s*(?:Color\(\s*0x([0-9A-Fa-f]{8})[Ll]?\s*\)|([A-Za-z_]\w*))"""
                )
                parameter.findAll(arguments).forEach { entry ->
                    val hex = entry.groupValues[2]
                    val reference = entry.groupValues[3]
                    val value = if (hex.isNotBlank()) {
                        runCatching { Color.parseColor("#$hex") }.getOrNull()
                    } else {
                        colorMap[reference]
                    }
                    if (value != null) colors[entry.groupValues[1]] = value
                }
                return colors
            }
            val lightScheme = schemeColors("lightColorScheme")
            val darkScheme = schemeColors("darkColorScheme")
            val systemDark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val usesDarkTheme = previewDarkThemeOverride ?: when {
                darkScheme.isNotEmpty() && lightScheme.isEmpty() -> true
                lightScheme.isNotEmpty() && darkScheme.isNotEmpty() -> systemDark
                else -> systemDark && darkScheme.isNotEmpty()
            }
            colorMap.putAll(if (usesDarkTheme) darkScheme.ifEmpty { lightScheme } else lightScheme.ifEmpty { darkScheme })
            val themeBackground = colorMap["background"] ?: colorMap["surface"] ?:
                if (usesDarkTheme) 0xFF171B24.toInt() else 0xFFF5F7FF.toInt()
            val themeOnBackground = colorMap["onSurface"] ?: colorMap["onBackground"] ?:
                if (usesDarkTheme) Color.WHITE else 0xFF263449.toInt()
            val stringResources = HashMap<String, String>()
            projectRoot.walkTopDown().maxDepth(10)
                .filter { file ->
                    file.isFile && file.name.equals("strings.xml", true) &&
                        file.parentFile?.name?.startsWith("values") == true
                }
                .take(50)
                .forEach { resourceFile ->
                    runCatching {
                        val parser = Xml.newPullParser()
                        resourceFile.inputStream().use { input ->
                            parser.setInput(input, "UTF-8")
                            var currentName: String? = null
                            val value = StringBuilder()
                            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                                when (parser.eventType) {
                                    XmlPullParser.START_TAG -> if (parser.name == "string") {
                                        currentName = parser.getAttributeValue(null, "name")
                                        value.setLength(0)
                                    }
                                    XmlPullParser.TEXT, XmlPullParser.CDSECT ->
                                        if (currentName != null) value.append(parser.text)
                                    XmlPullParser.END_TAG -> if (parser.name == "string" && currentName != null) {
                                        if (!stringResources.containsKey(currentName)) {
                                            stringResources[currentName!!] = value.toString().trim()
                                        }
                                        currentName = null
                                    }
                                }
                                parser.next()
                            }
                        }
                    }
                }
            fun firstLiteral(text: String): String? =
                Regex(""""((?:\\.|[^"\\])*)"""").find(text)?.groupValues?.get(1)
                    ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            fun firstPreviewText(text: String): String? {
                firstLiteral(text)?.let { return it }
                val resourceName = Regex("""R\.string\.(\w+)""").find(text)?.groupValues?.get(1) ?: return null
                return stringResources[resourceName]
            }
            fun iconGlyph(iconName: String?): String = when (iconName?.lowercase()) {
                "home" -> "⌂"
                "search" -> "⌕"
                "settings" -> "⚙"
                "menu" -> "☰"
                "add" -> "+"
                "close" -> "×"
                "arrowback" -> "←"
                "arrowforward" -> "→"
                "delete" -> "▤"
                "edit" -> "✎"
                "person", "accountcircle" -> "●"
                "favorite" -> "♥"
                "notifications" -> "♢"
                "cameraalt", "camera" -> "▣"
                "check" -> "✓"
                "morevert" -> "⋮"
                "share" -> "↗"
                "download" -> "↓"
                "visibility" -> "◉"
                "locationon" -> "⌖"
                "email" -> "✉"
                "phone" -> "☎"
                "calendar", "calendarmonth" -> "▦"
                "send" -> "➤"
                "info" -> "ⓘ"
                "star" -> "★"
                else -> "◆"
            }
            val pattern = Regex("""\b(Scaffold|Column|Row|Box|BoxWithConstraints|FlowRow|FlowColumn|LazyColumn|LazyRow|LazyVerticalGrid|LazyHorizontalGrid|Card|ElevatedCard|OutlinedCard|Surface|TopAppBar|CenterAlignedTopAppBar|SmallTopAppBar|LargeTopAppBar|BottomAppBar|NavigationBar|NavigationRail|NavigationDrawer|ModalNavigationDrawer|NavigationDrawerItem|NavigationBarItem|NavigationRailItem|Text|Button|OutlinedButton|TextButton|IconButton|FilledIconButton|FilledTonalButton|ElevatedButton|FloatingActionButton|ExtendedFloatingActionButton|OutlinedTextField|TextField|BasicTextField|SearchBar|Switch|Slider|RangeSlider|Checkbox|TriStateCheckbox|RadioButton|Spacer|Icon|Image|AsyncImage|CircularProgressIndicator|LinearProgressIndicator|DropdownMenu|AlertDialog|DatePicker|TimePicker|AssistChip|FilterChip|InputChip|SuggestionChip|Tab|TabRow|ListItem|Badge|HorizontalDivider|VerticalDivider|Divider)\s*\(""")
            screen.setBackgroundColor(themeBackground)
            val matches = pattern.findAll(structuralCode).toList()
            val consumed = ArrayList<IntRange>()
            data class PreviewNode(
                val start: Int,
                val end: Int,
                val view: View,
                val params: LinearLayout.LayoutParams,
                val container: Boolean,
                val args: String,
                val kind: String
            )
            val previewNodes = ArrayList<PreviewNode>()
            val buttonKinds = setOf(
                "Button", "OutlinedButton", "TextButton", "IconButton", "FilledIconButton",
                "FilledTonalButton", "ElevatedButton", "FloatingActionButton", "ExtendedFloatingActionButton"
            )
            val chipKinds = setOf("AssistChip", "FilterChip", "InputChip", "SuggestionChip")
            var rendered = 0
            for (match in matches) {
                if (consumed.any { match.range.first in it }) continue
                val open = structuralCode.indexOf('(', match.range.first)
                val close = findClosing(structuralCode, open, '(', ')')
                if (close < 0) continue
                val callArgs = code.substring(open + 1, close)
                val kind = match.groupValues[1]
                var label = firstPreviewText(callArgs)
                var endOfElement = close
                if (kind in buttonKinds) {
                    val lambdaStart = structuralCode.indexOf('{', close + 1).takeIf { it >= 0 && it - close < 100 }
                    if (lambdaStart != null) {
                        val lambdaEnd = findClosing(structuralCode, lambdaStart, '{', '}')
                        if (lambdaEnd > lambdaStart) {
                            val body = code.substring(lambdaStart, lambdaEnd + 1)
                            val textLabel = Regex("""Text\s*\(\s*"""").find(body)
                                ?.let { firstPreviewText(body.substring(it.range.first)) }
                            val iconName = Regex("""Icons\.(?:(?:AutoMirrored\.)?(?:Default|Filled|Outlined|Rounded|Sharp|TwoTone))\.(\w+)""")
                                .find(body)?.groupValues?.get(1)
                            label = textLabel ?: label ?: iconGlyph(iconName)
                            endOfElement = lambdaEnd
                        }
                    } else if (kind in setOf("IconButton", "FilledIconButton")) {
                        val iconName = Regex("""Icons\.(?:(?:AutoMirrored\.)?(?:Default|Filled|Outlined|Rounded|Sharp|TwoTone))\.(\w+)""")
                            .find(callArgs)?.groupValues?.get(1)
                        label = label ?: iconGlyph(iconName)
                    }
                }
                if (kind in setOf("OutlinedTextField", "TextField")) {
                    val labelBlock = Regex("""(?:label|placeholder)\s*=\s*\{\s*Text\s*\(""").find(callArgs)
                    if (labelBlock != null) label = firstPreviewText(callArgs.substring(labelBlock.range.last + 1)) ?: label
                }
                val isContainer = kind in setOf(
                    "Scaffold", "Column", "Row", "Box", "BoxWithConstraints", "FlowRow", "FlowColumn",
                    "LazyColumn", "LazyRow", "LazyVerticalGrid", "LazyHorizontalGrid",
                    "Card", "ElevatedCard", "OutlinedCard", "Surface",
                    "TopAppBar", "CenterAlignedTopAppBar", "SmallTopAppBar", "LargeTopAppBar",
                    "NavigationBar", "NavigationRail", "NavigationDrawer", "ModalNavigationDrawer",
                    "NavigationDrawerItem", "NavigationBarItem", "NavigationRailItem",
                    "Tab", "TabRow", "ListItem", "Badge", "BottomAppBar"
                )
                if (kind == "DropdownMenu") {
                    val lambdaStart = structuralCode.indexOf('{', close + 1).takeIf { it >= 0 && it - close < 100 }
                    if (lambdaStart != null) {
                        val lambdaEnd = findClosing(structuralCode, lambdaStart, '{', '}')
                        if (lambdaEnd > lambdaStart) endOfElement = lambdaEnd
                    }
                }
                if (kind in buttonKinds || kind in chipKinds || kind in setOf("DropdownMenu", "AlertDialog")) {
                    consumed.add(match.range.first..endOfElement)
                }
                if (isContainer) {
                    val lambdaStart = structuralCode.indexOf('{', close + 1).takeIf { it >= 0 && it - close < 100 }
                    if (lambdaStart != null) {
                        val lambdaEnd = findClosing(structuralCode, lambdaStart, '{', '}')
                        if (lambdaEnd > lambdaStart) endOfElement = lambdaEnd
                    }
                }
                val view: View? = when (kind) {
                    "Scaffold", "Column", "LazyColumn", "LazyVerticalGrid", "FlowColumn",
                    "NavigationDrawer", "ModalNavigationDrawer", "NavigationRail" ->
                        LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(10), dp(10), dp(10), dp(10))
                            setBackgroundColor(0xFFF5F7FF.toInt())
                        }
                    "Row", "LazyRow", "LazyHorizontalGrid", "FlowRow", "NavigationBar", "BottomAppBar",
                    "TopAppBar", "CenterAlignedTopAppBar", "SmallTopAppBar", "LargeTopAppBar",
                    "TabRow", "NavigationDrawerItem", "ListItem" ->
                        LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(6), dp(6), dp(6), dp(6))
                            if (kind in setOf("TopAppBar", "CenterAlignedTopAppBar", "SmallTopAppBar", "LargeTopAppBar")) {
                                minimumHeight = dp(if (kind == "LargeTopAppBar") 120 else 64)
                                setBackgroundColor(0xFFE8ECF7.toInt())
                            }
                        }
                    "NavigationBarItem", "NavigationRailItem", "Tab" ->
                        LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            setPadding(dp(8), dp(6), dp(8), dp(6))
                        }
                    "Badge" -> FrameLayout(this).apply { setPadding(dp(4), dp(2), dp(4), dp(2)) }
                    "Card", "ElevatedCard", "OutlinedCard", "Surface" -> LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setBackgroundColor(0xFFE8ECF7.toInt())
                    }
                    "Box", "BoxWithConstraints" -> FrameLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
                    "Spacer" -> Space(this).apply { minimumHeight = dp(12); minimumWidth = dp(12) }
                    "Icon" -> {
                        val iconName = Regex("""Icons\.(?:(?:AutoMirrored\.)?(?:Default|Filled|Outlined|Rounded|Sharp|TwoTone))\.(\w+)""")
                            .find(callArgs)?.groupValues?.get(1) ?: "Star"
                        val glyph = iconGlyph(iconName)
                        TextView(this).apply {
                            text = glyph
                            textSize = Regex("""size\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toFloatOrNull() ?: 22f
                            gravity = Gravity.CENTER
                            val directTint = Regex("""tint\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""")
                                .find(callArgs)?.groupValues?.get(1)
                            val themeTint = Regex("""tint\s*=\s*MaterialTheme\.colorScheme\.(\w+)""")
                                .find(callArgs)?.groupValues?.get(1)
                            val resolvedTint = directTint?.let {
                                runCatching { Color.parseColor("#" + it.takeLast(6)) }.getOrNull()
                            } ?: themeTint?.let { colorMap[it] } ?: themeOnBackground
                            setTextColor(resolvedTint)
                            contentDescription = iconName
                        }
                    }
                    "Image", "AsyncImage" -> {
                        val resourceName = Regex("""R\.drawable\.(\w+)""").find(callArgs)?.groupValues?.get(1)
                            ?: Regex("""painterResource\s*\(\s*R\.drawable\.(\w+)""").find(callArgs)?.groupValues?.get(1)
                        val imageFile = resourceName?.let { name ->
                            projectRoot.walkTopDown().maxDepth(10).firstOrNull {
                                it.isFile && it.nameWithoutExtension == name && it.parentFile?.name?.startsWith("drawable") == true &&
                                    it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")
                            }
                        }
                        if (imageFile != null) {
                            ImageView(this).apply {
                                setImageBitmap(android.graphics.BitmapFactory.decodeFile(imageFile.absolutePath))
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                adjustViewBounds = true
                                contentDescription = label ?: resourceName
                                minimumHeight = dp(72)
                            }
                        } else TextView(this).apply {
                            text = "▧ " + (label ?: resourceName ?: "Image preview")
                            gravity = Gravity.CENTER
                            textSize = 12f
                            setTextColor(0xFF65738B.toInt())
                            setBackgroundColor(0xFFE8ECF7.toInt())
                            minimumHeight = dp(72)
                        }
                    }
                    "DropdownMenu" -> Button(this).apply {
                        text = label ?: "Open menu ▾"
                        isAllCaps = false
                        setOnClickListener { anchor ->
                            val popup = PopupMenu(this@LivePreviewActivity, anchor)
                            val menuLambdaStart = structuralCode.indexOf('{', close + 1).takeIf { it >= 0 && it - close < 100 }
                            val menuLambdaBody = menuLambdaStart?.let { start ->
                                val menuLambdaEnd = findClosing(structuralCode, start, '{', '}')
                                if (menuLambdaEnd > start) code.substring(start + 1, menuLambdaEnd) else ""
                            } ?: ""
                            val optionNames = Regex("""Text\s*\(\s*"([^"]+)"""").findAll(menuLambdaBody).map { it.groupValues[1] }.distinct().take(12).toList()
                            (if (optionNames.isEmpty()) listOf("Option 1", "Option 2", "Option 3") else optionNames).forEachIndexed { index, option ->
                                popup.menu.add(0, index, index, option)
                            }
                            popup.setOnMenuItemClickListener { item ->
                                text = item.title
                                Toast.makeText(this@LivePreviewActivity, "Selected: ${item.title}", Toast.LENGTH_SHORT).show()
                                true
                            }
                            popup.show()
                        }
                    }
                    "AlertDialog" -> Button(this).apply {
                        text = label ?: "Show dialog"
                        isAllCaps = false
                        setOnClickListener {
                            AlertDialog.Builder(this@LivePreviewActivity)
                                .setTitle(label ?: "Preview dialog")
                                .setMessage("This is an interactive preview dialog. App actions are not executed.")
                                .setPositiveButton("OK", null)
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    "Text" -> label?.let { textValue ->
                        TextView(this).apply {
                            text = textValue
                            textSize = if (callArgs.contains("fontSize") && callArgs.contains("sp")) {
                                Regex("""fontSize\s*=\s*(\d+)""").find(callArgs)?.groupValues?.get(1)?.toFloatOrNull() ?: 16f
                            } else 16f
                            val textColorName = Regex("""color\s*=\s*MaterialTheme\.colorScheme\.(\w+)""").find(callArgs)?.groupValues?.get(1)
                            setTextColor(colorMap[textColorName] ?: colorMap["Ink"] ?: themeOnBackground)
                            if (callArgs.contains("FontWeight.Bold") || callArgs.contains("fontWeight = FontWeight.SemiBold")) setTypeface(typeface, android.graphics.Typeface.BOLD)
                            if (callArgs.contains("TextAlign.Center")) gravity = Gravity.CENTER
                            setPadding(0, dp(5), 0, dp(5))
                        }
                    }
                    "Button", "OutlinedButton", "TextButton", "IconButton", "FilledIconButton", "FilledTonalButton", "ElevatedButton", "FloatingActionButton", "ExtendedFloatingActionButton", "AssistChip", "FilterChip", "InputChip", "SuggestionChip" -> label?.let { textValue ->
                        Button(this).apply {
                            text = textValue
                            isAllCaps = false
                            contentDescription = textValue
                            var pressed = false
                            setOnClickListener {
                                pressed = !pressed
                                text = if (pressed) "✓ $textValue" else textValue
                                alpha = if (pressed) 0.78f else 1f
                                Toast.makeText(this@LivePreviewActivity, "Preview interaction: $textValue", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    "OutlinedTextField", "TextField", "BasicTextField", "SearchBar" -> EditText(this).apply {
                        hint = label ?: "Enter text"
                        contentDescription = label ?: "Preview text field"
                        textSize = 16f
                        val singleLine = kind == "SearchBar" ||
                            Regex("""singleLine\s*=\s*true""").containsMatchIn(callArgs)
                        setSingleLine(singleLine)
                        if (!singleLine) {
                            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            minLines = 2
                            maxLines = Regex("""maxLines\s*=\s*(\d+)""").find(callArgs)
                                ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(2, 8) ?: 4
                            gravity = Gravity.TOP or Gravity.START
                        }
                    }
                    "Switch" -> Switch(this).apply {
                        text = label ?: "Toggle"
                        contentDescription = label ?: "Preview switch"
                        isChecked = Regex("""checked\s*=\s*true""").containsMatchIn(callArgs)
                    }
                    "Checkbox", "TriStateCheckbox" -> CheckBox(this).apply {
                        text = label ?: "Check"
                        contentDescription = label ?: "Preview checkbox"
                        isChecked = Regex("""checked\s*=\s*true""").containsMatchIn(callArgs)
                    }
                    "Slider", "RangeSlider" -> SeekBar(this).apply {
                        contentDescription = label ?: if (kind == "RangeSlider") "Preview range slider (single-thumb approximation)" else "Preview slider"
                        max = 100
                        val value = Regex("""(?:^|[,\s])value\s*=\s*(-?\d+(?:\.\d+)?)f?""")
                            .find(callArgs)?.groupValues?.get(1)?.toFloatOrNull()
                        val range = Regex("""valueRange\s*=\s*(-?\d+(?:\.\d+)?)f?\s*\.\.\s*(-?\d+(?:\.\d+)?)f?""")
                            .find(callArgs)
                        progress = if (value != null && range != null) {
                            val lower = range.groupValues[1].toFloatOrNull() ?: 0f
                            val upper = range.groupValues[2].toFloatOrNull() ?: 1f
                            if (upper > lower) (((value - lower) / (upper - lower)) * 100f).toInt().coerceIn(0, 100) else 50
                        } else if (value != null && value in 0f..1f) (value * 100f).toInt() else 50
                    }
                    "Card", "ElevatedCard", "OutlinedCard", "Surface" -> LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setBackgroundColor(0xFFE8ECF7.toInt())
                    }
                    "CircularProgressIndicator", "LinearProgressIndicator" -> ProgressBar(this)
                    "RadioButton" -> RadioButton(this).apply {
                        contentDescription = label ?: "Radio option"
                        isChecked = Regex("""(?:selected|checked)\s*=\s*true""").containsMatchIn(callArgs)
                    }
                    "DatePicker" -> Button(this).apply {
                        text = label ?: "Choose date"
                        isAllCaps = false
                        setOnClickListener {
                            val calendar = java.util.Calendar.getInstance()
                            android.app.DatePickerDialog(
                                this@LivePreviewActivity,
                                { _, year, month, day ->
                                    text = String.format(java.util.Locale.getDefault(), "%02d/%02d/%04d", day, month + 1, year)
                                },
                                calendar.get(java.util.Calendar.YEAR),
                                calendar.get(java.util.Calendar.MONTH),
                                calendar.get(java.util.Calendar.DAY_OF_MONTH)
                            ).show()
                        }
                    }
                    "TimePicker" -> Button(this).apply {
                        text = label ?: "Choose time"
                        isAllCaps = false
                        setOnClickListener {
                            val calendar = java.util.Calendar.getInstance()
                            android.app.TimePickerDialog(
                                this@LivePreviewActivity,
                                { _, hour, minute ->
                                    text = String.format(java.util.Locale.getDefault(), "%02d:%02d", hour, minute)
                                },
                                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                                calendar.get(java.util.Calendar.MINUTE),
                                android.text.format.DateFormat.is24HourFormat(this@LivePreviewActivity)
                            ).show()
                        }
                    }
                    "HorizontalDivider", "VerticalDivider", "Divider" -> View(this).apply { setBackgroundColor(0xFFCBD5E1.toInt()); minimumHeight = dp(1) }
                    else -> null
                }
                if (view != null) {
                    val paddingMatch = Regex("""padding\s*\(\s*(?:(\d+)\.dp|(?:all|horizontal|vertical|start|end|top|bottom)\s*=\s*(\d+)\.dp)""").find(callArgs)
                    val allPad = (paddingMatch?.groupValues?.get(1)?.ifBlank { paddingMatch.groupValues[2] })?.toIntOrNull()?.let { dp(it) } ?: 0
                    val horizontalPad = Regex("""horizontal\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()?.let { dp(it) } ?: allPad
                    val verticalPad = Regex("""vertical\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()?.let { dp(it) } ?: allPad
                    val startPad = Regex("""start\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()?.let { dp(it) } ?: horizontalPad
                    val endPad = Regex("""end\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()?.let { dp(it) } ?: horizontalPad
                    val topPad = Regex("""top\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()?.let { dp(it) } ?: verticalPad
                    val bottomPad = Regex("""bottom\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()?.let { dp(it) } ?: verticalPad
                    if (paddingMatch != null) view.setPadding(startPad, topPad, endPad, bottomPad)
                    val bgHex = Regex("""background\s*\(\s*Color\(0x([0-9A-Fa-f]{8})\)""").find(callArgs)?.groupValues?.get(1)
                    if (bgHex != null) runCatching { view.setBackgroundColor(Color.parseColor("#" + bgHex.takeLast(6))) }
                    val bgName = Regex("""background\s*\(\s*([A-Za-z_]\w*)""").find(callArgs)?.groupValues?.get(1)
                    if (bgName != null) colorMap[bgName]?.let { view.setBackgroundColor(it) }
                    val schemeBg = Regex("""(?:containerColor|color)\s*=\s*MaterialTheme\.colorScheme\.(\w+)""").find(callArgs)?.groupValues?.get(1)
                    if (schemeBg != null) colorMap[schemeBg]?.let { view.setBackgroundColor(it) }
                    val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }
                    if (view is EditText) lp.height = dp(52)
                    val widthDp = Regex("""(?:width|size)\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\.width\s*\(\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()
                    val heightDp = Regex("""(?:height|size)\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\.height\s*\(\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()
                    widthDp?.let { lp.width = dp(it) }
                    heightDp?.let { lp.height = dp(it) }
                    if (callArgs.contains("fillMaxSize()")) { lp.width = -1; lp.height = -1 }
                    if (callArgs.contains("fillMaxWidth()")) lp.width = -1
                    if (callArgs.contains("fillMaxHeight()")) lp.height = -1
                    Regex("""weight\s*\(\s*([0-9.]+)f?""").find(callArgs)?.groupValues?.get(1)?.toFloatOrNull()?.let { lp.weight = it }
                    if (isContainer && view is LinearLayout) {
                        val horizontal = view.orientation == LinearLayout.HORIZONTAL
                        val mainGravity = if (horizontal) {
                            when {
                                callArgs.contains("Arrangement.Center") -> Gravity.CENTER_HORIZONTAL
                                callArgs.contains("Arrangement.End") -> Gravity.END
                                callArgs.contains("Arrangement.Start") -> Gravity.START
                                else -> Gravity.START
                            }
                        } else {
                            when {
                                callArgs.contains("Arrangement.Center") -> Gravity.CENTER_VERTICAL
                                callArgs.contains("Arrangement.Bottom") || callArgs.contains("Arrangement.End") -> Gravity.BOTTOM
                                callArgs.contains("Arrangement.Top") || callArgs.contains("Arrangement.Start") -> Gravity.TOP
                                else -> Gravity.TOP
                            }
                        }
                        val crossGravity = if (horizontal) {
                            when {
                                callArgs.contains("Alignment.CenterVertically") -> Gravity.CENTER_VERTICAL
                                callArgs.contains("Alignment.Bottom") -> Gravity.BOTTOM
                                callArgs.contains("Alignment.Top") -> Gravity.TOP
                                else -> Gravity.CENTER_VERTICAL
                            }
                        } else {
                            when {
                                callArgs.contains("Alignment.CenterHorizontally") -> Gravity.CENTER_HORIZONTAL
                                callArgs.contains("Alignment.End") -> Gravity.END
                                callArgs.contains("Alignment.Start") -> Gravity.START
                                else -> Gravity.CENTER_HORIZONTAL
                            }
                        }
                        view.gravity = mainGravity or crossGravity
                    }
                    if (view is TextView) {
                        val typographySize = when {
                            callArgs.contains("typography.displayLarge") -> 57f
                            callArgs.contains("typography.headlineLarge") -> 32f
                            callArgs.contains("typography.headlineMedium") -> 28f
                            callArgs.contains("typography.headlineSmall") -> 24f
                            callArgs.contains("typography.titleLarge") -> 22f
                            callArgs.contains("typography.titleMedium") -> 16f
                            callArgs.contains("typography.bodySmall") -> 12f
                            callArgs.contains("typography.labelSmall") -> 11f
                            else -> 0f
                        }
                        if (typographySize > 0f) view.textSize = typographySize
                        if (callArgs.contains("TextAlign.End")) view.gravity = Gravity.END
                        val maxLines = Regex("""maxLines\s*=\s*(\d+)""").find(callArgs)?.groupValues?.get(1)?.toIntOrNull()
                        if (maxLines != null) view.maxLines = maxLines
                    }
                    // Apply common Compose dimensions, typography, colors, shapes and font resources.
                    if (view is TextView) {
                        Regex("""fontSize\s*=\s*(\d+(?:\.\d+)?)\.sp""").find(callArgs)
                            ?.groupValues?.get(1)?.toFloatOrNull()?.let { view.textSize = it }

                        val fontResource = Regex("""R\.font\.(\w+)""").find(callArgs)?.groupValues?.get(1)
                        val fontFile = fontResource?.let { resourceName ->
                            projectRoot.walkTopDown().maxDepth(10).firstOrNull { file ->
                                file.isFile && file.nameWithoutExtension == resourceName &&
                                    file.parentFile?.name?.startsWith("font") == true &&
                                    file.extension.lowercase() in setOf("ttf", "otf", "ttc")
                            }
                        }
                        val familyTypeface = when {
                            fontFile != null -> runCatching {
                                android.graphics.Typeface.createFromFile(fontFile.absolutePath)
                            }.getOrNull()
                            callArgs.contains("FontFamily.Serif") -> android.graphics.Typeface.SERIF
                            callArgs.contains("FontFamily.Monospace") -> android.graphics.Typeface.MONOSPACE
                            callArgs.contains("FontFamily.Cursive") -> android.graphics.Typeface.create("cursive", android.graphics.Typeface.NORMAL)
                            else -> view.typeface
                        }
                        val weightStyle = when {
                            callArgs.contains("FontWeight.Black") || callArgs.contains("FontWeight.ExtraBold") ||
                                callArgs.contains("FontWeight.Bold") -> android.graphics.Typeface.BOLD
                            else -> android.graphics.Typeface.NORMAL
                        }
                        val italicStyle = if (callArgs.contains("FontStyle.Italic")) android.graphics.Typeface.ITALIC else 0
                        if (fontFile != null || callArgs.contains("FontFamily.")) {
                            view.typeface = android.graphics.Typeface.create(
                                familyTypeface ?: view.typeface, weightStyle or italicStyle
                            )
                        } else if (weightStyle != android.graphics.Typeface.NORMAL || italicStyle != 0) {
                            view.typeface = android.graphics.Typeface.create(view.typeface, weightStyle or italicStyle)
                        }

                        Regex("""lineHeight\s*=\s*(\d+(?:\.\d+)?)\.sp""").find(callArgs)
                            ?.groupValues?.get(1)?.toFloatOrNull()?.let { lineHeightSp ->
                                val currentTextSp = view.textSize / resources.displayMetrics.scaledDensity
                                view.setLineSpacing((lineHeightSp - currentTextSp) * resources.displayMetrics.scaledDensity, 1f)
                            }
                        Regex("""letterSpacing\s*=\s*(-?\d+(?:\.\d+)?)\.sp""").find(callArgs)
                            ?.groupValues?.get(1)?.toFloatOrNull()?.let { spacingSp ->
                                val currentTextSp = (view.textSize / resources.displayMetrics.scaledDensity).coerceAtLeast(1f)
                                view.letterSpacing = spacingSp / currentTextSp
                            }
                        if (callArgs.contains("TextDecoration.Underline")) {
                            view.paintFlags = view.paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                        }
                        if (callArgs.contains("TextDecoration.LineThrough")) {
                            view.paintFlags = view.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                        }
                        if (callArgs.contains("TextOverflow.Ellipsis")) {
                            view.ellipsize = android.text.TextUtils.TruncateAt.END
                        }
                        when {
                            callArgs.contains("TextAlign.Center") -> view.gravity = Gravity.CENTER
                            callArgs.contains("TextAlign.End") -> view.gravity = Gravity.END or (view.gravity and Gravity.VERTICAL_GRAVITY_MASK)
                            callArgs.contains("TextAlign.Start") -> view.gravity = Gravity.START or (view.gravity and Gravity.VERTICAL_GRAVITY_MASK)
                            callArgs.contains("TextAlign.Justify") && android.os.Build.VERSION.SDK_INT >= 26 -> {
                                view.justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
                            }
                        }
                    }
                    Regex("""alpha\s*=\s*([0-9.]+)f?""").find(callArgs)?.groupValues?.get(1)
                        ?.toFloatOrNull()?.let { view.alpha = it.coerceIn(0f, 1f) }
                    val directColor = Regex("""(?:contentColor|tint|color)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""")
                        .find(callArgs)?.groupValues?.get(1)
                    if (directColor != null && view is TextView) {
                        runCatching { view.setTextColor(Color.parseColor("#" + directColor.takeLast(6))) }
                    }
                    val buttonContainerColor = Regex("""containerColor\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""")
                        .find(callArgs)?.groupValues?.get(1)
                    if (buttonContainerColor != null) {
                        runCatching { view.setBackgroundColor(Color.parseColor("#" + buttonContainerColor.takeLast(6))) }
                    }
                    val cornerDp = Regex("""RoundedCornerShape\(\s*(\d+)\.dp\s*\)""")
                        .find(callArgs)?.groupValues?.get(1)?.toIntOrNull()
                    if (cornerDp != null) {
                        val fill = (view.background as? android.graphics.drawable.ColorDrawable)?.color ?: 0xFFE8ECF7.toInt()
                        view.background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(fill)
                            cornerRadius = dp(cornerDp).toFloat()
                        }
                    }
                    if (callArgs.contains("enabled = false") && view.isEnabled) view.isEnabled = false
                    previewNodes.add(PreviewNode(match.range.first, endOfElement, view, lp, isContainer, callArgs, kind))
                    rendered++
                }
            }
            // Rebuild the detected Compose call tree so nested composables stay inside their parent.
            val attached = HashSet<Int>()
            previewNodes.sortedBy { it.start }.forEachIndexed { index, node ->
                var parentIndex = -1
                var parentSpan = Int.MAX_VALUE
                if (node.start >= 0) {
                    previewNodes.forEachIndexed { candidateIndex, candidate ->
                        if (candidateIndex != index && candidate.container &&
                            candidate.start < node.start && candidate.end > node.end) {
                            val span = candidate.end - candidate.start
                            if (span < parentSpan) { parentSpan = span; parentIndex = candidateIndex }
                        }
                    }
                }
                val parent = if (parentIndex >= 0) previewNodes[parentIndex].view else screen
                if (parent is FrameLayout) {
                    val parentArgs = if (parentIndex >= 0) previewNodes[parentIndex].args else ""
                    val boxGravity = when {
                        parentArgs.contains("Alignment.TopStart") -> Gravity.TOP or Gravity.START
                        parentArgs.contains("Alignment.TopEnd") -> Gravity.TOP or Gravity.END
                        parentArgs.contains("Alignment.BottomStart") -> Gravity.BOTTOM or Gravity.START
                        parentArgs.contains("Alignment.BottomEnd") -> Gravity.BOTTOM or Gravity.END
                        parentArgs.contains("Alignment.CenterStart") -> Gravity.CENTER_VERTICAL or Gravity.START
                        parentArgs.contains("Alignment.CenterEnd") -> Gravity.CENTER_VERTICAL or Gravity.END
                        parentArgs.contains("Alignment.TopCenter") -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        parentArgs.contains("Alignment.BottomCenter") -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        else -> Gravity.CENTER
                    }
                    parent.addView(node.view, FrameLayout.LayoutParams(node.params.width, node.params.height, boxGravity))
                } else if (parent is LinearLayout) {
                    val parentArgs = if (parentIndex >= 0) previewNodes[parentIndex].args else ""
                    val horizontal = parent.orientation == LinearLayout.HORIZONTAL

                    if (node.params.weight > 0f) {
                        if (horizontal && node.params.width == -1) node.params.width = 0
                        if (!horizontal && node.params.height == -2) node.params.height = 0
                    }

                    val spacingDp = Regex("""Arrangement\.spacedBy\s*\(\s*(\d+(?:\.\d+)?)\.dp""")
                        .find(parentArgs)?.groupValues?.get(1)?.toFloatOrNull()?.toInt()
                        ?.let { dp(it) }
                    val defaultSpacing = if (spacingDp != null) spacingDp else dp(7)
                    node.params.setMargins(
                        node.params.leftMargin,
                        node.params.topMargin,
                        if (horizontal) defaultSpacing else node.params.rightMargin,
                        if (horizontal) node.params.bottomMargin else defaultSpacing
                    )

                    val crossGravity = if (horizontal) {
                        when {
                            parentArgs.contains("Alignment.CenterVertically") -> Gravity.CENTER_VERTICAL
                            parentArgs.contains("Alignment.Bottom") -> Gravity.BOTTOM
                            parentArgs.contains("Alignment.Top") -> Gravity.TOP
                            else -> 0
                        }
                    } else {
                        when {
                            parentArgs.contains("Alignment.CenterHorizontally") -> Gravity.CENTER_HORIZONTAL
                            parentArgs.contains("Alignment.End") -> Gravity.END
                            parentArgs.contains("Alignment.Start") -> Gravity.START
                            else -> 0
                        }
                    }
                    if (crossGravity != 0) node.params.gravity = crossGravity
                    parent.addView(node.view, node.params)
                } else {
                    screen.addView(node.view, node.params)
                }
                if (node.kind in setOf("Tab", "NavigationBarItem", "NavigationRailItem", "NavigationDrawerItem")) {
                    val selectionTag = "compose-preview-selection-" + node.kind
                    node.view.tag = selectionTag
                    val initiallySelected = Regex("""selected\s*=\s*true""").containsMatchIn(node.args)
                    fun tintPreviewText(target: View, color: Int) {
                        if (target is TextView) target.setTextColor(color)
                        if (target is ViewGroup) {
                            for (childIndex in 0 until target.childCount) {
                                tintPreviewText(target.getChildAt(childIndex), color)
                            }
                        }
                    }
                    fun applySelectionState(target: View, selected: Boolean) {
                        tintPreviewText(target, if (selected) 0xFF1D4ED8.toInt() else 0xFF263449.toInt())
                        target.background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(if (selected) 0xFFDCE7FF.toInt() else 0xFFE8ECF7.toInt())
                            cornerRadius = dp(9).toFloat()
                        }
                        target.isSelected = selected
                    }
                    applySelectionState(node.view, initiallySelected)
                    node.view.setOnClickListener {
                        if (parent is ViewGroup) {
                            for (childIndex in 0 until parent.childCount) {
                                val sibling = parent.getChildAt(childIndex)
                                if (sibling !== node.view && sibling.tag == selectionTag) {
                                    applySelectionState(sibling, false)
                                }
                            }
                        }
                        applySelectionState(node.view, true)
                        Toast.makeText(this@LivePreviewActivity, "Selected: " +
                            ((node.view as? TextView)?.text ?: node.kind), Toast.LENGTH_SHORT).show()
                    }
                }
                attached.add(index)
            }
            if (rendered == 0) {
                screen.addView(TextView(this).apply {
                    text = "Compose source was found, but supported UI calls could not be extracted. This is a static approximation, not a running Compose renderer."
                    setTextColor(Color.DKGRAY)
                    textSize = 14f
                })
            } else {
                screen.addView(TextView(this).apply {
                    text = "Static Compose preview · " + rendered + " detected elements · " +
                        sourceFiles.size + " Kotlin source file(s) scanned · " +
                        (if (usesDarkTheme) "dark" else "light") +
                        " theme · App logic and exact layout are not executed"
                    textSize = 11f
                    setTextColor(0xFF65738B.toInt())
                    setPadding(0, dp(12), 0, dp(8))
                })
            }
            content.addView(screen, LinearLayout.LayoutParams(-1, -2))
        } catch (e: Exception) {
            showMessage("Could not create Compose preview.\n\n" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun chooseLayout() {
        val choices = ArrayList<Pair<String, File>>()
        for (layout in layouts) {
            choices.add("[XML] " + layout.relativeTo(projectRoot).invariantSeparatorsPath to layout)
        }

        // Include only Kotlin files that actually declare or host Compose UI, even when
        // the project also contains several traditional XML layouts.
        var remainingReadBytes = 8L * 1024L * 1024L
        val kotlinFiles = projectRoot.walkTopDown().maxDepth(10)
            .filter { file ->
                file.isFile && file.extension.equals("kt", true) &&
                    file.path.split(File.separator).none { part ->
                        part in setOf(".gradle", "build", ".git", "node_modules", ".idea", "generated", ".dart_tool")
                    }
            }
            .take(200)
        for (source in kotlinFiles) {
            val size = source.length()
            if (size <= 0L || size > remainingReadBytes) continue
            val sourceCode = runCatching { source.readText() }.getOrNull() ?: continue
            remainingReadBytes -= size
            val hasComposeUi = Regex("""@Composable\b""").containsMatchIn(sourceCode) ||
                Regex("""\bsetContent\s*\(""").containsMatchIn(sourceCode) ||
                Regex("""\bComposeView\s*\(""").containsMatchIn(sourceCode)
            if (hasComposeUi && choices.none { it.second.canonicalFile == source.canonicalFile }) {
                choices.add("[Compose] " + source.relativeTo(projectRoot).invariantSeparatorsPath to source)
            }
        }

        if (choices.isEmpty()) {
            refreshCurrentPreview()
            return
        }
        val openChoice: (Int) -> Unit = { index ->
            val file = choices[index].second
            when (file.extension.lowercase()) {
                "xml" -> render(file)
                "kt" -> renderComposeFile(file)
                else -> refreshCurrentPreview()
            }
        }
        if (choices.size == 1) {
            openChoice(0)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Choose screen / layout")
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> openChoice(which) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun render(file: File) {
        selectedPreviewPath = file.relativeTo(projectRoot).invariantSeparatorsPath
        clearPreviewContent()
        title.text = "PREVIEW · " + file.name
        try {
            val sourceXml = file.readText()
            val unsupportedLayouts = listOf("ConstraintLayout", "RelativeLayout", "CoordinatorLayout", "MotionLayout")
                .filter { name -> Regex("<(?:[A-Za-z0-9_.]+:)?${name}\\b").containsMatchIn(sourceXml) }
            if (unsupportedLayouts.isNotEmpty()) {
                content.addView(TextView(this).apply {
                    text = "Approximate layout: ${unsupportedLayouts.joinToString(", ")} positioning rules are not fully simulated. Build & Run to verify exact placement."
                    textSize = 11f
                    setTextColor(0xFF6B4B12.toInt())
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setBackgroundColor(0xFFFFE9B8.toInt())
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
            val parser = Xml.newPullParser()
            sourceXml.byteInputStream().use { parser.setInput(it, "UTF-8") }
            val groups = ArrayList<ViewGroup>()
            val groupFlags = ArrayList<Boolean>()
            var rootView: View? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val tag = parser.name.substringAfterLast('.')
                    if (tag == "data" || tag == "variable" || tag == "import") { event = parser.next(); continue }
                    val child = makeView(tag, parser)
                    val isGroup = child is ViewGroup
                    if (rootView == null) rootView = child else groups.lastOrNull()?.addView(child, params(parser, groups.last()))
                    if (isGroup) groups.add(child as ViewGroup)
                    groupFlags.add(isGroup)
                } else if (event == XmlPullParser.END_TAG) {
                    val tag = parser.name.substringAfterLast('.')
                    if (tag != "data" && tag != "variable" && tag != "import" && groupFlags.isNotEmpty()) {
                        if (groupFlags.removeAt(groupFlags.lastIndex) && groups.isNotEmpty()) groups.removeAt(groups.lastIndex)
                    }
                }
                event = parser.next()
            }
            if (rootView == null) showMessage("No renderable root view in " + file.name)
            else content.addView(rootView, LinearLayout.LayoutParams(-1, -2))
        } catch (e: Exception) { showMessage("Could not render layout.\n\n" + (e.message ?: e.javaClass.simpleName)) }
    }

    private fun makeView(tag: String, p: XmlPullParser): View {
        val v: View = when (tag) {
            "LinearLayout" -> LinearLayout(this).apply {
                orientation = if (attr(p, "orientation") == "horizontal") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = gravity(attr(p, "gravity"))
            }
            "FrameLayout", "CoordinatorLayout" -> FrameLayout(this)
            "ConstraintLayout", "RelativeLayout", "MotionLayout" -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
            }
            "ScrollView", "NestedScrollView" -> ScrollView(this).apply { isFillViewport = attr(p, "fillViewport") == "true" }
            "HorizontalScrollView" -> HorizontalScrollView(this)
            "TextView", "CheckedTextView" -> TextView(this)
            "Button", "MaterialButton" -> Button(this).apply { isAllCaps = false }
            "EditText", "TextInputEditText" -> EditText(this)
            "CheckBox" -> CheckBox(this)
            "RadioButton" -> RadioButton(this)
            "Switch", "SwitchCompat" -> Switch(this)
            "ProgressBar" -> ProgressBar(this)
            "ImageView", "ImageButton" -> {
                val image = ImageView(this)
                val ref = attr(p, "src").ifBlank { attr(p, "background") }
                val resource = ref.substringAfter("@drawable/", "").substringAfter("@mipmap/", "")
                val bitmapFile = if (resource.isNotBlank()) projectRoot.walkTopDown().maxDepth(10).firstOrNull {
                    it.isFile && it.nameWithoutExtension == resource &&
                        it.parentFile?.name?.startsWith("drawable") == true &&
                        it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")
                } else null
                if (bitmapFile != null) {
                    runCatching { image.setImageBitmap(android.graphics.BitmapFactory.decodeFile(bitmapFile.absolutePath)) }
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                } else {
                    image.contentDescription = "Image resource: $ref (not found in supported drawable files)"
                    image.setBackgroundColor(0xFFE3E8EF.toInt())
                }
                image
            }
            "Space" -> Space(this)
            else -> LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        }
        val txt = attr(p, "text")
        if (v is TextView) {
            if (txt.isNotBlank()) v.text = resolveText(txt)
            val hint = attr(p, "hint")
            if (v is EditText && hint.isNotBlank()) v.hint = resolveText(hint)
            attr(p, "textSize").removeSuffix("sp").toFloatOrNull()?.let { v.textSize = it }
            color(attr(p, "textColor"))?.let { v.setTextColor(it) }
            v.gravity = gravity(attr(p, "gravity"))
            val maxLines = attr(p, "maxLines").toIntOrNull()
            if (maxLines != null && maxLines > 0) v.maxLines = maxLines
        } else if (v is CompoundButton && txt.isNotBlank()) v.text = resolveText(txt)
        color(attr(p, "background"))?.let { v.setBackgroundColor(it) }
        val allPadding = dim(attr(p, "padding"), 0).coerceAtLeast(0)
        val left = dim(attr(p, "paddingLeft"), allPadding).coerceAtLeast(0)
        val right = dim(attr(p, "paddingRight"), allPadding).coerceAtLeast(0)
        val top = dim(attr(p, "paddingTop"), allPadding).coerceAtLeast(0)
        val bottom = dim(attr(p, "paddingBottom"), allPadding).coerceAtLeast(0)
        v.setPadding(left, top, right, bottom)
        if (v is Button || v is CompoundButton || v is ImageButton) {
            v.setOnClickListener { Toast.makeText(this, "Preview control: " + txt.ifBlank { tag }, Toast.LENGTH_SHORT).show() }
        }
        return v
    }

    private fun params(p: XmlPullParser, parent: ViewGroup): ViewGroup.LayoutParams {
        val w = dim(attr(p, "layout_width"), -2)
        val h = dim(attr(p, "layout_height"), -2)
        val ml = dim(attr(p, "layout_marginLeft"), dim(attr(p, "layout_margin"), 0)).coerceAtLeast(0)
        val mr = dim(attr(p, "layout_marginRight"), dim(attr(p, "layout_margin"), 0)).coerceAtLeast(0)
        val mt = dim(attr(p, "layout_marginTop"), dim(attr(p, "layout_margin"), 0)).coerceAtLeast(0)
        val mb = dim(attr(p, "layout_marginBottom"), dim(attr(p, "layout_margin"), 0)).coerceAtLeast(0)
        if (parent is LinearLayout) return LinearLayout.LayoutParams(w, h).apply {
            setMargins(ml, mt, mr, mb)
            attr(p, "layout_weight").toFloatOrNull()?.let { weight = it }
            gravity = gravity(attr(p, "layout_gravity"))
        }
        if (parent is FrameLayout) return FrameLayout.LayoutParams(w, h, gravity(attr(p, "layout_gravity"))).apply {
            setMargins(ml, mt, mr, mb)
        }
        return ViewGroup.MarginLayoutParams(w, h).apply { setMargins(ml, mt, mr, mb) }
    }
    private fun attr(p: XmlPullParser, name: String) = p.getAttributeValue("http://schemas.android.com/apk/res/android", name) ?: p.getAttributeValue(null, name) ?: ""
    private fun valueResourceFiles(fileName: String): List<File> =
        projectRoot.walkTopDown().maxDepth(10)
            .filter { it.isFile && it.name == fileName && it.parentFile?.name?.startsWith("values") == true &&
                it.path.split(File.separator).none { part -> part in setOf(".gradle", "build", ".git") } }
            .take(30).toList()

    private fun resolveText(s: String): String {
        if (s.startsWith("@string/")) {
            val key = s.substringAfter('/')
            for (file in valueResourceFiles("strings.xml")) {
                val found = runCatching {
                    Regex("<string\\s+name=\"" + Regex.escape(key) + "\"[^>]*>(.*?)</string>", setOf(RegexOption.DOT_MATCHES_ALL))
                        .find(file.readText())?.groupValues?.get(1)
                }.getOrNull()
                if (found != null) return found.replace("\\n", "\n")
            }
        }
        return s
    }

    private fun color(s: String): Int? {
        if (s.isBlank()) return null
        if (s.startsWith("#")) return runCatching { Color.parseColor(s) }.getOrNull()
        if (s.startsWith("@color/")) {
            val key = s.substringAfter('/')
            for (file in valueResourceFiles("colors.xml")) {
                val value = runCatching {
                    Regex("<color\\s+name=\"" + Regex.escape(key) + "\"[^>]*>(.*?)</color>", setOf(RegexOption.DOT_MATCHES_ALL))
                        .find(file.readText())?.groupValues?.get(1)?.trim()
                }.getOrNull()
                if (value != null) return runCatching { Color.parseColor(value) }.getOrNull()
            }
        }
        return null
    }

    private fun gravity(s: String): Int {
        if (s.isBlank()) return Gravity.CENTER_VERTICAL
        var result = 0
        if (s.contains("center")) result = result or Gravity.CENTER
        if (s.contains("start") || s.contains("left")) result = result or Gravity.START
        if (s.contains("end") || s.contains("right")) result = result or Gravity.END
        if (s.contains("top")) result = result or Gravity.TOP
        if (s.contains("bottom")) result = result or Gravity.BOTTOM
        return if (result == 0) Gravity.CENTER_VERTICAL else result
    }

    private fun dim(s: String, fallback: Int): Int {
        if (s.isBlank()) return fallback
        if (s == "match_parent" || s == "fill_parent") return -1
        if (s == "wrap_content") return -2
        if (s.startsWith("@dimen/")) {
            val key = s.substringAfter('/')
            for (file in valueResourceFiles("dimens.xml")) {
                val value = runCatching {
                    Regex("<dimen\\s+name=\"" + Regex.escape(key) + "\"[^>]*>(.*?)</dimen>", setOf(RegexOption.DOT_MATCHES_ALL))
                        .find(file.readText())?.groupValues?.get(1)?.trim()
                }.getOrNull()
                if (value != null) return dim(value, fallback)
            }
        }
        return when {
            s.endsWith("dp") -> dp(s.removeSuffix("dp").toFloatOrNull()?.toInt() ?: 0)
            s.endsWith("sp") -> dp(s.removeSuffix("sp").toFloatOrNull()?.toInt() ?: 0)
            else -> fallback
        }
    }
    private fun showMessage(s: String) {
        clearPreviewContent()
        content.addView(TextView(this).apply { text = s; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(dp(14),dp(18),dp(14),dp(18)) })
    }
    override fun onDestroy() {
        if (::content.isInitialized) clearPreviewContent()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
