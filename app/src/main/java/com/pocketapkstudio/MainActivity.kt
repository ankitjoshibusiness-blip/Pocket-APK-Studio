package com.pocketapkstudio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

class MainActivity : androidx.activity.ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var projectName: TextView
    private lateinit var projectType: TextView
    private var selectedUri: Uri? = null
    private var selectedIsTree = false

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            selectedUri = it
            selectedIsTree = false
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            inspectZip(it)
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            selectedUri = it
            selectedIsTree = true
            projectName.text = it.lastPathSegment ?: "Selected project folder"
            projectType.text = "Folder selected — project scan will be added in the next build step."
            status.text = "Folder imported. Ready for project inspection."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(20))
            setBackgroundColor(0xFF10131B.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(label("POCKET APK STUDIO", 13, 0xFF9AA7FF.toInt(), true))
        content.addView(label("Build APKs on your phone", 27, 0xFFFFFFFF.toInt(), true).apply {
            setPadding(0, dp(10), 0, dp(6))
        })
        content.addView(label("Import an Android or Flutter project. Your files stay on this device.", 14, 0xFFB6BBC9.toInt(), false))
        content.addView(space(22))

        val importCard = card()
        importCard.addView(label("1. IMPORT PROJECT", 12, 0xFF9AA7FF.toInt(), true))
        importCard.addView(space(10))
        importCard.addView(actionButton("Choose ZIP project") { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) })
        importCard.addView(space(8))
        importCard.addView(actionButton("Choose project folder") { pickFolder.launch(null) })
        content.addView(importCard)
        content.addView(space(14))

        val projectCard = card()
        projectCard.addView(label("2. PROJECT DETAILS", 12, 0xFF9AA7FF.toInt(), true))
        projectName = label("No project selected", 18, 0xFFFFFFFF.toInt(), true)
        projectCard.addView(projectName)
        projectType = label("Supported targets: Android Java/Kotlin and Flutter", 14, 0xFFB6BBC9.toInt(), false)
        projectCard.addView(projectType)
        content.addView(projectCard)
        content.addView(space(14))

        val buildCard = card()
        buildCard.addView(label("3. BUILD", 12, 0xFF9AA7FF.toInt(), true))
        buildCard.addView(space(8))
        buildCard.addView(actionButton("Build APK", true) {
            if (selectedUri == null) {
                Toast.makeText(this, "Please import a project first.", Toast.LENGTH_SHORT).show()
            } else {
                status.text = "Project imported. Local Android SDK + Gradle build engine is not installed yet. APK compilation is not enabled in this starter version."
            }
        })
        buildCard.addView(space(8))
        status = label("Waiting for a project.", 13, 0xFFB6BBC9.toInt(), false)
        buildCard.addView(status)
        content.addView(buildCard)
        content.addView(space(18))
        content.addView(label("BUILD HISTORY", 12, 0xFF9AA7FF.toInt(), true))
        content.addView(label("No builds yet", 14, 0xFFB6BBC9.toInt(), false))
        content.addView(space(18))
        content.addView(label("Version 1.0 • Native Android starter", 12, 0xFF737B8D.toInt(), false))

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun inspectZip(uri: Uri) {
        status.text = "Scanning project ZIP…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    var hasGradle = false
                    var hasSettings = false
                    var hasFlutter = false
                    var hasPubspec = false
                    var firstName = "Imported ZIP project"
                    contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(input).use { zip ->
                            var count = 0
                            var entry = zip.nextEntry
                            while (entry != null && count < 20000) {
                                val name = entry.name.replace('\\', '/')
                                if (count == 0) firstName = name.substringBefore('/').ifBlank { firstName }
                                if (name.endsWith("build.gradle") || name.endsWith("build.gradle.kts")) hasGradle = true
                                if (name.endsWith("settings.gradle") || name.endsWith("settings.gradle.kts")) hasSettings = true
                                if (name.endsWith("pubspec.yaml")) hasPubspec = true
                                if (name.endsWith("flutter") || name.contains(".dart_tool/")) hasFlutter = true
                                zip.closeEntry()
                                entry = zip.nextEntry
                                count++
                            }
                        }
                    } ?: error("Could not open ZIP file")
                    val type = when {
                        hasPubspec || hasFlutter -> "Flutter project detected"
                        hasGradle || hasSettings -> "Android Gradle project detected (Java/Kotlin)"
                        else -> "Project type not recognized — check ZIP structure"
                    }
                    Triple(firstName, type, "Scanned project archive successfully.")
                } catch (e: Exception) {
                    Triple("Imported project", "Could not inspect ZIP", e.message ?: "Unknown error")
                }
            }
            projectName.text = result.first
            projectType.text = result.second
            status.text = result.third
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(0xFF1B202C.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1B202C.toInt())
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun actionButton(text: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else 0xFFE4E7F2.toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (primary) 0xFF596BEB.toInt() else 0xFF30394D.toInt())
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52))
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
