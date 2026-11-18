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
    private var explorerOpen = false
    private var currentFile: File? = null
    private val openTabs = LinkedHashMap<File, String>()
    private val handler = Handler(Looper.getMainLooper())
    private var loadingText = false
    private var darkTheme = true
    private var dirty = false
    private val autoSave = Runnable { saveCurrent(false) }

    private val editableExtensions = setOf("kt","java","xml","gradle","kts","properties","txt","md","json","yaml","yml","py","dart","sh","pro","toml","html","css")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(10),dp(12),dp(10)) }
        applyTheme()
        val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(3),dp(4),dp(3),dp(12)) }
        val logo=TextView(this).apply { text="⌘"; textSize=24f; gravity=Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); background=premiumGradient(0xFF6C4BFF.toInt(),0xFF078CEB.toInt(),15) }
        header.addView(logo,LinearLayout.LayoutParams(dp(42),dp(42)))
        val heading=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        heading.addView(label("POCKET APK STUDIO",15,true))
        heading.addView(label("PROJECT CODE EDITOR  •  PRO",9,false))
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(smallButton("☼ / ☾") { darkTheme=!darkTheme; applyTheme(); refreshEditorColors() })
        root.addView(header)
        val titleRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(3),dp(4),dp(3),dp(8)) }
        val titleBlock=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        titleBlock.addView(label("Code Workspace",20,true))
        titleBlock.addView(label("Edit  •  Preview  •  Build",11,false))
        titleRow.addView(titleBlock,LinearLayout.LayoutParams(0,-2,1f))
        titleRow.addView(label("●  READY",10,true).apply { setTextColor(0xFF48D6A0.toInt()); setPadding(dp(10),dp(7),dp(10),dp(7)); background=roundedSurface(if(darkTheme) 0xFF153A3B.toInt() else 0xFFDDF6ED.toInt(),30) })
        root.addView(titleRow)
        val toolbar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(7),dp(5),dp(7),dp(5)); background=roundedSurface(if(darkTheme) 0xFF17243A.toInt() else 0xFFE7ECF7.toInt(),14) }
        explorerToggle=smallButton("☰  Explorer") { toggleExplorer() }; toolbar.addView(explorerToggle)
        toolbar.addView(smallButton("⌕ Find") { showFindReplace(false) })
        toolbar.addView(smallButton("⇄ Replace") { showFindReplace(true) })
        toolbar.addView(smallButton("✓ Save") { saveCurrent(true) })
        root.addView(toolbar,LinearLayout.LayoutParams(-1,dp(48)).apply { bottomMargin=dp(8) })
        fileLabel=label("Select a source file to start editing",11,false).apply { setPadding(dp(9),dp(7),dp(9),dp(7)); background=roundedSurface(if(darkTheme) 0xFF121D30.toInt() else 0xFFEFF2F8.toInt(),9) }
        root.addView(fileLabel,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(6) })
        tabsBar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val tabsScroll=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(tabsBar) }
        root.addView(tabsScroll,LinearLayout.LayoutParams(-1,dp(42)))
        val workArea=FrameLayout(this).apply { background=roundedSurface(if(darkTheme) 0xFF101A2A.toInt() else 0xFFFFFFFF.toInt(),14); clipToOutline=true }
        val codeRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP }
        lineNumbers=TextView(this).apply { typeface=Typeface.MONOSPACE; textSize=12f; gravity=Gravity.TOP or Gravity.END; setPadding(dp(8),dp(12),dp(8),dp(12)); text="1" }
        codeRow.addView(lineNumbers,LinearLayout.LayoutParams(dp(42),-1))
        editor=EditText(this).apply {
            typeface=Typeface.MONOSPACE; textSize=13f; gravity=Gravity.TOP or Gravity.START
            setPadding(dp(10),dp(12),dp(10),dp(18)); minLines=18; setHorizontallyScrolling(true)
            isVerticalScrollBarEnabled=true; background=null
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        codeRow.addView(editor,LinearLayout.LayoutParams(0,-1,1f))
        val codeScroll=ScrollView(this).apply { addView(codeRow) }
        workArea.addView(codeScroll,FrameLayout.LayoutParams(-1,-1))
        explorerPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(10),dp(10),dp(10))
            background=roundedSurface(if(darkTheme) 0xFF17243A.toInt() else 0xFFF0F3FA.toInt(),12); elevation=dp(14).toFloat()
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
        val diagnostics=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(11),dp(8),dp(11),dp(8)); background=roundedSurface(if(darkTheme) 0xFF17243A.toInt() else 0xFFE9EEF8.toInt(),12) }
        diagnostics.addView(label("⌁  BUILD OUTPUT / PROBLEMS",10,true))
        errorsPanel=label("No recent build errors. Tap to view diagnostics.",11,false).apply { maxLines=3; setOnClickListener { showBuildErrors() } }
        diagnostics.addView(errorsPanel)
        root.addView(diagnostics,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(3) })
        root.addView(smallButton("⇧  SAVE CHANGES & RETURN TO PROJECT") { exportEditedZip() },LinearLayout.LayoutParams(-1,dp(46)).apply { topMargin=dp(7) })
        setContentView(root); refreshExplorer(); refreshEditorColors()
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
            list.addView(item, LinearLayout.LayoutParams(-1, dp(38)))
        }
    }

    private fun applyTheme() {
        root.setBackgroundColor(if(darkTheme) 0xFF080D1D.toInt() else 0xFFF4F6FA.toInt())
        if(::explorerPanel.isInitialized) explorerPanel.background=roundedSurface(if(darkTheme) 0xFF17243A.toInt() else 0xFFF0F3FA.toInt(),12)
    }

    private fun refreshEditorColors() {
        val bg = if (darkTheme) 0xFF172131.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (darkTheme) 0xFFE2E8F0.toInt() else 0xFF202938.toInt()
        editor.setTextColor(fg); editor.setBackgroundColor(bg)
        lineNumbers.setTextColor(if (darkTheme) 0xFF8290A5.toInt() else 0xFF7A8494.toInt())
        lineNumbers.setBackgroundColor(if (darkTheme) 0xFF131C29.toInt() else 0xFFE9EDF4.toInt())
        fileLabel.setTextColor(if (darkTheme) 0xFF9FB4FF.toInt() else 0xFF425DB5.toInt())
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
        val keywordColor = if (darkTheme) 0xFFC792EA.toInt() else 0xFF7B2CBF.toInt()
        val stringColor = if (darkTheme) 0xFFC3E88D.toInt() else 0xFF26733A.toInt()
        val commentColor = if (darkTheme) 0xFF8295A8.toInt() else 0xFF687786.toInt()
        val numberColor = if (darkTheme) 0xFFF78C6C.toInt() else 0xFFB45309.toInt()
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
        background=roundedSurface(if(darkTheme) 0xFF26365D.toInt() else 0xFFDCE5F7.toInt(),10)
        setPadding(dp(7),0,dp(7),0); stateListAnimator=null
        setOnClickListener { action() }
    }

    private fun roundedSurface(color:Int,radius:Int)=android.graphics.drawable.GradientDrawable().apply {
        setColor(color); cornerRadius=dp(radius).toFloat()
        setStroke(dp(1),if(darkTheme) 0xFF293A5C.toInt() else 0xFFD6DEEC.toInt())
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
