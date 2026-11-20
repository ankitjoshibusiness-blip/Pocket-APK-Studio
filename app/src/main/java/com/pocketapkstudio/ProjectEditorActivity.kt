package com.pocketapkstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.graphics.Rect
import android.view.WindowManager
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.LinkedHashMap
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
    private lateinit var errorsPanel: TextView
    private lateinit var root: LinearLayout
    private lateinit var explorerPanel: LinearLayout
    private lateinit var explorerToggle: Button
    private lateinit var diagnosticsPanel: LinearLayout
    private lateinit var exportButton: Button
    private var explorerOpen = false
    private var currentFile: File? = null
    private val openTabs = LinkedHashMap<File, String>()
    private val handler = Handler(Looper.getMainLooper())
    private var loadingText = false
    private var darkTheme = true
    private var dirty = false
    private val autoSave = Runnable { saveCurrent(false) }
    private val pendingAiChanges = LinkedHashMap<String, File>()
    private val aiSafeExtensions = setOf("kt","java","xml","gradle","kts","properties","txt","md","json","yaml","yml","dart","sh","pro","toml","html","css")

    private val editableExtensions = setOf("kt","java","xml","gradle","kts","properties","txt","md","json","yaml","yml","py","dart","sh","pro","toml","html","css")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val source = intent.getStringExtra("zip_uri")?.let { Uri.parse(it) }
        if (source == null) { finish(); return }
        rootDir = File(cacheDir, "editor-project").apply { deleteRecursively(); mkdirs() }
        try {
            contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    var count = 0
                    while (entry != null && count++ < 30000) {
                        val out = File(rootDir, entry.name).canonicalFile
                        require(out.path.startsWith(rootDir.canonicalPath + File.separator)) { "Unsafe ZIP path" }
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Cannot read ZIP")
            showEditor()
        } catch (e: Exception) {
            Toast.makeText(this, "ZIP open failed: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun showEditor() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(6))
            isFocusableInTouchMode = true
        }
        applyTheme()
        val toolbar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(7),dp(5),dp(7),dp(5)); background=roundedSurface(if(darkTheme) 0xFF2B3742.toInt() else 0xFFE7ECF7.toInt(),14) }
        fun addToolbarTile(tile: Button) {
            toolbar.addView(tile, LinearLayout.LayoutParams(-2, dp(42)).apply { rightMargin = dp(6) })
        }
        explorerToggle=smallButton("☰  Explorer") { toggleExplorer() }; addToolbarTile(explorerToggle)
        addToolbarTile(smallButton("＋ File") { showFileActions() })
        addToolbarTile(smallButton("⌕ Find") { showFindReplace(false) })
        addToolbarTile(smallButton("⇄ Replace") { showFindReplace(true) })
        addToolbarTile(smallButton("✓ Save") { saveCurrent(true) })
        root.addView(toolbar,LinearLayout.LayoutParams(-1,dp(48)).apply { bottomMargin=dp(5) })
        val livePreviewButton = smallButton("▶  Live UI Preview") { openLivePreview() }.apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedSurface(0xFF2563EB.toInt(), 12)
        }
        root.addView(livePreviewButton, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(7) })
        val aiChatButton = smallButton("✦  AI Developer Chat • Free AI") {
            saveCurrent(false)
            startActivityForResult(
                Intent(this, AiChatActivity::class.java).putExtra("project_root", rootDir.absolutePath),
                7105
            )
        }.apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedSurface(0xFF256B72.toInt(), 12)
        }
        root.addView(aiChatButton, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(7) })
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
        root.addView(aiActions, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
        fileLabel=label("Select a source file to start editing",11,false).apply { setPadding(dp(9),dp(7),dp(9),dp(7)); background=roundedSurface(if(darkTheme) 0xFF26333D.toInt() else 0xFFEFF2F8.toInt(),9) }
        root.addView(fileLabel,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        tabsBar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val tabsScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(tabsBar) }
        root.addView(tabsScroll,LinearLayout.LayoutParams(-1,dp(42)))
        val workArea=FrameLayout(this).apply { background=roundedSurface(if(darkTheme) 0xFF202A33.toInt() else 0xFFFFFFFF.toInt(),14); clipToOutline=true }
        val codeRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
        lineNumbers=TextView(this).apply { typeface=Typeface.MONOSPACE; textSize=12f; gravity=Gravity.TOP or Gravity.END; setPadding(dp(8),dp(12),dp(8),dp(12)); text="1" }
        codeRow.addView(lineNumbers,LinearLayout.LayoutParams(dp(42),-1))
        editor=EditText(this).apply {
            typeface=Typeface.MONOSPACE; textSize=13f; gravity=Gravity.TOP or Gravity.START
            setPadding(dp(10),dp(12),dp(10),dp(18)); setHorizontallyScrolling(true)
            isVerticalScrollBarEnabled=true; background=null
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        codeRow.addView(editor,LinearLayout.LayoutParams(0,-1,1f))
        val codeScroll=ScrollView(this).apply { addView(codeRow) }
        workArea.addView(codeScroll,FrameLayout.LayoutParams(-1,-1))
        explorerPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(10),dp(10),dp(10))
            background=roundedSurface(if(darkTheme) 0xFF2B3742.toInt() else 0xFFF0F3FA.toInt(),12); elevation=dp(14).toFloat()
        }
        val explorerHeader=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        explorerHeader.addView(label("PROJECT EXPLORER",11,true),LinearLayout.LayoutParams(0,dp(36),1f))
        explorerHeader.addView(smallButton("×") { toggleExplorer() })
        explorerPanel.addView(explorerHeader)
        val explorerScroll=ScrollView(this)
        val explorerFiles=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        explorerScroll.addView(explorerFiles)
        explorerPanel.addView(explorerScroll,LinearLayout.LayoutParams(-1,0,1f))
        workArea.addView(explorerPanel,FrameLayout.LayoutParams(dp(205),-1,Gravity.START))
        explorerPanel.translationX=-dp(225).toFloat(); explorerPanel.visibility=View.GONE
        root.addView(workArea,LinearLayout.LayoutParams(-1,0,1f).apply { topMargin=dp(5); bottomMargin=dp(7) })
        suggestionsBar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(3),dp(2),dp(3),dp(2)) }
        root.addView(suggestionsBar,LinearLayout.LayoutParams(-1,dp(40)))
        diagnosticsPanel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(11),dp(8),dp(11),dp(8)); background=roundedSurface(if(darkTheme) 0xFF2B3742.toInt() else 0xFFE9EEF8.toInt(),12) }
        diagnosticsPanel.addView(label("⌁  BUILD OUTPUT / PROBLEMS",10,true))
        errorsPanel=label("No recent build errors. Tap to view diagnostics.",11,false).apply { maxLines=3; setOnClickListener { showBuildErrors() } }
        diagnosticsPanel.addView(errorsPanel)
        root.addView(diagnosticsPanel,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(3) })
        exportButton=smallButton("⇧  SAVE CHANGES & RETURN TO PROJECT") { exportEditedZip() }
        root.addView(exportButton,LinearLayout.LayoutParams(-1,dp(42)).apply { topMargin=dp(5) })
        setContentView(root)
        refreshExplorer()
        refreshEditorColors()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val keyboardVisible = root.rootView.height - visible.bottom > dp(160)
            suggestionsBar.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
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
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                if(!loadingText) {
                    dirty=true; currentFile?.let { openTabs[it]=s?.toString() ?: "" }
                    updateLineNumbers(); updateSuggestions(); handler.removeCallbacks(autoSave); handler.postDelayed(autoSave,900); highlightCode()
                }
            }
            override fun afterTextChanged(s:Editable?) {}
        })
        updateBuildErrors(); renderTabs()
    }

    private fun openLivePreview() {
        saveCurrent(false)
        startActivityForResult(
            Intent(this, LivePreviewActivity::class.java).putExtra("project_root", rootDir.absolutePath),
            7103
        )
    }

    private fun toggleExplorer() {
        explorerOpen=!explorerOpen
        if(explorerOpen) {
            refreshExplorer(); explorerPanel.visibility=View.VISIBLE; explorerPanel.translationX=-dp(225).toFloat()
            explorerPanel.animate().translationX(0f).setDuration(280L).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            explorerToggle.text="☰  Explorer  ▾"
        } else {
            explorerPanel.animate().translationX(-dp(225).toFloat()).setDuration(240L).setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction { explorerPanel.visibility=View.GONE }.start()
            explorerToggle.text="☰  Explorer"
        }
    }

    private fun refreshExplorer() {
        if (!::explorerPanel.isInitialized) return
        val scroll = explorerPanel.getChildAt(1) as? ScrollView ?: return
        val list = scroll.getChildAt(0) as? LinearLayout ?: return
        list.removeAllViews()
        rootDir.walkTopDown().filter { it.isFile }.take(500).forEach { file ->
            val editable = file.extension.lowercase() in editableExtensions
            val item = smallButton((if (editable) "▤ " else "▧ ") + file.relativeTo(rootDir).invariantSeparatorsPath) {
                if (!editable || file.length() > 1024 * 1024) {
                    Toast.makeText(this, "This file cannot be opened in the text editor.", Toast.LENGTH_SHORT).show()
                } else {
                    openFile(file)
                    if (explorerOpen) toggleExplorer()
                }
            }
            item.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            item.maxLines = 1
            item.setOnLongClickListener { showFileContextMenu(file); true }
            list.addView(item, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(6) })
        }
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

    private fun promptCreateFile() {
        val box = EditText(this).apply { hint = "e.g. app/src/main/java/NewFile.kt"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Create new file").setMessage("Enter a project-relative path and file name.")
            .setView(box).setPositiveButton("Create") { _, _ ->
                val path = box.text.toString().trim().replace('\\', '/')
                if (path.isBlank()) return@setPositiveButton
                try {
                    val file = File(rootDir, path).canonicalFile
                    require(file.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                    require(!file.exists()) { "File already exists" }
                    require(file.name.contains('.')) { "Add a file extension, e.g. .kt or .xml" }
                    file.parentFile?.mkdirs()
                    file.writeText("", Charsets.UTF_8)
                    refreshExplorer()
                    openFile(file)
                    toast("File created")
                } catch (e: Exception) { toast("Create failed: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun promptCreateFolder() {
        val box = EditText(this).apply { hint = "e.g. app/src/main/res/drawable"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Create new folder").setView(box)
            .setPositiveButton("Create") { _, _ ->
                val path = box.text.toString().trim().replace('\\', '/')
                if (path.isBlank()) return@setPositiveButton
                try {
                    val dir = File(rootDir, path).canonicalFile
                    require(dir.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                    require(!dir.exists()) { "Folder already exists" }
                    dir.mkdirs()
                    refreshExplorer()
                    toast("Folder created")
                } catch (e: Exception) { toast("Create failed: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun promptRename(file: File) {
        val box = EditText(this).apply { setText(file.relativeTo(rootDir).invariantSeparatorsPath); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Rename / Move file").setMessage("Enter the new project-relative path.")
            .setView(box).setPositiveButton("Save") { _, _ ->
                val path = box.text.toString().trim().replace('\\', '/')
                if (path.isBlank()) return@setPositiveButton
                try {
                    saveCurrent(false)
                    val target = File(rootDir, path).canonicalFile
                    require(target.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                    require(!target.exists()) { "Destination already exists" }
                    target.parentFile?.mkdirs()
                    if (!file.renameTo(target)) error("Could not move or rename file")
                    val oldText = openTabs.remove(file)
                    if (oldText != null) openTabs[target] = oldText
                    if (currentFile == file) currentFile = target
                    refreshExplorer()
                    currentFile?.takeIf { it == target }?.let { openFile(it) }
                    renderTabs()
                    toast("File renamed / moved")
                } catch (e: Exception) { toast("Rename failed: ${e.message}") }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun copyFile(file: File) {
        val box = EditText(this).apply { setText(file.relativeTo(rootDir).invariantSeparatorsPath.substringBeforeLast('.', file.name) + "_copy." + file.extension); setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Copy file").setView(box).setPositiveButton("Copy") { _, _ ->
            try {
                val target = File(rootDir, box.text.toString().trim()).canonicalFile
                require(target.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                require(!target.exists()) { "Destination already exists" }
                target.parentFile?.mkdirs()
                file.copyTo(target)
                refreshExplorer()
                toast("File copied")
            } catch (e: Exception) { toast("Copy failed: ${e.message}") }
        }.setNegativeButton("Cancel", null).show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this).setTitle("Delete ${file.name}?").setMessage("This permanently removes the file from this project.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                try {
                    require(file.isFile && file.delete()) { "Could not delete file" }
                    openTabs.remove(file)
                    if (currentFile == file) {
                        currentFile = null
                        loadingText = true
                        editor.setText("")
                        loadingText = false
                        fileLabel.text = "Select a source file to start editing"
                        dirty = false
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
                val target = File(rootDir, path).canonicalFile
                require(target.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid path" }
                require(!target.exists()) { "File already exists" }
                target.parentFile?.mkdirs()
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
                val name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') } ?: "imported_file.txt"
                val target = File(rootDir, name).canonicalFile
                require(target.path.startsWith(rootDir.canonicalPath + File.separator)) { "Invalid file name" }
                require(!target.exists()) { "A file with this name already exists in project root" }
                contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("Cannot read selected file")
                refreshExplorer()
                openFile(target)
                toast("File imported")
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
        val files = rootDir.walkTopDown().filter { it.isFile }.toList()
        val labels = files.map { (if (it.extension.lowercase() in editableExtensions) "▤ " else "▧ ") + it.relativeTo(rootDir).invariantSeparatorsPath }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Project Explorer").setItems(labels) { _, which ->
            val file = files[which]
            if (file.extension.lowercase() !in editableExtensions || file.length() > 1024 * 1024) {
                Toast.makeText(this, "This file cannot be opened in the text editor.", Toast.LENGTH_SHORT).show()
            } else openFile(file)
        }.setNegativeButton("Close", null).show()
    }

    private fun openFile(file: File) {
        saveCurrent(false)
        currentFile = file
        if (!openTabs.containsKey(file)) openTabs[file] = file.readText(Charsets.UTF_8)
        loadingText = true
        editor.setText(openTabs[file] ?: "")
        editor.setSelection(editor.text.length)
        loadingText = false
        dirty = false
        fileLabel.text = file.relativeTo(rootDir).invariantSeparatorsPath
        updateLineNumbers()
        updateSuggestions()
        highlightCode()
        renderTabs()
    }

    private fun renderTabs() {
        tabsBar.removeAllViews()
        openTabs.keys.forEach { file ->
            val active = file == currentFile
            val tab = smallButton((if (active) "● " else "") + file.name) { openFile(file) }
            tabsBar.addView(tab)
        }
    }

    private fun updateLineNumbers() {
        val count = editor.text.toString().count { it == '\n' } + 1
        lineNumbers.text = (1..count).joinToString("\n")
    }

    private fun highlightCode() {
        if (loadingText || !::editor.isInitialized) return
        val source = editor.text.toString()
        val cursor = editor.selectionStart.coerceAtLeast(0)
        val styled = SpannableString(source)
        val keywordColor = if (darkTheme) 0xFFB7A3D0.toInt() else 0xFF7B2CBF.toInt()
        val stringColor = if (darkTheme) 0xFFA9C99B.toInt() else 0xFF26733A.toInt()
        val commentColor = if (darkTheme) 0xFF8295A8.toInt() else 0xFF687786.toInt()
        val numberColor = if (darkTheme) 0xFFD6A080.toInt() else 0xFFB45309.toInt()
        val patterns = listOf(
            Pair("\\b(class|object|fun|val|var|if|else|when|for|while|return|public|private|protected|override|import|package|interface|suspend|const|lateinit|new|static|void|int|String|true|false|null)\\b", keywordColor),
            Pair("""\"([^\\"]|\\.)*\"|'([^\\']|\\.)*'""", stringColor),
            Pair("//[^\\n]*|/\\*[\\s\\S]*?\\*/", commentColor),
            Pair("\\b\\d+\\b", numberColor)
        )
        patterns.forEach { (pattern, color) ->
            val matcher = Pattern.compile(pattern).matcher(source)
            while (matcher.find()) styled.setSpan(ForegroundColorSpan(color), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        loadingText = true
        editor.setText(styled)
        editor.setSelection(cursor.coerceAtMost(editor.text.length))
        loadingText = false
    }

    private fun updateSuggestions() {
        suggestionsBar.removeAllViews()
        val words = when (currentFile?.extension?.lowercase()) {
            "kt" -> listOf("fun", "val", "var", "class", "override", "private", "return", "if")
            "java" -> listOf("public", "private", "class", "void", "return", "new", "if")
            "xml" -> listOf("<LinearLayout", "<TextView", "<Button", "android:layout_width", "android:layout_height")
            "gradle", "kts" -> listOf("plugins", "dependencies", "implementation", "android", "repositories")
            "json" -> listOf("\"name\"", "\"version\"", "\"true\"")
            else -> listOf("TODO", "if", "else", "return", "class", "function")
        }
        words.forEach { word ->
            val b = smallButton(word) {
                val start = editor.selectionStart.coerceAtLeast(0)
                editor.text.insert(start, word)
                editor.setSelection((start + word.length).coerceAtMost(editor.text.length))
            }
            suggestionsBar.addView(b)
        }
    }

    private fun showFindReplace(replace: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val find = EditText(this).apply { hint = "Find text"; isSingleLine = true }
        box.addView(find)
        val replacement = EditText(this).apply { hint = "Replace with"; isSingleLine = true; visibility = if (replace) View.VISIBLE else View.GONE }
        box.addView(replacement)
        AlertDialog.Builder(this).setTitle(if (replace) "Find & Replace" else "Find")
            .setView(box).setPositiveButton(if (replace) "Replace all" else "Find next") { _, _ ->
                val needle = find.text.toString()
                if (needle.isNotEmpty()) {
                    if (replace) editor.text = android.text.SpannableStringBuilder(editor.text.toString().replace(needle, replacement.text.toString()))
                    else {
                        val start = (editor.selectionStart.coerceAtLeast(0)).coerceAtMost(editor.text.length)
                        val at = editor.text.toString().indexOf(needle, start).let { if (it < 0) editor.text.toString().indexOf(needle) else it }
                        if (at >= 0) { editor.requestFocus(); editor.setSelection(at, at + needle.length) }
                        else Toast.makeText(this, "Text not found", Toast.LENGTH_SHORT).show()
                    }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun saveCurrent(showMessage: Boolean) {
        val file = currentFile ?: return
        handler.removeCallbacks(autoSave)
        try {
            file.writeText(editor.text.toString(), Charsets.UTF_8)
            openTabs[file] = editor.text.toString()
            dirty = false
            if (showMessage) Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            if (showMessage) Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
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
        saveCurrent(false)
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
            currentFile?.let { if (it.isFile) openFile(it) }
            toast("Applied $applied AI file change(s). Original files backed up.")
        } catch (e: Exception) {
            toast("Could not apply all AI changes: ${e.message}. Check project files and backups.")
        }
    }

    private fun exportEditedZip() {
        saveCurrent(false)
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

    override fun onDestroy() {
        handler.removeCallbacks(autoSave)
        if (::editor.isInitialized && dirty) saveCurrent(false)
        super.onDestroy()
    }
}
