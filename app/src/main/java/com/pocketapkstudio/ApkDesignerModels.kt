package com.pocketapkstudio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin models, geometry operations, presets, theme harmonization, and
 * Jetpack Compose / Android XML code generators for the native APK Designer.
 */
object ApkDesignerModels {

    enum class ElementKind(val label: String) {
        TOP_BAR("App Bar"),
        BOTTOM_NAV("Bottom Nav"),
        BUTTON("Button"),
        OUTLINED_BUTTON("Outlined Button"),
        INPUT("Text Input"),
        SWITCH("Switch"),
        PROGRESS("Progress"),
        FAB("FAB +"),
        HEADING("Heading"),
        TEXT("Body Text"),
        IMAGE("Photo"),
        CARD("Card"),
        RECTANGLE("Rectangle"),
        CIRCLE("Circle"),
        DIVIDER("Divider"),
    }

    enum class TextAlignMode(val value: String) {
        LEFT("left"),
        CENTER("center"),
        RIGHT("right"),
    }

    enum class ScreenCategory(val badge: String) {
        PAGE("PAGE"),
        TAB("TAB"),
        DIALOG("WINDOW"),
        ACTIVITY("ACTIVITY"),
        XML("XML"),
    }

    enum class ResizeHandle {
        NW, N, NE, E, SE, S, SW, W
    }

    enum class CanvasAlign {
        LEFT, CENTER_H, RIGHT, TOP, CENTER_V, BOTTOM
    }

    data class PhotoFilters(
        val brightness: Int = 100,
        val contrast: Int = 100,
        val saturation: Int = 100,
        val grayscale: Boolean = false,
        val sepia: Boolean = false,
    )

    data class DesignerElement(
        val id: String,
        val kind: ElementKind,
        val name: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val text: String = "",
        val placeholder: String = "",
        val fontSize: Int = 14,
        val bold: Boolean = false,
        val textColor: String = "#EEF4F6",
        val textAlign: TextAlignMode = TextAlignMode.LEFT,
        val fillColor: String = "#2B3742",
        val strokeColor: String = "#3A4E5D",
        val strokeWidth: Int = 0,
        val borderRadius: Int = 12,
        val opacity: Int = 100,
        val shadow: Int = 0,
        val visible: Boolean = true,
        val locked: Boolean = false,
        val checked: Boolean = false,
        val progress: Int = 65,
        val imageUri: String? = null,
        val filters: PhotoFilters = PhotoFilters(),
    )

    data class DesignerScreen(
        val id: String,
        val name: String,
        val width: Int = 360,
        val height: Int = 780,
        val backgroundColor: String = "#202A33",
        val elements: List<DesignerElement> = emptyList(),
        val sourceFilePath: String? = null,
        val sourceKind: String? = null,
        val screenCategory: ScreenCategory = ScreenCategory.PAGE,
        val sourceSymbol: String? = null,
    )

    data class SnapResult(
        val x: Int,
        val y: Int,
        val guideX: Int? = null,
        val guideY: Int? = null,
    )

    private var seq = 1
    fun nextId(prefix: String = "el"): String = "${prefix}_${seq++}"

