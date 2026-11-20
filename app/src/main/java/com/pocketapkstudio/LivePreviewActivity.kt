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
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Lightweight Android layout/source preview; it does not execute app code. */
class LivePreviewActivity : Activity() {
    private lateinit var projectRoot: File
    private lateinit var content: LinearLayout
    private lateinit var title: TextView
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
        header.addView(Button(this).apply { text = "↻"; contentDescription = "Reload preview"; setOnClickListener { chooseLayout() } }, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)
        root.addView(TextView(this).apply { text = "Android layout preview · XML and Compose source"; textSize = 11f; setTextColor(0xFFB6C5D2.toInt()); setPadding(dp(4), dp(8), dp(4), dp(10)) })
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFFF5F7FA.toInt()) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val scroll = ScrollView(this); scroll.addView(content)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun findLayouts() {
        layouts = projectRoot.walkTopDown().maxDepth(8).filter { it.isFile && it.extension == "xml" && it.parentFile?.name == "layout" && it.path.split(File.separator).none { part -> part in setOf(".gradle", "build", ".git") } }.take(100).sortedBy { it.name }.toList()
        if (layouts.isEmpty()) { renderSourcePreview(); return }
        val first = layouts.firstOrNull { it.name == "activity_main.xml" } ?: layouts.firstOrNull { it.name == "main.xml" } ?: layouts.first()
        render(first)
    }


    /** Static Compose source preview. It approximates common composables without executing app code. */
    private fun renderSourcePreview() {
        content.removeAllViews()
        val sources = projectRoot.walkTopDown().maxDepth(10).filter {
            it.isFile && it.extension == "kt" && it.length() < 2_000_000L &&
                it.path.split(File.separator).none { part -> part in setOf(".gradle", "build", ".git", "node_modules", "generated") }
        }.toList()
        val source = sources.firstOrNull { it.name == "MainActivity.kt" && it.readText().contains("setContent") }
            ?: sources.firstOrNull { it.readText().contains("@Composable") }
        if (source == null) {
            title.text = "LIVE UI PREVIEW"
            showMessage("No Compose Kotlin source found. Add a MainActivity or @Composable screen.")
            return
        }
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
                    "DropdownMenu" -> TextView(this).apply { text = label ?: "Dropdown Menu"; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(0xFFE8ECF7.toInt()) }
                    "AlertDialog" -> TextView(this).apply { text = label ?: "Dialog Preview"; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(0xFFE8ECF7.toInt()) }
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
                            setOnClickListener { Toast.makeText(this@LivePreviewActivity, "Preview control only: $textValue", Toast.LENGTH_SHORT).show() }
                        }
                    }
                    "OutlinedTextField", "TextField" -> EditText(this).apply {
                        hint = label ?: "Enter text"
                        textSize = 16f
                        setSingleLine(true)
                    }
                    "Switch" -> Switch(this).apply { text = label ?: "Toggle" }
                    "Checkbox" -> CheckBox(this).apply { text = label ?: "Check" }
                    "Slider" -> SeekBar(this)
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
        findLayouts()
        if (layouts.size > 1) AlertDialog.Builder(this).setTitle("Choose layout")
            .setItems(layouts.map { it.name }.toTypedArray()) { _, which -> render(layouts[which]) }
            .setNegativeButton("Cancel", null).show()
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
            "LinearLayout" -> LinearLayout(this).apply { orientation = if (attr(p, "orientation") == "horizontal") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
            "FrameLayout", "ConstraintLayout", "CoordinatorLayout", "RelativeLayout", "ScrollView", "NestedScrollView" -> LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            "TextView", "CheckedTextView" -> TextView(this)
            "Button", "MaterialButton" -> Button(this).apply { isAllCaps = false }
            "EditText", "TextInputEditText" -> EditText(this)
            "CheckBox" -> CheckBox(this)
            "RadioButton" -> RadioButton(this)
            "Switch", "SwitchCompat" -> Switch(this)
            "ProgressBar" -> ProgressBar(this)
            "ImageView", "ImageButton" -> TextView(this).apply { text = "▧ Image placeholder"; gravity = Gravity.CENTER }
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
        } else if (v is CompoundButton && txt.isNotBlank()) v.text = resolveText(txt)
        color(attr(p, "background"))?.let { v.setBackgroundColor(it) }
        attr(p, "padding").removeSuffix("dp").toFloatOrNull()?.toInt()?.let { val n = dp(it); v.setPadding(n,n,n,n) }
        if (v is Button || v is CompoundButton) v.setOnClickListener { Toast.makeText(this, "Preview control: " + txt.ifBlank { tag }, Toast.LENGTH_SHORT).show() }
        return v
    }

    private fun params(p: XmlPullParser, parent: ViewGroup): ViewGroup.LayoutParams {
        val w = dim(attr(p, "layout_width"), -2); val h = dim(attr(p, "layout_height"), -2)
        if (parent is LinearLayout) return LinearLayout.LayoutParams(w, h).apply {
            val m = dim(attr(p, "layout_margin"), 0).coerceAtLeast(0); setMargins(m,m,m,m)
            attr(p, "layout_weight").toFloatOrNull()?.let { weight = it }
        }
        return ViewGroup.LayoutParams(w, h)
    }

    private fun attr(p: XmlPullParser, name: String) = p.getAttributeValue("http://schemas.android.com/apk/res/android", name) ?: p.getAttributeValue(null, name) ?: ""
    private fun resolveText(s: String): String {
        if (s.startsWith("@string/")) {
            val key = s.substringAfter('/')
            val f = File(projectRoot, "app/src/main/res/values/strings.xml")
            if (f.isFile) Regex("<string\\s+name=\"" + Regex.escape(key) + "\"[^>]*>(.*?)</string>", setOf(RegexOption.DOT_MATCHES_ALL)).find(f.readText())?.let { return it.groupValues[1] }
        }
        return s
    }
    private fun color(s: String): Int? = try { if (s.startsWith("#")) Color.parseColor(s) else null } catch (_: Exception) { null }
    private fun gravity(s: String): Int {
        var g = Gravity.CENTER_VERTICAL
        if (s.contains("center")) g = Gravity.CENTER
        else if (s.contains("end") || s.contains("right")) g = Gravity.END or Gravity.CENTER_VERTICAL
        else if (s.contains("start") || s.contains("left")) g = Gravity.START or Gravity.CENTER_VERTICAL
        return g
    }
    private fun dim(s: String, fallback: Int): Int = when {
        s == "match_parent" || s == "fill_parent" -> -1
        s == "wrap_content" || s.isBlank() -> -2
        s.endsWith("dp") -> dp(s.removeSuffix("dp").toFloatOrNull()?.toInt() ?: 0)
        else -> fallback
    }
    private fun showMessage(s: String) {
        content.removeAllViews()
        content.addView(TextView(this).apply { text = s; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(dp(14),dp(18),dp(14),dp(18)) })
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
