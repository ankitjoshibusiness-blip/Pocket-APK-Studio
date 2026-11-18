package com.pocketapkstudio

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProjectEditorActivity : Activity() {
    private lateinit var rootDir: File
    private lateinit var list: LinearLayout
    private lateinit var editor: EditText
    private lateinit var selectedFile: TextView
    private var currentFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra("zip_uri")?.let(Uri::parse)
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14,14,14,14) }
        root.setBackgroundColor(0xFF10131B.toInt())
        root.addView(TextView(this).apply { text = "PROJECT CODE EDITOR"; textSize = 18f; setTextColor(0xFFFFFFFF.toInt()) })
        root.addView(TextView(this).apply { text = "Tap a file to view or edit it"; setTextColor(0xFFB6BBC9.toInt()) })
        val save = Button(this).apply { text = "Save current file"; setOnClickListener { saveCurrent() } }
        root.addView(save)
        selectedFile = TextView(this).apply { setTextColor(0xFF9AA7FF.toInt()); text = "No file selected" }
        root.addView(selectedFile)
        editor = EditText(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF9AA0AE.toInt())
            setBackgroundColor(0xFF1B202C.toInt()); gravity = android.view.Gravity.TOP
            typeface = android.graphics.Typeface.MONOSPACE; textSize = 12f
            minLines = 12; setHorizontallyScrolling(true)
        }
        val editorScroll = ScrollView(this).apply { addView(editor) }
        root.addView(editorScroll, LinearLayout.LayoutParams(-1,0,1f))
        root.addView(Button(this).apply { text = "Refresh file list"; setOnClickListener { renderFiles() } })
        root.addView(Button(this).apply { text = "Save changes and return"; setOnClickListener { 
            saveCurrent()
            try {
                val out = File(cacheDir, "edited-project.zip")
                ZipOutputStream(out.outputStream().buffered()).use { zos ->
                    rootDir.walkTopDown().filter { it.isFile }.forEach { f ->
                        val name = f.relativeTo(rootDir).invariantSeparatorsPath
                        zos.putNextEntry(ZipEntry(name))
                        f.inputStream().buffered().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
                setResult(Activity.RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                finish()
            } catch (e: Exception) { Toast.makeText(this, "ZIP export failed: ${e.message}", Toast.LENGTH_LONG).show() }
        } })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fileScroll = ScrollView(this).apply { addView(list) }
        root.addView(TextView(this).apply { text = "PROJECT FILES"; setTextColor(0xFF9AA7FF.toInt()) })
        root.addView(fileScroll, LinearLayout.LayoutParams(-1, dp(180)))
        setContentView(root)
        renderFiles()
    }

    private fun renderFiles() {
        list.removeAllViews()
        rootDir.walkTopDown().filter { it != rootDir }.forEach { file ->
            val rel = file.relativeTo(rootDir).invariantSeparatorsPath
            val isDir = file.isDirectory
            val b = Button(this).apply {
                text = (if (isDir) "📁 " else "📄 ") + rel
                isAllCaps = false
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (isDir) Toast.makeText(this@ProjectEditorActivity, rel, Toast.LENGTH_SHORT).show()
                    else openFile(file)
                }
            }
            list.addView(b)
        }
    }

    private fun openFile(file: File) {
        if (file.length() > 1024 * 1024) {
            Toast.makeText(this, "File is larger than 1 MB; preview only is disabled.", Toast.LENGTH_SHORT).show()
            return
        }
        val ext = file.extension.lowercase()
        if (ext !in setOf("kt","java","xml","gradle","kts","properties","txt","md","json","yaml","yml","py","dart","sh","pro","toml","html","css")) {
            Toast.makeText(this, "This file type is not editable as text.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            currentFile = file
            selectedFile.text = file.relativeTo(rootDir).invariantSeparatorsPath
            editor.setText(file.readText(Charsets.UTF_8))
        } catch (e: Exception) { Toast.makeText(this, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun saveCurrent() {
        val file = currentFile ?: return
        try { file.writeText(editor.text.toString(), Charsets.UTF_8); Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_SHORT).show() }
        catch (e: Exception) { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
