package com.pocketapkstudio

import com.pocketapkstudio.ApkDesignerModels.DesignerElement
import com.pocketapkstudio.ApkDesignerModels.DesignerScreen
import com.pocketapkstudio.ApkDesignerModels.ElementKind
import com.pocketapkstudio.ApkDesignerModels.ScreenCategory
import com.pocketapkstudio.ApkDesignerModels.TextAlignMode
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin Full-App Multi-Page, Multi-Tab, Multi-Tile & Dialog Window Importer.
 * Extracts editable [DesignerScreen]s from:
 *  - XML layouts (resolving @color/* and @string/* resources)
 *  - Non-XML Jetpack Compose (@Composable screens & AlertDialog windows)
 *  - Programmatic Kotlin/Java Android View hierarchies (LinearLayout, ScrollView,
 *    custom helper functions like label/card/actionButton/welcomeMenuItem/workspaceSection,
 *    multi-tab pages, standalone builders, and AlertDialog.Builder windows)
 *  - Entire Android project directories (aggregating all UI files)
 */
object FullAppImportEngine {

    data class SourceFileEntry(
        val path: String,
        val content: String,
    )

    data class FullAppImportBundle(
        val screens: List<DesignerScreen>,
        val totalFilesScanned: Int,
        val summary: String,
    )

    private const val CANVAS_W = 360
    private const val MIN_CANVAS_H = 780

    private data class ResourceMaps(
        val colors: Map<String, String>,
        val strings: Map<String, String>,
    )

    private fun parseResourceMaps(files: List<SourceFileEntry>): ResourceMaps {
        val colors = mutableMapOf<String, String>()
        val strings = mutableMapOf<String, String>()
        val colorRx = Regex("""<color\s+name="([^"]+)"[^>]*>([^<]+)</color>""")
        val stringRx = Regex("""<string\s+name="([^"]+)"[^>]*>([^<]+)</string>""")

        for (file in files) {
            val lower = file.path.lowercase()
            if (!lower.endsWith(".xml")) continue
            if (lower.contains("/values") || lower.endsWith("colors.xml")) {
                colorRx.findAll(file.content).forEach { m ->
                    colors[m.groupValues[1]] = ApkDesignerColorUtils.normalizeHex(m.groupValues[2].trim())
                }
            }
            if (lower.contains("/values") || lower.endsWith("strings.xml")) {
                stringRx.findAll(file.content).forEach { m ->
                    strings[m.groupValues[1]] = m.groupValues[2].trim()
                }
            }
        }
        return ResourceMaps(colors, strings)
    }

    private fun resolveXmlColor(raw: String?, res: ResourceMaps, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        val t = raw.trim()
        if (t.startsWith("@color/")) {
            return res.colors[t.removePrefix("@color/")] ?: fallback
        }
        if (t.startsWith("#")) return ApkDesignerColorUtils.normalizeHex(t, fallback)
        return when (t.lowercase()) {
            "@android:color/white" -> "#FFFFFF"
            "@android:color/black" -> "#000000"
            "@android:color/transparent" -> "transparent"
            else -> fallback
        }
    }

    private fun resolveXmlString(raw: String?, res: ResourceMaps): String {
        if (raw.isNullOrBlank()) return ""
        val t = raw.trim()
        if (t.startsWith("@string/")) {
            return res.strings[t.removePrefix("@string/")] ?: t.removePrefix("@string/")
        }
        return t.replace("\\n", "\n")
    }

    private fun extractKtColorHex(expr: String?): String? {
        if (expr.isNullOrBlank()) return null
        val hexMatch = Regex("""0x([0-9A-Fa-f]{6,8})""").find(expr)
        if (hexMatch != null) {
            val raw = hexMatch.groupValues[1]
            return if (raw.length == 8) {
                val alpha = raw.substring(0, 2).toIntOrNull(16) ?: 255
                if (alpha == 0) "transparent" else "#${raw.substring(2).uppercase()}"
            } else {
                "#${raw.uppercase()}"
            }
        }
        val parseMatch = Regex("""Color\.parseColor\(\s*"(#[0-9A-Fa-f]{6,8})"\s*\)""").find(expr)
        if (parseMatch != null) return ApkDesignerColorUtils.normalizeHex(parseMatch.groupValues[1])

        return when {
            expr.contains("Color.White") || expr.contains("Color.WHITE") -> "#FFFFFF"
            expr.contains("Color.Black") || expr.contains("Color.BLACK") -> "#000000"
            expr.contains("Color.Transparent") || expr.contains("Color.TRANSPARENT") -> "transparent"
            expr.contains("Color.Gray") || expr.contains("Color.GRAY") -> "#8A98A5"
            expr.contains("Color.LightGray") -> "#CBD5E1"
            expr.contains("Color.DarkGray") -> "#334155"
            expr.contains("Color.Red") -> "#EF4444"
            expr.contains("Color.Blue") -> "#3B82F6"
            expr.contains("Color.Green") -> "#22C55E"
            expr.contains("Color.Cyan") -> "#06B6D4"
            else -> null
        }
    }

    private fun unescapeKotlinString(raw: String): String {
        return raw
            .replace("\\n", "\n")
            .replace("\\t", "  ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun extractFirstStringLiteral(expr: String): String? {
        var i = 0
        while (i < expr.length) {
            if (expr[i] == '"') {
                if (i + 2 < expr.length && expr.startsWith("\"\"\"", i)) {
                    val end = expr.indexOf("\"\"\"", i + 3)
                    return if (end > i) expr.substring(i + 3, end) else null
                }
                val sb = StringBuilder()
                i++
                while (i < expr.length) {
                    val ch = expr[i]
                    if (ch == '\\' && i + 1 < expr.length) {
                        val next = expr[i + 1]
                        when (next) {
                            'n' -> sb.append('\n')
                            't' -> sb.append("  ")
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            else -> sb.append(next)
                        }
                        i += 2
                    } else if (ch == '"') {
                        return sb.toString()
                    } else {
                        sb.append(ch)
                        i++
                    }
                }
                return sb.toString()
            }
            i++
        }
        return null
    }

    private fun skipStringOrComment(src: String, index: Int): Int {
        if (index >= src.length) return index
        val ch = src[index]
        if (ch == '/' && index + 1 < src.length && src[index + 1] == '/') {
            val nl = src.indexOf('\n', index + 2)
            return if (nl == -1) src.length else nl + 1
        }
        if (ch == '/' && index + 1 < src.length && src[index + 1] == '*') {
            val end = src.indexOf("*/", index + 2)
            return if (end == -1) src.length else end + 2
        }
        if (ch == '"') {
            if (index + 2 < src.length && src.startsWith("\"\"\"", index)) {
                val end = src.indexOf("\"\"\"", index + 3)
                return if (end == -1) src.length else end + 3
            }
            var i = index + 1
            while (i < src.length) {
                if (src[i] == '\\') {
                    i += 2
                } else if (src[i] == '"') {
                    return i + 1
                } else {
                    i++
                }
            }
            return src.length
        }
        if (ch == '\'') {
            var i = index + 1
            while (i < src.length) {
                if (src[i] == '\\') i += 2
                else if (src[i] == '\'') return i + 1
                else i++
            }
            return src.length
        }
        return index
    }

    private fun findMatchingDelimiter(src: String, openIndex: Int, openChar: Char, closeChar: Char): Int {
        if (openIndex < 0 || openIndex >= src.length || src[openIndex] != openChar) return -1
        var depth = 0
        var i = openIndex
        while (i < src.length) {
            val skipped = skipStringOrComment(src, i)
            if (skipped > i) {
                i = skipped
                continue
            }
            when (src[i]) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun splitTopLevelArgs(argStr: String): List<String> {
        val out = mutableListOf<String>()
        var depthParen = 0
        var depthBrace = 0
        var depthBracket = 0
        var start = 0
        var i = 0
        while (i < argStr.length) {
            val skipped = skipStringOrComment(argStr, i)
            if (skipped > i) {
                i = skipped
                continue
            }
            when (argStr[i]) {
                '(' -> depthParen++
                ')' -> depthParen--
                '{' -> depthBrace++
                '}' -> depthBrace--
                '[' -> depthBracket++
                ']' -> depthBracket--
                ',' -> if (depthParen == 0 && depthBrace == 0 && depthBracket == 0) {
                    out.add(argStr.substring(start, i).trim())
                    start = i + 1
                }
            }
            i++
        }
        val tail = argStr.substring(start).trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    private fun parseIntDp(raw: String?, fallback: Int? = null): Int? {
        if (raw.isNullOrBlank()) return fallback
        val m = Regex("""(-?\d+)""").find(raw) ?: return fallback
        return m.groupValues[1].toIntOrNull() ?: fallback
    }

    private fun estimateTextHeight(text: String, width: Int, fontSize: Int): Int {
        val charsPerLine = max(8, (width / max(fontSize * 0.56, 5.0)).toInt())
        val lines = text.split("\n").sumOf { line ->
            max(1, ceil(line.length.toDouble() / charsPerLine).toInt())
        }
        return max(22, (lines * fontSize * 1.42 + 8).roundToInt())
    }

    /* ---------------------------------------------------------------------- */
    /* 1. XML LAYOUT IMPORTER                                                 */
    /* ---------------------------------------------------------------------- */

    private fun importXmlScreen(file: SourceFileEntry, res: ResourceMaps): DesignerScreen {
        val xml = file.content
        val bgMatch = Regex("""android:background="([^"]+)"""").find(xml)
        val screenBg = resolveXmlColor(bgMatch?.groupValues?.get(1), res, "#202A33")
        val lightBg = ApkDesignerColorUtils.isLightHex(screenBg)
        val defaultTextColor = if (lightBg) "#17232B" else "#EEF4F6"

        val elements = mutableListOf<DesignerElement>()
        val tagRx = Regex("""<([A-Za-z0-9_.]+)\b([^>]*)/?>""")
        var cursorY = 24

        for (match in tagRx.findAll(xml)) {
            val rawTag = match.groupValues[1]
            val tag = rawTag.substringAfterLast('.')
            val attrs = match.groupValues[2]
            if (tag in setOf("LinearLayout", "FrameLayout", "RelativeLayout", "ConstraintLayout", "ScrollView", "HorizontalScrollView", "CoordinatorLayout", "merge", "include")) {
                continue
            }

            fun attr(name: String): String? =
                Regex("""$name="([^"]+)"""").find(attrs)?.groupValues?.get(1)

            val text = resolveXmlString(attr("android:text"), res)
            val hint = resolveXmlString(attr("android:hint"), res)
            val textSize = parseIntDp(attr("android:textSize"), 14) ?: 14
            val textColor = resolveXmlColor(attr("android:textColor"), res, defaultTextColor)
            val fill = resolveXmlColor(attr("android:background") ?: attr("app:backgroundTint"), res, "transparent")
            val gravity = attr("android:gravity") ?: attr("android:textAlignment") ?: ""
            val align = when {
                gravity.contains("center", ignoreCase = true) -> TextAlignMode.CENTER
                gravity.contains("end", ignoreCase = true) || gravity.contains("right", ignoreCase = true) -> TextAlignMode.RIGHT
                else -> TextAlignMode.LEFT
            }
            val rawW = attr("android:layout_width")
            val rawH = attr("android:layout_height")
            val marginStart = parseIntDp(attr("android:layout_marginStart") ?: attr("android:layout_marginLeft"), 18) ?: 18
            val marginTop = parseIntDp(attr("android:layout_marginTop"), null)
            val width = when {
                rawW == "match_parent" || rawW == "fill_parent" || rawW.isNullOrBlank() -> CANVAS_W - marginStart * 2
                rawW == "wrap_content" -> min(CANVAS_W - 36, max(120, text.length * (textSize / 2 + 2) + 32))
                else -> (parseIntDp(rawW, CANVAS_W - 36) ?: (CANVAS_W - 36)).coerceIn(32, CANVAS_W - 16)
            }

            val y = marginTop ?: cursorY
            val el = when (tag) {
                "Button", "MaterialButton", "AppCompatButton" -> {
                    val h = parseIntDp(rawH, 46) ?: 46
                    DesignerElement(
                        id = ApkDesignerModels.nextId("xml"),
                        kind = ElementKind.BUTTON,
                        name = text.ifBlank { "Button" }.take(22),
                        x = marginStart,
                        y = y,
                        width = width,
                        height = h,
                        text = text.ifBlank { "Button" },
                        fontSize = textSize,
                        bold = true,
                        textColor = if (fill == "transparent") "#FFFFFF" else textColor,
                        textAlign = TextAlignMode.CENTER,
                        fillColor = if (fill == "transparent") "#3E8790" else fill,
                        borderRadius = 12,
                    )
                }
                "EditText", "TextInputEditText", "AutoCompleteTextView" -> {
                    val h = parseIntDp(rawH, 46) ?: 46
                    DesignerElement(
                        id = ApkDesignerModels.nextId("xml"),
                        kind = ElementKind.INPUT,
                        name = hint.ifBlank { "Input" }.take(22),
                        x = marginStart,
                        y = y,
                        width = width,
                        height = h,
                        text = text,
                        placeholder = hint.ifBlank { "Enter value..." },
                        fontSize = textSize,
                        textColor = defaultTextColor,
                        textAlign = align,
                        fillColor = if (fill == "transparent") (if (lightBg) "#FFFFFF" else "#162028") else fill,
                        strokeColor = "#3A4E5D",
                        strokeWidth = 1,
                        borderRadius = 10,
                    )
                }
                "Switch", "SwitchCompat", "MaterialSwitch", "CheckBox" -> {
                    val h = parseIntDp(rawH, 40) ?: 40
                    DesignerElement(
                        id = ApkDesignerModels.nextId("xml"),
                        kind = ElementKind.SWITCH,
                        name = text.ifBlank { tag }.take(22),
                        x = marginStart,
                        y = y,
                        width = width,
                        height = h,
                        text = text.ifBlank { tag },
                        fontSize = textSize,
                        textColor = textColor,
                        fillColor = "#3E8790",
                        checked = attr("android:checked") == "true",
                    )
                }
                "ProgressBar", "LinearProgressIndicator" -> {
                    val h = parseIntDp(rawH, 14) ?: 14
                    DesignerElement(
                        id = ApkDesignerModels.nextId("xml"),
                        kind = ElementKind.PROGRESS,
                        name = "ProgressBar",
                        x = marginStart,
                        y = y,
                        width = width,
                        height = max(8, h),
                        fillColor = "#3E8790",
                        strokeColor = "#2B3B47",
                        progress = parseIntDp(attr("android:progress"), 60) ?: 60,
                    )
                }
                "ImageView", "ShapeableImageView" -> {
                    val h = parseIntDp(rawH, 140) ?: 140
                    DesignerElement(
                        id = ApkDesignerModels.nextId("xml"),
                        kind = ElementKind.IMAGE,
                        name = attr("android:contentDescription")?.take(22) ?: "ImageView",
                        x = marginStart,
                        y = y,
                        width = width,
                        height = h,
                        text = attr("android:src")?.substringAfterLast('/') ?: "Image",
                        textAlign = TextAlignMode.CENTER,
                        fillColor = if (fill == "transparent") "#243442" else fill,
                        strokeColor = "#4C6A7B",
                        strokeWidth = 1,
                        borderRadius = 14,
                    )
                }
                "FloatingActionButton" -> {
                    DesignerElement(
                        id = ApkDesignerModels.nextId("xml"),
                        kind = ElementKind.FAB,
                        name = "FAB",
                        x = CANVAS_W - 74,
                        y = max(y, 640),
                        width = 56,
                        height = 56,
                        text = "+",
                        fontSize = 24,
                        bold = true,
                        textColor = "#FFFFFF",
                        textAlign = TextAlignMode.CENTER,
                        fillColor = if (fill == "transparent") "#3E8790" else fill,
                        borderRadius = 28,
                    )
                }
                "TextView", "MaterialTextView" -> {
                    val isBold = attr("android:textStyle")?.contains("bold") == true || textSize >= 18
                    val h = parseIntDp(rawH, null) ?: estimateTextHeight(text.ifBlank { "Text" }, width, textSize)
                    DesignerElement(
                        id = ApkDesignerModels.nextId("xml"),
                        kind = if (isBold && textSize >= 17) ElementKind.HEADING else ElementKind.TEXT,
                        name = text.ifBlank { "TextView" }.take(22),
                        x = marginStart,
                        y = y,
                        width = width,
                        height = h,
                        text = text.ifBlank { "TextView" },
                        fontSize = textSize,
                        bold = isBold,
                        textColor = textColor,
                        textAlign = align,
                        fillColor = fill,
                        borderRadius = if (fill != "transparent") 8 else 0,
                    )
                }
                "View" -> {
                    val h = parseIntDp(rawH, 4) ?: 4
                    DesignerElement(
                        id = ApkDesignerModels.nextId("xml"),
                        kind = if (h <= 4) ElementKind.DIVIDER else ElementKind.RECTANGLE,
                        name = if (h <= 4) "Divider" else "View",
                        x = marginStart,
                        y = y,
                        width = width,
                        height = h,
                        fillColor = if (fill == "transparent") "#3A4E5D" else fill,
                        borderRadius = if (h <= 4) 1 else 10,
                    )
                }
                else -> null
            }

            if (el != null) {
                elements.add(el)
                cursorY = max(cursorY, el.y + el.height + 10)
            }
        }

        val screenName = file.path.substringAfterLast('/').removeSuffix(".xml")
            .split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
            .ifBlank { "XML Layout" }

        return DesignerScreen(
            id = ApkDesignerModels.nextId("xmlscr"),
            name = screenName,
            width = CANVAS_W,
            height = max(MIN_CANVAS_H, cursorY + 32),
            backgroundColor = screenBg,
            elements = elements,
            sourceFilePath = file.path,
            sourceKind = "xml",
            screenCategory = ScreenCategory.XML,
            sourceSymbol = screenName,
        )
    }

    /* ---------------------------------------------------------------------- */
    /* 2. JETPACK COMPOSE MULTI-SCREEN & ALERTDIALOG IMPORTER                 */
    /* ---------------------------------------------------------------------- */

    private fun extractComposeScreens(file: SourceFileEntry): List<DesignerScreen> {
        val code = file.content
        val screens = mutableListOf<DesignerScreen>()
        val fnRx = Regex("""(?:^|\n)\s*@Composable\b[\s\S]{0,180}?\bfun\s+([A-Z][A-Za-z0-9_]*)\s*\(""")

        for (m in fnRx.findAll(code)) {
            val fnName = m.groupValues[1]
            val openParen = code.indexOf('(', m.range.first)
            val closeParen = findMatchingDelimiter(code, openParen, '(', ')')
            if (closeParen < 0) continue
            val openBrace = code.indexOf('{', closeParen)
            if (openBrace < 0 || openBrace - closeParen > 80) continue
            val closeBrace = findMatchingDelimiter(code, openBrace, '{', '}')
            if (closeBrace < 0) continue

            val body = code.substring(openBrace + 1, closeBrace)
            val parsed = parseComposeBodyToScreen(
                body = body,
                name = fnName.removeSuffix("Screen").ifBlank { fnName },
                filePath = file.path,
                category = ScreenCategory.PAGE,
                symbol = fnName,
            )
            if (parsed.elements.isNotEmpty()) {
                screens.add(parsed)
            }

            // Also extract inline AlertDialog(...) windows inside this Composable
            val dlgRx = Regex("""\bAlertDialog\s*\(""")
            var dlgIdx = 0
            for (dm in dlgRx.findAll(body)) {
                val dOpen = body.indexOf('(', dm.range.first)
                val dClose = findMatchingDelimiter(body, dOpen, '(', ')')
                if (dClose < 0) continue
                val dArgs = body.substring(dOpen + 1, dClose)
                val titleText = Regex("""title\s*=\s*\{[\s\S]*?Text\(\s*"([^"]+)"""").find(dArgs)?.groupValues?.get(1)
                    ?: "Dialog ${++dlgIdx}"
                val bodyText = Regex("""text\s*=\s*\{[\s\S]*?Text\(\s*"([^"]+)"""").find(dArgs)?.groupValues?.get(1)
                    ?: ""
                val confirmText = Regex("""confirmButton\s*=\s*\{[\s\S]*?Text\(\s*"([^"]+)"""").find(dArgs)?.groupValues?.get(1)
                    ?: "OK"
                val dismissText = Regex("""dismissButton\s*=\s*\{[\s\S]*?Text\(\s*"([^"]+)"""").find(dArgs)?.groupValues?.get(1)

                val dlgEls = mutableListOf<DesignerElement>()
                dlgEls.add(
                    DesignerElement(
                        id = ApkDesignerModels.nextId("dlg"),
                        kind = ElementKind.CARD,
                        name = "Dialog Card",
                        x = 20,
                        y = 140,
                        width = 320,
                        height = 240,
                        fillColor = "#24323D",
                        strokeColor = "#3A4E5D",
                        strokeWidth = 1,
                        borderRadius = 20,
                        shadow = 6,
                    )
                )
                dlgEls.add(
                    DesignerElement(
                        id = ApkDesignerModels.nextId("dlg"),
                        kind = ElementKind.HEADING,
                        name = "Dialog Title",
                        x = 38,
                        y = 162,
                        width = 284,
                        height = 30,
                        text = unescapeKotlinString(titleText),
                        fontSize = 18,
                        bold = true,
                        textColor = "#EEF4F6",
                        fillColor = "transparent",
                    )
                )
                var cy = 200
                if (bodyText.isNotBlank()) {
                    val unesc = unescapeKotlinString(bodyText)
                    val h = estimateTextHeight(unesc, 284, 13)
                    dlgEls.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("dlg"),
                            kind = ElementKind.TEXT,
                            name = "Dialog Message",
                            x = 38,
                            y = cy,
                            width = 284,
                            height = h,
                            text = unesc,
                            fontSize = 13,
                            textColor = "#B6C5CE",
                            fillColor = "transparent",
                        )
                    )
                    cy += h + 18
                } else {
                    cy += 24
                }
                if (dismissText != null) {
                    dlgEls.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("dlg"),
                            kind = ElementKind.BUTTON,
                            name = "Dismiss Button",
                            x = 38,
                            y = cy,
                            width = 136,
                            height = 42,
                            text = unescapeKotlinString(dismissText),
                            fontSize = 13,
                            bold = true,
                            textColor = "#EEF4F6",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = "#344653",
                            borderRadius = 12,
                        )
                    )
                }
                dlgEls.add(
                    DesignerElement(
                        id = ApkDesignerModels.nextId("dlg"),
                        kind = ElementKind.BUTTON,
                        name = "Confirm Button",
                        x = if (dismissText != null) 186 else 180,
                        y = cy,
                        width = if (dismissText != null) 136 else 142,
                        height = 42,
                        text = unescapeKotlinString(confirmText),
                        fontSize = 13,
                        bold = true,
                        textColor = "#FFFFFF",
                        textAlign = TextAlignMode.CENTER,
                        fillColor = "#3E8790",
                        borderRadius = 12,
                    )
                )
                dlgEls[0] = dlgEls[0].copy(height = max(180, (cy + 62) - 140))

                screens.add(
                    DesignerScreen(
                        id = ApkDesignerModels.nextId("cdlg"),
                        name = "Dialog: ${unescapeKotlinString(titleText)}",
                        width = CANVAS_W,
                        height = MIN_CANVAS_H,
                        backgroundColor = "#121A21",
                        elements = dlgEls,
                        sourceFilePath = file.path,
                        sourceKind = "compose",
                        screenCategory = ScreenCategory.DIALOG,
                        sourceSymbol = "AlertDialog:$titleText",
                    )
                )
            }
        }
        return screens
    }

    private fun parseComposeBodyToScreen(
        body: String,
        name: String,
        filePath: String,
        category: ScreenCategory,
        symbol: String,
    ): DesignerScreen {
        val bgMatch = Regex("""\.background\(\s*([^,)]+)""").find(body)
        val screenBg = extractKtColorHex(bgMatch?.groupValues?.get(1)) ?: "#18222B"
        val isLight = ApkDesignerColorUtils.isLightHex(screenBg)
        val fallbackText = if (isLight) "#17232B" else "#EEF4F6"

        val elements = mutableListOf<DesignerElement>()
        val callRx = Regex("""\b(TopAppBar|CenterAlignedTopAppBar|Card|Surface|Button|ElevatedButton|OutlinedButton|OutlinedTextField|TextField|Switch|LinearProgressIndicator|FloatingActionButton|Text|HorizontalDivider|Divider|Spacer)\s*\(""")

        var cursorY = 24
        var i = 0
        while (i < body.length) {
            val match = callRx.find(body, i) ?: break
            val widget = match.groupValues[1]
            val openParen = body.indexOf('(', match.range.first)
            val closeParen = findMatchingDelimiter(body, openParen, '(', ')')
            if (closeParen < 0) {
                i = match.range.last + 1
                continue
            }
            val args = body.substring(openParen + 1, closeParen)
            var trailLambda = ""
            var nextPos = closeParen + 1
            var ws = nextPos
            while (ws < body.length && body[ws].isWhitespace()) ws++
            if (ws < body.length && body[ws] == '{') {
                val closeBrace = findMatchingDelimiter(body, ws, '{', '}')
                if (closeBrace > ws) {
                    trailLambda = body.substring(ws + 1, closeBrace)
                    nextPos = closeBrace + 1
                }
            }

            val offX = Regex("""offset\([^)]*x\s*=\s*(\d+)\.dp""").find(args)?.groupValues?.get(1)?.toIntOrNull()
            val offY = Regex("""offset\([^)]*y\s*=\s*(\d+)\.dp""").find(args)?.groupValues?.get(1)?.toIntOrNull()
            val explicitW = Regex("""(?:width|size)\(\s*(?:width\s*=\s*)?(\d+)\.dp""").find(args)?.groupValues?.get(1)?.toIntOrNull()
            val explicitH = Regex("""height\s*=\s*(\d+)\.dp|\.height\(\s*(\d+)\.dp""").find(args)?.let {
                (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull()
            }
            val colorExpr = Regex("""(?:containerColor|color)\s*=\s*([^,)\n]+)""").find(args)?.groupValues?.get(1)
            val textColorExpr = Regex("""contentColor\s*=\s*([^,)\n]+)""").find(args)?.groupValues?.get(1)
            val fontSize = Regex("""fontSize\s*=\s*(\d+)\.sp""").find(args)?.groupValues?.get(1)?.toIntOrNull() ?: 14
            val radius = Regex("""RoundedCornerShape\(\s*(\d+)\.dp""").find(args)?.groupValues?.get(1)?.toIntOrNull() ?: 12
            val align = when {
                args.contains("TextAlign.Center") -> TextAlignMode.CENTER
                args.contains("TextAlign.End") || args.contains("TextAlign.Right") -> TextAlignMode.RIGHT
                else -> TextAlignMode.LEFT
            }

            val x = offX ?: 18
            val y = offY ?: cursorY
            val w = (explicitW ?: (CANVAS_W - x * 2)).coerceIn(36, CANVAS_W)

            when (widget) {
                "Spacer" -> {
                    val spH = explicitH ?: 12
                    cursorY += spH
                }
                "TopAppBar", "CenterAlignedTopAppBar" -> {
                    val label = extractFirstStringLiteral(args) ?: name
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("cmp"),
                            kind = ElementKind.TOP_BAR,
                            name = "TopAppBar",
                            x = 0,
                            y = 0,
                            width = CANVAS_W,
                            height = 56,
                            text = label,
                            fontSize = 18,
                            bold = true,
                            textColor = "#EEF4F6",
                            textAlign = if (widget.startsWith("Center")) TextAlignMode.CENTER else TextAlignMode.LEFT,
                            fillColor = extractKtColorHex(colorExpr) ?: "#1B252E",
                            borderRadius = 0,
                        )
                    )
                    cursorY = max(cursorY, 68)
                }
                "Button", "ElevatedButton", "OutlinedButton" -> {
                    val label = extractFirstStringLiteral(trailLambda) ?: extractFirstStringLiteral(args) ?: "Button"
                    val h = explicitH ?: 46
                    val fill = extractKtColorHex(colorExpr) ?: if (widget == "OutlinedButton") "transparent" else "#3E8790"
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("cmp"),
                            kind = if (widget == "OutlinedButton") ElementKind.OUTLINED_BUTTON else ElementKind.BUTTON,
                            name = label.take(22),
                            x = x,
                            y = y,
                            width = w,
                            height = h,
                            text = label,
                            fontSize = fontSize,
                            bold = true,
                            textColor = extractKtColorHex(textColorExpr) ?: "#FFFFFF",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = fill,
                            strokeColor = if (widget == "OutlinedButton") "#568B91" else "transparent",
                            strokeWidth = if (widget == "OutlinedButton") 2 else 0,
                            borderRadius = radius,
                        )
                    )
                    cursorY = max(cursorY, y + h + 10)
                    i = nextPos
                    continue
                }
                "OutlinedTextField", "TextField" -> {
                    val ph = Regex("""placeholder\s*=\s*\{[\s\S]*?"([^"]+)"""").find(args)?.groupValues?.get(1)
                        ?: Regex("""label\s*=\s*\{[\s\S]*?"([^"]+)"""").find(args)?.groupValues?.get(1)
                        ?: "Enter value..."
                    val h = explicitH ?: 48
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("cmp"),
                            kind = ElementKind.INPUT,
                            name = ph.take(22),
                            x = x,
                            y = y,
                            width = w,
                            height = h,
                            placeholder = unescapeKotlinString(ph),
                            fontSize = 14,
                            textColor = fallbackText,
                            fillColor = if (isLight) "#FFFFFF" else "#162028",
                            strokeColor = "#3A4E5D",
                            strokeWidth = 1,
                            borderRadius = 10,
                        )
                    )
                    cursorY = max(cursorY, y + h + 10)
                }
                "Switch" -> {
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("cmp"),
                            kind = ElementKind.SWITCH,
                            name = "Switch",
                            x = x,
                            y = y,
                            width = min(w, 260),
                            height = 38,
                            text = "Toggle Option",
                            textColor = fallbackText,
                            fillColor = "#3E8790",
                            checked = args.contains("checked = true"),
                        )
                    )
                    cursorY = max(cursorY, y + 46)
                }
                "LinearProgressIndicator" -> {
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("cmp"),
                            kind = ElementKind.PROGRESS,
                            name = "Progress",
                            x = x,
                            y = y,
                            width = w,
                            height = 12,
                            fillColor = extractKtColorHex(colorExpr) ?: "#3E8790",
                            strokeColor = "#2B3B47",
                            progress = 68,
                        )
                    )
                    cursorY = max(cursorY, y + 22)
                }
                "FloatingActionButton" -> {
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("cmp"),
                            kind = ElementKind.FAB,
                            name = "FAB",
                            x = offX ?: (CANVAS_W - 74),
                            y = offY ?: max(cursorY, 640),
                            width = 56,
                            height = 56,
                            text = extractFirstStringLiteral(trailLambda) ?: "+",
                            fontSize = 22,
                            bold = true,
                            textColor = "#FFFFFF",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = extractKtColorHex(colorExpr) ?: "#3E8790",
                            borderRadius = 28,
                        )
                    )
                    i = nextPos
                    continue
                }
                "Card", "Surface" -> {
                    val h = explicitH ?: 108
                    val fill = extractKtColorHex(colorExpr) ?: if (isLight) "#FFFFFF" else "#2B3742"
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("cmp"),
                            kind = ElementKind.CARD,
                            name = widget,
                            x = x,
                            y = y,
                            width = w,
                            height = h,
                            fillColor = fill,
                            strokeColor = "#3C5060",
                            strokeWidth = 1,
                            borderRadius = radius,
                            shadow = 3,
                        )
                    )
                    if (offY == null) cursorY = max(cursorY, y + 14)
                }
                "HorizontalDivider", "Divider" -> {
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("cmp"),
                            kind = ElementKind.DIVIDER,
                            name = "Divider",
                            x = x,
                            y = y,
                            width = w,
                            height = 2,
                            fillColor = extractKtColorHex(colorExpr) ?: "#3A4E5D",
                        )
                    )
                    cursorY = max(cursorY, y + 12)
                }
                "Text" -> {
                    val label = extractFirstStringLiteral(args)
                    if (!label.isNullOrBlank()) {
                        val isBold = args.contains("FontWeight.Bold") || args.contains("FontWeight.SemiBold") || fontSize >= 17
                        val h = explicitH ?: estimateTextHeight(label, w, fontSize)
                        elements.add(
                            DesignerElement(
                                id = ApkDesignerModels.nextId("cmp"),
                                kind = if (isBold && fontSize >= 17) ElementKind.HEADING else ElementKind.TEXT,
                                name = label.take(22),
                                x = x,
                                y = y,
                                width = w,
                                height = h,
                                text = label,
                                fontSize = fontSize,
                                bold = isBold,
                                textColor = extractKtColorHex(colorExpr) ?: fallbackText,
                                textAlign = align,
                                fillColor = "transparent",
                            )
                        )
                        cursorY = max(cursorY, y + h + 8)
                    }
                }
            }
            i = closeParen + 1
        }

        return DesignerScreen(
            id = ApkDesignerModels.nextId("cmpscr"),
            name = name,
            width = CANVAS_W,
            height = max(MIN_CANVAS_H, cursorY + 32),
            backgroundColor = screenBg,
            elements = elements,
            sourceFilePath = filePath,
            sourceKind = "compose",
            screenCategory = category,
            sourceSymbol = symbol,
        )
    }

    /* ---------------------------------------------------------------------- */
    /* 3. PROGRAMMATIC KOTLIN VIEW GRAPH IMPORTER (MainActivity.kt & others)  */
    /* ---------------------------------------------------------------------- */

    private data class KtNode(
        val id: String,
        var varName: String? = null,
        var kind: String = "container", // container, card, text, button, input, spinner, progress, image, spacer, menuTile, accordionTile, navTab
        var orientation: String = "vertical",
        var text: String? = null,
        var subtitle: String? = null,
        var hint: String? = null,
        var icon: String? = null,
        var fontSize: Int? = null,
        var bold: Boolean = false,
        var textColor: String? = null,
        var textAlign: TextAlignMode? = null,
        var fillColor: String? = null,
        var strokeColor: String? = null,
        var strokeWidth: Int = 0,
        var borderRadius: Int? = null,
        var width: Int? = null,
        var height: Int? = null,
        var weight: Float = 0f,
        var padLeft: Int = 0,
        var padTop: Int = 0,
        var padRight: Int = 0,
        var padBottom: Int = 0,
        var isOval: Boolean = false,
        val children: MutableList<KtNode> = mutableListOf(),
    )

    private fun extractBackgroundStyle(block: String): Triple<String?, String?, Int?> {
        var fill: String? = null
        var stroke: String? = null
        var radius: Int? = null

        val prem = Regex("""premiumGradient\(\s*([^,]+),\s*([^,]+),\s*(\d+)""").find(block)
        if (prem != null) {
            fill = extractKtColorHex(prem.groupValues[1])
            radius = prem.groupValues[3].toIntOrNull()
        }
        val rnd = Regex("""roundedSurface\(\s*([^,]+),\s*(\d+)""").find(block)
        if (rnd != null) {
            fill = extractKtColorHex(rnd.groupValues[1])
            radius = rnd.groupValues[2].toIntOrNull()
        }
        val grad = Regex("""intArrayOf\(\s*([^,)]+)""").find(block)
        if (grad != null && fill == null) {
            fill = extractKtColorHex(grad.groupValues[1])
        }
        val solid = Regex("""setColor\(\s*([^)]+)\)""").find(block)
        if (solid != null && fill == null) {
            fill = extractKtColorHex(solid.groupValues[1])
        }
        val bgCol = Regex("""setBackgroundColor\(\s*([^)]+)\)""").find(block)
        if (bgCol != null && fill == null) {
            fill = extractKtColorHex(bgCol.groupValues[1])
        }
        val strokeMatch = Regex("""setStroke\(\s*[^,]+,\s*([^)]+)\)""").find(block)
        if (strokeMatch != null) {
            stroke = extractKtColorHex(strokeMatch.groupValues[1])
        }
        val cornerMatch = Regex("""cornerRadius\s*=\s*dp\((\d+)\)""").find(block)
        if (cornerMatch != null) {
            radius = cornerMatch.groupValues[1].toIntOrNull()
        }
        return Triple(fill, stroke, radius)
    }

    private fun applyBlockModifiers(node: KtNode, block: String) {
        if (block.contains("LinearLayout.HORIZONTAL")) node.orientation = "horizontal"
        if (block.contains("LinearLayout.VERTICAL")) node.orientation = "vertical"
        if (block.contains("Gravity.CENTER")) node.textAlign = TextAlignMode.CENTER
        else if (block.contains("Gravity.END") || block.contains("Gravity.RIGHT")) node.textAlign = TextAlignMode.RIGHT

        val textAssign = Regex("""\btext\s*=\s*("[^"]*")""").find(block)
        if (textAssign != null) node.text = extractFirstStringLiteral(textAssign.groupValues[1])
        val hintAssign = Regex("""\bhint\s*=\s*("[^"]*")""").find(block)
        if (hintAssign != null) node.hint = extractFirstStringLiteral(hintAssign.groupValues[1])
        val tsAssign = Regex("""\btextSize\s*=\s*(\d+(?:\.\d+)?)f?""").find(block)
        if (tsAssign != null) node.fontSize = tsAssign.groupValues[1].toDoubleOrNull()?.roundToInt()
        val tcAssign = Regex("""setTextColor\(\s*([^)]+)\)""").find(block)
        if (tcAssign != null) node.textColor = extractKtColorHex(tcAssign.groupValues[1])

        val pad = Regex("""setPadding\(\s*dp\((\d+)\)\s*,\s*dp\((\d+)\)\s*,\s*dp\((\d+)\)\s*,\s*dp\((\d+)\)\s*\)""").find(block)
        if (pad != null) {
            node.padLeft = pad.groupValues[1].toIntOrNull() ?: 0
            node.padTop = pad.groupValues[2].toIntOrNull() ?: 0
            node.padRight = pad.groupValues[3].toIntOrNull() ?: 0
            node.padBottom = pad.groupValues[4].toIntOrNull() ?: 0
        }
        val (fill, stroke, radius) = extractBackgroundStyle(block)
        if (fill != null) node.fillColor = fill
        if (stroke != null) {
            node.strokeColor = stroke
            node.strokeWidth = 1
        }
        if (radius != null) node.borderRadius = radius
        if (block.contains("GradientDrawable.OVAL")) node.isOval = true
    }

    private fun parseLayoutParamsSpec(lpExpr: String?): Triple<Int?, Int?, Float> {
        if (lpExpr.isNullOrBlank()) return Triple(null, null, 0f)
        val lpCall = Regex("""LayoutParams\(([^)]+)\)""").find(lpExpr) ?: return Triple(null, null, 0f)
        val args = splitTopLevelArgs(lpCall.groupValues[1])
        val w = args.getOrNull(0)?.let { Regex("""dp\((\d+)\)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val h = args.getOrNull(1)?.let { Regex("""dp\((\d+)\)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val weight = args.getOrNull(2)?.removeSuffix("f")?.toFloatOrNull() ?: 0f
        return Triple(w, h, weight)
    }

    private fun createNodeFromExpr(expr: String, env: MutableMap<String, KtNode>): KtNode? {
        val trimmed = expr.trim()
        // Variable reference
        if (Regex("""^[A-Za-z_][A-Za-z0-9_]*$""").matches(trimmed)) {
            return env[trimmed]
        }

        // Helper: space(dp)
        val spaceMatch = Regex("""^space\(\s*(\d+)\s*\)$""").find(trimmed)
        if (spaceMatch != null) {
            val v = spaceMatch.groupValues[1].toIntOrNull() ?: 8
            return KtNode(id = ApkDesignerModels.nextId("n"), kind = "spacer", width = v, height = v)
        }

        // Helper: label(...)
        if (trimmed.startsWith("label(")) {
            val close = findMatchingDelimiter(trimmed, trimmed.indexOf('('), '(', ')')
            if (close > 0) {
                val args = splitTopLevelArgs(trimmed.substring(trimmed.indexOf('(') + 1, close))
                val text = args.getOrNull(0)?.let { extractFirstStringLiteral(it) } ?: ""
                val size = args.getOrNull(1)?.let { parseIntDp(it, 14) } ?: 14
                val color = args.getOrNull(2)?.let { extractKtColorHex(it) }
                val bold = args.any { it == "true" }
                val node = KtNode(
                    id = ApkDesignerModels.nextId("n"),
                    kind = "text",
                    text = text,
                    fontSize = size,
                    bold = bold,
                    textColor = color,
                    fillColor = "transparent",
                )
                val applyIdx = trimmed.indexOf(".apply", close)
                if (applyIdx >= 0) {
                    val bStart = trimmed.indexOf('{', applyIdx)
                    val bEnd = findMatchingDelimiter(trimmed, bStart, '{', '}')
                    if (bEnd > bStart) applyBlockModifiers(node, trimmed.substring(bStart + 1, bEnd))
                }
                return node
            }
        }

        // Helper: heroText("Manage") / heroActionArrow()
        if (trimmed.startsWith("heroText(")) {
            val text = extractFirstStringLiteral(trimmed) ?: ""
            return KtNode(
                id = ApkDesignerModels.nextId("n"),
                kind = "text",
                text = text,
                fontSize = 15,
                textColor = "#315E70",
                fillColor = "transparent",
                width = max(14, text.length * 9 + 10),
            )
        }
        if (trimmed.startsWith("heroActionArrow(")) {
            return KtNode(
                id = ApkDesignerModels.nextId("n"),
                kind = "button",
                text = "→",
                fontSize = 20,
                bold = true,
                textColor = "#FFFFFF",
                textAlign = TextAlignMode.CENTER,
                fillColor = "#3E8790",
                strokeColor = "#75AEB1",
                strokeWidth = 1,
                borderRadius = 16,
                width = 52,
                height = 38,
            )
        }

        // Helper: welcomeMenuItem(symbol, title) { ... }
        if (trimmed.startsWith("welcomeMenuItem(")) {
            val close = findMatchingDelimiter(trimmed, trimmed.indexOf('('), '(', ')')
            if (close > 0) {
                val args = splitTopLevelArgs(trimmed.substring(trimmed.indexOf('(') + 1, close))
                val icon = args.getOrNull(0)?.let { extractFirstStringLiteral(it) } ?: "•"
                val title = args.getOrNull(1)?.let { extractFirstStringLiteral(it) } ?: "Menu Item"
                return KtNode(
                    id = ApkDesignerModels.nextId("n"),
                    kind = "menuTile",
                    icon = icon,
                    text = title,
                    fillColor = "#2B3742",
                    strokeColor = "#394B59",
                    strokeWidth = 1,
                    borderRadius = 14,
                    height = 54,
                )
            }
        }

        // Helper: actionButton / smallButton / button
        if (trimmed.startsWith("actionButton(") || trimmed.startsWith("smallButton(")) {
            val close = findMatchingDelimiter(trimmed, trimmed.indexOf('('), '(', ')')
            if (close > 0) {
                val args = splitTopLevelArgs(trimmed.substring(trimmed.indexOf('(') + 1, close))
                val text = args.getOrNull(0)?.let { extractFirstStringLiteral(it) } ?: "Action"
                val primary = args.getOrNull(1) == "true"
                val node = KtNode(
                    id = ApkDesignerModels.nextId("n"),
                    kind = "button",
                    text = text,
                    fontSize = if (trimmed.startsWith("smallButton(")) 12 else 14,
                    bold = true,
                    textColor = "#FFFFFF",
                    textAlign = TextAlignMode.CENTER,
                    fillColor = if (primary) "#568B91" else "#344653",
                    strokeColor = if (primary) "#75AEB1" else "#435867",
                    strokeWidth = 1,
                    borderRadius = 12,
                    height = if (trimmed.startsWith("smallButton(")) 40 else 44,
                )
                val applyIdx = trimmed.indexOf(".apply", close)
                if (applyIdx >= 0) {
                    val bStart = trimmed.indexOf('{', applyIdx)
                    val bEnd = findMatchingDelimiter(trimmed, bStart, '{', '}')
                    if (bEnd > bStart) applyBlockModifiers(node, trimmed.substring(bStart + 1, bEnd))
                }
                return node
            }
        }

        // Helper: navButton(icon, label)
        if (trimmed.startsWith("navButton(")) {
            val close = findMatchingDelimiter(trimmed, trimmed.indexOf('('), '(', ')')
            if (close > 0) {
                val args = splitTopLevelArgs(trimmed.substring(trimmed.indexOf('(') + 1, close))
                val icon = args.getOrNull(0)?.let { extractFirstStringLiteral(it) } ?: "•"
                val title = args.getOrNull(1)?.let { extractFirstStringLiteral(it) } ?: "Tab"
                return KtNode(
                    id = ApkDesignerModels.nextId("n"),
                    kind = "navTab",
                    icon = icon,
                    text = title,
                )
            }
        }

        // Helper: card()
        if (trimmed.startsWith("card(")) {
            val node = KtNode(
                id = ApkDesignerModels.nextId("n"),
                kind = "card",
                fillColor = "#2B3742",
                strokeColor = "#384B59",
                strokeWidth = 1,
                borderRadius = 16,
                padLeft = 14,
                padTop = 12,
                padRight = 14,
                padBottom = 12,
            )
            val applyIdx = trimmed.indexOf(".apply")
            if (applyIdx >= 0) {
                val bStart = trimmed.indexOf('{', applyIdx)
                val bEnd = findMatchingDelimiter(trimmed, bStart, '{', '}')
                if (bEnd > bStart) applyBlockModifiers(node, trimmed.substring(bStart + 1, bEnd))
            }
            return node
        }

        // Standard Android widgets: LinearLayout, ScrollView, FrameLayout, TextView, Button, EditText, Spinner, ProgressBar, ImageView
        val ctorMatch = Regex("""^(LinearLayout|ScrollView|HorizontalScrollView|FrameLayout|TextView|Button|EditText|Spinner|ProgressBar|ImageView)\(""").find(trimmed)
        if (ctorMatch != null) {
            val cls = ctorMatch.groupValues[1]
            val kind = when (cls) {
                "TextView" -> "text"
                "Button" -> "button"
                "EditText" -> "input"
                "Spinner" -> "spinner"
                "ProgressBar" -> "progress"
                "ImageView" -> "image"
                else -> "container"
            }
            val node = KtNode(
                id = ApkDesignerModels.nextId("n"),
                kind = kind,
                fillColor = when (kind) {
                    "input", "spinner" -> "#1A252D"
                    "button" -> "#344653"
                    else -> "transparent"
                },
                strokeColor = if (kind == "input" || kind == "spinner") "#3A4E5D" else null,
                strokeWidth = if (kind == "input" || kind == "spinner") 1 else 0,
                borderRadius = when (kind) {
                    "input", "spinner" -> 10
                    "button" -> 12
                    else -> 0
                },
                height = when (kind) {
                    "input", "spinner" -> 44
                    "progress" -> 10
                    else -> null
                },
            )
            val applyIdx = trimmed.indexOf(".apply")
            if (applyIdx >= 0) {
                val bStart = trimmed.indexOf('{', applyIdx)
                val bEnd = findMatchingDelimiter(trimmed, bStart, '{', '}')
                if (bEnd > bStart) {
                    val body = trimmed.substring(bStart + 1, bEnd)
                    applyBlockModifiers(node, body)
                    evaluateStatementsInto(body, env, node)
                }
            }
            return node
        }

        return null
    }

    private fun splitStatements(block: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var depthParen = 0
        var depthBrace = 0
        var depthBracket = 0
        var i = 0
        while (i < block.length) {
            val skipped = skipStringOrComment(block, i)
            if (skipped > i) {
                current.append(block, i, skipped)
                i = skipped
                continue
            }
            val ch = block[i]
            when (ch) {
                '(' -> { depthParen++; current.append(ch) }
                ')' -> { depthParen--; current.append(ch) }
                '{' -> { depthBrace++; current.append(ch) }
                '}' -> { depthBrace--; current.append(ch) }
                '[' -> { depthBracket++; current.append(ch) }
                ']' -> { depthBracket--; current.append(ch) }
                '\n', ';' -> {
                    if (depthParen == 0 && depthBrace == 0 && depthBracket == 0) {
                        val s = current.toString().trim()
                        if (s.isNotEmpty()) statements.add(s)
                        current.setLength(0)
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) statements.add(tail)
        return statements
    }

    private fun evaluateStatementsInto(
        block: String,
        env: MutableMap<String, KtNode>,
        implicitParent: KtNode? = null,
    ) {
        val statements = splitStatements(block)
        for (rawStmt in statements) {
            val stmt = rawStmt.trim()
            if (stmt.isEmpty() || stmt.startsWith("//") || stmt.startsWith("/*")) continue
            // Skip nested function declarations (their bodies are not executed at definition time)
            if (Regex("""^(?:private\s+)?fun\s+""").containsMatchIn(stmt)) continue

            // Destructuring: val (homeScroll, homeContent) = page()
            val pageDestr = Regex("""^val\s+\(\s*(\w+)\s*,\s*(\w+)\s*\)\s*=\s*page\(\)""").find(stmt)
            if (pageDestr != null) {
                val scrollVar = pageDestr.groupValues[1]
                val contentVar = pageDestr.groupValues[2]
                val contentNode = KtNode(
                    id = ApkDesignerModels.nextId("pg"),
                    varName = contentVar,
                    kind = "container",
                    padLeft = 14,
                    padTop = 10,
                    padRight = 14,
                    padBottom = 16,
                )
                val scrollNode = KtNode(
                    id = ApkDesignerModels.nextId("sc"),
                    varName = scrollVar,
                    kind = "container",
                )
                scrollNode.children.add(contentNode)
                env[scrollVar] = scrollNode
                env[contentVar] = contentNode
                continue
            }

            // Variable declaration or assignment: (val|var) foo = expr  OR  foo = expr
            val assignMatch = Regex("""^(?:(?:val|var)\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]+)?=\s*([\s\S]+)$""").find(stmt)
            if (assignMatch != null && !stmt.startsWith("if ") && !stmt.startsWith("if(")) {
                val name = assignMatch.groupValues[1]
                val rhs = assignMatch.groupValues[2].trim()
                val created = createNodeFromExpr(rhs, env)
                if (created != null) {
                    created.varName = name
                    env[name] = created
                }
                continue
            }

            // workspaceSection("Title", "Subtitle", contentVar, ..., parent)
            if (stmt.startsWith("workspaceSection(")) {
                val close = findMatchingDelimiter(stmt, stmt.indexOf('('), '(', ')')
                if (close > 0) {
                    val args = splitTopLevelArgs(stmt.substring(stmt.indexOf('(') + 1, close))
                    val title = args.getOrNull(0)?.let { extractFirstStringLiteral(it) } ?: "Section"
                    val subtitle = args.getOrNull(1)?.let { extractFirstStringLiteral(it) } ?: ""
                    val childVar = args.getOrNull(2)?.trim()
                    val parentVar = args.getOrNull(4)?.substringAfter("=")?.trim() ?: "editorContent"
                    val parentNode = env[parentVar]
                    val accNode = KtNode(
                        id = ApkDesignerModels.nextId("acc"),
                        kind = "accordionTile",
                        text = title,
                        subtitle = subtitle,
                        fillColor = "#263640",
                        strokeColor = "#384B59",
                        strokeWidth = 1,
                        borderRadius = 14,
                    )
                    childVar?.let { env[it] }?.let { accNode.children.add(it) }
                    parentNode?.children?.add(accNode)
                }
                continue
            }

            // addPageBackHeader(target, "Title")
            if (stmt.startsWith("addPageBackHeader(")) {
                val close = findMatchingDelimiter(stmt, stmt.indexOf('('), '(', ')')
                if (close > 0) {
                    val args = splitTopLevelArgs(stmt.substring(stmt.indexOf('(') + 1, close))
                    val targetNode = args.getOrNull(0)?.trim()?.let { env[it] }
                    val title = args.getOrNull(1)?.let { extractFirstStringLiteral(it) } ?: ""
                    if (targetNode != null && title.isNotBlank()) {
                        val headerRow = KtNode(
                            id = ApkDesignerModels.nextId("hdr"),
                            kind = "container",
                            orientation = "horizontal",
                            padTop = 4,
                            padBottom = 10,
                        )
                        headerRow.children.add(
                            KtNode(
                                id = ApkDesignerModels.nextId("bk"),
                                kind = "button",
                                text = "‹",
                                fontSize = 22,
                                bold = true,
                                textColor = "#EEF4F6",
                                textAlign = TextAlignMode.CENTER,
                                fillColor = "#344653",
                                borderRadius = 12,
                                width = 44,
                                height = 40,
                            )
                        )
                        headerRow.children.add(
                            KtNode(
                                id = ApkDesignerModels.nextId("ht"),
                                kind = "text",
                                text = title,
                                fontSize = 17,
                                bold = true,
                                textColor = "#E2E8ED",
                                weight = 1f,
                                padLeft = 8,
                            )
                        )
                        targetNode.children.add(0, headerRow)
                    }
                }
                continue
            }

            // listOf(a, b, c).forEach { target.addView(it) }
            val listForEach = Regex("""^listOf\(([^)]+)\)\.forEach\s*\{([\s\S]+)\}$""").find(stmt)
            if (listForEach != null) {
                val items = splitTopLevelArgs(listForEach.groupValues[1])
                val loopBody = listForEach.groupValues[2]
                val addTarget = Regex("""([A-Za-z_][A-Za-z0-9_]*)\.addView\(\s*it\s*""").find(loopBody)?.groupValues?.get(1)
                val targetNode = addTarget?.let { env[it] }
                if (targetNode != null) {
                    for (itemName in items) {
                        env[itemName.trim()]?.let { targetNode.children.add(it) }
                    }
                }
                continue
            }

            // target.addView(childExpr, layoutParams?)  OR  addView(childExpr, layoutParams?) inside apply
            val addViewMatch = Regex("""^(?:([A-Za-z_][A-Za-z0-9_]*)\.)?addView\(""").find(stmt)
            if (addViewMatch != null) {
                val targetName = addViewMatch.groupValues[1]
                val parent = if (targetName.isNotEmpty()) env[targetName] else implicitParent
                val openP = stmt.indexOf('(')
                val closeP = findMatchingDelimiter(stmt, openP, '(', ')')
                if (parent != null && closeP > openP) {
                    val args = splitTopLevelArgs(stmt.substring(openP + 1, closeP))
                    val childExpr = args.getOrNull(0)
                    val lpExpr = args.getOrNull(1)
                    if (!childExpr.isNullOrBlank()) {
                        val childNode = createNodeFromExpr(childExpr, env)
                        if (childNode != null) {
                            val (w, h, weight) = parseLayoutParamsSpec(lpExpr)
                            if (w != null) childNode.width = w
                            if (h != null) childNode.height = h
                            if (weight > 0f) childNode.weight = weight
                            parent.children.add(childNode)
                        }
                    }
                }
                continue
            }

            // Post-creation property mutation: someVar.text = "..." / someVar.background = ...
            val propAssign = Regex("""^([A-Za-z_][A-Za-z0-9_]*)\.(text|hint|background|setTextColor)\b([\s\S]+)$""").find(stmt)
            if (propAssign != null) {
                val target = env[propAssign.groupValues[1]]
                if (target != null) {
                    applyBlockModifiers(target, stmt.substringAfter('.'))
                }
            }
        }
    }

    private fun layoutKtTreeToElements(
        rootNode: KtNode,
        topChrome: KtNode? = null,
        bottomNavTabs: List<String> = emptyList(),
        activeBottomTab: String? = null,
    ): Pair<List<DesignerElement>, Int> {
        val elements = mutableListOf<DesignerElement>()
        var maxBottom = 64

        fun layoutNode(node: KtNode, x: Int, y: Int, availW: Int, parentTextColor: String): Int {
            when (node.kind) {
                "spacer" -> return min(24, node.height ?: 8)

                "menuTile" -> {
                    val h = node.height ?: 54
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("tile"),
                            kind = ElementKind.CARD,
                            name = node.text ?: "Menu Tile",
                            x = x,
                            y = y,
                            width = availW,
                            height = h,
                            fillColor = node.fillColor ?: "#2B3742",
                            strokeColor = node.strokeColor ?: "#394B59",
                            strokeWidth = 1,
                            borderRadius = node.borderRadius ?: 14,
                            shadow = 2,
                        )
                    )
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("tico"),
                            kind = ElementKind.TEXT,
                            name = "${node.text ?: "Tile"} Icon",
                            x = x + 12,
                            y = y + (h - 24) / 2,
                            width = 30,
                            height = 24,
                            text = node.icon ?: "•",
                            fontSize = 18,
                            bold = true,
                            textColor = "#91B5B5",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = "transparent",
                        )
                    )
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("ttxt"),
                            kind = ElementKind.HEADING,
                            name = node.text ?: "Tile Title",
                            x = x + 48,
                            y = y + (h - 24) / 2,
                            width = max(80, availW - 82),
                            height = 24,
                            text = node.text ?: "",
                            fontSize = 15,
                            bold = true,
                            textColor = "#E2E8ED",
                            textAlign = TextAlignMode.LEFT,
                            fillColor = "transparent",
                        )
                    )
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("tarr"),
                            kind = ElementKind.TEXT,
                            name = "Chevron",
                            x = x + availW - 28,
                            y = y + (h - 24) / 2,
                            width = 20,
                            height = 24,
                            text = "›",
                            fontSize = 20,
                            textColor = "#91A5B0",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = "transparent",
                        )
                    )
                    maxBottom = max(maxBottom, y + h)
                    return h + 8
                }

                "accordionTile" -> {
                    val cardIndex = elements.size
                    val secCard = DesignerElement(
                        id = ApkDesignerModels.nextId("acc"),
                        kind = ElementKind.CARD,
                        name = node.text ?: "Section",
                        x = x,
                        y = y,
                        width = availW,
                        height = 80,
                        fillColor = node.fillColor ?: "#263640",
                        strokeColor = node.strokeColor ?: "#384B59",
                        strokeWidth = 1,
                        borderRadius = 14,
                        shadow = 2,
                    )
                    elements.add(secCard)

                    val innerX = x + 12
                    val innerW = max(60, availW - 24)
                    var cy = y + 10
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("acct"),
                            kind = ElementKind.HEADING,
                            name = node.text ?: "Section Title",
                            x = innerX,
                            y = cy,
                            width = innerW - 20,
                            height = 22,
                            text = node.text ?: "Section",
                            fontSize = 13,
                            bold = true,
                            textColor = "#EEF4F6",
                            fillColor = "transparent",
                        )
                    )
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("accc"),
                            kind = ElementKind.TEXT,
                            name = "Toggle",
                            x = x + availW - 26,
                            y = cy,
                            width = 16,
                            height = 20,
                            text = "⌃",
                            fontSize = 14,
                            textColor = "#98AEBA",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = "transparent",
                        )
                    )
                    cy += 22
                    if (!node.subtitle.isNullOrBlank()) {
                        elements.add(
                            DesignerElement(
                                id = ApkDesignerModels.nextId("accs"),
                                kind = ElementKind.TEXT,
                                name = "Subtitle",
                                x = innerX,
                                y = cy,
                                width = innerW - 16,
                                height = 18,
                                text = node.subtitle!!,
                                fontSize = 11,
                                textColor = "#A8BAC3",
                                fillColor = "transparent",
                            )
                        )
                        cy += 20
                    }
                    for (child in node.children) {
                        cy += layoutNode(child, innerX, cy, innerW, "#EEF4F6")
                    }
                    val totalH = max(64, cy - y + 10)
                    elements[cardIndex] = secCard.copy(height = totalH)
                    maxBottom = max(maxBottom, y + totalH)
                    return totalH + 10
                }

                "card", "container" -> {
                    val hasBg = node.kind == "card" || (!node.fillColor.isNullOrBlank() && node.fillColor != "transparent")
                    val bgFill = node.fillColor ?: if (node.kind == "card") "#2B3742" else "transparent"
                    val nextFallback = if (hasBg && bgFill != "transparent") {
                        if (ApkDesignerColorUtils.isLightHex(bgFill)) "#17232B" else "#EEF4F6"
                    } else parentTextColor

                    val bgIndex = if (hasBg) {
                        val idx = elements.size
                        elements.add(
                            DesignerElement(
                                id = ApkDesignerModels.nextId("box"),
                                kind = ElementKind.CARD,
                                name = node.varName ?: "Card",
                                x = x,
                                y = y,
                                width = availW,
                                height = 64,
                                fillColor = bgFill,
                                strokeColor = node.strokeColor ?: if (ApkDesignerColorUtils.isLightHex(bgFill)) "#D9E5E9" else "#354957",
                                strokeWidth = max(1, node.strokeWidth),
                                borderRadius = node.borderRadius ?: 16,
                                shadow = 3,
                            )
                        )
                        idx
                    } else -1

                    val padL = if (hasBg) max(12, node.padLeft) else node.padLeft
                    val padR = if (hasBg) max(12, node.padRight) else node.padRight
                    val padT = if (hasBg) max(10, node.padTop) else node.padTop
                    val padB = if (hasBg) max(10, node.padBottom) else node.padBottom
                    val innerX = x + padL
                    val innerW = max(40, availW - padL - padR)

                    if (node.orientation == "horizontal" && node.children.isNotEmpty()) {
                        val visible = node.children.filter { it.kind != "spacer" }
                        val hasWeighted = visible.any { it.weight > 0f }
                        fun resolvedW(c: KtNode): Int? {
                            if (c.width != null) return c.width
                            if (hasWeighted && c.weight == 0f && (c.kind == "text" || c.kind == "button")) {
                                val len = (c.text ?: "").trim().length
                                val fs = c.fontSize ?: 12
                                val padH = max(12, c.padLeft + c.padRight)
                                return (len * fs * 0.68 + padH + 14).roundToInt().coerceIn(44, 116)
                            }
                            return null
                        }
                        val fixedTotal = visible.sumOf { resolvedW(it) ?: 0 }
                        val flexChildren = visible.filter { resolvedW(it) == null }
                        val totalWeight = flexChildren.sumOf { (if (it.weight > 0f) it.weight else 1f).toDouble() }.toFloat()
                        val gaps = max(0, visible.size - 1) * 6
                        val remW = max(40, innerW - fixedTotal - gaps)
                        val allFixed = flexChildren.isEmpty() && fixedTotal + gaps < innerW
                        var cx = if (allFixed && node.textAlign == TextAlignMode.CENTER) {
                            innerX + (innerW - (fixedTotal + gaps)) / 2
                        } else innerX

                        var maxH = 0
                        for (child in node.children) {
                            if (child.kind == "spacer") {
                                cx += min(12, child.width ?: 6)
                                continue
                            }
                            val wrapW = resolvedW(child)
                            val slotW = wrapW ?: max(
                                38,
                                ((remW * (if (child.weight > 0f) child.weight else 1f)) / max(1f, totalWeight)).roundToInt()
                            )
                            val adjusted = if (wrapW != null && child.width == null && !child.fillColor.isNullOrBlank() && child.fillColor != "transparent") {
                                child.copy(textAlign = child.textAlign ?: TextAlignMode.CENTER)
                            } else child
                            val ch = layoutNode(adjusted, cx, y + padT, min(slotW, innerW), nextFallback)
                            maxH = max(maxH, ch)
                            cx += slotW + 6
                        }
                        val totalH = max(36, maxH + padT + padB)
                        if (bgIndex >= 0) elements[bgIndex] = elements[bgIndex].copy(height = totalH)
                        maxBottom = max(maxBottom, y + totalH)
                        return totalH + if (hasBg) 10 else 4
                    }

                    var cy = y + padT
                    for (child in node.children) {
                        val cw = child.width?.let { min(it, innerW) } ?: innerW
                        val cx = if (child.width != null && (node.textAlign == TextAlignMode.CENTER || child.textAlign == TextAlignMode.CENTER)) {
                            innerX + (innerW - cw) / 2
                        } else innerX
                        cy += layoutNode(child, cx, cy, cw, nextFallback)
                    }
                    val totalH = max(if (hasBg) 52 else 0, cy - y + padB)
                    if (bgIndex >= 0) elements[bgIndex] = elements[bgIndex].copy(height = totalH)
                    maxBottom = max(maxBottom, y + totalH)
                    return totalH + if (hasBg) 10 else 4
                }

                "text" -> {
                    val text = (node.text ?: "").trim()
                    if (text.isEmpty()) return 0
                    val fs = (node.fontSize ?: 14).coerceIn(10, 30)
                    val w = node.width?.let { min(it, availW) } ?: availW
                    val h = node.height ?: estimateTextHeight(text, w, fs)
                    val hasFill = !node.fillColor.isNullOrBlank() && node.fillColor != "transparent"
                    val kind = when {
                        node.isOval -> ElementKind.CIRCLE
                        node.bold || fs >= 17 -> ElementKind.HEADING
                        else -> ElementKind.TEXT
                    }
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("txt"),
                            kind = kind,
                            name = node.varName ?: text.take(22),
                            x = x,
                            y = y,
                            width = w,
                            height = h,
                            text = text,
                            fontSize = fs,
                            bold = node.bold || fs >= 17,
                            textColor = node.textColor ?: parentTextColor,
                            textAlign = node.textAlign ?: if (hasFill) TextAlignMode.CENTER else TextAlignMode.LEFT,
                            fillColor = if (hasFill) node.fillColor!! else "transparent",
                            strokeColor = node.strokeColor ?: "transparent",
                            strokeWidth = node.strokeWidth,
                            borderRadius = node.borderRadius ?: if (hasFill) 12 else 0,
                        )
                    )
                    maxBottom = max(maxBottom, y + h)
                    return h + 6
                }

                "button" -> {
                    val label = (node.text ?: "Button").trim()
                    val w = node.width?.let { min(it, availW) } ?: availW
                    val h = node.height ?: 44
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("btn"),
                            kind = ElementKind.BUTTON,
                            name = node.varName ?: label.take(22),
                            x = x,
                            y = y,
                            width = w,
                            height = h,
                            text = label,
                            fontSize = (node.fontSize ?: 14).coerceIn(10, 24),
                            bold = true,
                            textColor = node.textColor ?: "#FFFFFF",
                            textAlign = node.textAlign ?: TextAlignMode.CENTER,
                            fillColor = node.fillColor ?: "#344653",
                            strokeColor = node.strokeColor ?: "#435867",
                            strokeWidth = node.strokeWidth,
                            borderRadius = node.borderRadius ?: 12,
                            shadow = 2,
                        )
                    )
                    maxBottom = max(maxBottom, y + h)
                    return h + 8
                }

                "input", "spinner" -> {
                    val w = node.width?.let { min(it, availW) } ?: availW
                    val h = node.height ?: 44
                    val textVal = if (node.kind == "spinner") "${node.text ?: "Option"} ▾" else (node.text ?: "")
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("inp"),
                            kind = ElementKind.INPUT,
                            name = node.varName ?: (node.hint ?: "Field").take(22),
                            x = x,
                            y = y,
                            width = w,
                            height = h,
                            text = textVal,
                            placeholder = node.hint ?: "Enter value...",
                            fontSize = 13,
                            textColor = "#EEF4F6",
                            fillColor = node.fillColor ?: "#1A252D",
                            strokeColor = node.strokeColor ?: "#3A4E5D",
                            strokeWidth = 1,
                            borderRadius = 10,
                        )
                    )
                    maxBottom = max(maxBottom, y + h)
                    return h + 8
                }

                "progress" -> {
                    val h = max(8, node.height ?: 10)
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("prg"),
                            kind = ElementKind.PROGRESS,
                            name = node.varName ?: "ProgressBar",
                            x = x,
                            y = y,
                            width = availW,
                            height = h,
                            fillColor = "#568B91",
                            strokeColor = "#2B3B47",
                            progress = 55,
                        )
                    )
                    maxBottom = max(maxBottom, y + h)
                    return h + 8
                }

                "image" -> {
                    val w = node.width?.let { min(it, availW) } ?: 36
                    val h = node.height ?: 36
                    elements.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("img"),
                            kind = ElementKind.CIRCLE,
                            name = node.varName ?: "App Icon",
                            x = x,
                            y = y,
                            width = w,
                            height = h,
                            text = "🚀",
                            fontSize = (min(w, h) * 0.5f).roundToInt().coerceIn(14, 28),
                            textColor = "#FFFFFF",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = "#2E6A75",
                            borderRadius = min(w, h) / 2,
                        )
                    )
                    maxBottom = max(maxBottom, y + h)
                    return h + 6
                }

                else -> return 0
            }
        }

        var topY = 12
        if (topChrome != null) {
            topY += layoutNode(topChrome, 12, topY, CANVAS_W - 24, "#EEF4F6") + 4
        }
        layoutNode(rootNode, 0, topY, CANVAS_W, "#EEF4F6")

        if (bottomNavTabs.isNotEmpty()) {
            val navY = max(MIN_CANVAS_H - 68, maxBottom + 16)
            elements.add(
                DesignerElement(
                    id = ApkDesignerModels.nextId("bnav"),
                    kind = ElementKind.BOTTOM_NAV,
                    name = "Bottom Navigation",
                    x = 8,
                    y = navY,
                    width = CANVAS_W - 16,
                    height = 60,
                    text = bottomNavTabs.joinToString(" | "),
                    placeholder = activeBottomTab ?: bottomNavTabs.first(),
                    fontSize = 10,
                    bold = true,
                    textColor = "#EEF4F6",
                    textAlign = TextAlignMode.CENTER,
                    fillColor = "#273640",
                    strokeColor = "#354957",
                    strokeWidth = 1,
                    borderRadius = 16,
                )
            )
            maxBottom = max(maxBottom, navY + 66)
        }

        return elements to max(MIN_CANVAS_H, maxBottom + 20)
    }

    private fun extractAlertDialogScreens(file: SourceFileEntry): List<DesignerScreen> {
        val code = file.content
        val dialogs = mutableListOf<DesignerScreen>()
        val seenTitles = mutableSetOf<String>()

        var i = 0
        while (i < code.length) {
            val skipped = skipStringOrComment(code, i)
            if (skipped > i) {
                i = skipped
                continue
            }
            if (code.startsWith("AlertDialog.Builder", i)) {
                val endIdx = min(code.length, i + 1800)
                val showIdx = code.indexOf(".show()", i)
                val sliceEnd = if (showIdx in i..endIdx) showIdx + 7 else endIdx
                val chunk = code.substring(i, sliceEnd)

                val title = Regex("""\.setTitle\(\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1)
                    ?.let(::unescapeKotlinString)
                if (title.isNullOrBlank() || !seenTitles.add(title)) {
                    i += 19
                    continue
                }

                val message = Regex("""\.setMessage\(\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1)
                    ?.let(::unescapeKotlinString)
                val positive = Regex("""\.setPositiveButton\(\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1)
                    ?.let(::unescapeKotlinString)
                val negative = Regex("""\.setNegativeButton\(\s*"([^"]+)"""").find(chunk)?.groupValues?.get(1)
                    ?.let(::unescapeKotlinString)
                val itemsMatch = Regex("""\.setItems\(\s*arrayOf\(([^)]+)\)""").find(chunk)
                val listItems = itemsMatch?.groupValues?.get(1)
                    ?.let { splitTopLevelArgs(it).mapNotNull(::extractFirstStringLiteral) }
                    ?: emptyList()

                val viewVar = Regex("""\.setView\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\)""").find(chunk)?.groupValues?.get(1)
                val priorBlockStart = max(0, i - 2600)
                val priorContext = code.substring(priorBlockStart, i)

                val els = mutableListOf<DesignerElement>()
                val cardY = 86
                els.add(
                    DesignerElement(
                        id = ApkDesignerModels.nextId("dlg"),
                        kind = ElementKind.CARD,
                        name = "Dialog Surface",
                        x = 18,
                        y = cardY,
                        width = 324,
                        height = 240,
                        fillColor = "#24323D",
                        strokeColor = "#3B4F5E",
                        strokeWidth = 1,
                        borderRadius = 20,
                        shadow = 6,
                    )
                )
                var cy = cardY + 20
                els.add(
                    DesignerElement(
                        id = ApkDesignerModels.nextId("dlg"),
                        kind = ElementKind.HEADING,
                        name = "Dialog Title",
                        x = 34,
                        y = cy,
                        width = 292,
                        height = 28,
                        text = title,
                        fontSize = 18,
                        bold = true,
                        textColor = "#FFFFFF",
                        fillColor = "transparent",
                    )
                )
                cy += 34

                if (!message.isNullOrBlank()) {
                    val mh = estimateTextHeight(message, 292, 12)
                    els.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("dlg"),
                            kind = ElementKind.TEXT,
                            name = "Dialog Message",
                            x = 34,
                            y = cy,
                            width = 292,
                            height = mh,
                            text = message,
                            fontSize = 12,
                            textColor = "#B6C5CE",
                            fillColor = "transparent",
                        )
                    )
                    cy += mh + 12
                }

                // Render .setItems(...) options
                for (item in listItems) {
                    els.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("dlgi"),
                            kind = ElementKind.BUTTON,
                            name = item.take(22),
                            x = 34,
                            y = cy,
                            width = 292,
                            height = 42,
                            text = item,
                            fontSize = 13,
                            bold = true,
                            textColor = "#EEF4F6",
                            textAlign = TextAlignMode.LEFT,
                            fillColor = "#2F414F",
                            strokeColor = "#425867",
                            strokeWidth = 1,
                            borderRadius = 10,
                        )
                    )
                    cy += 48
                }

                // Render .setView(viewVar) form controls if present
                if (viewVar != null) {
                    val env = mutableMapOf<String, KtNode>()
                    evaluateStatementsInto(priorContext, env)
                    val customNode = env[viewVar]
                    if (customNode != null) {
                        val (formEls, _) = layoutKtTreeToElements(customNode)
                        val offsetY = cy - (formEls.minOfOrNull { it.y } ?: 0)
                        for (fe in formEls) {
                            val clampedW = min(fe.width, 292)
                            els.add(
                                fe.copy(
                                    x = 34 + min(fe.x, 12),
                                    y = fe.y + offsetY,
                                    width = clampedW,
                                )
                            )
                            cy = max(cy, fe.y + offsetY + fe.height + 6)
                        }
                    }
                }

                cy += 10
                if (negative != null && positive != null) {
                    els.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("dlgb"),
                            kind = ElementKind.BUTTON,
                            name = negative,
                            x = 34,
                            y = cy,
                            width = 140,
                            height = 42,
                            text = negative,
                            fontSize = 13,
                            bold = true,
                            textColor = "#EEF4F6",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = "#344653",
                            borderRadius = 12,
                        )
                    )
                    els.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("dlgb"),
                            kind = ElementKind.BUTTON,
                            name = positive,
                            x = 186,
                            y = cy,
                            width = 140,
                            height = 42,
                            text = positive,
                            fontSize = 13,
                            bold = true,
                            textColor = "#FFFFFF",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = "#568B91",
                            borderRadius = 12,
                        )
                    )
                    cy += 52
                } else if (positive != null) {
                    els.add(
                        DesignerElement(
                            id = ApkDesignerModels.nextId("dlgb"),
                            kind = ElementKind.BUTTON,
                            name = positive,
                            x = 186,
                            y = cy,
                            width = 140,
                            height = 42,
                            text = positive,
                            fontSize = 13,
                            bold = true,
                            textColor = "#FFFFFF",
                            textAlign = TextAlignMode.CENTER,
                            fillColor = "#568B91",
                            borderRadius = 12,
                        )
                    )
                    cy += 52
                }

                els[0] = els[0].copy(height = max(160, cy - cardY + 10))
                dialogs.add(
                    DesignerScreen(
                        id = ApkDesignerModels.nextId("dlgscr"),
                        name = "Dialog: $title",
                        width = CANVAS_W,
                        height = max(MIN_CANVAS_H, cy + 48),
                        backgroundColor = "#131C24",
                        elements = els,
                        sourceFilePath = file.path,
                        sourceKind = "kotlin-view",
                        screenCategory = ScreenCategory.DIALOG,
                        sourceSymbol = "AlertDialog:$title",
                    )
                )
                i = sliceEnd
                continue
            }
            i++
        }
        return dialogs
    }

    private fun extractProgrammaticKotlinScreens(file: SourceFileEntry): List<DesignerScreen> {
        val code = file.content
        val screens = mutableListOf<DesignerScreen>()

        val onCreateMatch = Regex("""override\s+fun\s+onCreate\s*\([^)]*\)\s*\{""").find(code)
            ?: Regex("""private\s+fun\s+(?:showEditor|buildScreen)\s*\([^)]*\)\s*\{""").find(code)

        if (onCreateMatch != null) {
            val openBrace = code.indexOf('{', onCreateMatch.range.first)
            val closeBrace = findMatchingDelimiter(code, openBrace, '{', '}')
            if (closeBrace > openBrace) {
                val body = code.substring(openBrace + 1, closeBrace)
                val env = mutableMapOf<String, KtNode>()
                evaluateStatementsInto(body, env)

                val appBar = env["appBar"]
                val tabBar = env["tabBar"]
                val bottomTabs = tabBar?.children
                    ?.filter { it.kind == "navTab" }
                    ?.map { "${it.icon ?: "•"} ${it.text ?: "Tab"}".trim() }
                    ?: emptyList()

                val pagesListMatch = Regex("""val\s+pages\s*=\s*listOf\(([^)]+)\)""").find(body)
                if (pagesListMatch != null) {
                    val pageVars = splitTopLevelArgs(pagesListMatch.groupValues[1])
                    pageVars.forEachIndexed { idx, varName ->
                        var pageNode = env[varName] ?: return@forEachIndexed
                        // Unwrap transparent single-child ScrollView container
                        if (pageNode.kind == "container" && pageNode.children.size == 1 && pageNode.children[0].kind == "container") {
                            pageNode = pageNode.children[0]
                        }
                        val pageName = inferPageName(varName, pageNode, idx)
                        val activeBottom = bottomTabs.getOrNull(idx)
                        val (els, h) = layoutKtTreeToElements(
                            rootNode = pageNode,
                            topChrome = appBar,
                            bottomNavTabs = bottomTabs,
                            activeBottomTab = activeBottom,
                        )
                        if (els.isNotEmpty()) {
                            screens.add(
                                DesignerScreen(
                                    id = ApkDesignerModels.nextId("ktpg"),
                                    name = pageName,
                                    width = CANVAS_W,
                                    height = h,
                                    backgroundColor = "#202A33",
                                    elements = els,
                                    sourceFilePath = file.path,
                                    sourceKind = "kotlin-view",
                                    screenCategory = ScreenCategory.TAB,
                                    sourceSymbol = varName,
                                )
                            )
                        }
                    }
                } else {
                    val rootNode = env["root"] ?: env["contentRoot"] ?: env["body"]
                    if (rootNode != null) {
                        val (els, h) = layoutKtTreeToElements(rootNode)
                        if (els.isNotEmpty()) {
                            val title = file.path.substringAfterLast('/').removeSuffix(".kt")
                                .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                            screens.add(
                                DesignerScreen(
                                    id = ApkDesignerModels.nextId("ktact"),
                                    name = title,
                                    width = CANVAS_W,
                                    height = h,
                                    backgroundColor = "#17212B",
                                    elements = els,
                                    sourceFilePath = file.path,
                                    sourceKind = "kotlin-view",
                                    screenCategory = ScreenCategory.ACTIVITY,
                                    sourceSymbol = title,
                                )
                            )
                        }
                    }
                }
            }
        }

        // Standalone screen builder functions: buildEnvironmentSetupPage, build*Page, build*Screen
        val builderRx = Regex("""(?:private\s+)?fun\s+(build[A-Z][A-Za-z0-9_]*(?:Page|Screen|View))\s*\([^)]*\)[^{]*\{""")
        for (bm in builderRx.findAll(code)) {
            val fnName = bm.groupValues[1]
            val openB = code.indexOf('{', bm.range.first)
            val closeB = findMatchingDelimiter(code, openB, '{', '}')
            if (closeB <= openB) continue
            val body = code.substring(openB + 1, closeB)
            val env = mutableMapOf<String, KtNode>()
            evaluateStatementsInto(body, env)
            val rootNode = env["body"] ?: env["root"] ?: env["content"] ?: env.values.firstOrNull { it.children.isNotEmpty() }
            if (rootNode != null) {
                val (els, h) = layoutKtTreeToElements(rootNode)
                if (els.isNotEmpty()) {
                    val niceName = fnName.removePrefix("build").removeSuffix("Page").removeSuffix("Screen")
                        .replace(Regex("([a-z])([A-Z])"), "$1 $2").trim()
                    screens.add(
                        DesignerScreen(
                            id = ApkDesignerModels.nextId("ktbld"),
                            name = niceName.ifBlank { fnName },
                            width = CANVAS_W,
                            height = h,
                            backgroundColor = "#202A33",
                            elements = els,
                            sourceFilePath = file.path,
                            sourceKind = "kotlin-view",
                            screenCategory = ScreenCategory.PAGE,
                            sourceSymbol = fnName,
                        )
                    )
                }
            }
        }

        // AlertDialog windows
        screens.addAll(extractAlertDialogScreens(file))
        return screens
    }

    private fun inferPageName(varName: String, pageNode: KtNode, index: Int): String {
        // Look for an explicit addPageBackHeader title
        val firstRow = pageNode.children.firstOrNull()
        if (firstRow != null && firstRow.orientation == "horizontal" && firstRow.children.size >= 2) {
            val maybeTitle = firstRow.children[1].text?.trim()
            if (!maybeTitle.isNullOrBlank() && firstRow.children[0].text == "‹") {
                return maybeTitle
            }
        }
        val lower = varName.lowercase()
        return when {
            lower.contains("home") -> "Home"
            lower.contains("editor") || lower.contains("workspace") -> "Workspace"
            lower.contains("tool") -> "APK Tools"
            lower.contains("preview") -> "App Preview"
            lower.contains("setting") -> "Settings"
            else -> "Page ${index + 1}"
        }
    }

    /* ---------------------------------------------------------------------- */
    /* PUBLIC API: SINGLE-FILE & FULL-PROJECT IMPORTERS                       */
    /* ---------------------------------------------------------------------- */

    fun importAllScreensFromFile(
        file: SourceFileEntry,
        projectFiles: List<SourceFileEntry> = listOf(file),
    ): FullAppImportBundle {
        val res = parseResourceMaps(projectFiles)
        val lower = file.path.lowercase()
        val screens = mutableListOf<DesignerScreen>()

        when {
            lower.endsWith(".xml") -> {
                if (lower.contains("/layout") || file.content.contains("xmlns:android")) {
                    screens.add(importXmlScreen(file, res))
                }
            }
            lower.endsWith(".kt") || lower.endsWith(".java") -> {
                val hasCompose = Regex("""(?:^|\n)\s*@Composable\b""").containsMatchIn(file.content)
                if (hasCompose) {
                    screens.addAll(extractComposeScreens(file))
                }
                val hasProgrammatic =
                    file.content.contains("LinearLayout(") ||
                    file.content.contains("AlertDialog.Builder") ||
                    file.content.contains("setContentView(")
                if (hasProgrammatic && screens.isEmpty()) {
                    screens.addAll(extractProgrammaticKotlinScreens(file))
                } else if (hasCompose) {
                    // Check for any AlertDialog.Builder windows even in mixed files
                    val extraDialogs = extractAlertDialogScreens(file)
                    val existingNames = screens.map { it.name }.toSet()
                    extraDialogs.filterTo(screens) { it.name !in existingNames }
                }
                // Check setContentView(R.layout.xyz) link
                val layoutLink = Regex("""setContentView\(\s*R\.layout\.([a-zA-Z0-9_]+)\s*\)""").find(file.content)?.groupValues?.get(1)
                if (layoutLink != null) {
                    val xmlFile = projectFiles.find { it.path.endsWith("/$layoutLink.xml") || it.path == "$layoutLink.xml" }
                    if (xmlFile != null) {
                        screens.add(0, importXmlScreen(xmlFile, res))
                    }
                }
            }
        }

        val summary = if (screens.isEmpty()) {
            "No UI elements found in ${file.path.substringAfterLast('/')}"
        } else {
            val totalEls = screens.sumOf { it.elements.size }
            "Extracted ${screens.size} screen(s) ($totalEls elements)"
        }
        return FullAppImportBundle(screens, 1, summary)
    }

    fun importFullProject(
        projectFiles: List<SourceFileEntry>,
        preferredActivePath: String? = null,
    ): FullAppImportBundle {
        val candidates = projectFiles.filter { f ->
            val p = f.path.lowercase()
            when {
                p.endsWith(".xml") && p.contains("/layout") -> true
                (p.endsWith(".kt") || p.endsWith(".java")) && (
                    Regex("""(?:^|\n)\s*@Composable\b""").containsMatchIn(f.content) ||
                    f.content.contains("LinearLayout(") ||
                    f.content.contains("setContentView(") ||
                    f.content.contains("AlertDialog.Builder")
                ) -> true
                else -> false
            }
        }.sortedWith(
            compareBy<SourceFileEntry>(
                { if (it.path == preferredActivePath) 0 else 1 },
                { if (it.path.endsWith("MainActivity.kt") || it.path.endsWith("MainActivity.java")) 0 else 1 },
                { it.path },
            )
        )

        val collected = mutableListOf<DesignerScreen>()
        val seenKeys = mutableSetOf<String>()

        for (file in candidates) {
            if (collected.size >= 28) break
            val bundle = importAllScreensFromFile(file, projectFiles)
            for (screen in bundle.screens) {
                if (collected.size >= 28) break
                val key = "${screen.sourceFilePath}::${screen.sourceSymbol ?: screen.name}"
                if (seenKeys.add(key)) {
                    collected.add(screen)
                }
            }
        }

        val totalEls = collected.sumOf { it.elements.size }
        return FullAppImportBundle(
            screens = collected,
            totalFilesScanned = candidates.size,
            summary = "Loaded ${collected.size} screens ($totalEls elements) from ${candidates.size} UI files",
        )
    }

    fun scanProjectDirectory(rootDir: File, preferredActiveFile: File? = null): FullAppImportBundle {
        if (!rootDir.isDirectory) {
            return FullAppImportBundle(emptyList(), 0, "Project folder unavailable")
        }
        val ignoredDirs = setOf(".git", ".gradle", "build", "node_modules", ".idea", ".playwright-cli", "dist")
        val entries = mutableListOf<SourceFileEntry>()
        rootDir.walkTopDown()
            .onEnter { dir -> dir.name !in ignoredDirs }
            .maxDepth(12)
            .filter { f ->
                f.isFile &&
                f.length() <= 350_000L &&
                f.extension.lowercase() in setOf("kt", "java", "xml")
            }
            .take(250)
            .forEach { file ->
                val rel = runCatching { file.relativeTo(rootDir).invariantSeparatorsPath }.getOrDefault(file.name)
                runCatching { file.readText() }.getOrNull()?.let { text ->
                    entries.add(SourceFileEntry(rel, text))
                }
            }
        val preferredRel = preferredActiveFile?.let {
            runCatching { it.relativeTo(rootDir).invariantSeparatorsPath }.getOrNull()
        }
        return importFullProject(entries, preferredRel)
    }
}
