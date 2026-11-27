package com.pocketapkstudio

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.pocketapkstudio.ApkDesignerModels.CanvasAlign
import com.pocketapkstudio.ApkDesignerModels.DesignerElement
import com.pocketapkstudio.ApkDesignerModels.DesignerScreen
import com.pocketapkstudio.ApkDesignerModels.ElementKind
import com.pocketapkstudio.ApkDesignerModels.TextAlignMode
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Native Figma-style APK Designer Activity for Pocket APK Studio.
 *
 * Mirrors every web-app design capability natively on Android:
 *  - Compact collapsible Tools & Style drawers so Canvas Area stays maximum
 *  - Full-App Import (all pages, tabs, tiles, and AlertDialog windows from any project)
 *  - Touch drag + 8-point resize handles + smart center/grid snap without ScrollView freeze
 *  - Text alignment controls (Left / Center / Right) in both quick bar and Style inspector
 *  - Interactive HSV visual color picker + swatches + Hex input + 6 Color Combination Boxes
 *  - Photo upload from device gallery with Brightness / Contrast / Saturation / Grayscale / Sepia
 *  - 2x PNG export/share + Jetpack Compose & Android XML code export/apply back to project
 */
class ApkDesignerActivity : ComponentActivity() {

    private val screens = mutableListOf<DesignerScreen>()
    private var activeScreenIndex = 0
    private var selectedElementId: String? = null

    private val undoStack = ArrayDeque<List<DesignerScreen>>()
    private val redoStack = ArrayDeque<List<DesignerScreen>>()

    private var leftPanelOpen = false
    private var rightPanelOpen = false
    private var leftTab = 0 // 0=Add, 1=Layers, 2=Presets/Pages
    private var colorTarget = 0 // 0=Fill, 1=Text, 2=Stroke, 3=CanvasBG

    private var projectRootDir: File? = null
    private var activeSourceFile: File? = null
    private var pendingPhotoReplaceId: String? = null

    private lateinit var canvasView: ApkDesignerCanvasView
    private lateinit var canvasScrollView: ScrollView
    private lateinit var screenSpinner: Spinner
    private lateinit var zoomLabel: TextView
    private lateinit var toolsToggleBtn: Button
    private lateinit var styleToggleBtn: Button
    private lateinit var gridToggleBtn: Button
    private lateinit var snapToggleBtn: Button
    private lateinit var leftDrawer: LinearLayout
    private lateinit var leftDrawerContent: LinearLayout
    private lateinit var rightDrawer: LinearLayout
    private lateinit var rightDrawerContent: LinearLayout
    private lateinit var quickBar: LinearLayout
    private lateinit var toastBanner: TextView

    private val pickPhotoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val replaceId = pendingPhotoReplaceId
        pendingPhotoReplaceId = null
        val uriStr = uri.toString()
        if (replaceId != null) {
            updateSelectedElement(recordUndo = true) { it.copy(imageUri = uriStr) }
            showBanner("Photo applied to element")
        } else {
            val photo = ApkDesignerModels.createDefaultElement(ElementKind.IMAGE, 24, 110).copy(
                imageUri = uriStr,
                name = "Uploaded Photo",
                text = "",
            )
            addElementToActiveScreen(photo)
            showBanner("Photo added to canvas")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.getStringExtra("project_root")?.let { path ->
            val dir = File(path)
            if (dir.isDirectory) projectRootDir = dir
        }
        intent.getStringExtra("active_file")?.let { path ->
            val f = File(path)
            if (f.isFile) activeSourceFile = f
        }

        // Load full-app screens from project if provided, otherwise start with Studio Dashboard preset
        val importedScreens = loadInitialScreens()
        if (importedScreens.isNotEmpty()) {
            screens.addAll(importedScreens)
            val requestedIndex = intent.getIntExtra("initial_screen_index", 0)
            activeScreenIndex = requestedIndex.coerceIn(0, screens.lastIndex)
        } else {
            screens.add(ApkDesignerModels.createStarterScreen("dashboard"))
        }

        buildUi()
        syncScreenSpinner()
        refreshCanvasAndBars()

        if (importedScreens.isNotEmpty()) {
            val totalEls = importedScreens.sumOf { it.elements.size }
            showBanner("Loaded ${importedScreens.size} pages/windows ($totalEls editable elements)")
        }
    }

    private fun loadInitialScreens(): List<DesignerScreen> {
        val dir = projectRootDir
        val file = activeSourceFile
        if (dir != null && dir.isDirectory) {
            val bundle = FullAppImportEngine.scanProjectDirectory(dir, file)
            if (bundle.screens.isNotEmpty()) return bundle.screens
        }
        if (file != null && file.isFile) {
            val entry = FullAppImportEngine.SourceFileEntry(file.name, runCatching { file.readText() }.getOrDefault(""))
            val bundle = FullAppImportEngine.importAllScreensFromFile(entry)
            if (bundle.screens.isNotEmpty()) return bundle.screens
        }
        return emptyList()
    }

