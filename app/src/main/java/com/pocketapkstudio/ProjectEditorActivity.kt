package com.pocketapkstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.graphics.Rect
import android.view.WindowManager
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.LinkedHashMap
import java.util.ArrayDeque
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectEditorActivity : Activity() {
    private lateinit var rootDir: File
    private lateinit var editor: EditText
    private lateinit var lineNumbers: TextView
    private lateinit var fileLabel: TextView
    private lateinit var tabsBar: LinearLayout
    private lateinit var suggestionsBar: LinearLayout
    private lateinit var editorContainer: FrameLayout
    private lateinit var workArea: FrameLayout
    private lateinit var designPanel: LinearLayout
    private lateinit var designCanvas: LinearLayout
    private lateinit var designToggleButton: Button
    private var designMode = false
    private lateinit var inlineGhostView: TextView
    private var inlineCompletionSuffix = ""
    private var inlineCompletionTokenStart = -1
    private lateinit var errorsPanel: TextView
    private lateinit var problemPanel: TextView
    private var editorProblems: List<Pair<Int, String>> = emptyList()
    private lateinit var root: LinearLayout
    private lateinit var contentRoot: LinearLayout
    private var landscapeMode = false
    private lateinit var explorerPanel: LinearLayout
    private lateinit var explorerScrollView: ScrollView
    private lateinit var codeScrollView: ScrollView
    private lateinit var explorerToggle: Button
    private lateinit var diagnosticsPanel: LinearLayout
    private lateinit var exportButton: Button
    private var explorerOpen = false
    private var currentFile: File? = null
    private val openTabs = LinkedHashMap<File, String>()
    private val handler = Handler(Looper.getMainLooper())
    private var loadingText = false
    private var autoEditing = false
    private var darkTheme = true
    private var dirty = false
    private val expandedDirectories = linkedSetOf<String>()
    private val undoHistory = ArrayDeque<String>()
    private val redoHistory = ArrayDeque<String>()
    private var textBeforeChange = ""
    private var lastUndoAt = 0L
    private val autoSave = Runnable { saveCurrent(false) }
    private val pendingAiChanges = LinkedHashMap<String, File>()
    private val aiSafeExtensions = setOf("kt","java","xml","gradle","kts","properties","txt","md","json","yaml","yml","dart","sh","pro","toml","html","css")

    private val editableExtensions = setOf("kt","java","xml","gradle","kts","properties","txt","md","json","yaml","yml","py","dart","sh","pro","toml","html","css")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val source = intent.getStringExtra("zip_uri")?.let { Uri.parse(it) }
        if (source == null) { finish(); return }
        rootDir = File(cacheDir, "editor-project")
        val restoreWorkspace = savedInstanceState?.getBoolean("workspace_loaded", false) == true && rootDir.isDirectory
        if (!restoreWorkspace) rootDir.deleteRecursively()
        rootDir.mkdirs()
        try {
            if (!restoreWorkspace) contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    var count = 0
                    var totalBytes = 0L
                    val rootPath = rootDir.canonicalPath + File.separator
                    while (entry != null) {
                        require(++count <= 30000) { "Project ZIP contains too many entries (limit 30000)" }
                        val out = File(rootDir, entry.name).canonicalFile
                        require(out.path.startsWith(rootPath)) { "Unsafe ZIP path" }
                        if (entry.isDirectory) {
                            require(out.mkdirs() || out.isDirectory) { "Could not create project folder" }
                        } else {
                            out.parentFile?.let { require(it.mkdirs() || it.isDirectory) { "Could not create project folder" } }
                            out.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    totalBytes += n
                                    require(totalBytes <= 250L * 1024L * 1024L) { "Project ZIP exceeds 250 MB uncompressed limit" }
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: if (!restoreWorkspace) error("Cannot read ZIP") else Unit
            if (restoreWorkspace) {
                savedInstanceState?.getStringArrayList("expanded_directories")?.let { expandedDirectories.addAll(it) }
            }
            showEditor()
            if (restoreWorkspace) {
                val savedTabs = savedInstanceState?.getStringArrayList("open_tabs") ?: arrayListOf()
                val activePath = savedInstanceState?.getString("active_file")
                savedTabs.forEach { relative ->
                    val file = File(rootDir, relative).canonicalFile
                    if (file.isFile && file.path.startsWith(rootDir.canonicalPath + File.separator) &&
                        file.extension.lowercase() in editableExtensions && file.length() <= 1024 * 1024) openFile(file)
                }
                activePath?.let { relative ->
                    val active = File(rootDir, relative).canonicalFile
                    if (active.isFile && active.path.startsWith(rootDir.canonicalPath + File.separator)) openFile(active)
                }
                val cursor = savedInstanceState?.getInt("editor_cursor", -1) ?: -1
                if (cursor >= 0 && ::editor.isInitialized) editor.setSelection(cursor.coerceAtMost(editor.text.length))
                savedInstanceState?.getInt("code_scroll_y", 0)?.let { y -> codeScrollView.post { codeScrollView.scrollTo(0, y) } }
                savedInstanceState?.getInt("explorer_scroll_y", 0)?.let { y -> explorerScrollView.post { explorerScrollView.scrollTo(0, y) } }
                if (savedInstanceState?.getBoolean("explorer_open", false) == true) {
                    explorerOpen = false
                    toggleExplorer()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "ZIP open failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showEditor() {
        landscapeMode = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        root = LinearLayout(this).apply {
            orientation = if (landscapeMode) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(6))
            isFocusableInTouchMode = true
        }
        applyTheme()
        if (landscapeMode) {
            val sidebar = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(dp(5), dp(8), dp(5), dp(6))
                background = roundedSurface(if (darkTheme) 0xFF2B3742.toInt() else 0xFFE7ECF7.toInt(), 14)
            }
            sidebar.addView(label("Pocket APK", 11, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER },
                LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            fun nav(text: String, action: () -> Unit) {
                sidebar.addView(smallButton(text, action).apply {
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding(dp(2), 0, dp(2), 0)
                }, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(5) })
            }
            nav("▣ Project") { toggleExplorer() }
            nav("</> Editor") { editor.requestFocus() }
            nav("⚙ Build") { showBuildErrors() }
            nav("▶ Run") { openLivePreview() }
            nav("••• More") { showMoreMenu(root) }
            root.addView(sidebar, LinearLayout.LayoutParams(dp(76), -1).apply { rightMargin = dp(8) })
            contentRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            root.addView(contentRoot, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            contentRoot = root
        }
        val toolbar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(7),dp(5),dp(7),dp(5)); background=roundedSurface(if(darkTheme) 0xFF2B3742.toInt() else 0xFFE7ECF7.toInt(),14) }
        fun addToolbarTile(tile: Button) {
            toolbar.addView(tile, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
        }
        explorerToggle=smallButton("☰  Explorer") { toggleExplorer() }
        if (landscapeMode) {
            addToolbarTile(smallButton("⋮") { showMoreMenu(toolbar) })
        } else {
            addToolbarTile(explorerToggle)
            addToolbarTile(smallButton("＋ File") { showFileActions() })
            addToolbarTile(smallButton("⌕ Find") { showFindReplace(false) })
            addToolbarTile(smallButton("⇄ Replace") { showFindReplace(true) })
            addToolbarTile(smallButton("↶ Undo") { undoEdit() })
            addToolbarTile(smallButton("↷ Redo") { redoEdit() })
            addToolbarTile(smallButton("✓ Save") { saveCurrent(true) })
            addToolbarTile(smallButton("⌕ Project") { showProjectSearch() })
            addToolbarTile(smallButton("↗ Definition") { showSymbolNavigation(false) })
            addToolbarTile(smallButton("⌁ References") { showSymbolNavigation(true) })
        }
        val toolbarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(toolbar, LinearLayout.LayoutParams(-2, -1))
        }
        designToggleButton = smallButton("▣  Design") { toggleXmlDesign() }
        designToggleButton.visibility = View.GONE
        addToolbarTile(designToggleButton)
        contentRoot.addView(toolbarScroll,LinearLayout.LayoutParams(-1,dp(48)).apply { bottomMargin=dp(5) })
        val livePreviewButton = smallButton("▶  Live UI Preview") { openLivePreview() }.apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedSurface(0xFF2563EB.toInt(), 12)
        }
        if (!landscapeMode) contentRoot.addView(livePreviewButton, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(7) })
        val aiActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shareAiButton = smallButton("✦  Share to ChatGPT") { shareProjectWithChatGPT() }.apply {
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedSurface(0xFF256B72.toInt(), 12)
        }
        val importAiButton = smallButton("⇩  Import AI Changes") { chooseAiChangesZip() }.apply {
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedSurface(0xFF3E607C.toInt(), 12)
        }
        aiActions.addView(shareAiButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(5) })
        aiActions.addView(importAiButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(5) })
        if (!landscapeMode) contentRoot.addView(aiActions, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
        fileLabel=label("Select a source file to start editing",11,false).apply { setPadding(dp(9),dp(7),dp(9),dp(7)); background=roundedSurface(if(darkTheme) 0xFF26333D.toInt() else 0xFFEFF2F8.toInt(),9) }
        contentRoot.addView(fileLabel,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        tabsBar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val tabsScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(tabsBar) }
        contentRoot.addView(tabsScroll,LinearLayout.LayoutParams(-1,dp(42)))
        workArea=FrameLayout(this).apply { background=roundedSurface(if(darkTheme) 0xFF202A33.toInt() else 0xFFFFFFFF.toInt(),14); clipToOutline=true }
        val codeRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
        lineNumbers=TextView(this).apply { typeface=Typeface.MONOSPACE; textSize=12f; gravity=Gravity.TOP or Gravity.END; setPadding(dp(8),dp(12),dp(8),dp(12)); text="1" }
        codeRow.addView(lineNumbers,LinearLayout.LayoutParams(dp(42),-1))
        editor=EditText(this).apply {
            typeface=Typeface.MONOSPACE; textSize=13f; gravity=Gravity.TOP or Gravity.START
            setPadding(dp(10),dp(12),dp(10),dp(18)); setHorizontallyScrolling(true)
            isVerticalScrollBarEnabled=true; background=null
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        editorContainer=FrameLayout(this)
        editorContainer.addView(editor,FrameLayout.LayoutParams(-1,-1))
        inlineGhostView=TextView(this).apply {
            typeface=Typeface.MONOSPACE; textSize=13f; setPadding(0,0,0,0)
            setTextColor(if(darkTheme) 0xFF87939D.toInt() else 0xFF9AA4AF.toInt())
            alpha=0.78f; isClickable=false; isFocusable=false; visibility=View.GONE
        }
        editorContainer.addView(inlineGhostView,FrameLayout.LayoutParams(-2,-2))
        codeRow.addView(editorContainer,LinearLayout.LayoutParams(0,-1,1f))
        codeScrollView=ScrollView(this).apply { isFillViewport = true; addView(codeRow) }
        workArea.addView(codeScrollView,FrameLayout.LayoutParams(-1,-1))
        designPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
            setBackgroundColor(if (darkTheme) 0xFF172131.toInt() else 0xFFF5F7FB.toInt())
        }
        designPanel.addView(label("XML VISUAL DESIGNER · Preview & Components", 12, true), LinearLayout.LayoutParams(-1, -2))
        val palette = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("＋ Text", "＋ Button", "＋ Input", "＋ Image").forEachIndexed { index, title ->
            palette.addView(smallButton(title) {
                appendXmlDesignComponent(listOf("TextView", "Button", "EditText", "ImageView")[index])
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(4) })
        }
        designPanel.addView(palette, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(5); bottomMargin = dp(8) })
        val previewScroll = ScrollView(this).apply { isFillViewport = true }
        designCanvas = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(18), dp(14), dp(18))
            background = roundedSurface(if (darkTheme) 0xFF263445.toInt() else 0xFFE3E9F2.toInt(), 12)
        }
        previewScroll.addView(designCanvas)
        designPanel.addView(previewScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        designPanel.addView(label("Tap a component to edit its text. Palette buttons add components to the XML.", 10, false),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        workArea.addView(designPanel, FrameLayout.LayoutParams(-1, -1))
        explorerPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(10),dp(10),dp(10))
            background=roundedSurface(if(darkTheme) 0xFF2B3742.toInt() else 0xFFF0F3FA.toInt(),12); elevation=dp(14).toFloat()
        }
        val explorerHeader=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        explorerHeader.addView(label("PROJECT EXPLORER",11,true),LinearLayout.LayoutParams(0,dp(36),1f))
        explorerHeader.addView(smallButton("← Editor") { toggleExplorer() })
        explorerPanel.addView(explorerHeader)
        explorerScrollView=ScrollView(this)
        val explorerFiles=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        explorerScrollView.addView(explorerFiles)
        explorerPanel.addView(explorerScrollView,LinearLayout.LayoutParams(-1,0,1f))
        workArea.addView(explorerPanel,FrameLayout.LayoutParams(-1,-1))
        explorerPanel.visibility=View.GONE
        contentRoot.addView(workArea,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(5); bottomMargin=dp(7) })
        suggestionsBar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(3),dp(2),dp(3),dp(2)); visibility=View.GONE }
        diagnosticsPanel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(11),dp(8),dp(11),dp(8)); background=roundedSurface(if(darkTheme) 0xFF2B3742.toInt() else 0xFFE9EEF8.toInt(),12) }
        diagnosticsPanel.addView(label("⌁  BUILD OUTPUT / PROBLEMS",10,true))
        errorsPanel=label("No recent build errors. Tap to view diagnostics.",11,false).apply { maxLines=3; setOnClickListener { showBuildErrors() } }
        diagnosticsPanel.addView(errorsPanel)
        problemPanel = label("No code issues detected.", 11, false).apply { maxLines = 4; setTextColor(if (darkTheme) 0xFFFF8A80.toInt() else 0xFFB3261E.toInt()) }
        diagnosticsPanel.addView(problemPanel)
        if (!landscapeMode) contentRoot.addView(diagnosticsPanel,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(3) })
        val chatWithAiButton = smallButton("✦  CHAT WITH AI  ·  OPEN AI DEVELOPER") {
            if (!saveCurrent(false)) return@smallButton
            startActivityForResult(
                Intent(this, AiChatActivity::class.java)
                    .putExtra("project_root", rootDir.absolutePath),
                7105
            )
        }.apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedSurface(0xFF256B72.toInt(), 12)
        }
        if (!landscapeMode) contentRoot.addView(chatWithAiButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(5); bottomMargin = dp(3) })
        exportButton=smallButton("⇧  SAVE CHANGES & RETURN TO PROJECT") { exportEditedZip() }
        if (!landscapeMode) contentRoot.addView(exportButton,LinearLayout.LayoutParams(-1,dp(42)).apply { topMargin=dp(5) })
        setContentView(root)
        refreshExplorer()
        refreshEditorColors()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val keyboardVisible = root.rootView.height - visible.bottom > dp(160)
            suggestionsBar.visibility = View.GONE
            diagnosticsPanel.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            exportButton.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            if (keyboardVisible && editor.hasFocus()) {
                editor.post {
                    val line = editor.layout?.getLineForOffset(editor.selectionStart.coerceAtLeast(0)) ?: 0
                    val y = editor.layout?.getLineTop(line) ?: 0
                    if (y < editor.scrollY || y > editor.scrollY + editor.height - dp(36)) {
                        editor.post { editor.requestRectangleOnScreen(Rect(0, y, editor.width, y + dp(32)), false) }
                    }
                }
            }
        }
        editor.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {
                if (!loadingText) textBeforeChange = s?.toString() ?: ""
            }
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                if(!loadingText && !autoEditing) {
                    val nextText = s?.toString() ?: ""
                    if (count == 1 && before == 0 && start in nextText.indices && nextText[start] == ' ' && inlineCompletionSuffix.isNotEmpty()) {
                        val suffix = inlineCompletionSuffix
                        autoEditing = true
                        try {
                            editor.text.replace(start, start + 1, suffix + " ")
                            editor.setSelection(start + suffix.length + 1)
                        } finally { autoEditing = false }
                        updateInlineCompletion()
                        return
                    }
                    if (textBeforeChange != nextText) {
                        val now = System.currentTimeMillis()
                        // Group rapid keystrokes into one undo step instead of undoing one character at a time.
                        if (now - lastUndoAt > 700L || undoHistory.isEmpty()) {
                            undoHistory.addLast(textBeforeChange)
                            while (undoHistory.size > 100) undoHistory.removeFirst()
                        }
                        lastUndoAt = now
                        redoHistory.clear()
                    }
                    dirty=true; currentFile?.let { openTabs[it]=nextText }
                    applyTypingAssist(start, before, count, nextText)
                    updateLineNumbers(); updateSuggestions(); handler.removeCallbacks(autoSave); handler.postDelayed(autoSave,900); highlightCode(); editor.post { updateInlineCompletion() }
                }
            }
            override fun afterTextChanged(s:Editable?) {}
        })
        editor.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN && inlineCompletionSuffix.isNotEmpty() && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == android.view.KeyEvent.KEYCODE_TAB)) {
                acceptInlineCompletion(false); true
            } else false
        }
        editor.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_UP) editor.post { updateInlineCompletion() }
            false
        }
        editor.setOnScrollChangeListener { _, _, _, _, _ -> updateInlineCompletion() }
        updateBuildErrors(); renderTabs()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("＋ Create file / folder").setOnMenuItemClickListener { showFileActions(); true }
        popup.menu.add("⌕ Find").setOnMenuItemClickListener { showFindReplace(false); true }
        popup.menu.add("⇄ Replace").setOnMenuItemClickListener { showFindReplace(true); true }
        popup.menu.add("↶ Undo").setOnMenuItemClickListener { undoEdit(); true }
        popup.menu.add("↷ Redo").setOnMenuItemClickListener { redoEdit(); true }
        popup.menu.add("✓ Save").setOnMenuItemClickListener { saveCurrent(true); true }
        popup.menu.add("⌕ Search project").setOnMenuItemClickListener { showProjectSearch(); true }
        popup.menu.add("↗ Go to Definition").setOnMenuItemClickListener { showSymbolNavigation(false); true }
        popup.menu.add("⌁ Find References").setOnMenuItemClickListener { showSymbolNavigation(true); true }
        popup.menu.add("✦ Share to ChatGPT").setOnMenuItemClickListener { shareProjectWithChatGPT(); true }
        popup.menu.add("⇩ Import AI changes").setOnMenuItemClickListener { chooseAiChangesZip(); true }
        popup.menu.add("✦ Chat with AI").setOnMenuItemClickListener {
            if (saveCurrent(false)) startActivityForResult(Intent(this, AiChatActivity::class.java).putExtra("project_root", rootDir.absolutePath), 7105)
            true
        }
        popup.menu.add("⇧ Save changes & return").setOnMenuItemClickListener { exportEditedZip(); true }
        popup.show()
    }

    private fun toggleXmlDesign() {
        if (currentFile?.extension?.equals("xml", true) != true) {
            toast("Open an XML layout file first")
            return
        }
        if (designMode) {
            designMode = false
            designPanel.visibility = View.GONE
            codeScrollView.visibility = View.VISIBLE
            designToggleButton.text = "▣  Design"
            editor.requestFocus()
        } else {
            if (!saveCurrent(false)) return
            designMode = true
            renderXmlDesignPreview()
            codeScrollView.visibility = View.GONE
            designPanel.visibility = View.VISIBLE
            designToggleButton.text = "</>  Code"
        }
    }

    private fun appendXmlDesignComponent(tag: String) {
        val source = editor.text.toString()
        val rootMatch = Regex("<([A-Za-z_][A-Za-z0-9_.]*)(\\s[^>]*?)?>").find(source)
        if (rootMatch == null) {
            toast("XML root layout not found. Add a root ViewGroup first.")
            return
        }
        val rootTag = rootMatch.groupValues[1]
        val closing = Regex("</" + Regex.escape(rootTag) + "\\s*>").find(source)
        val textValue = when (tag) { "TextView" -> "New Text"; "Button" -> "New Button"; else -> "" }
        val component = buildString {
            append("    <").append(tag)
            append(" android:layout_width=\"match_parent\" android:layout_height=\"wrap_content\"")
            if (tag != "ImageView") append(" android:text=\"").append(textValue).append("\"")
            if (tag == "ImageView") append(" android:contentDescription=\"Image\"")
            append(" />\n")
        }
        val updated = if (closing != null) {
            source.substring(0, closing.range.first) + component + source.substring(closing.range.first)
        } else {
            val selfClosing = Regex("<" + Regex.escape(rootTag) + "([^>]*)/>").find(source)
            if (selfClosing == null) {
                toast("Could not safely add a component to this XML layout")
                return
            }
            val expanded = "<" + rootTag + selfClosing.groupValues[1] + ">\n" + component + "</" + rootTag + ">"
            source.substring(0, selfClosing.range.first) + expanded + source.substring(selfClosing.range.last + 1)
        }
        applyDesignXml(updated)
        toast(tag + " added to XML")
    }

    private fun applyDesignXml(updated: String) {
        loadingText = true
        editor.setText(updated)
        editor.setSelection(editor.text.length)
        loadingText = false
        currentFile?.let { openTabs[it] = updated }
        dirty = true
        updateLineNumbers()
        highlightCode()
        handler.removeCallbacks(autoSave)
        handler.postDelayed(autoSave, 500)
        renderTabs()
        if (designMode) renderXmlDesignPreview()
    }

    private fun renderXmlDesignPreview() {
        if (!::designCanvas.isInitialized || currentFile?.extension?.equals("xml", true) != true) return
        designCanvas.removeAllViews()
        val source = editor.text.toString()
        val phoneBg = if (darkTheme) 0xFF101820.toInt() else 0xFFFFFFFF.toInt()
        val phone = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(18))
            background = roundedSurface(phoneBg, 20)
            elevation = dp(3).toFloat()
        }
        phone.addView(label("PREVIEW · " + (currentFile?.name ?: "layout.xml"), 10, true), LinearLayout.LayoutParams(-1, dp(30)))
        val componentPattern = Regex("<(?:[A-Za-z0-9_.]+\\.)?(TextView|Button|EditText|ImageView|CheckBox|Switch|RadioButton|ProgressBar)(\\s[^>]*?)?\\s*/?>")
        val matches = componentPattern.findAll(source).toList()
        if (matches.isEmpty()) {
            phone.addView(label("No supported UI components found. Use the palette above to add Text, Button, Input or Image.", 12, false),
                LinearLayout.LayoutParams(-1, dp(100)))
        } else {
            matches.forEachIndexed { index, match ->
                val tag = match.groupValues[1]
                val attrs = match.groupValues.getOrElse(2) { "" }
                val text = Regex("""android:text\s*=\s*["']([^"']*)["']""").find(attrs)?.groupValues?.get(1).orEmpty()
                val view: View = when (tag) {
                    "Button" -> Button(this).apply { this.text = text.ifBlank { "Button" }; isAllCaps = false }
                    "EditText" -> EditText(this).apply { hint = text.ifBlank { "Enter text" }; textSize = 14f; isSingleLine = true }
                    "ImageView" -> TextView(this).apply { this.text = "▧  Image"; gravity = Gravity.CENTER; textSize = 14f }
                    "CheckBox" -> CheckBox(this).apply { this.text = text.ifBlank { "Option" } }
                    "Switch" -> Switch(this).apply { this.text = text.ifBlank { "Switch" } }
                    "RadioButton" -> RadioButton(this).apply { this.text = text.ifBlank { "Option" } }
                    "ProgressBar" -> ProgressBar(this)
                    else -> TextView(this).apply { this.text = text.ifBlank { "TextView" }; textSize = 16f }
                }
                view.setOnClickListener { editXmlDesignComponent(index, tag, text) }
                phone.addView(view, LinearLayout.LayoutParams(-1, if (tag == "ImageView") dp(92) else dp(52)).apply { bottomMargin = dp(7) })
            }
        }
        designCanvas.addView(phone, LinearLayout.LayoutParams(-1, -2))
        designCanvas.addView(label(matches.size.toString() + " supported component(s) detected", 10, false),
            LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(8) })
    }

    private fun editXmlDesignComponent(index: Int, tag: String, currentText: String) {
        val source = editor.text.toString()
        val componentPattern = Regex("<(?:[A-Za-z0-9_.]+\\.)?(TextView|Button|EditText|ImageView|CheckBox|Switch|RadioButton|ProgressBar)(\\s[^>]*?)?\\s*/?>")
        val matches = componentPattern.findAll(source).toList()
        if (index !in matches.indices || matches[index].groupValues[1] != tag) {
            toast("Component changed; reopen Design preview")
            return
        }
        val originalElement = matches[index].value
        fun readAttr(name: String, fallback: String = ""): String {
            return Regex("""\\b${Regex.escape(name)}\\s*=\\s*["']([^"']*)["']""")
                .find(originalElement)?.groupValues?.get(1) ?: fallback
        }
        fun field(hintText: String, value: String): EditText = EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            setText(value)
            textSize = 14f
        }
        val textField = field("Text / Hint", currentText)
        val sizeField = field("Text size (e.g. 16sp)", readAttr("android:textSize"))
        val colorField = field("Text color (e.g. #FF0000)", readAttr("android:textColor"))
        val widthField = field("Width (match_parent / wrap_content / 120dp)", readAttr("android:layout_width", "match_parent"))
        val heightField = field("Height (wrap_content / 48dp)", readAttr("android:layout_height", "wrap_content"))
        val marginField = field("Margin (e.g. 8dp)", readAttr("android:layout_margin"))
        val paddingField = field("Padding (e.g. 8dp)", readAttr("android:padding"))
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(2))
            listOf(textField, sizeField, colorField, widthField, heightField, marginField, paddingField).forEach {
                addView(it, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(3) })
            }
        }
        val scroll = ScrollView(this).apply { addView(fields) }
        AlertDialog.Builder(this)
            .setTitle("Properties · $tag")
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Apply") { _, _ ->
                val latest = editor.text.toString()
                val latestMatches = componentPattern.findAll(latest).toList()
                if (index !in latestMatches.indices || latestMatches[index].groupValues[1] != tag) {
                    toast("Component changed; reopen Design preview")
                    return@setPositiveButton
                }
                var element = latestMatches[index].value
                fun writeAttr(name: String, value: String) {
                    val attrPattern = Regex("""\\b${Regex.escape(name)}\\s*=\\s*["'][^"']*["']""")
                    val escaped = value.trim().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                    element = if (attrPattern.containsMatchIn(element)) {
                        attrPattern.replace(element, "$name=\"$escaped\"")
                    } else if (value.isNotBlank()) {
                        element.replace(Regex("\\s*/?>$")) { suffix -> " $name=\"$escaped\"" + suffix.value }
                    } else element
                }
                if (tag != "ImageView" && tag != "ProgressBar") {
                    writeAttr(if (tag == "EditText") "android:hint" else "android:text", textField.text.toString())
                }
                writeAttr("android:textSize", sizeField.text.toString())
                writeAttr("android:textColor", colorField.text.toString())
                writeAttr("android:layout_width", widthField.text.toString())
                writeAttr("android:layout_height", heightField.text.toString())
                writeAttr("android:layout_margin", marginField.text.toString())
                writeAttr("android:padding", paddingField.text.toString())
                val match = latestMatches[index]
                applyDesignXml(latest.substring(0, match.range.first) + element + latest.substring(match.range.last + 1))
                toast("Component properties updated")
            }
            .show()
    }

    private fun openLivePreview() {
        if (!saveCurrent(false)) return
        startActivityForResult(
            Intent(this, LivePreviewActivity::class.java).putExtra("project_root", rootDir.absolutePath),
            7103
        )
    }

    private fun toggleExplorer() {
        explorerOpen = !explorerOpen
        if (explorerOpen) {
            refreshExplorer()
            explorerPanel.visibility = View.VISIBLE
            explorerPanel.bringToFront()
            explorerToggle.text = "☰  Explorer  ▾"
        } else {
            explorerPanel.visibility = View.GONE
            explorerToggle.text = "☰  Explorer"
        }
    }
    private fun refreshExplorer() {
        if (!::explorerPanel.isInitialized) return
        val list = explorerScrollView.getChildAt(0) as? LinearLayout ?: return
        list.removeAllViews()
        val count = intArrayOf(0)
        addExplorerDirectoryChildren(rootDir, list, 0, count)
        if (count[0] == 0) list.addView(label("This project folder is empty.", 11, false))
    }

    private fun addExplorerDirectoryChildren(directory: File, list: LinearLayout, depth: Int, count: IntArray) {
        if (count[0] >= 500) return
        val children = directory.listFiles()?.filterNot {
            it.name in setOf(".git", ".gradle", ".idea", "build", "node_modules", ".dart_tool")
        }?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: return

        fun explorerRow(text: String, indent: Int, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(if (darkTheme) 0xFFE8EEFF.toInt() else 0xFF263653.toInt())
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                includeFontPadding = true
                setPadding(dp(10 + indent * 13), dp(7), dp(8), dp(7))
                background = roundedSurface(if (darkTheme) 0xFF3C4A56.toInt() else 0xFFDCE5F7.toInt(), 10)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

        children.filter { it.isDirectory }.forEach { dir ->
            if (count[0] >= 500) return
            count[0]++
            val relative = dir.relativeTo(rootDir).invariantSeparatorsPath
            val expanded = relative in expandedDirectories
            val row = explorerRow((if (expanded) "▾  " else "▸  ") + dir.name, depth) {
                if (expanded) expandedDirectories.remove(relative) else expandedDirectories.add(relative)
                refreshExplorer()
            }
            row.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(dir.name)
                    .setItems(arrayOf("Create file here", "Create folder here", "Rename folder", "Delete folder")) { _, action ->
                        when (action) {
                            0 -> promptCreateFile(relative + "/")
                            1 -> promptCreateFolder(relative + "/")
                            2 -> promptRename(dir)
                            3 -> confirmDeleteDirectory(dir)
                        }
                    }.setNegativeButton("Cancel", null).show()
                true
            }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(3) })
            if (expanded) addExplorerDirectoryChildren(dir, list, depth + 1, count)
        }

        children.filter { it.isFile }.forEach { file ->
            if (count[0] >= 500) return
            count[0]++
            val editable = file.extension.lowercase() in editableExtensions
            val prefix = if (editable) "▤  " else "▧  "
            val item = explorerRow(prefix + file.name, depth + 1) {
                if (!editable || file.length() > 1024 * 1024) {
                    toast("This file cannot be opened in the text editor.")
                } else {
                    openFile(file)
                    if (explorerOpen) toggleExplorer()
                }
            }
            item.setOnLongClickListener { showFileContextMenu(file); true }
            list.addView(item, LinearLayout.LayoutParams(-1, dp(40)).apply { bottomMargin = dp(3) })
        }
    }

    private fun confirmDeleteDirectory(directory: File) {
        AlertDialog.Builder(this).setTitle("Delete folder ${directory.name}?")
            .setMessage("This permanently removes the folder and all files inside it.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                try {
                    val safeDirectory = directory.canonicalFile
                    require(safeDirectory != rootDir.canonicalFile && safeDirectory.path.startsWith(rootDir.canonicalPath + File.separator)) { "Cannot delete the project root" }
                    if (!saveCurrent(false)) return@setPositiveButton
                    val removed = safeDirectory.walkTopDown().filter { it.isFile }.toSet()
                    require(safeDirectory.isDirectory && safeDirectory.deleteRecursively()) { "Could not delete folder" }
                    removed.forEach { openTabs.remove(it) }
                    if (currentFile in removed) {
                        currentFile = null
                        loadingText = true
                        editor.setText("")
                        loadingText = false
                        fileLabel.text = "Select a source file to start editing"
                        dirty = false
                        undoHistory.clear(); redoHistory.clear()
                    }
                    val rel = directory.relativeTo(rootDir).invariantSeparatorsPath
                    expandedDirectories.removeAll { it == rel || it.startsWith(rel + "/") }
                    refreshExplorer(); renderTabs(); toast("Folder deleted")
                } catch (e: Exception) { toast("Delete failed: ${e.message}") }
            }.show()
    }

    private fun showFileActions() {
        val actions = arrayOf("Create new file", "Create new folder", "Import file from device", "Rename current file", "Delete current file", "Save current file as…")
        AlertDialog.Builder(this).setTitle("File & Project Actions").setItems(actions) { _, which ->
            when (which) {
                0 -> promptCreateFile()
                1 -> promptCreateFolder()
                2 -> importFile()
                3 -> currentFile?.let { promptRename(it) } ?: toast("Open a file first")
                4 -> currentFile?.let { confirmDelete(it) } ?: toast("Open a file first")
                5 -> promptSaveAs()
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun showFileContextMenu(file: File) {
        val actions = arrayOf("Open / Edit", "Rename", "Copy", "Delete")
        AlertDialog.Builder(this).setTitle(file.name).setItems(actions) { _, which ->
            when (which) {
                0 -> openFile(file)
                1 -> promptRename(file)
                2 -> copyFile(file)
                3 -> confirmDelete(file)
            }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun promptCreateFile(initialPath: String = "") {
        val box = EditText(this).apply { hint = "e.g. app/src/main/java/NewFile.kt"; setText(initialPath); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Create new file").setMessage("Enter a project-relative path and file name.")
            .setView(box).setPositiveButton("Create") { _, _ ->
                val path = box.text.toString().trim().replace('\\', '/')
                if (path.isBlank()) return@setPositiveButton
                try {
                    if (!saveCurrent(false)) return@setPositiveButton
                    val file = File(rootDir, path).canonicalFile
                    require(file.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                    require(!file.exists()) { "File already exists" }
                    require(file.name.contains('.')) { "Add a file extension, e.g. .kt or .xml" }
                    val parent = file.parentFile
                    require(parent != null && (parent.mkdirs() || parent.isDirectory)) { "Could not create parent folder" }
                    file.writeText("", Charsets.UTF_8)
                    refreshExplorer()
                    openFile(file)
                    toast("File created")
                } catch (e: Exception) { toast("Create failed: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun promptCreateFolder(initialPath: String = "") {
        val box = EditText(this).apply { hint = "e.g. app/src/main/res/drawable"; setText(initialPath); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Create new folder").setView(box)
            .setPositiveButton("Create") { _, _ ->
                val path = box.text.toString().trim().replace('\\', '/')
                if (path.isBlank()) return@setPositiveButton
                try {
                    val dir = File(rootDir, path).canonicalFile
                    require(dir.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                    require(!dir.exists()) { "Folder already exists" }
                    require(dir.mkdirs() || dir.isDirectory) { "Could not create folder" }
                    refreshExplorer()
                    toast("Folder created")
                } catch (e: Exception) { toast("Create failed: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun promptRename(file: File) {
        val isDirectory = file.isDirectory
        val box = EditText(this).apply { setText(file.relativeTo(rootDir).invariantSeparatorsPath); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle(if (isDirectory) "Rename / Move folder" else "Rename / Move file")
            .setMessage("Enter the new project-relative path.")
            .setView(box).setPositiveButton("Save") { _, _ ->
                val path = box.text.toString().trim().replace('\\', '/')
                if (path.isBlank()) return@setPositiveButton
                try {
                    if (!saveCurrent(false)) return@setPositiveButton
                    val source = file.canonicalFile
                    val target = File(rootDir, path).canonicalFile
                    require(target.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                    require(target != source) { "Choose a different destination" }
                    if (isDirectory) require(!target.path.startsWith(source.path + File.separator)) { "A folder cannot be moved inside itself" }
                    require(!target.exists()) { "Destination already exists" }
                    val affectedTabs = if (isDirectory) openTabs.entries.filter { it.key.path.startsWith(source.path + File.separator) }.toList()
                        else openTabs.entries.filter { it.key == source }
                    val oldActive = currentFile
                    val parent = target.parentFile
                    require(parent != null && (parent.mkdirs() || parent.isDirectory)) { "Could not create destination folder" }
                    if (!source.renameTo(target)) error("Could not move or rename")
                    affectedTabs.forEach { entry ->
                        val relativeChild = entry.key.relativeTo(source)
                        val newFile = File(target, relativeChild.path)
                        openTabs.remove(entry.key)
                        openTabs[newFile] = entry.value
                    }
                    if (oldActive != null && (oldActive == source || (isDirectory && oldActive.path.startsWith(source.path + File.separator)))) {
                        val relativeActive = if (oldActive == source) null else oldActive.relativeTo(source)
                        currentFile = if (relativeActive == null) target else File(target, relativeActive.path)
                    }
                    if (isDirectory) {
                        val oldPrefix = source.relativeTo(rootDir).invariantSeparatorsPath
                        val newPrefix = target.relativeTo(rootDir).invariantSeparatorsPath
                        val updated = expandedDirectories.map { if (it == oldPrefix || it.startsWith(oldPrefix + "/")) newPrefix + it.removePrefix(oldPrefix) else it }
                        expandedDirectories.clear(); expandedDirectories.addAll(updated)
                    }
                    refreshExplorer()
                    currentFile?.takeIf { it.isFile }?.let { openFile(it) }
                    renderTabs()
                    toast(if (isDirectory) "Folder renamed / moved" else "File renamed / moved")
                } catch (e: Exception) { toast("Rename failed: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun copyFile(file: File) {
        val source = file.canonicalFile
        if (!source.isFile || !source.path.startsWith(rootDir.canonicalPath + File.separator)) {
            toast("Invalid project file")
            return
        }
        if (source == currentFile && !saveCurrent(false)) return
        val relative = source.relativeTo(rootDir).invariantSeparatorsPath
        val baseName = if (source.name.startsWith(".") && source.name.count { it == '.' } == 1) source.name else source.name.substringBeforeLast('.', source.name)
        val defaultCopyName = relative.substringBeforeLast('/') .let { parent -> if (parent.isEmpty()) "" else "$parent/" } + baseName + "_copy" + source.extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        val box = EditText(this).apply { setText(defaultCopyName); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Copy file").setView(box).setPositiveButton("Copy") { _, _ ->
            try {
                val target = File(rootDir, box.text.toString().trim()).canonicalFile
                require(target.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                require(!target.exists()) { "Destination already exists" }
                val parent = target.parentFile
                require(parent != null && (parent.mkdirs() || parent.isDirectory)) { "Could not create destination folder" }
                source.copyTo(target)
                refreshExplorer()
                toast("File copied")
            } catch (e: Exception) { toast("Copy failed: ${e.message}") }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this).setTitle("Delete ${file.name}?").setMessage("This permanently removes the file from this project.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                try {
                    val safeFile = file.canonicalFile
                    require(safeFile.isFile && safeFile.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid project file" }
                    if (safeFile == currentFile && !saveCurrent(false)) return@setPositiveButton
                    require(safeFile.delete()) { "Could not delete file" }
                    openTabs.remove(safeFile)
                    if (currentFile == safeFile) {
                        currentFile = null
                        loadingText = true
                        editor.setText("")
                        loadingText = false
                        fileLabel.text = "Select a source file to start editing"
                        dirty = false
                        undoHistory.clear(); redoHistory.clear()
                    }
                    refreshExplorer()
                    renderTabs()
                    toast("File deleted")
                } catch (e: Exception) { toast("Delete failed: ${e.message}") }
            }.show()
    }

    private fun promptSaveAs() {
        val box = EditText(this).apply { hint = "e.g. app/src/main/java/Backup.kt"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Save current file as").setView(box).setPositiveButton("Save As") { _, _ ->
            val path = box.text.toString().trim().replace('\\', '/')
            if (path.isBlank()) return@setPositiveButton
            try {
                if (!saveCurrent(false)) return@setPositiveButton
                val target = File(rootDir, path).canonicalFile
                require(target.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                require(!target.exists()) { "File already exists" }
                val parent = target.parentFile
                require(parent != null && (parent.mkdirs() || parent.isDirectory)) { "Could not create destination folder" }
                target.writeText(editor.text.toString(), Charsets.UTF_8)
                openFile(target)
                refreshExplorer()
                toast("Saved as ${target.name}")
            } catch (e: Exception) { toast("Save As failed: ${e.message}") }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun importFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, 7102)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7103 && resultCode == RESULT_OK) {
            // Forward the runtime-preview ZIP to MainActivity, which owns the Termux build pipeline.
            setResult(RESULT_OK, data)
            finish()
            return
        }
        if (requestCode == 7105) {
            if (resultCode == RESULT_OK && data?.getBooleanExtra("runtime_preview_build", false) == true) {
                setResult(RESULT_OK, data)
                finish()
                return
            }
            refreshExplorer()
            if (!saveCurrent(false)) return
            val fileToReload = currentFile
            if (fileToReload != null && fileToReload.isFile) {
                openTabs.remove(fileToReload)
                currentFile = null
                openFile(fileToReload)
            }
            return
        }
        if (requestCode == 7104 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            previewAndApplyAiChanges(uri)
            return
        }
        if (requestCode == 7102 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                }
                val name = (displayName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "imported_file.txt")
                    .substringAfterLast('/').substringAfterLast('\\').trim().takeIf { it.isNotBlank() } ?: "imported_file.txt"
                val target = File(rootDir, name).canonicalFile
                require(target.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid file name" }
                require(!target.exists()) { "A file with this name already exists in project root" }
                val parent = target.parentFile ?: error("Invalid import destination")
                val temp = File.createTempFile(".pocket-import-", ".tmp", parent)
                try {
                    var importedBytes = 0L
                    contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                importedBytes += n
                                require(importedBytes <= 100L * 1024L * 1024L) { "Imported file exceeds 100 MB limit" }
                                output.write(buffer, 0, n)
                            }
                        }
                    } ?: error("Cannot read selected file")
                    require(!target.exists() && temp.renameTo(target)) { "Could not finish importing file" }
                } catch (e: Exception) {
                    temp.delete()
                    throw e
                }
                refreshExplorer()
                if (target.extension.lowercase() in editableExtensions && target.length() <= 1024 * 1024) {
                    openFile(target)
                    toast("File imported and opened")
                } else {
                    toast("File imported. This file type or size is not supported by the text editor.")
                }
            } catch (e: Exception) { toast("Import failed: ${e.message}") }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun applyTheme() {
        root.setBackgroundColor(if(darkTheme) 0xFF202A33.toInt() else 0xFFF4F6FA.toInt())
        if(::explorerPanel.isInitialized) explorerPanel.background=roundedSurface(if(darkTheme) 0xFF2B3742.toInt() else 0xFFF0F3FA.toInt(),12)
    }

    private fun refreshEditorColors() {
        val bg = if (darkTheme) 0xFF172131.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (darkTheme) 0xFFE2E8F0.toInt() else 0xFF202938.toInt()
        editor.setTextColor(fg); editor.setBackgroundColor(bg)
        lineNumbers.setTextColor(if (darkTheme) 0xFF8290A5.toInt() else 0xFF7A8494.toInt())
        lineNumbers.setBackgroundColor(if (darkTheme) 0xFF131C29.toInt() else 0xFFE9EDF4.toInt())
        fileLabel.setTextColor(if (darkTheme) 0xFF9FC3C4.toInt() else 0xFF425DB5.toInt())
        errorsPanel.setTextColor(if (darkTheme) 0xFFFFB4A8.toInt() else 0xFFB3261E.toInt())
        highlightCode()
    }

    private fun showFilePicker() {
        val excludedDirectories = setOf(".git", ".gradle", ".idea", "build", "node_modules", ".dart_tool")
        val files = rootDir.walkTopDown().onEnter { directory ->
            directory == rootDir || directory.name !in excludedDirectories
        }.filter { it.isFile }.take(500).toList()
        if (files.isEmpty()) { toast("No project files found"); return }
        val labels = files.map { (if (it.extension.lowercase() in editableExtensions) "▤ " else "▧ ") + it.relativeTo(rootDir).invariantSeparatorsPath }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Project Files (${files.size}${if (files.size == 500) "+" else ""})").setItems(labels) { _, which ->
            val file = files[which]
            if (file.extension.lowercase() !in editableExtensions || file.length() > 1024 * 1024) toast("This file cannot be opened in the text editor.")
            else openFile(file)
        }.setNegativeButton("Close", null).show()
    }

    private fun openFile(file: File) {
        if (!saveCurrent(false)) return
        currentFile = file
        if (!openTabs.containsKey(file)) openTabs[file] = file.readText(Charsets.UTF_8)
        undoHistory.clear(); redoHistory.clear()
        lastUndoAt = 0L
        loadingText = true
        editor.setText(openTabs[file] ?: "")
        editor.setSelection(editor.text.length)
        loadingText = false
        dirty = false
        fileLabel.text = file.relativeTo(rootDir).invariantSeparatorsPath
        if (::designToggleButton.isInitialized) {
            designToggleButton.visibility = if (file.extension.equals("xml", true)) View.VISIBLE else View.GONE
            if (!file.extension.equals("xml", true) && designMode) {
                designMode = false
                codeScrollView.visibility = View.VISIBLE
                designPanel.visibility = View.GONE
            }
        }
        updateLineNumbers()
        updateSuggestions()
        highlightCode()
        renderTabs()
    }

    private fun renderTabs() {
        tabsBar.removeAllViews()
        openTabs.keys.toList().forEach { file ->
            val active = file == currentFile
            val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val tab = smallButton((if (active) "● " else "") + file.name + if (active && dirty) " •" else "") { openFile(file) }
            tabRow.addView(tab, LinearLayout.LayoutParams(-2, dp(36)))
            tabRow.addView(smallButton("×") { closeTab(file) }, LinearLayout.LayoutParams(dp(32), dp(36)))
            tabsBar.addView(tabRow, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(5) })
        }
    }

    private fun closeTab(file: File) {
        if (file == currentFile) {
            if (!saveCurrent(false)) return
            openTabs.remove(file); currentFile = null
            loadingText = true; editor.setText(""); loadingText = false
            dirty = false; fileLabel.text = "Select a source file to start editing"
            undoHistory.clear(); redoHistory.clear()
            val next = openTabs.keys.lastOrNull()
            if (next != null) openFile(next) else { updateLineNumbers(); updateSuggestions(); renderTabs() }
        } else { openTabs.remove(file); renderTabs() }
    }

    private fun undoEdit() {
        if (undoHistory.isEmpty()) { toast("Nothing to undo"); return }
        val previous = undoHistory.removeLast()
        lastUndoAt = 0L
        redoHistory.addLast(editor.text.toString())
        loadingText = true; editor.setText(previous); editor.setSelection(editor.text.length); loadingText = false
        currentFile?.let { openTabs[it] = previous }; dirty = true
        updateLineNumbers(); updateSuggestions(); highlightCode()
        handler.removeCallbacks(autoSave); handler.postDelayed(autoSave, 900); renderTabs()
    }

    private fun redoEdit() {
        if (redoHistory.isEmpty()) { toast("Nothing to redo"); return }
        val next = redoHistory.removeLast()
        lastUndoAt = 0L
        undoHistory.addLast(editor.text.toString())
        while (undoHistory.size > 100) undoHistory.removeFirst()
        loadingText = true; editor.setText(next); editor.setSelection(editor.text.length); loadingText = false
        currentFile?.let { openTabs[it] = next }; dirty = true
        updateLineNumbers(); updateSuggestions(); highlightCode()
        handler.removeCallbacks(autoSave); handler.postDelayed(autoSave, 900); renderTabs()
    }

    private fun updateLineNumbers() {
        val count = editor.text.toString().count { it == '\n' } + 1
        lineNumbers.text = (1..count).joinToString("\n")
    }

    private fun highlightCode() {
        if (loadingText || !::editor.isInitialized) return
        val source = editor.text.toString()
        if (source.isEmpty()) return

        val ext = currentFile?.extension?.lowercase() ?: ""
        val keywordColor = if (darkTheme) 0xFFC7A7F5.toInt() else 0xFF7B2CBF.toInt()
        val stringColor = if (darkTheme) 0xFFA9D18E.toInt() else 0xFF26733A.toInt()
        val commentColor = if (darkTheme) 0xFF8295A8.toInt() else 0xFF687786.toInt()
        val numberColor = if (darkTheme) 0xFFE6A477.toInt() else 0xFFB45309.toInt()
        val typeColor = if (darkTheme) 0xFF80CBC4.toInt() else 0xFF087F8C.toInt()
        val tagColor = if (darkTheme) 0xFF80B7F0.toInt() else 0xFF1769AA.toInt()
        val attributeColor = if (darkTheme) 0xFFE6C27A.toInt() else 0xFF996515.toInt()
        val annotationColor = if (darkTheme) 0xFFE0A0C0.toInt() else 0xFFAD3975.toInt()

        val patterns = mutableListOf<Pair<String, Int>>()
        when (ext) {
            "kt" -> {
                patterns += Pair("""//[^\n]*|/\*[\s\S]*?\*/""", commentColor)
                patterns += Pair(""""([^"\\]|\\.)*"|'([^'\\]|\\.)*'|""" + "'''" + """[\s\S]*?""" + "'''", stringColor)
                patterns += Pair("""@\w+""", annotationColor)
                patterns += Pair("""\b(package|import|as|class|object|interface|typealias|fun|val|var|if|else|when|for|while|do|return|break|continue|throw|try|catch|finally|this|super|is|in|as|public|private|protected|internal|override|open|abstract|final|data|sealed|enum|annotation|companion|suspend|inline|noinline|crossinline|reified|const|lateinit|by|init|constructor|where|true|false|null)\b""", keywordColor)
                patterns += Pair("""\b(String|Int|Long|Short|Byte|Float|Double|Boolean|Char|Unit|Any|Nothing|List|MutableList|Set|Map|Array|Pair|Triple)\b""", typeColor)
            }
            "java" -> {
                patterns += Pair("""//[^\n]*|/\*[\s\S]*?\*/""", commentColor)
                patterns += Pair(""""([^"\\]|\\.)*"|'([^'\\]|\\.)*'""", stringColor)
                patterns += Pair("""@\w+""", annotationColor)
                patterns += Pair("""\b(package|import|class|interface|enum|extends|implements|public|private|protected|static|final|abstract|void|new|this|super|return|if|else|switch|case|default|for|while|do|break|continue|try|catch|finally|throw|throws|synchronized|volatile|transient|native|instanceof|var|true|false|null)\b""", keywordColor)
                patterns += Pair("""\b(String|Integer|Long|Short|Byte|Float|Double|Boolean|Character|Object|List|ArrayList|Map|HashMap|Set|void)\b""", typeColor)
            }
            "xml" -> {
                patterns += Pair("""<!--[\s\S]*?-->""", commentColor)
                patterns += Pair(""""([^"\\]|\\.)*|'([^'\\]|\\.)*'""", stringColor)
                patterns += Pair("""</?[A-Za-z_][\w:.-]*|/?>""", tagColor)
                patterns += Pair("""\b[A-Za-z_][\w:.-]*(?=\s*=)""", attributeColor)
                patterns += Pair("""\b(true|false|match_parent|wrap_content|dp|sp|px)\b""", keywordColor)
            }
            "gradle", "kts" -> {
                patterns += Pair("""//[^\n]*|/\*[\s\S]*?\*/""", commentColor)
                patterns += Pair(""""([^"\\]|\\.)*"|'([^'\\]|\\.)*'""", stringColor)
                patterns += Pair("""@\w+""", annotationColor)
                patterns += Pair("""\b(plugins|pluginManagement|dependencies|implementation|api|compileOnly|runtimeOnly|testImplementation|android|repositories|mavenCentral|google|namespace|compileSdk|minSdk|targetSdk|defaultConfig|applicationId|versionCode|versionName|buildTypes|release|debug|signingConfig|tasks|register|include|project|id|version|alias|kotlin|java|true|false|null)\b""", keywordColor)
            }
            else -> {
                patterns += Pair("""//[^\n]*|/\*[\s\S]*?\*/|#[^\n]*""", commentColor)
                patterns += Pair(""""([^"\\]|\\.)*"|'([^'\\]|\\.)*'""", stringColor)
                patterns += Pair("""\b(class|object|fun|val|var|if|else|when|for|while|return|public|private|protected|override|import|package|interface|suspend|const|lateinit|new|static|void|int|String|true|false|null)\b""", keywordColor)
            }
        }
        patterns += Pair("""\b\d+(?:\.\d+)?\b""", numberColor)

        val styled = SpannableString(source)
        patterns.forEach { (pattern, color) ->
            val matcher = Pattern.compile(pattern).matcher(source)
            while (matcher.find()) {
                if (matcher.start() < matcher.end()) {
                    styled.setSpan(ForegroundColorSpan(color), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        val cursor = editor.selectionStart.coerceAtLeast(0)
        val selectionEnd = editor.selectionEnd.coerceAtLeast(cursor)
        val scrollY = editor.scrollY
        loadingText = true
        editor.setText(styled)
        editor.setSelection(cursor.coerceAtMost(editor.text.length), selectionEnd.coerceAtMost(editor.text.length))
        loadingText = false
        updateEditorDiagnostics()
        editor.post { editor.scrollTo(editor.scrollX, scrollY) }
    }

    private fun updateEditorDiagnostics() {
        if (!::editor.isInitialized || !::problemPanel.isInitialized) return
        val source = editor.text.toString()
        val ext = currentFile?.extension?.lowercase() ?: ""
        val stack = ArrayDeque<Pair<Char, Int>>()
        val issues = mutableListOf<Pair<Int, String>>()
        var quote: Char? = null
        var quoteStart = -1
        var escaped = false
        var lineComment = false
        var blockComment = false
        var xmlComment = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            if (xmlComment) {
                if (c == '-' && next == '-' && source.getOrNull(i + 2) == '>') { xmlComment = false; i += 3; continue }
                i++; continue
            }
            if (lineComment) {
                if (c == '\n') lineComment = false
                i++; continue
            }
            if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i += 2; continue }
                i++; continue
            }
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
                i++; continue
            }
            if (ext == "xml" && c == '<' && source.startsWith("<!--", i)) { xmlComment = true; i += 4; continue }
            if (ext != "xml" && c == '/' && next == '/') { lineComment = true; i += 2; continue }
            if (ext != "xml" && c == '/' && next == '*') { blockComment = true; i += 2; continue }
            if (c == '"' || c == '\'') { quote = c; quoteStart = i; i++; continue }
            if (c == '(' || c == '[' || c == '{') stack.addLast(c to i)
            else if (c == ')' || c == ']' || c == '}') {
                val expected = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                if (stack.isEmpty() || stack.last().first != expected) {
                    issues += i to ("Unexpected '" + c + "' — no matching opening bracket")
                } else stack.removeLast()
            }
            i++
        }
        if (quote != null) issues += quoteStart.coerceAtLeast(0) to "Unclosed string literal"
        if (blockComment) issues += source.lastIndexOf("/*").coerceAtLeast(0) to "Unclosed block comment"
        if (xmlComment) issues += source.lastIndexOf("<!--").coerceAtLeast(0) to "Unclosed XML comment"
        stack.forEach { (ch, pos) ->
            val close = when (ch) { '(' -> ')'; '[' -> ']'; else -> '}' }
            issues += pos to ("Missing closing '" + close + "'")
        }
        editorProblems = issues.distinctBy { it.first to it.second }.sortedBy { it.first }
        val red = if (darkTheme) 0xFFFF7777.toInt() else 0xFFB3261E.toInt()
        val editable = editor.text
        editorProblems.forEach { (position, _) ->
            if (position in 0 until editable.length) {
                editable.setSpan(UnderlineSpan(), position, position + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                editable.setSpan(ForegroundColorSpan(red), position, position + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val lineIssues = editorProblems.map { (position, message) ->
            val line = source.substring(0, position.coerceIn(0, source.length)).count { it == '\n' } + 1
            line to message
        }
        val lineCount = source.count { it == '\n' } + 1
        lineNumbers.text = (1..lineCount).joinToString("\n") { line ->
            if (lineIssues.any { it.first == line }) "! " + line else "  " + line
        }
        problemPanel.text = if (lineIssues.isEmpty()) "✓ No code issues detected." else
            lineIssues.take(4).joinToString("\n") { (line, message) -> "Line " + line + ": " + message } +
                if (lineIssues.size > 4) "\n+ " + (lineIssues.size - 4) + " more issue(s)" else ""
    }

    private fun applyTypingAssist(start: Int, before: Int, count: Int, text: String) {
        if (autoEditing || loadingText || count != 1 || before != 0 || start < 0 || start >= text.length) return
        val typed = text[start]
        val editable = editor.text
        val cursor = (start + 1).coerceAtMost(editable.length)
        val closing = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
        autoEditing = true
        try {
            if (typed == '\n') {
                val previousLineStart = text.substring(0, start).lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
                val previousLine = text.substring(previousLineStart, start)
                val indent = previousLine.takeWhile { it == ' ' || it == '\t' }
                val extraIndent = if (previousLine.trimEnd().endsWith("{") || previousLine.trimEnd().endsWith("[") || previousLine.trimEnd().endsWith("(")) "    " else ""
                val addition = indent + extraIndent
                if (addition.isNotEmpty()) {
                    editable.insert(cursor, addition)
                    editor.setSelection(cursor + addition.length)
                }
            } else if (typed in closing.keys) {
                val next = editable.getOrNull(cursor)
                if (next != closing[typed]) {
                    editable.insert(cursor, closing.getValue(typed).toString())
                    editor.setSelection(cursor)
                }
            } else if (typed in listOf(')', ']', '}', '"', '\'')) {
                if (editable.getOrNull(cursor) == typed) editor.setSelection(cursor + 1)
            } else if (typed == '\t') {
                editable.replace(start, start + 1, "    ")
                editor.setSelection(start + 4)
            }
        } finally {
            autoEditing = false
        }
    }

    private fun updateSuggestions() {
        updateInlineCompletion()
    }

    private fun completionWords(): List<String> {
        return when (currentFile?.extension?.lowercase() ?: "") {
            "kt" -> listOf("function", "fun", "val", "var", "class", "object", "override", "private", "public", "return", "if", "else", "when", "for", "while", "import", "package", "suspend", "data")
            "java" -> listOf("function", "public", "private", "protected", "class", "interface", "void", "static", "final", "return", "new", "if", "else", "for", "while", "import", "package")
            "xml" -> listOf("LinearLayout", "ConstraintLayout", "TextView", "EditText", "Button", "ImageView", "android:layout_width", "android:layout_height", "android:id", "match_parent", "wrap_content")
            "gradle", "kts" -> listOf("plugins", "dependencies", "implementation", "api", "android", "repositories", "mavenCentral", "google", "compileSdk", "minSdk", "targetSdk", "namespace")
            else -> listOf("function", "TODO", "if", "else", "return", "class", "true", "false", "null")
        }
    }

    private fun updateInlineCompletion() {
        if (!::inlineGhostView.isInitialized || !::editor.isInitialized) return
        val cursor = editor.selectionStart.coerceIn(0, editor.text.length)
        val source = editor.text.toString()
        val tokenStart = source.substring(0, cursor).indexOfLast { !it.isLetterOrDigit() && it != '_' && it != ':' }.let { it + 1 }
        val prefix = source.substring(tokenStart, cursor)
        val candidate = if (prefix.isNotEmpty() && prefix.length <= 32) {
            completionWords().firstOrNull { it.startsWith(prefix, ignoreCase = true) && it.length > prefix.length }
        } else null
        if (candidate == null || !editor.hasFocus() || editor.layout == null) {
            inlineCompletionSuffix = ""
            inlineCompletionTokenStart = -1
            inlineGhostView.text = ""
            inlineGhostView.visibility = View.GONE
            return
        }
        inlineCompletionSuffix = candidate.substring(prefix.length)
        inlineCompletionTokenStart = tokenStart
        inlineGhostView.text = inlineCompletionSuffix
        val layout = editor.layout ?: return
        val line = layout.getLineForOffset(cursor)
        val x = editor.totalPaddingLeft + layout.getPrimaryHorizontal(cursor) - editor.scrollX
        val y = editor.totalPaddingTop + layout.getLineTop(line) - editor.scrollY
        inlineGhostView.translationX = x
        inlineGhostView.translationY = y.toFloat()
        inlineGhostView.visibility = View.VISIBLE
        inlineGhostView.bringToFront()
    }

    private fun acceptInlineCompletion(addSpace: Boolean) {
        val suffix = inlineCompletionSuffix
        if (suffix.isEmpty()) return
        val cursor = editor.selectionStart.coerceIn(0, editor.text.length)
        val start = inlineCompletionTokenStart
        if (start < 0 || start > cursor) return
        val currentPrefix = editor.text.substring(start, cursor)
        if (currentPrefix.isEmpty()) return
        autoEditing = true
        try {
            val addition = suffix + if (addSpace) " " else ""
            editor.text.insert(cursor, addition)
            editor.setSelection(cursor + addition.length)
        } finally { autoEditing = false }
        inlineCompletionSuffix = ""
        inlineCompletionTokenStart = -1
        updateInlineCompletion()
        editor.invalidate()
    }

    private fun showFindReplace(replace: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val find = EditText(this).apply { hint = "Find text"; isSingleLine = true }
        box.addView(find)
        val replacement = EditText(this).apply { hint = "Replace with"; isSingleLine = true; visibility = if (replace) View.VISIBLE else View.GONE }
        box.addView(replacement)
        AlertDialog.Builder(this).setTitle(if (replace) "Find & Replace" else "Find")
            .setMessage(if (replace) "Replace matches in the currently open file. Search is case-insensitive." else "Search in the currently open file. Search is case-insensitive.")
            .setView(box).setPositiveButton(if (replace) "Replace all" else "Find next") { _, _ ->
                val needle = find.text.toString()
                if (needle.isNotEmpty()) {
                    if (replace) {
                        val source = editor.text.toString()
                        var occurrences = 0
                        var offset = 0
                        while (offset <= source.length - needle.length) {
                            val at = source.indexOf(needle, offset, ignoreCase = true)
                            if (at < 0) break
                            occurrences++
                            offset = at + needle.length
                        }
                        if (occurrences == 0) toast("Text not found")
                        else {
                            editor.text = android.text.SpannableStringBuilder(source.replace(needle, replacement.text.toString(), ignoreCase = true))
                            toast("Replaced $occurrences occurrence(s)")
                        }
                    }
                    else {
                        val length = editor.text.length
                        val selectionEnd = editor.selectionEnd.coerceAtLeast(0).coerceAtMost(length)
                        val selectionStart = editor.selectionStart.coerceAtLeast(0).coerceAtMost(length)
                        val start = if (selectionEnd > selectionStart) selectionEnd else selectionStart
                        val source = editor.text.toString()
                        val found = source.indexOf(needle, start, ignoreCase = true)
                        val at = if (found >= 0) found else source.indexOf(needle, 0, ignoreCase = true)
                        if (at >= 0) {
                            editor.requestFocus()
                            editor.setSelection(at, at + needle.length)
                            editor.post {
                                val line = editor.layout?.getLineForOffset(at) ?: 0
                                val y = editor.layout?.getLineTop(line) ?: 0
                                editor.requestRectangleOnScreen(Rect(0, y, editor.width, y + dp(32)), false)
                            }
                        } else Toast.makeText(this, "Text not found", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showProjectSearch() {
        val queryBox = EditText(this).apply { hint = "Search all project files"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Find in Project").setMessage("Search text files up to 1 MB each.")
            .setView(queryBox)
            .setPositiveButton("Search") { _, _ ->
                val query = queryBox.text.toString()
                if (query.isBlank()) return@setPositiveButton
                try {
                    if (!saveCurrent(false)) return@setPositiveButton
                    val matches = ArrayList<Pair<File, Int>>()
                    val labels = ArrayList<String>()
                    rootDir.walkTopDown().onEnter { directory ->
                        directory == rootDir || directory.name !in setOf(".git", ".gradle", ".idea", "build", "node_modules", ".dart_tool")
                    }.filter { file ->
                        file.isFile && file.extension.lowercase() in editableExtensions && file.length() <= 1024 * 1024
                    }.forEach { file ->
                        if (matches.size >= 300) return@forEach
                        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
                        var offset = 0
                        while (matches.size < 300) {
                            val at = content.indexOf(query, offset, ignoreCase = true)
                            if (at < 0) break
                            val line = content.substring(0, at).count { it == '\n' } + 1
                            val lineStart = if (at == 0) 0 else content.lastIndexOf('\n', at - 1).let { if (it < 0) 0 else it + 1 }
                            val lineEnd = content.indexOf('\n', at).let { if (it < 0) content.length else it }
                            val preview = content.substring(lineStart, lineEnd).trim().take(90)
                            matches.add(file to at)
                            labels.add("${file.relativeTo(rootDir).invariantSeparatorsPath}:$line  $preview")
                            offset = at + query.length.coerceAtLeast(1)
                        }
                    }
                    if (matches.isEmpty()) toast("No matches found in project")
                    else AlertDialog.Builder(this).setTitle("Project matches (${matches.size}${if (matches.size == 300) "+" else ""})")
                        .setItems(labels.toTypedArray()) { _, index ->
                            val (file, at) = matches[index]
                            openFile(file)
                            editor.requestFocus()
                            editor.setSelection(at, (at + query.length).coerceAtMost(editor.text.length))
                            editor.post {
                                val line = editor.layout?.getLineForOffset(at) ?: 0
                                val y = editor.layout?.getLineTop(line) ?: 0
                                editor.requestRectangleOnScreen(Rect(0, y, editor.width, y + dp(32)), false)
                            }
                        }.setNegativeButton("Close", null).show()
                } catch (e: Exception) { toast("Project search failed: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showSymbolNavigation(findReferences: Boolean) {
        if (currentFile == null) {
            toast("Open a source file first")
            return
        }
        val source = editor.text.toString()
        val cursor = editor.selectionStart.coerceIn(0, source.length)
        if (source.isEmpty()) {
            toast("Place the cursor on a symbol name")
            return
        }
        val current = cursor.coerceAtMost(source.length - 1)
        val start = if (source[current].isLetterOrDigit() || source[current] == '_') current
            else (cursor - 1).takeIf { it >= 0 && (source[it].isLetterOrDigit() || source[it] == '_') } ?: -1
        if (start < 0) {
            toast("Place the cursor on a symbol name")
            return
        }
        var left = start
        var right = start + 1
        while (left > 0 && (source[left - 1].isLetterOrDigit() || source[left - 1] == '_')) left--
        while (right < source.length && (source[right].isLetterOrDigit() || source[right] == '_')) right++
        val symbol = source.substring(left, right)
        if (symbol.isBlank() || symbol.first().isDigit()) {
            toast("Place the cursor on a valid symbol name")
            return
        }
        if (!saveCurrent(false)) return

        val declaration = Regex(
            """\b(?:fun|class|interface|object|enum\s+class|data\s+class|sealed\s+class|annotation\s+class|val|var|const\s+val|typealias|def|function)\s+${Regex.escape(symbol)}\b"""
        )
        val exactWord = Regex("""\b${Regex.escape(symbol)}\b""")
        val matches = ArrayList<Triple<File, Int, String>>()
        rootDir.walkTopDown().onEnter { dir ->
            dir == rootDir || dir.name !in setOf(".git", ".gradle", ".idea", "build", "node_modules", ".dart_tool")
        }.filter { file ->
            file.isFile && file.extension.lowercase() in editableExtensions && file.length() <= 1024 * 1024
        }.forEach { file ->
            if (matches.size >= 200) return@forEach
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            val regex = if (findReferences) exactWord else declaration
            regex.findAll(content).forEach { match ->
                if (matches.size >= 200) return@forEach
                val offset = match.range.first
                val line = content.substring(0, offset).count { it == '\n' } + 1
                val lineStart = content.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
                val lineEnd = content.indexOf('\n', offset).let { if (it < 0) content.length else it }
                val preview = content.substring(lineStart, lineEnd).trim().take(100)
                matches.add(Triple(file, offset, "${file.relativeTo(rootDir).invariantSeparatorsPath}:$line  $preview"))
            }
        }
        if (matches.isEmpty()) {
            toast(if (findReferences) "No references found for '$symbol'" else "Definition not found for '$symbol'")
            return
        }
        val title = if (findReferences) "References to '$symbol'" else "Definitions of '$symbol'"
        val labels = matches.map { it.third }.toTypedArray()
        AlertDialog.Builder(this).setTitle("$title (${matches.size}${if (matches.size == 200) "+" else ""})")
            .setItems(labels) { _, index ->
                val (file, offset) = matches[index]
                openFile(file)
                editor.requestFocus()
                val end = (offset + symbol.length).coerceAtMost(editor.text.length)
                editor.setSelection(offset.coerceAtMost(end), end)
                editor.post {
                    val line = editor.layout?.getLineForOffset(offset.coerceAtMost(editor.text.length)) ?: 0
                    val y = editor.layout?.getLineTop(line) ?: 0
                    editor.requestRectangleOnScreen(Rect(0, y, editor.width, y + dp(32)), false)
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun saveCurrent(showMessage: Boolean): Boolean {
        val file = currentFile ?: return true
        handler.removeCallbacks(autoSave)
        val content = editor.text.toString()
        try {
            require(file.canonicalPath.startsWith(rootDir.canonicalPath + File.separator)) { "File is outside the project" }
            val parent = file.parentFile ?: error("Invalid file location")
            require(parent.isDirectory) { "Parent folder no longer exists" }
            val temp = File.createTempFile(".pocket-save-", ".tmp", parent)
            val backup = File(parent, ".pocket-backup-${System.currentTimeMillis()}-${file.name}")
            try {
                java.io.FileOutputStream(temp).use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                if (file.exists()) {
                    require(file.renameTo(backup)) { "Could not preserve previous file before saving" }
                }
                if (!temp.renameTo(file)) {
                    if (backup.exists()) backup.renameTo(file)
                    error("Could not replace file with saved content")
                }
                backup.delete()
            } finally {
                temp.delete()
                if (backup.exists() && !file.exists()) backup.renameTo(file)
            }
            openTabs[file] = content
            dirty = false
            if (showMessage) toast("Saved: ${file.name}")
            return true
        } catch (e: Exception) {
            dirty = true
            openTabs[file] = content
            toast("Save failed: ${e.message ?: "Unknown error"}")
            return false
        }
    }

    private fun updateBuildErrors() {
        val log = getSharedPreferences("build_state", MODE_PRIVATE).getString("last_build_log", "") ?: ""
        val matches = log.lineSequence().filter { it.contains("error", true) || it.contains("failed", true) || it.contains("exception", true) }.take(4).toList()
        errorsPanel.text = if (matches.isEmpty()) "No recent build errors. Tap to view build log." else matches.joinToString("\n")
    }

    private fun showBuildErrors() {
        val log = getSharedPreferences("build_state", MODE_PRIVATE).getString("last_build_log", "No build log available.") ?: "No build log available."
        AlertDialog.Builder(this).setTitle("Build output / errors").setMessage(log)
            .setPositiveButton("OK", null).show()
    }


    private fun isAiSafeProjectFile(file: File): Boolean {
        val relative = file.relativeTo(rootDir).invariantSeparatorsPath
        val lower = relative.lowercase()
        val parts = lower.split("/")
        if (parts.any { it in setOf(".git", ".gradle", ".idea", ".kotlin", "build", ".dart_tool", "node_modules") }) return false
        if (parts.any { it.startsWith("keystore") || it == "secrets" }) return false
        if (file.name in setOf("local.properties", "google-services.json", "key.properties")) return false
        if (file.extension.lowercase() !in aiSafeExtensions) return false
        if (file.name.endsWith(".jks", true) || file.name.endsWith(".keystore", true) || file.name.contains("secret", true)) return false
        return file.length() <= 1024 * 1024
    }

    private fun shareProjectWithChatGPT() {
        if (!saveCurrent(false)) return
        try {
            val out = File(cacheDir, "ai-project-source.zip")
            var count = 0
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                rootDir.walkTopDown().filter { it.isFile && isAiSafeProjectFile(it) }.forEach { file ->
                    require(++count <= 1500) { "Project has too many source files (limit 1500)" }
                    zos.putNextEntry(ZipEntry(file.relativeTo(rootDir).invariantSeparatorsPath))
                    file.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                    require(out.length() <= 15L * 1024L * 1024L) { "AI source ZIP exceeds 15 MB" }
                }
            }
            require(count > 0) { "No supported source files found" }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
            val prompt = "Please inspect this Pocket APK Studio Android project. Apply my requested code changes, preserve project-relative file paths, and return a ZIP containing only the changed or new files. Do not include secrets, keystores, local.properties, or build outputs. Requested change: [WRITE YOUR REQUEST HERE]."
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, prompt)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share project source with ChatGPT"))
        } catch (e: Exception) {
            toast("AI export failed: ${e.message}")
        }
    }

    private fun chooseAiChangesZip() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(pick, 7104) }
        catch (e: Exception) { toast("Could not open ZIP picker: ${e.message}") }
    }

    private fun previewAndApplyAiChanges(uri: Uri) {
        val staging = File(cacheDir, "ai-change-staging").apply { deleteRecursively(); mkdirs() }
        pendingAiChanges.clear()
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    var count = 0
                    var total = 0L
                    while (entry != null) {
                        require(++count <= 500) { "AI ZIP contains too many files (limit 500)" }
                        val name = entry.name.replace('\\', '/')
                        if (!entry.isDirectory) {
                            val target = File(staging, name).canonicalFile
                            require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Unsafe ZIP path rejected" }
                            val relative = name.lowercase()
                            val parts = relative.split("/")
                            require(parts.none { it in setOf(".git", ".gradle", ".idea", "build", ".dart_tool", "node_modules") }) { "Build/cache files are not accepted" }
                            require(parts.none { it.startsWith("keystore") || it == "secrets" }) { "Sensitive files are not accepted" }
                            require(target.name !in setOf("local.properties", "google-services.json", "key.properties")) { "Sensitive configuration file rejected" }
                            require(target.extension.lowercase() in aiSafeExtensions) { "Unsupported file type: $name" }
                            require(!target.name.endsWith(".jks", true) && !target.name.endsWith(".keystore", true) && !target.name.contains("secret", true)) { "Sensitive file rejected" }
                            target.parentFile?.mkdirs()
                            target.outputStream().use { output ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n <= 0) break
                                    total += n
                                    require(total <= 15L * 1024L * 1024L) { "AI changes ZIP exceeds 15 MB" }
                                    output.write(buffer, 0, n)
                                }
                            }
                            val current = File(rootDir, name).canonicalFile
                            require(current.path.startsWith(rootDir.canonicalPath + File.separator)) { "Unsafe project path rejected" }
                            val changed = !current.isFile || current.length() != target.length() || !current.readBytes().contentEquals(target.readBytes())
                            if (changed) pendingAiChanges[name] = target
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Cannot read selected ZIP")
            if (pendingAiChanges.isEmpty()) {
                toast("No changed or new source files found in this ZIP.")
                return
            }
            val preview = pendingAiChanges.entries.joinToString("\n") { (path, file) ->
                (if (File(rootDir, path).exists()) "MODIFIED  " else "NEW        ") + path + "  (" + file.length() + " bytes)"
            }
            AlertDialog.Builder(this)
                .setTitle("Review AI changes • ${pendingAiChanges.size} file(s)")
                .setMessage("Only listed files will be updated. Existing originals will be backed up. No files will be deleted.\n\n$preview")
                .setNegativeButton("Reject", null)
                .setPositiveButton("Apply Changes") { _, _ -> applyPendingAiChanges() }
                .show()
        } catch (e: Exception) {
            pendingAiChanges.clear()
            toast("AI ZIP rejected: ${e.message}")
        }
    }

    private fun applyPendingAiChanges() {
        if (pendingAiChanges.isEmpty()) return
        if (!saveCurrent(false)) return
        val previouslyOpenFile = currentFile
        try {
            val backupRoot = File(filesDir, "ai-change-backups/${System.currentTimeMillis()}").apply { mkdirs() }
            pendingAiChanges.forEach { (relative, staged) ->
                val target = File(rootDir, relative).canonicalFile
                require(target.path.startsWith(rootDir.canonicalPath + File.separator)) { "Unsafe destination path" }
                if (target.isFile) {
                    val backup = File(backupRoot, relative)
                    backup.parentFile?.mkdirs()
                    target.copyTo(backup, overwrite = true)
                }
                target.parentFile?.mkdirs()
                staged.copyTo(target, overwrite = true)
            }
            val applied = pendingAiChanges.size
            pendingAiChanges.clear()
            refreshExplorer()
            if (previouslyOpenFile != null && previouslyOpenFile.isFile) {
                openTabs.remove(previouslyOpenFile)
                currentFile = null
                openFile(previouslyOpenFile)
            }
            toast("Applied $applied AI file change(s). Original files backed up.")
        } catch (e: Exception) {
            toast("Could not apply all AI changes: ${e.message}. Check project files and backups.")
        }
    }

    private fun exportEditedZip() {
        if (!saveCurrent(false)) return
        try {
            val out = File(cacheDir, "edited-project.zip")
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                rootDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    zos.putNextEntry(ZipEntry(file.relativeTo(rootDir).invariantSeparatorsPath))
                    file.inputStream().buffered().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
            setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "ZIP export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun label(text:String,size:Int,bold:Boolean):TextView=TextView(this).apply {
        this.text=text; textSize=size.toFloat()
        setTextColor(if(darkTheme) 0xFFE8EEFF.toInt() else 0xFF202938.toInt())
        if(bold) setTypeface(typeface,Typeface.BOLD)
        setPadding(dp(4),dp(4),dp(4),dp(4))
    }

    private fun smallButton(text:String,action:()->Unit):Button=Button(this).apply {
        this.text=text; isAllCaps=false; textSize=10.5f; minHeight=dp(36); minimumHeight=dp(36)
        setTextColor(if(darkTheme) 0xFFE8EEFF.toInt() else 0xFF263653.toInt())
        background=roundedSurface(if(darkTheme) 0xFF3C4A56.toInt() else 0xFFDCE5F7.toInt(),10)
        setPadding(dp(7),0,dp(7),0); stateListAnimator=null
        setOnClickListener { action() }
    }

    private fun roundedSurface(color:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius=dp(radius).toFloat()
        setStroke(dp(1),if(darkTheme) 0xFF3C4A56.toInt() else 0xFFD6DEEC.toInt())
    }

    private fun premiumGradient(start:Int,end:Int,radius:Int)=android.graphics.drawable.GradientDrawable(
        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(start,end)
    ).apply { cornerRadius=dp(radius).toFloat() }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::editor.isInitialized || !::rootDir.isInitialized) return

        // Capture the live editor buffer before rebuilding the orientation-specific UI.
        // openFile() saves the previously active editor before switching files, so clear
        // currentFile after saving; otherwise the newly-created empty EditText can overwrite
        // the user's source file during the tab-restore loop.
        val active = currentFile
        val tabs = openTabs.keys.toList()
        val liveText = editor.text.toString()
        if (active != null) openTabs[active] = liveText
        val cursor = editor.selectionStart.coerceAtLeast(0)
        val codeY = if (::codeScrollView.isInitialized) codeScrollView.scrollY else 0
        val explorerY = if (::explorerScrollView.isInitialized) explorerScrollView.scrollY else 0
        val wasExplorerOpen = explorerOpen

        if (!saveCurrent(false)) {
            toast("Rotation paused: could not save current file. Your editor text is kept open.")
            return
        }

        currentFile = null
        explorerOpen = false
        showEditor()
        tabs.filter { it.isFile }.forEach { openFile(it) }
        active?.takeIf { it.isFile }?.let { openFile(it) }
        if (::editor.isInitialized) editor.setSelection(cursor.coerceAtMost(editor.text.length))
        codeScrollView.post { codeScrollView.scrollTo(0, codeY) }
        explorerScrollView.post { explorerScrollView.scrollTo(0, explorerY) }
        if (wasExplorerOpen) toggleExplorer()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::editor.isInitialized && dirty) saveCurrent(false)
        super.onSaveInstanceState(outState)
        outState.putBoolean("workspace_loaded", ::rootDir.isInitialized && rootDir.isDirectory)
        if (::rootDir.isInitialized && rootDir.isDirectory) {
            outState.putStringArrayList("open_tabs", ArrayList(openTabs.keys.mapNotNull { file ->
                runCatching { file.relativeTo(rootDir).invariantSeparatorsPath }.getOrNull()
            }))
            outState.putString("active_file", currentFile?.let {
                runCatching { it.relativeTo(rootDir).invariantSeparatorsPath }.getOrNull()
            })
            outState.putStringArrayList("expanded_directories", ArrayList(expandedDirectories))
            outState.putBoolean("explorer_open", explorerOpen)
            if (::explorerScrollView.isInitialized) outState.putInt("explorer_scroll_y", explorerScrollView.scrollY)
            if (::codeScrollView.isInitialized) outState.putInt("code_scroll_y", codeScrollView.scrollY)
            if (::editor.isInitialized) outState.putInt("editor_cursor", editor.selectionStart.coerceAtLeast(0))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoSave)
        if (::editor.isInitialized && dirty) saveCurrent(false)
        super.onDestroy()
    }
}