    fun createDefaultElement(kind: ElementKind, x: Int = 24, y: Int = 120): DesignerElement {
        return when (kind) {
            ElementKind.TOP_BAR -> DesignerElement(
                id = nextId("bar"),
                kind = kind,
                name = "App Bar",
                x = 0,
                y = 0,
                width = 360,
                height = 56,
                text = "Pocket APK Studio",
                fontSize = 18,
                bold = true,
                textColor = "#EEF4F6",
                textAlign = TextAlignMode.LEFT,
                fillColor = "#1B252E",
                strokeColor = "#2E3F4D",
                strokeWidth = 1,
                borderRadius = 0,
            )
            ElementKind.BOTTOM_NAV -> DesignerElement(
                id = nextId("nav"),
                kind = kind,
                name = "Bottom Nav",
                x = 0,
                y = 716,
                width = 360,
                height = 64,
                text = "⌂ Home | </> Editor | ⬡ Tools | ◉ Preview | ⚙ Settings",
                placeholder = "⌂ Home",
                fontSize = 11,
                bold = true,
                textColor = "#EEF4F6",
                textAlign = TextAlignMode.CENTER,
                fillColor = "#273640",
                strokeColor = "#354957",
                strokeWidth = 1,
                borderRadius = 16,
            )
            ElementKind.BUTTON -> DesignerElement(
                id = nextId("btn"),
                kind = kind,
                name = "Primary Button",
                x = x,
                y = y,
                width = 200,
                height = 46,
                text = "Continue",
                fontSize = 15,
                bold = true,
                textColor = "#FFFFFF",
                textAlign = TextAlignMode.CENTER,
                fillColor = "#3E8790",
                borderRadius = 12,
                shadow = 3,
            )
            ElementKind.OUTLINED_BUTTON -> DesignerElement(
                id = nextId("obtn"),
                kind = kind,
                name = "Outlined Button",
                x = x,
                y = y,
                width = 200,
                height = 46,
                text = "Secondary Action",
                fontSize = 14,
                bold = true,
                textColor = "#DCE9E8",
                textAlign = TextAlignMode.CENTER,
                fillColor = "transparent",
                strokeColor = "#568B91",
                strokeWidth = 2,
                borderRadius = 12,
            )
            ElementKind.INPUT -> DesignerElement(
                id = nextId("inp"),
                kind = kind,
                name = "Text Input",
                x = x,
                y = y,
                width = 312,
                height = 46,
                text = "",
                placeholder = "Enter value...",
                fontSize = 14,
                textColor = "#EEF4F6",
                textAlign = TextAlignMode.LEFT,
                fillColor = "#162028",
                strokeColor = "#3A4E5D",
                strokeWidth = 1,
                borderRadius = 10,
            )
            ElementKind.SWITCH -> DesignerElement(
                id = nextId("sw"),
                kind = kind,
                name = "Toggle Switch",
                x = x,
                y = y,
                width = 260,
                height = 40,
                text = "Enable Dark Theme",
                fontSize = 14,
                textColor = "#EEF4F6",
                fillColor = "#3E8790",
                checked = true,
                borderRadius = 10,
            )
            ElementKind.PROGRESS -> DesignerElement(
                id = nextId("prg"),
                kind = kind,
                name = "Progress Bar",
                x = x,
                y = y,
                width = 300,
                height = 14,
                fillColor = "#3E8790",
                strokeColor = "#2B3B47",
                borderRadius = 8,
                progress = 68,
            )
            ElementKind.FAB -> DesignerElement(
                id = nextId("fab"),
                kind = kind,
                name = "Floating Action",
                x = 286,
                y = 636,
                width = 56,
                height = 56,
                text = "+",
                fontSize = 24,
                bold = true,
                textColor = "#FFFFFF",
                textAlign = TextAlignMode.CENTER,
                fillColor = "#3E8790",
                borderRadius = 28,
                shadow = 5,
            )
            ElementKind.HEADING -> DesignerElement(
                id = nextId("hd"),
                kind = kind,
                name = "Heading",
                x = x,
                y = y,
                width = 300,
                height = 36,
                text = "Welcome Back",
                fontSize = 22,
                bold = true,
                textColor = "#EEF4F6",
                textAlign = TextAlignMode.LEFT,
                fillColor = "transparent",
                borderRadius = 0,
            )
            ElementKind.TEXT -> DesignerElement(
                id = nextId("txt"),
                kind = kind,
                name = "Body Text",
                x = x,
                y = y,
                width = 300,
                height = 28,
                text = "Edit text, color, alignment and position directly on canvas.",
                fontSize = 13,
                bold = false,
                textColor = "#A8BAC3",
                textAlign = TextAlignMode.LEFT,
                fillColor = "transparent",
                borderRadius = 0,
            )
            ElementKind.IMAGE -> DesignerElement(
                id = nextId("img"),
                kind = kind,
                name = "Photo Frame",
                x = x,
                y = y,
                width = 312,
                height = 160,
                text = "Tap Style to Pick Photo",
                fontSize = 13,
                textColor = "#DCE9E8",
                textAlign = TextAlignMode.CENTER,
                fillColor = "#243442",
                strokeColor = "#4C6A7B",
                strokeWidth = 1,
                borderRadius = 16,
            )
            ElementKind.CARD -> DesignerElement(
                id = nextId("crd"),
                kind = kind,
                name = "Card Surface",
                x = x,
                y = y,
                width = 312,
                height = 136,
                fillColor = "#2B3742",
                strokeColor = "#3C5060",
                strokeWidth = 1,
                borderRadius = 16,
                shadow = 3,
            )
            ElementKind.RECTANGLE -> DesignerElement(
                id = nextId("rct"),
                kind = kind,
                name = "Rectangle",
                x = x,
                y = y,
                width = 160,
                height = 96,
                fillColor = "#354957",
                strokeColor = "#568B91",
                strokeWidth = 1,
                borderRadius = 12,
            )
            ElementKind.CIRCLE -> DesignerElement(
                id = nextId("crc"),
                kind = kind,
                name = "Circle",
                x = x,
                y = y,
                width = 72,
                height = 72,
                text = "★",
                fontSize = 24,
                bold = true,
                textColor = "#FFFFFF",
                textAlign = TextAlignMode.CENTER,
                fillColor = "#3E8790",
                borderRadius = 36,
            )
            ElementKind.DIVIDER -> DesignerElement(
                id = nextId("div"),
                kind = kind,
                name = "Divider",
                x = x,
                y = y,
                width = 312,
                height = 2,
                fillColor = "#3A4E5D",
                borderRadius = 1,
            )
        }
    }

