package com.pocketapkstudio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.Executors

class AiChatActivity : Activity() {
    private lateinit var projectRoot: File
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var prompt: EditText
    private lateinit var send: Button
    private lateinit var progress: ProgressBar
    private lateinit var providerInfo: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("ai_developer_settings", MODE_PRIVATE) }
    private val history = mutableListOf<Pair<String, String>>()
    private var busy = false
    private var pending = LinkedHashMap<String, File>()
    private val extensions = setOf("kt","java","xml","gradle","kts","properties","txt","md","json","yaml","yml","dart","sh","pro","toml","html","css")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectRoot = File(intent.getStringExtra("project_root") ?: "")
        if (!projectRoot.isDirectory) { finish(); return }
        buildUi()
        addMessage("AI DEVELOPER", "Namaste! Gujarati ke English ma command lakho. Hu current project ni files inspect kari code changes taiyar karish. Changes apply karta pehla review ane backup thase. API key mate ⚙ dabavo.")
        val initialPrompt = intent.getStringExtra("initial_prompt").orEmpty()
        if (initialPrompt.isNotBlank()) {
            prompt.setText(initialPrompt)
            prompt.setSelection(prompt.text.length)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(10)); setBackgroundColor(0xFF17212B.toInt()) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = "✦  AI DEVELOPER"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFE8F0F7.toInt()) }, LinearLayout.LayoutParams(0, dp(46), 1f))
        top.addView(button("↶ Undo") { undoLast() })
        top.addView(button("⚙") { settings() })
        root.addView(top)
        providerInfo = TextView(this).apply { text = providerName(); textSize = 11f; setTextColor(0xFF9FB5C7.toInt()); setPadding(dp(5), 0, 0, dp(5)) }
        root.addView(providerInfo)
        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Explain project", "Fix build errors", "Improve UI").forEach { label ->
            quick.addView(button(label) {
                prompt.setText(when (label) {
                    "Explain project" -> "Aa project nu structure ane important files samjavo. Code change na karo."
                    "Fix build errors" -> "Recent build log analyze karo, error nu root cause find karo ane jaruri code/config files fix karo."
                    else -> "Current app ni UI ne modern, clean ane mobile-friendly banao. Existing functionality preserve karo."
                })
                sendMessage()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(4) })
        }
        root.addView(quick)
        val previewButton = button("▶  Live UI Preview") { openLivePreview() }.apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(0xFF256B72.toInt(), 11)
        }
        root.addView(previewButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
        scroll = ScrollView(this).apply { isFillViewport = true; setPadding(0, dp(8), 0, dp(8)) }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(7) })
        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)).apply { gravity = Gravity.CENTER })
        val composer = LinearLayout(this).apply { gravity = Gravity.BOTTOM; setPadding(dp(7), dp(5), dp(7), dp(5)); background = rounded(0xFF24313D.toInt(), 15) }
        prompt = EditText(this).apply {
            hint = "App ma shu badalvu chhe?"; textSize = 14f; minLines = 1; maxLines = 5; background = null
            setTextColor(0xFFF2F6FA.toInt()); setHintTextColor(0xFF91A1B2.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        composer.addView(prompt, LinearLayout.LayoutParams(0, -2, 1f))
        send = button("➤") { sendMessage() }
        composer.addView(send, LinearLayout.LayoutParams(dp(50), dp(46)))
        root.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        setContentView(root)
    }

    private fun openLivePreview() {
        if (busy) {
            Toast.makeText(this, "AI request complete thava do.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivityForResult(
            android.content.Intent(this, LivePreviewActivity::class.java)
                .putExtra("project_root", projectRoot.absolutePath),
            7106
        )
    }

    private fun settings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val choice = Spinner(this)
        choice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Auto: Gemini first, Groq backup", "Gemini 3.8 Flash", "Groq GPT-OSS 120B"))
        choice.setSelection(when (prefs.getString("provider", "auto")) { "gemini" -> 1; "groq" -> 2; else -> 0 })
        box.addView(TextView(this).apply { text = "Free AI provider"; setTextColor(0xFFE8F0F7.toInt()) })
        box.addView(choice)
        val gemini = EditText(this).apply { hint = "Gemini API key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(readSecret("gemini_key")) }
        val groq = EditText(this).apply { hint = "Groq API key"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(readSecret("groq_key")) }
        box.addView(gemini); box.addView(groq)
        box.addView(TextView(this).apply {
            text = "Project source ane prompt selected AI provider ne mokalase. API keys Android Keystore thi encrypt kari store thase. Sensitive code share na karo."
            textSize = 11f; setTextColor(0xFF9FB5C7.toInt()); setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this).setTitle("Free AI setup").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val mode = when (choice.selectedItemPosition) { 1 -> "gemini"; 2 -> "groq"; else -> "auto" }
                prefs.edit().putString("provider", mode).apply()
                storeSecret("gemini_key", gemini.text.toString().trim())
                storeSecret("groq_key", groq.text.toString().trim())
                providerInfo.text = providerName()
                addMessage("STATUS", "AI settings saved: " + providerName())
            }
            .setNeutralButton("Clear keys") { _, _ -> prefs.edit().remove("gemini_key").remove("groq_key").apply(); providerInfo.text = providerName() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun providerName() = when (prefs.getString("provider", "auto")) {
        "gemini" -> "Free tier • Gemini 3.8 Flash"
        "groq" -> "Free tier • Groq GPT-OSS 120B"
        else -> "Free tier • Auto: Gemini → Groq"
    }

    private fun sendMessage() {
        if (busy) return
        val question = prompt.text.toString().trim()
        if (question.isBlank()) return
        val g = readSecret("gemini_key")
        val r = readSecret("groq_key")
        val mode = prefs.getString("provider", "auto") ?: "auto"
        if ((mode == "gemini" && g.isBlank()) || (mode == "groq" && r.isBlank()) || (mode == "auto" && g.isBlank() && r.isBlank())) {
            settings()
            Toast.makeText(this, "Free AI API key add karo.", Toast.LENGTH_LONG).show()
            return
        }
        prompt.setText("")
        addMessage("YOU", question)
        busy = true; send.isEnabled = false; progress.visibility = View.VISIBLE
        worker.execute {
            try {
                val result = requestAi(question, projectContext(), mode, g, r)
                val json = parseResponse(result)
                val answer = json.optString("message", "AI response ready.")
                val array = json.optJSONArray("files") ?: JSONArray()
                val staged = LinkedHashMap<String, File>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val path = item.optString("path").replace('\\', '/').trim()
                    val content = item.optString("content", "")
                    if (!safePath(path) || content.length > 1_000_000) continue
                    val file = File(cacheDir, "ai-v2-stage/$i.txt")
                    file.parentFile?.mkdirs(); file.writeText(content, Charsets.UTF_8)
                    staged[path] = file
                }
                runOnUiThread {
                    history.add(question to answer)
                    addMessage("AI DEVELOPER", answer)
                    pending = staged
                    if (pending.isNotEmpty()) {
                        addMessage("STATUS", pending.size.toString() + " file(s) ready. Review pachi j apply thase.")
                        reviewChanges()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { addMessage("AI DEVELOPER", "Request failed: " + (e.message ?: "Unknown error") + "\nAPI key, internet ane free-tier limit check karo.") }
            } finally {
                runOnUiThread { busy = false; send.isEnabled = true; progress.visibility = View.GONE }
            }
        }
    }

    private fun projectContext(): String {
        val all = projectRoot.walkTopDown().filter { it.isFile && safeProjectFile(it) }.take(20).toList()
        val out = StringBuilder("PROJECT FILE TREE:\n")
        all.take(250).forEach { out.append(it.relativeTo(projectRoot).invariantSeparatorsPath).append('\n') }
        out.append("\nSOURCE CONTENTS:\n")
        var used = 0
        all.take(35).forEach { file ->
            if (used >= 16000) return@forEach
            val content = try { file.readText(Charsets.UTF_8) } catch (_: Exception) { return@forEach }
            val part = content.take(minOf(content.length, 3000, 16000 - used))
            out.append("\n--- ").append(file.relativeTo(projectRoot).invariantSeparatorsPath).append(" ---\n").append(part).append('\n')
            used += part.length
        }
        val log = getSharedPreferences("build_state", MODE_PRIVATE).getString("last_build_log", "") ?: ""
        if (log.isNotBlank()) out.append("\nRECENT BUILD LOG:\n").append(log.takeLast(8000))
        return out.toString()
    }

    private fun systemPrompt() = """
You are Pocket APK Studio's coding agent. Understand Gujarati, Hindi and English.
Inspect the supplied project tree, source files and build log. Support Android Kotlin/Java/XML/Gradle and Flutter/Dart.
Return ONLY valid JSON: {"message":"explanation in user's language","files":[{"path":"relative/path","content":"COMPLETE updated file content"}]}.
For no code edits return files: []. Never return patches; every changed file must contain its complete content.
Preserve existing functionality, imports, style and project conventions. Never delete files or invent APIs.
Use safe project-relative paths only. Never output secrets, API keys, keystores, local.properties, google-services.json, build outputs or generated files.
For build fixes, use evidence from the supplied log. If uncertain, explain instead of guessing.
""".trimIndent()

    private fun requestAi(question: String, context: String, mode: String, gemini: String, groq: String): String {
        val recent = history.takeLast(6).joinToString("\n") { "USER: " + it.first + "\nAI: " + it.second }
        val promptText = "PROJECT CONTEXT:\n" + context + "\nRECENT CHAT:\n" + recent + "\nUSER REQUEST:\n" + question
        if (mode == "gemini") return geminiRequest(gemini, promptText)
        if (mode == "groq") return groqRequest(groq, promptText)
        var first: Exception? = null
        if (gemini.isNotBlank()) try { return geminiRequest(gemini, promptText) } catch (e: Exception) { first = e }
        if (groq.isNotBlank()) try { return groqRequest(groq, promptText) } catch (e: Exception) {
            throw IllegalStateException("Gemini: " + (first?.message ?: "not configured") + " | Groq: " + (e.message ?: "failed"))
        }
        throw first ?: IllegalStateException("No AI provider configured")
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 20000; c.readTimeout = 90000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val response = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP " + code + ": " + response.take(600))
        return response
    }

    private fun geminiRequest(key: String, userText: String): String {
        val systemParts = JSONArray().put(JSONObject().put("text", systemPrompt()))
        val systemInstruction = JSONObject().put("parts", systemParts)
        val userParts = JSONArray().put(JSONObject().put("text", userText))
        val userContent = JSONObject().put("role", "user").put("parts", userParts)
        val contents = JSONArray().put(userContent)
        val generation = JSONObject()
            .put("temperature", 0.15)
            .put("maxOutputTokens", 4096)
            .put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("systemInstruction", systemInstruction)
            .put("contents", contents)
            .put("generationConfig", generation)
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=" + key
        val response = JSONObject(post(endpoint, body))
        val parts = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini returned no content")
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }.joinToString("\n")
    }

    private fun groqRequest(key: String, userText: String): String {
        val body = JSONObject().put("model", "openai/gpt-oss-120b")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt()))
                .put(JSONObject().put("role", "user").put("content", userText)))
            .put("temperature", 0.15).put("max_completion_tokens", 4096)
            .put("response_format", JSONObject().put("type", "json_object"))
        val response = JSONObject(post("https://api.groq.com/openai/v1/chat/completions", body, mapOf("Authorization" to "Bearer " + key)))
        return response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: throw IllegalStateException("Groq returned no content")
    }

    private fun parseResponse(raw: String): JSONObject {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        require(a >= 0 && b > a) { "AI response is not valid JSON" }
        return JSONObject(text.substring(a, b + 1))
    }

    private fun encryptionKey(): SecretKey {
        val alias = "pocket_apk_studio_ai_key"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun storeSecret(name: String, value: String) {
        if (value.isBlank()) { prefs.edit().remove(name).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    private fun readSecret(name: String): String {
        val encoded = prefs.getString(name, null) ?: return ""
        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 12)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun safeProjectFile(file: File): Boolean {
        val rel = file.relativeTo(projectRoot).invariantSeparatorsPath.lowercase()
        val parts = rel.split("/")
        if (parts.any { it.startsWith(".") || it in setOf("build","node_modules",".dart_tool","secrets") || it.startsWith("keystore") }) return false
        if (file.name in setOf("local.properties","google-services.json","key.properties") || file.name.contains("secret", true)) return false
        if (file.extension.lowercase() in setOf("jks","keystore","pem","p12")) return false
        return file.extension.lowercase() in extensions && file.length() <= 1024 * 1024
    }

    private fun safePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains(':')) return false
        val parts = path.lowercase().split("/")
        if (parts.any { it == ".." || it.startsWith(".") || it in setOf("build","node_modules",".dart_tool","secrets") || it.startsWith("keystore") }) return false
        val name = parts.last()
        if (name in setOf("local.properties","google-services.json","key.properties") || name.contains("secret")) return false
        return name.substringAfterLast('.', "").lowercase() in extensions
    }

    private fun reviewChanges() {
        val summary = StringBuilder("Review before applying. Existing files will be backed up; new files can be removed by Undo.\n\n")
        pending.forEach { (path, staged) ->
            val old = File(projectRoot, path)
            val before = if (old.isFile) try { old.readText() } catch (_: Exception) { "" } else ""
            val after = try { staged.readText() } catch (_: Exception) { "" }
            summary.append(if (old.isFile) "MODIFIED " else "NEW ").append(path)
                .append("\nLines: ").append(before.lines().size).append(" → ").append(after.lines().size)
                .append("\nPreview:\n").append(after.lines().take(7).joinToString("\n").take(700)).append("\n\n")
        }
        val view = TextView(this).apply { text = summary.toString(); textSize = 12f; setTextColor(0xFFE8F0F7.toInt()); setPadding(dp(14), dp(10), dp(14), dp(10)); typeface = Typeface.MONOSPACE }
        AlertDialog.Builder(this).setTitle("Review " + pending.size + " AI file change(s)")
            .setView(ScrollView(this).apply { addView(view) })
            .setNegativeButton("Reject", null).setPositiveButton("Apply Changes") { _, _ -> applyChanges() }.show()
    }

    private fun applyChanges() {
        val backup = File(filesDir, "ai-v2-backups/" + System.currentTimeMillis()).apply { mkdirs() }
        val paths = JSONArray()
        try {
            pending.forEach { (path, staged) ->
                require(safePath(path))
                val target = File(projectRoot, path).canonicalFile
                require(target.path.startsWith(projectRoot.canonicalPath + File.separator))
                if (target.isFile) {
                    val save = File(backup, path); save.parentFile?.mkdirs(); target.copyTo(save, overwrite = true)
                }
                target.parentFile?.mkdirs(); staged.copyTo(target, overwrite = true); paths.put(path)
            }
            prefs.edit().putString("last_backup", backup.absolutePath).putString("last_paths", paths.toString()).apply()
            val count = pending.size; pending.clear()
            addMessage("STATUS", count.toString() + " file(s) applied. Backup saved. Editor par pacha jai updated files check karo; pachhi Save & Return thi build karo.")
        } catch (e: Exception) { addMessage("STATUS", "Apply error: " + e.message + ". Backup available chhe.") }
    }

    private fun undoLast() {
        val backupPath = prefs.getString("last_backup", null)
        val raw = prefs.getString("last_paths", null)
        if (backupPath == null || raw == null) { Toast.makeText(this, "Undo mate AI changes nathi.", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Undo last AI changes?")
            .setMessage("Modified files restore thase ane AI e banaveli new files delete thase.")
            .setNegativeButton("Cancel", null).setPositiveButton("Undo") { _, _ ->
                try {
                    val paths = JSONArray(raw)
                    for (i in 0 until paths.length()) {
                        val path = paths.getString(i); require(safePath(path))
                        val target = File(projectRoot, path).canonicalFile
                        require(target.path.startsWith(projectRoot.canonicalPath + File.separator))
                        val saved = File(backupPath, path)
                        if (saved.isFile) saved.copyTo(target, overwrite = true) else target.delete()
                    }
                    prefs.edit().remove("last_backup").remove("last_paths").apply()
                    addMessage("STATUS", "Last AI changes undo thai gaya.")
                } catch (e: Exception) { addMessage("STATUS", "Undo failed: " + e.message) }
            }.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7106 && resultCode == RESULT_OK) {
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private fun addMessage(who: String, text: String) {
        if (!::messages.isInitialized) return
        val bubble = TextView(this).apply {
            this.text = who + "\n" + text; textSize = 13f; setTextColor(0xFFE8F0F7.toInt())
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = rounded(when (who) { "YOU" -> 0xFF31536B.toInt(); "AI DEVELOPER" -> 0xFF293A46.toInt(); else -> 0xFF35404A.toInt() }, 13)
        }
        messages.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 10f; minHeight = dp(36)
        setTextColor(0xFFE8F0F7.toInt()); background = rounded(0xFF354957.toInt(), 10)
        setPadding(dp(4), 0, dp(4), 0); setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
