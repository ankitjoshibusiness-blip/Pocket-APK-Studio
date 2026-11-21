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

/** Lightweight Android layout/source preview; it does not execute app code. */
class LivePreviewActivity : Activity() {
    private lateinit var projectRoot: File
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
    private lateinit var previewScroll: ScrollView
    private lateinit var previewHorizontal: HorizontalScrollView
    private var previewWidthDp = 360
    private var previewHeightDp = 800
    private var layouts: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectRoot = File(intent.getStringExtra("project_root") ?: "")
        if (!projectRoot.isDirectory) { Toast.makeText(this, "Project folder unavailable.", Toast.LENGTH_LONG).show(); finish(); return }
        buildScreen()
        findLayouts()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xFF121922.toInt()) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(Button(this).apply { text = "‹ Back"; isAllCaps = false; setOnClickListener { finish() } }, LinearLayout.LayoutParams(-2, dp(44)))
        title = TextView(this).apply { text = "LIVE UI PREVIEW"; textSize = 17f; setTextColor(Color.WHITE); setPadding(dp(12), 0, 0, 0) }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "☷"; contentDescription = "Choose screen"; setOnClickListener { chooseLayout() } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        header.addView(Button(this).apply { text = "↻"; contentDescription = "Refresh preview"; setOnClickListener { refreshCurrentPreview() } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)
        root.addView(TextView(this).apply { text = "Source preview is an approximation. Use Build & Run on Device for the exact compiled app."; textSize = 11f; setTextColor(0xFFFFD28A.toInt()); setPadding(dp(4), dp(8), dp(4), dp(6)) })
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
        val buildRunButton = Button(this).apply {
            text = "Build & Run on Device"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF2563EB.toInt())
            setOnClickListener { exportProjectForDeviceRun() }
        }
        root.addView(buildRunButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(7); bottomMargin = dp(8) })
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFFE0E5EC.toInt()) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        previewHorizontal = HorizontalScrollView(this).apply {
            isFillViewport = false
            isHorizontalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content, FrameLayout.LayoutParams(-2, -2))
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

    private fun applyDeviceProfile() {
        if (!::content.isInitialized) return
        content.layoutParams = (content.layoutParams ?: FrameLayout.LayoutParams(-1, -2)).apply {
            width = dp(previewWidthDp)
            height = -2
        }
        content.minimumHeight = dp(previewHeightDp)
        content.requestLayout()
        title.text = "LIVE UI PREVIEW · ${previewWidthDp}×${previewHeightDp} dp"
        previewHorizontal.post { previewHorizontal.scrollTo(0, 0) }
        previewScroll.post { previewScroll.scrollTo(0, 0) }
    }

    private fun findLayouts() {
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
        content.removeAllViews()
        findLayouts()
    }

    /** Static Compose source preview. It approximates common composables without executing app code. */
    private fun renderSourcePreview() {
        content.removeAllViews()
        val sources = projectRoot.walkTopDown().maxDepth(10).filter {
            it.isFile && it.extension == "kt" && it.length() < 2_000_000L &&
                it.path.split(File.separator).none { part -> part in setOf(".gradle", "build", ".git", "node_modules", "generated") }
        }.toList()
        val source = sources.firstOrNull { it.name == "MainActivity.kt" && runCatching { it.readText().contains("setContent") }.getOrDefault(false) }
            ?: sources.firstOrNull { runCatching { it.readText().contains("@Composable") }.getOrDefault(false) }
        if (source == null) {
            title.text = "LIVE UI PREVIEW"
            showMessage("No Compose Kotlin source found. Add a MainActivity or @Composable screen.")
            return
        }
        renderComposeFile(source)
    }

    private fun renderComposeFile(source: File) {
        title.text = "COMPOSE PREVIEW · " + source.name
        try {
            val code = source.readText()
            val screen = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setBackgroundColor(0xFFF5F7FF.toInt())
            }
            val colorMap = HashMap<String, Int>()
            Regex("""(?:private\s+)?val\s+(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""").findAll(code).forEach {
                runCatching { colorMap[it.groupValues[1]] = android.graphics.Color.parseColor("#" + it.groupValues[2].takeLast(6)) }
            }
            Regex("""(primary|secondary|tertiary|background|surface|onPrimary|onSecondary|onSurface|error)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""").findAll(code).forEach { match ->
                runCatching { colorMap[match.groupValues[1]] = android.graphics.Color.parseColor("#" + match.groupValues[2].takeLast(6)) }
            }
            val usesDarkTheme = Regex("""darkColorScheme\s*\(""").containsMatchIn(code)
            val themeBackground = colorMap["background"] ?: colorMap["surface"] ?: if (usesDarkTheme) 0xFF171B24.toInt() else 0xFFF5F7FF.toInt()
            val themeOnBackground = colorMap["onSurface"] ?: if (usesDarkTheme) Color.WHITE else 0xFF263449.toInt()
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
            fun firstLiteral(text: String): String? =
                Regex(""""((?:\\.|[^"\\])*)"""").find(text)?.groupValues?.get(1)
                    ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
            val pattern = Regex("""\b(Scaffold|Column|Row|Box|LazyColumn|LazyRow|Card|Surface|TopAppBar|CenterAlignedTopAppBar|NavigationBar|NavigationRail|NavigationDrawer|ModalNavigationDrawer|Text|Button|OutlinedButton|TextButton|IconButton|OutlinedTextField|TextField|Switch|Slider|Checkbox|Spacer|Icon|Image|AsyncImage|CircularProgressIndicator|LinearProgressIndicator|DropdownMenu|AlertDialog)\s*\(""")
            screen.setBackgroundColor(themeBackground)
            val matches = pattern.findAll(code).toList()
            val consumed = ArrayList<IntRange>()
            data class PreviewNode(val start: Int, val end: Int, val view: View, val params: LinearLayout.LayoutParams, val container: Boolean, val args: String)
            val previewNodes = ArrayList<PreviewNode>()
            var rendered = 0
            for (match in matches) {
                if (consumed.any { match.range.first in it }) continue
                val open = code.indexOf('(', match.range.first)
                val close = findClosing(code, open, '(', ')')
                if (close < 0) continue
                val callArgs = code.substring(open + 1, close)
                val kind = match.groupValues[1]
                var label = firstLiteral(callArgs)
                var endOfElement = close
                if (kind in setOf("Button", "OutlinedButton", "TextButton", "IconButton")) {
                    val lambdaStart = code.indexOf('{', close + 1).takeIf { it >= 0 && it - close < 100 }
                    if (lambdaStart != null) {
                        val lambdaEnd = findClosing(code, lambdaStart, '{', '}')
                        if (lambdaEnd > lambdaStart) {
                            val body = code.substring(lambdaStart, lambdaEnd + 1)
                            label = Regex("""Text\s*\(\s*"""").find(body)?.let { firstLiteral(body.substring(it.range.first)) } ?: label
                            endOfElement = lambdaEnd
                        }
                    }
                }
                if (kind in setOf("OutlinedTextField", "TextField")) {
                    val labelBlock = Regex("""(?:label|placeholder)\s*=\s*\{\s*Text\s*\(""").find(callArgs)
                    if (labelBlock != null) label = firstLiteral(callArgs.substring(labelBlock.range.last + 1)) ?: label
                }
                val isContainer = kind in setOf("Scaffold", "Column", "Row", "Box", "LazyColumn", "LazyRow", "Card", "Surface", "NavigationBar", "NavigationRail", "NavigationDrawer", "ModalNavigationDrawer")
                if (kind in setOf("Button", "OutlinedButton", "TextButton", "IconButton")) consumed.add(match.range.first..endOfElement)
                if (isContainer) {
                    val lambdaStart = code.indexOf('{', close + 1).takeIf { it >= 0 && it - close < 100 }
                    if (lambdaStart != null) {
                        val lambdaEnd = findClosing(code, lambdaStart, '{', '}')
                        if (lambdaEnd > lambdaStart) endOfElement = lambdaEnd
                    }
                }
                val view: View? = when (kind) {
                    "Scaffold", "Column", "LazyColumn", "NavigationDrawer", "ModalNavigationDrawer", "Card", "Surface" -> LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(if (kind == "Card" || kind == "Surface") 0xFFE8ECF7.toInt() else 0xFFF5F7FF.toInt()) }
                    "Row", "LazyRow", "NavigationBar", "NavigationRail" -> LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
                    "Box" -> FrameLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
                    "TopAppBar", "CenterAlignedTopAppBar" -> TextView(this).apply { text = label ?: "App Bar"; textSize = 18f; setTextColor(0xFF263449.toInt()); setPadding(dp(12), dp(14), dp(12), dp(14)); setBackgroundColor(0xFFE8ECF7.toInt()) }
                    "Spacer" -> Space(this).apply { minimumHeight = dp(12); minimumWidth = dp(12) }
                    "Icon" -> {
                        val iconName = Regex("""Icons\.(?:Filled|Outlined|Rounded|Sharp|TwoTone)\.(\w+)""").find(callArgs)?.groupValues?.get(1) ?: "Star"
                        val glyph = when (iconName.lowercase()) {
                            "home" -> "⌂"; "search" -> "⌕"; "settings" -> "⚙"; "menu" -> "☰"; "add" -> "+"; "close" -> "×"
                            "arrowback" -> "←"; "delete" -> "▤"; "edit" -> "✎"; "person" -> "●"; "favorite" -> "♥"
                            "notifications" -> "♢"; "cameraalt", "camera" -> "▣"; "check" -> "✓"; "morevert" -> "⋮"
                            "share" -> "↗"; "download" -> "↓"; "visibility" -> "◉"; "locationon" -> "⌖"; else -> "◆"
                        }
                        TextView(this).apply {
                            text = glyph
                            textSize = Regex("""size\s*=\s*(\d+)\.dp""").find(callArgs)?.groupValues?.get(1)?.toFloatOrNull() ?: 22f
                            gravity = Gravity.CENTER
                            val tint = Regex("""tint\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)""").find(callArgs)?.groupValues?.get(1)
                            setTextColor(tint?.let { runCatching { Color.parseColor("#" + it.takeLast(6)) }.getOrNull() } ?: themeOnBackground)
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
                            val menuLambdaStart = code.indexOf('{', close + 1).takeIf { it >= 0 && it - close < 100 }
                            val menuLambdaBody = menuLambdaStart?.let { start ->
                                val menuLambdaEnd = findClosing(code, start, '{', '}')
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
                    "Button", "OutlinedButton", "TextButton", "IconButton" -> label?.let { textValue ->
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
                    "OutlinedTextField", "TextField" -> EditText(this).apply {
                        hint = label ?: "Enter text"
                        contentDescription = label ?: "Preview text field"
                        textSize = 16f
                        setSingleLine(true)
                    }
                    "Switch" -> Switch(this).apply {
                        text = label ?: "Toggle"
                        contentDescription = label ?: "Preview switch"
                    }
                    "Checkbox" -> CheckBox(this).apply {
                        text = label ?: "Check"
                        contentDescription = label ?: "Preview checkbox"
                    }
                    "Slider" -> SeekBar(this).apply {
                        contentDescription = label ?: "Preview slider"
                        max = 100
                        progress = 50
                    }
                    "Card", "Surface" -> TextView(this).apply {
                        text = label ?: if (kind == "Card") "▣ Card container" else "▣ Surface container"
                        textSize = 14f
                        setTextColor(0xFF263449.toInt())
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        setBackgroundColor(0xFFE8ECF7.toInt())
                    }
                    "CircularProgressIndicator", "LinearProgressIndicator" -> ProgressBar(this)
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
                        if (kind == "Row" || kind == "LazyRow" || kind == "NavigationBar" || kind == "NavigationRail") {
                            view.gravity = when {
                                callArgs.contains("Arrangement.Center") -> Gravity.CENTER
                                callArgs.contains("Arrangement.End") -> Gravity.END or Gravity.CENTER_VERTICAL
                                callArgs.contains("Arrangement.SpaceBetween") -> Gravity.CENTER_VERTICAL
                                else -> Gravity.CENTER_VERTICAL
                            }
                        } else {
                            view.gravity = when {
                                callArgs.contains("Arrangement.Center") -> Gravity.CENTER_HORIZONTAL
                                callArgs.contains("Arrangement.Bottom") -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                            }
                        }
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
                    if (callArgs.contains("enabled = false") && view.isEnabled) view.isEnabled = false
                    previewNodes.add(PreviewNode(match.range.first, endOfElement, view, lp, isContainer, callArgs))
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
                    parent.addView(node.view, node.params)
                } else {
                    screen.addView(node.view, node.params)
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
                    text = "Static Compose preview · $rendered detected elements · App logic and exact layout are not executed"
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
        if (layouts.size > 1) {
            AlertDialog.Builder(this).setTitle("Choose screen / layout")
                .setItems(layouts.map { it.relativeTo(projectRoot).invariantSeparatorsPath }.toTypedArray()) { _, which ->
                    render(layouts[which])
                }
                .setNegativeButton("Cancel", null).show()
        } else {
            val sourceFiles = projectRoot.walkTopDown().maxDepth(10)
                .filter { it.isFile && it.extension == "kt" &&
                    it.path.split(File.separator).none { p -> p in setOf(".gradle", "build", ".git", "node_modules") } }
                .take(100).toList()
            if (sourceFiles.size > 1) AlertDialog.Builder(this).setTitle("Choose Compose source")
                .setItems(sourceFiles.map { it.relativeTo(projectRoot).invariantSeparatorsPath }.toTypedArray()) { _, which ->
                    renderComposeFile(sourceFiles[which])
                }.setNegativeButton("Cancel", null).show()
            else refreshCurrentPreview()
        }
    }

    private fun render(file: File) {
        content.removeAllViews()
        title.text = "PREVIEW · " + file.name
        try {
            val parser = Xml.newPullParser()
            file.inputStream().use { parser.setInput(it, "UTF-8") }
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
            "FrameLayout", "ConstraintLayout", "CoordinatorLayout", "RelativeLayout" -> FrameLayout(this)
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
        content.removeAllViews()
        content.addView(TextView(this).apply { text = s; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(dp(14),dp(18),dp(14),dp(18)) })
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