    fun createStarterScreen(presetId: String): DesignerScreen {
        return when (presetId) {
            "login" -> DesignerScreen(
                id = nextId("scr"),
                name = "Clean Login",
                backgroundColor = "#141C24",
                elements = listOf(
                    createDefaultElement(ElementKind.CIRCLE, 144, 72).copy(text = "⌘", fillColor = "#DCE9E8", textColor = "#3E6B6E"),
                    createDefaultElement(ElementKind.HEADING, 30, 162).copy(text = "Sign in to Studio", textAlign = TextAlignMode.CENTER),
                    createDefaultElement(ElementKind.TEXT, 30, 202).copy(text = "Access your Android projects and cloud builds", textAlign = TextAlignMode.CENTER),
                    createDefaultElement(ElementKind.CARD, 20, 250).copy(width = 320, height = 270, fillColor = "#1E2A35"),
                    createDefaultElement(ElementKind.INPUT, 36, 276).copy(width = 288, placeholder = "Email address"),
                    createDefaultElement(ElementKind.INPUT, 36, 336).copy(width = 288, placeholder = "Password"),
                    createDefaultElement(ElementKind.BUTTON, 36, 404).copy(width = 288, text = "Sign In"),
                    createDefaultElement(ElementKind.OUTLINED_BUTTON, 36, 460).copy(width = 288, text = "Create Account"),
                ),
            )
            "profile" -> DesignerScreen(
                id = nextId("scr"),
                name = "Profile & Photo",
                backgroundColor = "#1A232C",
                elements = listOf(
                    createDefaultElement(ElementKind.TOP_BAR, 0, 0).copy(text = "Creator Profile"),
                    createDefaultElement(ElementKind.IMAGE, 20, 74).copy(width = 320, height = 180, text = "Cover Banner Photo"),
                    createDefaultElement(ElementKind.CIRCLE, 142, 214).copy(width = 76, height = 76, text = "AJ", fillColor = "#3E8790", strokeColor = "#FFFFFF", strokeWidth = 2),
                    createDefaultElement(ElementKind.HEADING, 24, 302).copy(width = 312, text = "Android Builder", textAlign = TextAlignMode.CENTER),
                    createDefaultElement(ElementKind.TEXT, 24, 338).copy(width = 312, text = "Pocket APK Studio · Material 3 Creator", textAlign = TextAlignMode.CENTER),
                    createDefaultElement(ElementKind.CARD, 20, 384).copy(width = 320, height = 136),
                    createDefaultElement(ElementKind.SWITCH, 36, 404).copy(width = 288, text = "Auto-sync GitHub Releases"),
                    createDefaultElement(ElementKind.BUTTON, 36, 456).copy(width = 288, text = "Edit Profile"),
                    createDefaultElement(ElementKind.BOTTOM_NAV, 0, 716),
                ),
            )
            "blank" -> DesignerScreen(
                id = nextId("scr"),
                name = "Blank Screen",
                backgroundColor = "#202A33",
                elements = emptyList(),
            )
            else -> DesignerScreen(
                id = nextId("scr"),
                name = "Studio Dashboard",
                backgroundColor = "#202A33",
                elements = listOf(
                    createDefaultElement(ElementKind.TOP_BAR, 0, 0).copy(text = "Pocket APK Studio"),
                    createDefaultElement(ElementKind.CARD, 18, 72).copy(width = 324, height = 128, fillColor = "#FFFFFF", strokeColor = "#D9E5E9"),
                    createDefaultElement(ElementKind.HEADING, 34, 88).copy(width = 292, text = "WORKSPACE", textColor = "#27414E", textAlign = TextAlignMode.CENTER),
                    createDefaultElement(ElementKind.TEXT, 34, 124).copy(width = 292, text = "Manage · Create · Edit Android Apps", textColor = "#607784", textAlign = TextAlignMode.CENTER),
                    createDefaultElement(ElementKind.BUTTON, 90, 152).copy(width = 180, height = 38, text = "Open Workspace →"),
                    createDefaultElement(ElementKind.CARD, 18, 218).copy(width = 324, height = 148),
                    createDefaultElement(ElementKind.HEADING, 34, 234).copy(width = 292, fontSize = 16, text = "Build Status"),
                    createDefaultElement(ElementKind.PROGRESS, 34, 274).copy(width = 292, progress = 76),
                    createDefaultElement(ElementKind.BUTTON, 34, 304).copy(width = 292, height = 44, text = "Build Signed Debug APK"),
                    createDefaultElement(ElementKind.BOTTOM_NAV, 0, 716),
                ),
            )
        }
    }

