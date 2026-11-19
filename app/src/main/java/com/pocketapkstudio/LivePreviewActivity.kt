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

/** Lightweight renderer for Android XML layouts; it does not execute app code. */
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
        root.addView(TextView(this).apply { text = "Android XML layout preview · Basic controls can be tried"; textSize = 11f; setTextColor(0xFFB6C5D2.toInt()); setPadding(dp(4), dp(8), dp(4), dp(10)) })
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFFF5F7FA.toInt()) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val scroll = ScrollView(this); scroll.addView(content)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun findLayouts() {
        layouts = File(projectRoot, "app/src/main/res/layout").walkTopDown().filter { it.isFile && it.extension == "xml" }.take(100).sortedBy { it.name }.toList()
        if (layouts.isEmpty()) { showMessage("No Android XML layouts found. Jetpack Compose and Flutter previews are not supported in this first version."); return }
        val first = layouts.firstOrNull { it.name == "activity_main.xml" } ?: layouts.firstOrNull { it.name == "main.xml" } ?: layouts.first()
        render(first)
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
            "LinearLayout" -> LinearLayout(this).apply { orientation = if (attr(p, "orientation") == "horizontal") HORIZONTAL else VERTICAL }
            "FrameLayout", "ConstraintLayout", "CoordinatorLayout", "RelativeLayout", "ScrollView", "NestedScrollView" -> LinearLayout(this).apply { orientation = VERTICAL }
            "TextView", "CheckedTextView" -> TextView(this)
            "Button", "MaterialButton" -> Button(this).apply { isAllCaps = false }
            "EditText", "TextInputEditText" -> EditText(this)
            "CheckBox" -> CheckBox(this)
            "RadioButton" -> RadioButton(this)
            "Switch", "SwitchCompat" -> Switch(this)
            "ProgressBar" -> ProgressBar(this)
            "ImageView", "ImageButton" -> TextView(this).apply { text = "▧ Image placeholder"; gravity = Gravity.CENTER }
            "Space" -> Space(this)
            else -> LinearLayout(this).apply { orientation = VERTICAL }
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
            if (f.isFile) Regex("<string\\s+name=[\\"']" + Regex.escape(key) + "[\\"'][^>]*>(.*?)</string>", setOf(RegexOption.DOT_MATCHES_ALL)).find(f.readText())?.let { return it.groupValues[1] }
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
