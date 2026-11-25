package com.pocketapkstudio

import java.util.ArrayDeque

/**
 * Pure helper utilities for XML visual designer, file copy naming, completion words,
 * and bracket/string diagnostics. Kept framework-free so they can be unit tested on JVM.
 */
internal object EditorHelpers {

    fun findColorHexInColorsXml(colorsXml: String, colorName: String): String? {
        val pattern = Regex("""<color\s+name=["']${Regex.escape(colorName)}["']\s*>([^<]+)</color>""")
        return pattern.find(colorsXml)?.groupValues?.get(1)?.trim()
    }

    fun readXmlAttribute(element: String, attributeName: String, fallback: String = ""): String {
        val pattern = Regex("""\b${Regex.escape(attributeName)}\s*=\s*["']([^"']*)["']""")
        return pattern.find(element)?.groupValues?.get(1) ?: fallback
    }

    fun writeXmlAttribute(element: String, attributeName: String, value: String): String {
        val attrPattern = Regex("""\b${Regex.escape(attributeName)}\s*=\s*["'][^"']*["']""")
        val escaped = value.trim().replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        return if (attrPattern.containsMatchIn(element)) {
            attrPattern.replace(element, "$attributeName=\"$escaped\"")
        } else if (value.isNotBlank()) {
            element.replace(Regex("""\s*/?>$""")) { suffix -> " $attributeName=\"$escaped\"" + suffix.value }
        } else {
            element
        }
    }

    fun defaultCopyRelativePath(relativePath: String, fileName: String, extension: String): String {
        val parentDir = if ('/' in relativePath) relativePath.substringBeforeLast('/') + "/" else ""
        val baseName = if (fileName.startsWith(".") && fileName.count { it == '.' } == 1) {
            fileName
        } else {
            fileName.substringBeforeLast('.', fileName)
        }
        val suffix = extension.takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        return parentDir + baseName + "_copy" + suffix
    }

    fun completionWordsForExtension(extension: String): List<String> = when (extension.lowercase()) {
        "kt" -> listOf("fun", "val", "var", "class", "object", "override", "private", "public", "return", "if", "else", "when", "for", "while", "import", "package", "suspend", "data")
        "java" -> listOf("public", "private", "protected", "class", "interface", "void", "static", "final", "return", "new", "if", "else", "for", "while", "import", "package")
        "xml" -> listOf("LinearLayout", "ConstraintLayout", "TextView", "EditText", "Button", "ImageView", "android:layout_width", "android:layout_height", "android:id", "match_parent", "wrap_content")
        "gradle", "kts" -> listOf("plugins", "dependencies", "implementation", "api", "android", "repositories", "mavenCentral", "google", "compileSdk", "minSdk", "targetSdk", "namespace")
        else -> listOf("function", "TODO", "if", "else", "return", "class", "true", "false", "null")
    }

    fun scanDiagnostics(source: String, extension: String): List<Pair<Int, String>> {
        val ext = extension.lowercase()
        val stack = ArrayDeque<Pair<Char, Int>>()
        val issues = mutableListOf<Pair<Int, String>>()
        var quote: Char? = null
        var quoteStart = -1
        var inRawString = false
        var rawStringStart = -1
        var escaped = false
        var lineComment = false
        var blockCommentDepth = 0
        var xmlComment = false
        var i = 0
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            if (xmlComment) {
                if (c == '-' && next == '-' && source.getOrNull(i + 2) == '>') {
                    xmlComment = false
                    i += 3
                    continue
                }
                i++
                continue
            }
            if (lineComment) {
                if (c == '\n') lineComment = false
                i++
                continue
            }
            if (blockCommentDepth > 0) {
                if ((ext == "kt" || ext == "kts") && c == '/' && next == '*') {
                    blockCommentDepth++
                    i += 2
                    continue
                }
                if (c == '*' && next == '/') {
                    blockCommentDepth--
                    i += 2
                    continue
                }
                i++
                continue
            }
            if (inRawString) {
                if (source.startsWith("\"\"\"", i)) {
                    inRawString = false
                    i += 3
                    continue
                }
                i++
                continue
            }
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
                i++
                continue
            }
            if (ext == "xml" && c == '<' && source.startsWith("<!--", i)) {
                xmlComment = true
                i += 4
                continue
            }
            if (ext != "xml" && c == '/' && next == '/') {
                lineComment = true
                i += 2
                continue
            }
            if (ext != "xml" && c == '/' && next == '*') {
                blockCommentDepth = 1
                i += 2
                continue
            }
            if ((ext == "kt" || ext == "kts") && source.startsWith("\"\"\"", i)) {
                inRawString = true
                rawStringStart = i
                i += 3
                continue
            }
            if (c == '"' || c == '\'') {
                quote = c
                quoteStart = i
                i++
                continue
            }
            if (c == '(' || c == '[' || c == '{') {
                stack.addLast(c to i)
            } else if (c == ')' || c == ']' || c == '}') {
                val expected = when (c) {
                    ')' -> '('
                    ']' -> '['
                    else -> '{'
                }
                if (stack.isEmpty() || stack.last().first != expected) {
                    issues += i to ("Unexpected '" + c + "' — no matching opening bracket")
                } else {
                    stack.removeLast()
                }
            }
            i++
        }
        if (inRawString) issues += rawStringStart.coerceAtLeast(0) to "Unclosed string literal"
        if (quote != null) issues += quoteStart.coerceAtLeast(0) to "Unclosed string literal"
        if (blockCommentDepth > 0) issues += source.lastIndexOf("/*").coerceAtLeast(0) to "Unclosed block comment"
        if (xmlComment) issues += source.lastIndexOf("<!--").coerceAtLeast(0) to "Unclosed XML comment"
        stack.forEach { (ch, pos) ->
            val close = when (ch) {
                '(' -> ')'
                '[' -> ']'
                else -> '}'
            }
            issues += pos to ("Missing closing '" + close + "'")
        }
        return issues.distinctBy { it.first to it.second }.sortedBy { it.first }
    }
}