    fun moveElementWithSnap(
        screen: DesignerScreen,
        element: DesignerElement,
        targetX: Int,
        targetY: Int,
        snapEnabled: Boolean,
        gridSize: Int = 8,
    ): SnapResult {
        var x = targetX.coerceIn(-element.width + 16, screen.width - 16)
        var y = targetY.coerceIn(0, max(screen.height - 16, 0))
        var guideX: Int? = null
        var guideY: Int? = null
        if (!snapEnabled) return SnapResult(x, y)

        val centerCanvasX = screen.width / 2
        val centerCanvasY = screen.height / 2
        val elemCenterX = x + element.width / 2
        val elemCenterY = y + element.height / 2

        if (abs(elemCenterX - centerCanvasX) <= 8) {
            x = centerCanvasX - element.width / 2
            guideX = centerCanvasX
        } else {
            x = ((x.toFloat() / gridSize).roundToInt()) * gridSize
        }

        if (abs(elemCenterY - centerCanvasY) <= 8) {
            y = centerCanvasY - element.height / 2
            guideY = centerCanvasY
        } else {
            y = ((y.toFloat() / gridSize).roundToInt()) * gridSize
        }

        return SnapResult(x, y, guideX, guideY)
    }

    fun resizeElementFromHandle(
        element: DesignerElement,
        handle: ResizeHandle,
        dx: Int,
        dy: Int,
        minWidth: Int = 24,
        minHeight: Int = 12,
    ): DesignerElement {
        var x = element.x
        var y = element.y
        var w = element.width
        var h = element.height

        when (handle) {
            ResizeHandle.NW -> {
                val newW = max(minWidth, w - dx)
                val newH = max(minHeight, h - dy)
                x += (w - newW)
                y += (h - newH)
                w = newW
                h = newH
            }
            ResizeHandle.N -> {
                val newH = max(minHeight, h - dy)
                y += (h - newH)
                h = newH
            }
            ResizeHandle.NE -> {
                val newH = max(minHeight, h - dy)
                y += (h - newH)
                w = max(minWidth, w + dx)
                h = newH
            }
            ResizeHandle.E -> {
                w = max(minWidth, w + dx)
            }
            ResizeHandle.SE -> {
                w = max(minWidth, w + dx)
                h = max(minHeight, h + dy)
            }
            ResizeHandle.S -> {
                h = max(minHeight, h + dy)
            }
            ResizeHandle.SW -> {
                val newW = max(minWidth, w - dx)
                x += (w - newW)
                w = newW
                h = max(minHeight, h + dy)
            }
            ResizeHandle.W -> {
                val newW = max(minWidth, w - dx)
                x += (w - newW)
                w = newW
            }
        }
        return element.copy(x = x, y = y, width = w, height = h)
    }

    fun alignElementInCanvas(screen: DesignerScreen, element: DesignerElement, align: CanvasAlign): DesignerElement {
        return when (align) {
            CanvasAlign.LEFT -> element.copy(x = 16)
            CanvasAlign.CENTER_H -> element.copy(x = ((screen.width - element.width) / 2).coerceAtLeast(0))
            CanvasAlign.RIGHT -> element.copy(x = (screen.width - element.width - 16).coerceAtLeast(0))
            CanvasAlign.TOP -> element.copy(y = 16)
            CanvasAlign.CENTER_V -> element.copy(y = ((screen.height - element.height) / 2).coerceAtLeast(0))
            CanvasAlign.BOTTOM -> element.copy(y = (screen.height - element.height - 16).coerceAtLeast(0))
        }
    }