    private fun activeScreen(): DesignerScreen = screens[activeScreenIndex.coerceIn(0, screens.lastIndex)]

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E141A.toInt())
        }

        // 1. ULTRA-COMPACT TOP TOOLBAR (Horizontally scrollable so every control stays crisp on phones)
        val toolbarRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0xFF151E26.toInt())
        }

        toolbarRow.addView(chipBtn("‹", bg = 0xFF243340.toInt()) { finish() }, lpWrap(right = 6))
        toolsToggleBtn = chipBtn("Tools", bg = 0xFF243340.toInt()) {
            leftPanelOpen = !leftPanelOpen
            if (leftPanelOpen && resources.displayMetrics.widthPixels < dp(760)) rightPanelOpen = false
            updateDrawerVisibility()
        }
        toolbarRow.addView(toolsToggleBtn, lpWrap(right = 6))

        screenSpinner = Spinner(this).apply {
            background = pillBg(0xFF1E2B35.toInt(), 0xFF334756.toInt(), 8)
            setPadding(dp(6), 0, dp(6), 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position in screens.indices && position != activeScreenIndex) {
                        activeScreenIndex = position
                        selectedElementId = null
                        refreshCanvasAndBars()
                        applyAutoFitZoom()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        toolbarRow.addView(screenSpinner, LinearLayout.LayoutParams(dp(146), dp(36)).apply { rightMargin = dp(4) })
        toolbarRow.addView(chipBtn("+", bg = 0xFF243340.toInt()) { addBlankScreen() }, lpWrap(right = 8))

        toolbarRow.addView(chipBtn("↶", bg = 0xFF1E2B35.toInt()) { performUndo() }, lpWrap(right = 4))
        toolbarRow.addView(chipBtn("↷", bg = 0xFF1E2B35.toInt()) { performRedo() }, lpWrap(right = 8))

        gridToggleBtn = chipBtn("Grid", bg = 0xFF3E8790.toInt()) {
            canvasView.showGrid = !canvasView.showGrid
            gridToggleBtn.background = pillBg(if (canvasView.showGrid) 0xFF3E8790.toInt() else 0xFF1E2B35.toInt(), 0xFF395060.toInt(), 8)
        }
        toolbarRow.addView(gridToggleBtn, lpWrap(right = 4))

        snapToggleBtn = chipBtn("Snap", bg = 0xFF3E8790.toInt()) {
            canvasView.snapToGrid = !canvasView.snapToGrid
            snapToggleBtn.background = pillBg(if (canvasView.snapToGrid) 0xFF3E8790.toInt() else 0xFF1E2B35.toInt(), 0xFF395060.toInt(), 8)
        }
        toolbarRow.addView(snapToggleBtn, lpWrap(right = 8))

        toolbarRow.addView(chipBtn("−", bg = 0xFF1E2B35.toInt()) { setZoomLevel(canvasView.zoom - 0.1f) }, lpWrap(right = 2))
        zoomLabel = TextView(this).apply {
            text = "88%"
            textSize = 11f
            setTextColor(0xFFDCE7EC.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { applyAutoFitZoom() }
        }
        toolbarRow.addView(zoomLabel, LinearLayout.LayoutParams(-2, dp(36)))
        toolbarRow.addView(chipBtn("+", bg = 0xFF1E2B35.toInt()) { setZoomLevel(canvasView.zoom + 0.1f) }, lpWrap(right = 8))

        toolbarRow.addView(chipBtn("PNG", bg = 0xFF243340.toInt()) { exportPng() }, lpWrap(right = 4))
        toolbarRow.addView(chipBtn("</> Code", bg = 0xFF3E8790.toInt()) { showCodeExportDialog() }, lpWrap(right = 4))

        styleToggleBtn = chipBtn("Style", bg = 0xFF243340.toInt()) {
            rightPanelOpen = !rightPanelOpen
            if (rightPanelOpen && resources.displayMetrics.widthPixels < dp(760)) leftPanelOpen = false
            updateDrawerVisibility()
        }
        toolbarRow.addView(styleToggleBtn, lpWrap())

        val topScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF151E26.toInt())
            addView(toolbarRow)
        }
        root.addView(topScroll, LinearLayout.LayoutParams(-1, dp(48)))

        // 2. WORKSPACE FRAME: Full-bleed scrollable Canvas + Floating Overlay Collapsible Drawers + Quick Bar
        val workspaceFrame = FrameLayout(this).apply {
            setBackgroundColor(0xFF0C1218.toInt())
        }

        // Scrollable canvas area (aligns top so tall full-app pages never clip!)
        canvasView = ApkDesignerCanvasView(this).apply {
            onSelectElement = { id ->
                selectedElementId = id
                refreshQuickBar()
                if (rightPanelOpen) rebuildRightInspector()
                if (leftPanelOpen && leftTab == 1) rebuildLeftDrawerContent()
            }
            onElementUpdated = { updatedEl, commitUndo ->
                replaceElementOnActiveScreen(updatedEl, recordUndo = commitUndo)
                if (commitUndo && rightPanelOpen) rebuildRightInspector()
            }
        }
        val canvasHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            setPadding(dp(16), dp(20), dp(16), dp(84))
        }
        val phoneFrame = FrameLayout(this).apply {
            background = pillBg(0xFF18222B.toInt(), 0xFF334756.toInt(), 18)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToOutline = true
            addView(canvasView, FrameLayout.LayoutParams(-2, -2))
        }
        canvasHost.addView(phoneFrame, LinearLayout.LayoutParams(-2, -2))

        val hCanvasScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = false
            addView(canvasHost, FrameLayout.LayoutParams(-1, -2))
        }
        canvasScrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(hCanvasScroll, FrameLayout.LayoutParams(-1, -2))
        }
        workspaceFrame.addView(canvasScrollView, FrameLayout.LayoutParams(-1, -1))

        // Toast notification banner at top of canvas
        toastBanner = TextView(this).apply {
            visibility = View.GONE
            textSize = 11.5f
            setTextColor(0xFFDFF7F4.toInt())
            background = pillBg(0xEE18333A.toInt(), 0xFF4CA5A8.toInt(), 10)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            gravity = Gravity.CENTER
        }
        workspaceFrame.addView(
            toastBanner,
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) }
        )

        // Floating Bottom Quick Bar
        quickBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillBg(0xF2151E26.toInt(), 0xFF334756.toInt(), 14)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            elevation = dp(6).toFloat()
        }
        workspaceFrame.addView(
            quickBar,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) }
        )

        // Collapsible LEFT Drawer (Tools: Add / Layers / Presets & Pages)
        leftDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = pillBg(0xFA141D25.toInt(), 0xFF2D3F4E.toInt(), 0)
            elevation = dp(10).toFloat()
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val leftTabsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val leftTabButtons = mutableListOf<Button>()
        fun updateLeftTabStyles() {
            leftTabButtons.forEachIndexed { idx, btn ->
                btn.background = pillBg(if (leftTab == idx) 0xFF3E8790.toInt() else 0xFF202C37.toInt(), 0xFF395060.toInt(), 8)
            }
        }
        fun addLeftTabBtn(label: String, tabIdx: Int) {
            val btn = chipBtn(label, bg = if (leftTab == tabIdx) 0xFF3E8790.toInt() else 0xFF202C37.toInt()) {
                leftTab = tabIdx
                updateLeftTabStyles()
                rebuildLeftDrawerContent()
            }
            leftTabButtons.add(btn)
            leftTabsRow.addView(btn, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })
        }
        addLeftTabBtn("Add", 0)
        addLeftTabBtn("Layers", 1)
        addLeftTabBtn("Pages", 2)
        leftTabsRow.addView(chipBtn("✕", bg = 0xFF263340.toInt()) {
            leftPanelOpen = false
            updateDrawerVisibility()
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        leftDrawer.addView(leftTabsRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        leftDrawerContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val leftScroll = ScrollView(this).apply { addView(leftDrawerContent) }
        leftDrawer.addView(leftScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        workspaceFrame.addView(
            leftDrawer,
            FrameLayout.LayoutParams(dp(254), -1, Gravity.START)
        )

        // Collapsible RIGHT Drawer (Style Inspector + Alignment + HSV Picker + Color Combinations)
        rightDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = pillBg(0xFA141D25.toInt(), 0xFF2D3F4E.toInt(), 0)
            elevation = dp(10).toFloat()
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val rightHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rightHeader.addView(
            TextView(this).apply {
                text = "Style & Color Inspector"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFEEF4F6.toInt())
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        rightHeader.addView(chipBtn("✕", bg = 0xFF263340.toInt()) {
            rightPanelOpen = false
            updateDrawerVisibility()
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        rightDrawer.addView(rightHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        rightDrawerContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rightScroll = ScrollView(this).apply { addView(rightDrawerContent) }
        rightDrawer.addView(rightScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        workspaceFrame.addView(
            rightDrawer,
            FrameLayout.LayoutParams(dp(278), -1, Gravity.END)
        )

        root.addView(workspaceFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        // Auto-fit canvas once laid out
        workspaceFrame.post { applyAutoFitZoom() }
    }

    private fun applyAutoFitZoom() {
        val availW = maxOf(dp(220), canvasScrollView.width - dp(40))
        val availH = maxOf(dp(320), canvasScrollView.height - dp(96))
        val scr = activeScreen()
        // Cap effective height at 840dp for tall scrollable pages so they remain large & readable!
        val fitH = min(scr.height, 840)
        val density = resources.displayMetrics.density
        val ratioW = availW / (scr.width * density)
        val ratioH = availH / (fitH * density)
        val fit = min(ratioW, min(ratioH, 1.05f)).coerceIn(0.45f, 1.15f)
        setZoomLevel(fit)
    }

    private fun setZoomLevel(z: Float) {
        canvasView.zoom = z.coerceIn(0.35f, 1.8f)
        zoomLabel.text = "${(canvasView.zoom * 100f).roundToInt()}%"
    }

    private fun updateDrawerVisibility() {
        leftDrawer.visibility = if (leftPanelOpen) View.VISIBLE else View.GONE
        rightDrawer.visibility = if (rightPanelOpen) View.VISIBLE else View.GONE
        toolsToggleBtn.background = pillBg(if (leftPanelOpen) 0xFF3E8790.toInt() else 0xFF243340.toInt(), 0xFF395060.toInt(), 8)
        styleToggleBtn.background = pillBg(if (rightPanelOpen) 0xFF3E8790.toInt() else 0xFF243340.toInt(), 0xFF395060.toInt(), 8)
        if (leftPanelOpen) rebuildLeftDrawerContent()
        if (rightPanelOpen) rebuildRightInspector()
    }

    private fun syncScreenSpinner() {
        val labels = screens.map { "[${it.screenCategory.badge}] ${it.name}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        screenSpinner.adapter = adapter
        screenSpinner.setSelection(activeScreenIndex.coerceIn(0, screens.lastIndex), false)
    }

    private fun refreshCanvasAndBars() {
        canvasView.screen = activeScreen()
        canvasView.selectedId = selectedElementId
        refreshQuickBar()
        if (leftPanelOpen) rebuildLeftDrawerContent()
        if (rightPanelOpen) rebuildRightInspector()
    }

    private fun pushUndoSnapshot() {
        undoStack.addLast(screens.map { it.copy(elements = it.elements.toList()) })
        if (undoStack.size > 30) undoStack.removeFirst()
        redoStack.clear()
    }

    private fun performUndo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(screens.map { it.copy(elements = it.elements.toList()) })
        val prev = undoStack.removeLast()
        screens.clear()
        screens.addAll(prev)
        activeScreenIndex = activeScreenIndex.coerceIn(0, screens.lastIndex)
        syncScreenSpinner()
        refreshCanvasAndBars()
    }

    private fun performRedo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(screens.map { it.copy(elements = it.elements.toList()) })
        val next = redoStack.removeLast()
        screens.clear()
        screens.addAll(next)
        activeScreenIndex = activeScreenIndex.coerceIn(0, screens.lastIndex)
        syncScreenSpinner()
        refreshCanvasAndBars()
    }

    private fun addBlankScreen() {
        pushUndoSnapshot()
        val s = ApkDesignerModels.createStarterScreen("blank").copy(name = "Screen ${screens.size + 1}")
        screens.add(s)
        activeScreenIndex = screens.lastIndex
        selectedElementId = null
        syncScreenSpinner()
        refreshCanvasAndBars()
    }

    private fun addElementToActiveScreen(el: DesignerElement) {
        pushUndoSnapshot()
        val scr = activeScreen()
        screens[activeScreenIndex] = scr.copy(elements = scr.elements + el)
        selectedElementId = el.id
        refreshCanvasAndBars()
    }

    private fun replaceElementOnActiveScreen(updated: DesignerElement, recordUndo: Boolean) {
        if (recordUndo) pushUndoSnapshot()
        val scr = activeScreen()
        val newList = scr.elements.map { if (it.id == updated.id) updated else it }
        screens[activeScreenIndex] = scr.copy(elements = newList)
        canvasView.screen = screens[activeScreenIndex]
    }

    private fun updateSelectedElement(recordUndo: Boolean = true, transform: (DesignerElement) -> DesignerElement) {
        val id = selectedElementId ?: return
        val scr = activeScreen()
        val target = scr.elements.find { it.id == id } ?: return
        replaceElementOnActiveScreen(transform(target), recordUndo)
        refreshQuickBar()
        if (rightPanelOpen) rebuildRightInspector()
    }

    private fun duplicateSelectedElement() {
        val id = selectedElementId ?: return
        val el = activeScreen().elements.find { it.id == id } ?: return
        val copy = el.copy(
            id = ApkDesignerModels.nextId("dup"),
            name = "${el.name} Copy",
            x = (el.x + 14).coerceAtMost(activeScreen().width - 24),
            y = el.y + 14,
        )
        addElementToActiveScreen(copy)
    }

    private fun deleteSelectedElement() {
        val id = selectedElementId ?: return
        pushUndoSnapshot()
        val scr = activeScreen()
        screens[activeScreenIndex] = scr.copy(elements = scr.elements.filterNot { it.id == id })
        selectedElementId = null
        refreshCanvasAndBars()
    }

    private fun refreshQuickBar() {
        quickBar.removeAllViews()
        val sel = activeScreen().elements.find { it.id == selectedElementId }
        if (sel == null) {
            quickBar.addView(chipBtn("T Text", bg = 0xFF202D38.toInt()) {
                addElementToActiveScreen(ApkDesignerModels.createDefaultElement(ElementKind.TEXT, 24, 140))
            }, lpWrap(right = 6))
            quickBar.addView(chipBtn("+ Button", bg = 0xFF202D38.toInt()) {
                addElementToActiveScreen(ApkDesignerModels.createDefaultElement(ElementKind.BUTTON, 80, 200))
            }, lpWrap(right = 6))
            quickBar.addView(chipBtn("Photo", bg = 0xFF202D38.toInt()) {
                pendingPhotoReplaceId = null
                pickPhotoLauncher.launch("image/*")
            }, lpWrap(right = 6))
            quickBar.addView(chipBtn("All Tools", bg = 0xFF3E8790.toInt()) {
                leftPanelOpen = true
                leftTab = 0
                updateDrawerVisibility()
            }, lpWrap())
        } else {
            quickBar.addView(
                TextView(this).apply {
                    text = sel.name.take(12)
                    textSize = 11.5f
                    setTextColor(0xFFDCE7EC.toInt())
                    setPadding(dp(4), 0, dp(6), 0)
                },
                LinearLayout.LayoutParams(-2, -2)
            )
            // Direct Text Alignment buttons right on the quick bar!
            listOf(
                "≡L" to TextAlignMode.LEFT,
                "≡C" to TextAlignMode.CENTER,
                "≡R" to TextAlignMode.RIGHT,
            ).forEach { (lbl, mode) ->
                val active = sel.textAlign == mode
                quickBar.addView(
                    chipBtn(lbl, bg = if (active) 0xFF3E8790.toInt() else 0xFF202D38.toInt()) {
                        updateSelectedElement { it.copy(textAlign = mode) }
                    },
                    lpWrap(right = 3)
                )
            }
            quickBar.addView(chipBtn("Style", bg = 0xFF3E8790.toInt()) {
                rightPanelOpen = true
                updateDrawerVisibility()
            }, lpWrap(left = 4, right = 4))
            quickBar.addView(chipBtn("⧉", bg = 0xFF243340.toInt()) { duplicateSelectedElement() }, lpWrap(right = 4))
            quickBar.addView(chipBtn("🗑", bg = 0xFF5D2932.toInt()) { deleteSelectedElement() }, lpWrap())
        }
    }

    private fun rebuildLeftDrawerContent() {
        leftDrawerContent.removeAllViews()
        when (leftTab) {
            0 -> {
                // Upload Photo button
                leftDrawerContent.addView(
                    actionTileBtn("📷  Upload Photo from Device", 0xFF24404A.toInt()) {
                        pendingPhotoReplaceId = null
                        pickPhotoLauncher.launch("image/*")
                    },
                    LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) }
                )
                leftDrawerContent.addView(sectionHeading("ANDROID UI COMPONENTS"))
                val uiKinds = listOf(
                    ElementKind.TOP_BAR, ElementKind.BOTTOM_NAV,
                    ElementKind.BUTTON, ElementKind.OUTLINED_BUTTON,
                    ElementKind.INPUT, ElementKind.SWITCH,
                    ElementKind.PROGRESS, ElementKind.FAB,
                )
                addTwoColumnKindGrid(uiKinds)
                leftDrawerContent.addView(sectionHeading("TEXT, PHOTOS & SHAPES"))
                val shapeKinds = listOf(
                    ElementKind.HEADING, ElementKind.TEXT,
                    ElementKind.IMAGE, ElementKind.CARD,
                    ElementKind.RECTANGLE, ElementKind.CIRCLE,
                    ElementKind.DIVIDER,
                )
                addTwoColumnKindGrid(shapeKinds)
            }
            1 -> {
                val els = activeScreen().elements
                leftDrawerContent.addView(sectionHeading("LAYERS (${els.size})"))
                if (els.isEmpty()) {
                    leftDrawerContent.addView(mutedLabel("No elements on this screen yet."))
                }
                for (el in els.reversed()) {
                    val isSel = el.id == selectedElementId
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(6), dp(6), dp(6))
                        background = pillBg(if (isSel) 0xFF274A54.toInt() else 0xFF1D2933.toInt(), 0xFF334756.toInt(), 8)
                        setOnClickListener {
                            selectedElementId = el.id
                            refreshCanvasAndBars()
                        }
                    }
                    row.addView(
                        TextView(this).apply {
                            text = el.name.take(16)
                            textSize = 11.5f
                            setTextColor(if (el.visible) 0xFFEEF4F6.toInt() else 0xFF6B7E8A.toInt())
                        },
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                    row.addView(miniBtn("↑") {
                        pushUndoSnapshot()
                        screens[activeScreenIndex] = activeScreen().copy(
                            elements = ApkDesignerModels.reorderLayer(activeScreen().elements, el.id, true)
                        )
                        refreshCanvasAndBars()
                    }, lpWrap(right = 2))
                    row.addView(miniBtn("↓") {
                        pushUndoSnapshot()
                        screens[activeScreenIndex] = activeScreen().copy(
                            elements = ApkDesignerModels.reorderLayer(activeScreen().elements, el.id, false)
                        )
                        refreshCanvasAndBars()
                    }, lpWrap(right = 2))
                    row.addView(miniBtn(if (el.visible) "👁" else "—") {
                        replaceElementOnActiveScreen(el.copy(visible = !el.visible), recordUndo = true)
                        rebuildLeftDrawerContent()
                    }, lpWrap(right = 2))
                    row.addView(miniBtn(if (el.locked) "🔒" else "🔓") {
                        replaceElementOnActiveScreen(el.copy(locked = !el.locked), recordUndo = true)
                        rebuildLeftDrawerContent()
                    }, lpWrap())
                    leftDrawerContent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
                }
            }
            2 -> {
                if (projectRootDir != null) {
                    leftDrawerContent.addView(
                        actionTileBtn("✦  Import Full App (All Pages & Windows)", 0xFF3E8790.toInt()) {
                            val fresh = loadInitialScreens()
                            if (fresh.isNotEmpty()) {
                                pushUndoSnapshot()
                                screens.clear()
                                screens.addAll(fresh)
                                activeScreenIndex = 0
                                selectedElementId = null
                                syncScreenSpinner()
                                refreshCanvasAndBars()
                                applyAutoFitZoom()
                                showBanner("Imported ${fresh.size} pages, tabs & dialogs")
                            } else {
                                showBanner("No UI screens found in project")
                            }
                        },
                        LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(10) }
                    )
                }
                leftDrawerContent.addView(sectionHeading("LOADED PAGES & WINDOWS (${screens.size})"))
                screens.forEachIndexed { idx, scr ->
                    val active = idx == activeScreenIndex
                    val btn = actionTileBtn(
                        "[${scr.screenCategory.badge}]  ${scr.name} (${scr.elements.size})",
                        if (active) 0xFF2D5964.toInt() else 0xFF1D2933.toInt()
                    ) {
                        activeScreenIndex = idx
                        selectedElementId = null
                        syncScreenSpinner()
                        refreshCanvasAndBars()
                        applyAutoFitZoom()
                    }
                    leftDrawerContent.addView(btn, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(5) })
                }
                leftDrawerContent.addView(sectionHeading("STARTER PRESETS"))
                listOf(
                    "dashboard" to "Studio Dashboard",
                    "login" to "Clean Login",
                    "profile" to "Profile & Photo",
                    "blank" to "Blank Screen",
                ).forEach { (presetId, title) ->
                    leftDrawerContent.addView(
                        actionTileBtn("+ $title", 0xFF202D38.toInt()) {
                            pushUndoSnapshot()
                            screens.add(ApkDesignerModels.createStarterScreen(presetId))
                            activeScreenIndex = screens.lastIndex
                            selectedElementId = null
                            syncScreenSpinner()
                            refreshCanvasAndBars()
                            applyAutoFitZoom()
                        },
                        LinearLayout.LayoutParams(-1, dp(36)).apply { bottomMargin = dp(5) }
                    )
                }
            }
        }
    }

    private fun addTwoColumnKindGrid(kinds: List<ElementKind>) {
        var i = 0
        while (i < kinds.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val k1 = kinds[i]
            row.addView(
                actionTileBtn(k1.label, 0xFF1E2B35.toInt()) {
                    addElementToActiveScreen(ApkDesignerModels.createDefaultElement(k1, 24, 140))
                },
                LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) }
            )
            if (i + 1 < kinds.size) {
                val k2 = kinds[i + 1]
                row.addView(
                    actionTileBtn(k2.label, 0xFF1E2B35.toInt()) {
                        addElementToActiveScreen(ApkDesignerModels.createDefaultElement(k2, 24, 140))
                    },
                    LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(4) }
                )
            } else {
                row.addView(View(this), LinearLayout.LayoutParams(0, dp(36), 1f))
            }
            leftDrawerContent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
            i += 2
        }
    }

    private fun rebuildRightInspector() {
        rightDrawerContent.removeAllViews()
        val sel = activeScreen().elements.find { it.id == selectedElementId }

        // 1. COLOR COMBINATION BOXES (Always accessible so user can theme the entire screen in 1 tap!)
        rightDrawerContent.addView(sectionHeading("COLOR COMBINATIONS (1-TAP THEME)"))
        for (combo in ApkDesignerColorUtils.COLOR_COMBINATIONS) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = pillBg(0xFF1C2731.toInt(), 0xFF314453.toInt(), 10)
                setOnClickListener {
                    pushUndoSnapshot()
                    screens[activeScreenIndex] = ApkDesignerModels.applyColorCombination(activeScreen(), combo)
                    refreshCanvasAndBars()
                    showBanner("Applied ${combo.name} color palette")
                }
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(
                TextView(this).apply {
                    text = combo.name
                    textSize = 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFFEEF4F6.toInt())
                },
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            top.addView(
                TextView(this).apply {
                    text = "Apply"
                    textSize = 10.5f
                    setTextColor(0xFF69C1B8.toInt())
                }
            )
            card.addView(top)

            val swatches = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(5), 0, 0)
            }
            listOf(combo.background, combo.surface, combo.primary, combo.accent, combo.text).forEach { hex ->
                val box = View(this).apply {
                    background = pillBg(ApkDesignerColorUtils.parseColorInt(hex), 0xFF425868.toInt(), 5)
                }
                swatches.addView(box, LinearLayout.LayoutParams(0, dp(16), 1f).apply { rightMargin = dp(4) })
            }
            card.addView(swatches)
            rightDrawerContent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }

        // 2. VISUAL HSV COLOR PICKER + SWATCHES + HEX
        rightDrawerContent.addView(sectionHeading("COLOR PICKER"))
        val targetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val targetLabels = if (sel != null) listOf("Fill", "Text", "Stroke", "BG") else listOf("Canvas BG")
        if (sel == null) colorTarget = 3
        targetLabels.forEachIndexed { idx, lbl ->
            val actualIdx = if (sel == null) 3 else idx
            targetRow.addView(
                chipBtn(lbl, bg = if (colorTarget == actualIdx) 0xFF3E8790.toInt() else 0xFF1F2C36.toInt()) {
                    colorTarget = actualIdx
                    rebuildRightInspector()
                },
                LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(3) }
            )
        }
        rightDrawerContent.addView(targetRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val currentHex = when {
            colorTarget == 3 || sel == null -> activeScreen().backgroundColor
            colorTarget == 0 -> sel.fillColor
            colorTarget == 1 -> sel.textColor
            else -> sel.strokeColor
        }

        fun applyColorHex(hex: String, recordUndo: Boolean = false) {
            val liveEl = activeScreen().elements.find { it.id == selectedElementId }
            if (colorTarget == 3 || liveEl == null) {
                if (recordUndo) pushUndoSnapshot()
                screens[activeScreenIndex] = activeScreen().copy(backgroundColor = hex)
                canvasView.screen = screens[activeScreenIndex]
            } else {
                val updated = when (colorTarget) {
                    0 -> liveEl.copy(fillColor = hex)
                    1 -> liveEl.copy(textColor = hex)
                    else -> liveEl.copy(strokeColor = hex, strokeWidth = maxOf(1, liveEl.strokeWidth))
                }
                replaceElementOnActiveScreen(updated, recordUndo)
            }
        }

        val hexInput = EditText(this).apply {
            setText(currentHex)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFEEF4F6.toInt())
            background = pillBg(0xFF101820.toInt(), 0xFF334756.toInt(), 8)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setSingleLine(true)
        }

        val hsvPicker = HsvColorPickerView(this).apply {
            setHexColor(currentHex)
            onColorChanged = { hex ->
                hexInput.setText(hex)
                applyColorHex(hex, recordUndo = false)
            }
        }
        rightDrawerContent.addView(hsvPicker, LinearLayout.LayoutParams(-1, dp(156)).apply { bottomMargin = dp(6) })

        val hexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hexRow.addView(hexInput, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) })
        hexRow.addView(chipBtn("Set", bg = 0xFF3E8790.toInt()) {
            val norm = ApkDesignerColorUtils.normalizeHex(hexInput.text.toString(), currentHex)
            hsvPicker.setHexColor(norm)
            applyColorHex(norm, recordUndo = true)
            rebuildRightInspector()
        }, lpWrap())
        rightDrawerContent.addView(hexRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        // Quick swatches grid (2 rows of 8)
        for (rowIdx in 0..1) {
            val sRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val slice = ApkDesignerColorUtils.QUICK_SWATCHES.drop(rowIdx * 8).take(8)
            for (swHex in slice) {
                val box = View(this).apply {
                    background = pillBg(ApkDesignerColorUtils.parseColorInt(swHex), 0xFF455B6B.toInt(), 5)
                    setOnClickListener {
                        hsvPicker.setHexColor(swHex)
                        hexInput.setText(swHex)
                        applyColorHex(swHex, recordUndo = true)
                    }
                }
                sRow.addView(box, LinearLayout.LayoutParams(0, dp(22), 1f).apply { rightMargin = dp(3) })
            }
            rightDrawerContent.addView(sRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }

        if (sel == null) {
            rightDrawerContent.addView(mutedLabel("Tap any element on the canvas to edit its text, alignment, size, or photo filters."))
            return
        }

        // 3. TEXT & ALIGNMENT CONTROLS
        rightDrawerContent.addView(sectionHeading("TEXT & ALIGNMENT"))
        val textEdit = EditText(this).apply {
            setText(sel.text)
            hint = "Element text"
            textSize = 12.5f
            setTextColor(0xFFEEF4F6.toInt())
            setHintTextColor(0xFF7B909E.toInt())
            background = pillBg(0xFF101820.toInt(), 0xFF334756.toInt(), 8)
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        val applyTextRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        applyTextRow.addView(textEdit, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(4) })
        applyTextRow.addView(chipBtn("Apply", bg = 0xFF3E8790.toInt()) {
            updateSelectedElement { it.copy(text = textEdit.text.toString()) }
        }, lpWrap())
        rightDrawerContent.addView(applyTextRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        val alignRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "Left" to TextAlignMode.LEFT,
            "Center" to TextAlignMode.CENTER,
            "Right" to TextAlignMode.RIGHT,
        ).forEach { (label, mode) ->
            alignRow.addView(
                chipBtn(label, bg = if (sel.textAlign == mode) 0xFF3E8790.toInt() else 0xFF1F2C36.toInt()) {
                    updateSelectedElement { it.copy(textAlign = mode) }
                },
                LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(3) }
            )
        }
        rightDrawerContent.addView(alignRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        // Font size + Bold toggle
        val fontRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fontRow.addView(chipBtn("A−", bg = 0xFF1F2C36.toInt()) {
            updateSelectedElement { it.copy(fontSize = (it.fontSize - 1).coerceAtLeast(8)) }
        }, lpWrap(right = 4))
        fontRow.addView(
            TextView(this).apply {
                text = "${sel.fontSize}sp"
                textSize = 12f
                setTextColor(0xFFEEF4F6.toInt())
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        fontRow.addView(chipBtn("A+", bg = 0xFF1F2C36.toInt()) {
            updateSelectedElement { it.copy(fontSize = (it.fontSize + 1).coerceAtMost(48)) }
        }, lpWrap(right = 6))
        fontRow.addView(chipBtn("Bold", bg = if (sel.bold) 0xFF3E8790.toInt() else 0xFF1F2C36.toInt()) {
            updateSelectedElement { it.copy(bold = !it.bold) }
        }, lpWrap())
        rightDrawerContent.addView(fontRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // 4. CANVAS ALIGNMENT & GEOMETRY
        rightDrawerContent.addView(sectionHeading("CANVAS ALIGNMENT"))
        val posAlignRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "⇤" to CanvasAlign.LEFT,
            "↔" to CanvasAlign.CENTER_H,
            "⇥" to CanvasAlign.RIGHT,
            "⤒" to CanvasAlign.TOP,
            "↕" to CanvasAlign.CENTER_V,
            "⤓" to CanvasAlign.BOTTOM,
        ).forEach { (sym, alignMode) ->
            posAlignRow.addView(
                chipBtn(sym, bg = 0xFF1F2C36.toInt()) {
                    updateSelectedElement { ApkDesignerModels.alignElementInCanvas(activeScreen(), it, alignMode) }
                },
                LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(2) }
            )
        }
        rightDrawerContent.addView(posAlignRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // Radius & Opacity sliders
        rightDrawerContent.addView(sectionHeading("RADIUS (${sel.borderRadius}dp) · OPACITY (${sel.opacity}%)"))
        rightDrawerContent.addView(
            SeekBar(this).apply {
                max = 40
                progress = sel.borderRadius.coerceIn(0, 40)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) replaceElementOnActiveScreen(sel.copy(borderRadius = p), false)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = rebuildRightInspector()
                })
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        )
        rightDrawerContent.addView(
            SeekBar(this).apply {
                max = 100
                progress = sel.opacity.coerceIn(10, 100)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) replaceElementOnActiveScreen(sel.copy(opacity = p.coerceAtLeast(10)), false)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = rebuildRightInspector()
                })
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        )

        // 5. PHOTO UPLOAD & FILTERS (When Image element selected)
        if (sel.kind == ElementKind.IMAGE) {
            rightDrawerContent.addView(sectionHeading("PHOTO & FILTERS"))
            rightDrawerContent.addView(
                actionTileBtn("📷  Choose / Replace Photo", 0xFF3E8790.toInt()) {
                    pendingPhotoReplaceId = sel.id
                    pickPhotoLauncher.launch("image/*")
                },
                LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(6) }
            )
            val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            filterRow.addView(
                chipBtn("Grayscale", bg = if (sel.filters.grayscale) 0xFF3E8790.toInt() else 0xFF1F2C36.toInt()) {
                    updateSelectedElement { it.copy(filters = it.filters.copy(grayscale = !it.filters.grayscale)) }
                },
                LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) }
            )
            filterRow.addView(
                chipBtn("Sepia", bg = if (sel.filters.sepia) 0xFF3E8790.toInt() else 0xFF1F2C36.toInt()) {
                    updateSelectedElement { it.copy(filters = it.filters.copy(sepia = !it.filters.sepia)) }
                },
                LinearLayout.LayoutParams(0, dp(34), 1f)
            )
            rightDrawerContent.addView(filterRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            rightDrawerContent.addView(mutedLabel("Brightness (${sel.filters.brightness}%)"))
            rightDrawerContent.addView(SeekBar(this).apply {
                max = 180
                progress = sel.filters.brightness.coerceIn(20, 180)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) replaceElementOnActiveScreen(sel.copy(filters = sel.filters.copy(brightness = p.coerceAtLeast(20))), false)
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar?) = rebuildRightInspector()
                })
            })
        }
    }

    private fun exportPng() {
        runCatching {
            val bmp = canvasView.renderToBitmap(2f)
            val outDir = File(cacheDir, "designer-exports").apply { mkdirs() }
            val safeName = activeScreen().name.replace(Regex("[^A-Za-z0-9_-]+"), "_").ifBlank { "screen" }
            val outFile = File(outDir, "${safeName}.png")
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            bmp.recycle()
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", outFile)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Export PNG Design"))
        }.onFailure { err ->
            Toast.makeText(this, "PNG export failed: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCodeExportDialog() {
        val scr = activeScreen()
        val composeCode = ApkDesignerModels.generateComposeCode(scr)
        val xmlCode = ApkDesignerModels.generateXmlLayout(scr)
        var showingCompose = true

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
        }
        val toggleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val codeView = EditText(this).apply {
            setText(composeCode)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFFE2EDF2.toInt())
            setBackgroundColor(0xFF121B23.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 16
        }
        lateinit var btnCompose: Button
        lateinit var btnXml: Button
        btnCompose = chipBtn("Jetpack Compose (.kt)", bg = 0xFF3E8790.toInt()) {
            showingCompose = true
            codeView.setText(composeCode)
            btnCompose.background = pillBg(0xFF3E8790.toInt(), 0xFF395060.toInt(), 8)
            btnXml.background = pillBg(0xFF243340.toInt(), 0xFF395060.toInt(), 8)
        }
        btnXml = chipBtn("Android XML (.xml)", bg = 0xFF243340.toInt()) {
            showingCompose = false
            codeView.setText(xmlCode)
            btnCompose.background = pillBg(0xFF243340.toInt(), 0xFF395060.toInt(), 8)
            btnXml.background = pillBg(0xFF3E8790.toInt(), 0xFF395060.toInt(), 8)
        }
        toggleRow.addView(btnCompose, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) })
        toggleRow.addView(btnXml, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(4) })
        box.addView(toggleRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        box.addView(codeView, LinearLayout.LayoutParams(-1, dp(280)))

        val builder = AlertDialog.Builder(this)
            .setTitle("Export Design Code — ${scr.name}")
            .setView(box)
            .setNeutralButton("Copy Code") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("APK Designer Code", codeView.text.toString()))
                showBanner("Code copied to clipboard")
            }
            .setNegativeButton("Close", null)

        if (projectRootDir != null) {
            builder.setPositiveButton("Apply to Project") { _, _ ->
                applyExportedCodeToProject(scr, codeView.text.toString(), showingCompose)
            }
        }
        builder.show()
    }

    private fun applyExportedCodeToProject(screen: DesignerScreen, code: String, isCompose: Boolean) {
        val dir = projectRootDir ?: return
        runCatching {
            val safeName = screen.name.replace(Regex("[^A-Za-z0-9]+"), "").ifBlank { "Designed" }
            val target = if (isCompose) {
                val srcFile = screen.sourceFilePath?.takeIf { it.endsWith(".kt") && screen.sourceKind == "compose" }
                if (srcFile != null) File(dir, srcFile)
                else File(dir, "app/src/main/java/com/pocketapkstudio/designer/${safeName}Screen.kt")
            } else {
                val srcFile = screen.sourceFilePath?.takeIf { it.endsWith(".xml") }
                if (srcFile != null) File(dir, srcFile)
                else File(dir, "app/src/main/res/layout/designer_${safeName.lowercase()}.xml")
            }
            target.parentFile?.mkdirs()
            target.writeText(code)
            val relPath = target.relativeTo(dir).invariantSeparatorsPath
            setResult(
                RESULT_OK,
                Intent().putExtra("designer_exported_path", relPath)
            )
            showBanner("Saved to $relPath")
            Toast.makeText(this, "Applied design to $relPath", Toast.LENGTH_LONG).show()
        }.onFailure { err ->
            Toast.makeText(this, "Save failed: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showBanner(msg: String) {
        toastBanner.text = "✓  $msg"
        toastBanner.visibility = View.VISIBLE
        toastBanner.removeCallbacks(hideBannerTask)
        toastBanner.postDelayed(hideBannerTask, 2600L)
    }

    private val hideBannerTask = Runnable { toastBanner.visibility = View.GONE }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun pillBg(fill: Int, stroke: Int = 0xFF334756.toInt(), radiusDp: Int = 10): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun chipBtn(label: String, bg: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11.5f
        setTextColor(0xFFEEF4F6.toInt())
        background = pillBg(bg, 0xFF395060.toInt(), 8)
        setPadding(dp(10), 0, dp(10), 0)
        minWidth = dp(34)
        minHeight = dp(34)
        setOnClickListener { onClick() }
    }

    private fun miniBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 10.5f
        setTextColor(0xFFDCE7EC.toInt())
        background = pillBg(0xFF243442.toInt(), 0xFF384C5C.toInt(), 6)
        setPadding(dp(4), 0, dp(4), 0)
        minWidth = dp(26)
        minHeight = dp(26)
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        setOnClickListener { onClick() }
    }

    private fun actionTileBtn(label: String, bg: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11.5f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(0xFFEEF4F6.toInt())
        background = pillBg(bg, 0xFF364B5B.toInt(), 9)
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun sectionHeading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF8CA5B5.toInt())
        setPadding(dp(2), dp(8), dp(2), dp(5))
    }

    private fun mutedLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(0xFF8EA4B2.toInt())
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }

    private fun lpWrap(left: Int = 0, right: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            leftMargin = dp(left)
            rightMargin = dp(right)
        }
}