    fun reorderLayer(elements: List<DesignerElement>, id: String, moveForward: Boolean): List<DesignerElement> {
        val index = elements.indexOfFirst { it.id == id }
        if (index < 0) return elements
        val target = if (moveForward) min(elements.lastIndex, index + 1) else max(0, index - 1)
        if (target == index) return elements
        val mutable = elements.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(target, item)
        return mutable
    }

    fun applyColorCombination(
        screen: DesignerScreen,
        combo: ApkDesignerColorUtils.ColorCombination,
    ): DesignerScreen {
        val updated = screen.elements.map { el ->
            when (el.kind) {
                ElementKind.TOP_BAR, ElementKind.BOTTOM_NAV -> el.copy(
                    fillColor = combo.surface,
                    textColor = combo.text,
                    strokeColor = combo.stroke,
                )
                ElementKind.BUTTON, ElementKind.FAB -> el.copy(
                    fillColor = combo.primary,
                    textColor = "#FFFFFF",
                )
                ElementKind.OUTLINED_BUTTON -> el.copy(
                    strokeColor = combo.primary,
                    textColor = combo.text,
                )
                ElementKind.CARD, ElementKind.RECTANGLE -> el.copy(
                    fillColor = combo.surface,
                    strokeColor = combo.stroke,
                    textColor = combo.text,
                )
                ElementKind.CIRCLE -> el.copy(
                    fillColor = combo.accent,
                    textColor = if (ApkDesignerColorUtils.isLightHex(combo.accent)) "#0F172A" else "#FFFFFF",
                )
                ElementKind.INPUT -> el.copy(
                    fillColor = combo.background,
                    strokeColor = combo.stroke,
                    textColor = combo.text,
                )
                ElementKind.SWITCH, ElementKind.PROGRESS -> el.copy(
                    fillColor = combo.primary,
                    textColor = combo.text,
                )
                ElementKind.HEADING -> el.copy(textColor = combo.text)
                ElementKind.TEXT -> el.copy(textColor = combo.mutedText)
                ElementKind.DIVIDER -> el.copy(fillColor = combo.stroke)
                ElementKind.IMAGE -> el.copy(strokeColor = combo.stroke)
            }
        }
        return screen.copy(backgroundColor = combo.background, elements = updated)
    }

    private fun composeColorLiteral(hex: String): String {
        val norm = ApkDesignerColorUtils.normalizeHex(hex, "#202A33")
        if (norm == "transparent") return "Color.Transparent"
        return "Color(0xFF${norm.removePrefix("#")})"
    }

    private fun escapeKt(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    fun generateComposeCode(screen: DesignerScreen, packageName: String = "com.pocketapkstudio.app"): String {
        val fnName = screen.name
            .replace(Regex("[^A-Za-z0-9]+"), " ")
            .trim()
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercaseChar() } }
            .ifBlank { "DesignedScreen" }
            .let { if (it.first().isDigit()) "Screen$it" else it }

        val sb = StringBuilder()
        sb.appendLine("package $packageName")
        sb.appendLine()
        sb.appendLine("import androidx.compose.foundation.background")
        sb.appendLine("import androidx.compose.foundation.border")
        sb.appendLine("import androidx.compose.foundation.layout.*")
        sb.appendLine("import androidx.compose.foundation.rememberScrollState")
        sb.appendLine("import androidx.compose.foundation.shape.RoundedCornerShape")
        sb.appendLine("import androidx.compose.foundation.verticalScroll")
        sb.appendLine("import androidx.compose.material3.*")
        sb.appendLine("import androidx.compose.runtime.*")
        sb.appendLine("import androidx.compose.ui.Alignment")
        sb.appendLine("import androidx.compose.ui.Modifier")
        sb.appendLine("import androidx.compose.ui.graphics.Color")
        sb.appendLine("import androidx.compose.ui.text.font.FontWeight")
        sb.appendLine("import androidx.compose.ui.text.style.TextAlign")
        sb.appendLine("import androidx.compose.ui.unit.dp")
        sb.appendLine("import androidx.compose.ui.unit.sp")
        sb.appendLine()
        sb.appendLine("@Composable")
        sb.appendLine("fun ${fnName}Screen() {")
        sb.appendLine("    val scrollState = rememberScrollState()")
        sb.appendLine("    Box(")
        sb.appendLine("        modifier = Modifier")
        sb.appendLine("            .fillMaxSize()")
        sb.appendLine("            .background(${composeColorLiteral(screen.backgroundColor)})")
        sb.appendLine("            .verticalScroll(scrollState)")
        sb.appendLine("    ) {")
        sb.appendLine("        Box(modifier = Modifier.width(${screen.width}.dp).height(${screen.height}.dp)) {")

        screen.elements.filter { it.visible }.forEach { el ->
            val alignExpr = when (el.textAlign) {
                TextAlignMode.CENTER -> "TextAlign.Center"
                TextAlignMode.RIGHT -> "TextAlign.End"
                TextAlignMode.LEFT -> "TextAlign.Start"
            }
            val weightExpr = if (el.bold) "FontWeight.Bold" else "FontWeight.Normal"
            val mod = "Modifier.offset(x = ${el.x}.dp, y = ${el.y}.dp).size(width = ${el.width}.dp, height = ${el.height}.dp)"
            when (el.kind) {
                ElementKind.BUTTON, ElementKind.FAB -> {
                    sb.appendLine("            Button(")
                    sb.appendLine("                onClick = {},")
                    sb.appendLine("                modifier = $mod,")
                    sb.appendLine("                shape = RoundedCornerShape(${el.borderRadius}.dp),")
                    sb.appendLine("                colors = ButtonDefaults.buttonColors(containerColor = ${composeColorLiteral(el.fillColor)}, contentColor = ${composeColorLiteral(el.textColor)})")
                    sb.appendLine("            ) {")
                    sb.appendLine("                Text(\"${escapeKt(el.text)}\", fontSize = ${el.fontSize}.sp, fontWeight = $weightExpr, textAlign = $alignExpr)")
                    sb.appendLine("            }")
                }
                ElementKind.OUTLINED_BUTTON -> {
                    sb.appendLine("            OutlinedButton(")
                    sb.appendLine("                onClick = {},")
                    sb.appendLine("                modifier = $mod,")
                    sb.appendLine("                shape = RoundedCornerShape(${el.borderRadius}.dp)")
                    sb.appendLine("            ) {")
                    sb.appendLine("                Text(\"${escapeKt(el.text)}\", color = ${composeColorLiteral(el.textColor)}, fontSize = ${el.fontSize}.sp, fontWeight = $weightExpr, textAlign = $alignExpr)")
                    sb.appendLine("            }")
                }
                ElementKind.HEADING, ElementKind.TEXT -> {
                    sb.appendLine("            Text(")
                    sb.appendLine("                text = \"${escapeKt(el.text)}\",")
                    sb.appendLine("                color = ${composeColorLiteral(el.textColor)},")
                    sb.appendLine("                fontSize = ${el.fontSize}.sp,")
                    sb.appendLine("                fontWeight = $weightExpr,")
                    sb.appendLine("                textAlign = $alignExpr,")
                    sb.appendLine("                modifier = $mod")
                    sb.appendLine("            )")
                }
                ElementKind.INPUT -> {
                    val stateName = "input_${el.id.replace(Regex("[^A-Za-z0-9]"), "_")}"
                    sb.appendLine("            var $stateName by remember { mutableStateOf(\"${escapeKt(el.text)}\") }")
                    sb.appendLine("            OutlinedTextField(")
                    sb.appendLine("                value = $stateName,")
                    sb.appendLine("                onValueChange = { $stateName = it },")
                    sb.appendLine("                placeholder = { Text(\"${escapeKt(el.placeholder)}\") },")
                    sb.appendLine("                modifier = $mod")
                    sb.appendLine("            )")
                }
                ElementKind.SWITCH -> {
                    val stateName = "checked_${el.id.replace(Regex("[^A-Za-z0-9]"), "_")}"
                    sb.appendLine("            var $stateName by remember { mutableStateOf(${el.checked}) }")
                    sb.appendLine("            Row(modifier = $mod, verticalAlignment = Alignment.CenterVertically) {")
                    sb.appendLine("                Text(\"${escapeKt(el.text)}\", color = ${composeColorLiteral(el.textColor)}, modifier = Modifier.weight(1f))")
                    sb.appendLine("                Switch(checked = $stateName, onCheckedChange = { $stateName = it })")
                    sb.appendLine("            }")
                }
                ElementKind.PROGRESS -> {
                    val frac = (el.progress.coerceIn(0, 100) / 100f)
                    sb.appendLine("            LinearProgressIndicator(progress = { ${frac}f }, modifier = $mod, color = ${composeColorLiteral(el.fillColor)})")
                }
                else -> {
                    val borderPart = if (el.strokeWidth > 0 && el.strokeColor != "transparent") {
                        ".border(${el.strokeWidth}.dp, ${composeColorLiteral(el.strokeColor)}, RoundedCornerShape(${el.borderRadius}.dp))"
                    } else ""
                    sb.appendLine("            Box(")
                    sb.appendLine("                modifier = $mod.background(${composeColorLiteral(el.fillColor)}, RoundedCornerShape(${el.borderRadius}.dp))$borderPart,")
                    sb.appendLine("                contentAlignment = Alignment.Center")
                    sb.appendLine("            ) {")
                    if (el.text.isNotBlank()) {
                        sb.appendLine("                Text(\"${escapeKt(el.text)}\", color = ${composeColorLiteral(el.textColor)}, fontSize = ${el.fontSize}.sp, fontWeight = $weightExpr, textAlign = $alignExpr)")
                    }
                    sb.appendLine("            }")
                }
            }
        }
        sb.appendLine("        }")
        sb.appendLine("    }")
        sb.appendLine("}")
        return sb.toString()
    }

    fun generateXmlLayout(screen: DesignerScreen): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.appendLine("<ScrollView xmlns:android=\"http://schemas.android.com/apk/res/android\"")
        sb.appendLine("    android:layout_width=\"match_parent\"")
        sb.appendLine("    android:layout_height=\"match_parent\"")
        sb.appendLine("    android:background=\"${ApkDesignerColorUtils.normalizeHex(screen.backgroundColor, "#202A33")}\">")
        sb.appendLine("    <FrameLayout")
        sb.appendLine("        android:layout_width=\"match_parent\"")
        sb.appendLine("        android:layout_height=\"${screen.height}dp\">")

        screen.elements.filter { it.visible }.forEachIndexed { idx, el ->
            val tag = when (el.kind) {
                ElementKind.BUTTON, ElementKind.OUTLINED_BUTTON, ElementKind.FAB -> "Button"
                ElementKind.INPUT -> "EditText"
                ElementKind.SWITCH -> "Switch"
                ElementKind.PROGRESS -> "ProgressBar"
                ElementKind.IMAGE -> "ImageView"
                ElementKind.HEADING, ElementKind.TEXT, ElementKind.TOP_BAR -> "TextView"
                else -> "View"
            }
            val gravity = when (el.textAlign) {
                TextAlignMode.CENTER -> "center"
                TextAlignMode.RIGHT -> "end|center_vertical"
                TextAlignMode.LEFT -> "start|center_vertical"
            }
            sb.appendLine("        <$tag")
            sb.appendLine("            android:id=\"@+id/designer_el_${idx + 1}\"")
            sb.appendLine("            android:layout_width=\"${el.width}dp\"")
            sb.appendLine("            android:layout_height=\"${el.height}dp\"")
            sb.appendLine("            android:layout_marginStart=\"${el.x}dp\"")
            sb.appendLine("            android:layout_marginTop=\"${el.y}dp\"")
            if (el.fillColor != "transparent") {
                sb.appendLine("            android:background=\"${ApkDesignerColorUtils.normalizeHex(el.fillColor)}\"")
            }
            if (tag == "TextView" || tag == "Button" || tag == "EditText" || tag == "Switch") {
                if (el.text.isNotBlank()) {
                    sb.appendLine("            android:text=\"${escapeXml(el.text)}\"")
                }
                if (tag == "EditText" && el.placeholder.isNotBlank()) {
                    sb.appendLine("            android:hint=\"${escapeXml(el.placeholder)}\"")
                }
                sb.appendLine("            android:textSize=\"${el.fontSize}sp\"")
                sb.appendLine("            android:textColor=\"${ApkDesignerColorUtils.normalizeHex(el.textColor, "#EEF4F6")}\"")
                sb.appendLine("            android:gravity=\"$gravity\"")
            }
            sb.appendLine("            />")
        }

        sb.appendLine("    </FrameLayout>")
        sb.appendLine("</ScrollView>")
        return sb.toString()
    }
}
