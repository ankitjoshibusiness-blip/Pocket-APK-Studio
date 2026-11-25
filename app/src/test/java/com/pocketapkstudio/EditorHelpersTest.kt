package com.pocketapkstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHelpersTest {

    @Test
    fun findsColorHexInColorsXml() {
        val xml = """
            <resources>
                <color name="primary_accent">#FF3E8790</color>
                <color name='secondary_bg'>#202A33</color>
            </resources>
        """.trimIndent()
        assertEquals("#FF3E8790", EditorHelpers.findColorHexInColorsXml(xml, "primary_accent"))
        assertEquals("#202A33", EditorHelpers.findColorHexInColorsXml(xml, "secondary_bg"))
        assertNull(EditorHelpers.findColorHexInColorsXml(xml, "missing_color"))
    }

    @Test
    fun readsAndUpdatesXmlAttributesWithoutDuplicating() {
        val element = """<TextView android:layout_width="match_parent" android:text="Hello" />"""
        assertEquals("Hello", EditorHelpers.readXmlAttribute(element, "android:text"))
        assertEquals("match_parent", EditorHelpers.readXmlAttribute(element, "android:layout_width"))
        assertEquals("fallback", EditorHelpers.readXmlAttribute(element, "android:textColor", "fallback"))

        val updated = EditorHelpers.writeXmlAttribute(element, "android:text", "Updated")
        assertEquals("""<TextView android:layout_width="match_parent" android:text="Updated" />""", updated)
        assertEquals(1, Regex("""android:text=""").findAll(updated).count())

        val withNewAttr = EditorHelpers.writeXmlAttribute(updated, "android:textColor", "#FF0000")
        assertEquals(
            """<TextView android:layout_width="match_parent" android:text="Updated" android:textColor="#FF0000" />""",
            withNewAttr
        )
    }

    @Test
    fun buildsCorrectDefaultCopyPathForRootAndNestedFiles() {
        assertEquals(
            "settings.gradle_copy.kts",
            EditorHelpers.defaultCopyRelativePath("settings.gradle.kts", "settings.gradle.kts", "kts")
        )
        assertEquals(
            "app/src/main/MainActivity_copy.kt",
            EditorHelpers.defaultCopyRelativePath("app/src/main/MainActivity.kt", "MainActivity.kt", "kt")
        )
        assertEquals(
            ".gitignore_copy",
            EditorHelpers.defaultCopyRelativePath(".gitignore", ".gitignore", "")
        )
    }

    @Test
    fun kotlinAndJavaCompletionsDoNotSuggestFunctionKeyword() {
        val ktWords = EditorHelpers.completionWordsForExtension("kt")
        val javaWords = EditorHelpers.completionWordsForExtension("java")
        assertFalse(ktWords.contains("function"))
        assertTrue(ktWords.contains("fun"))
        assertFalse(javaWords.contains("function"))
    }

    @Test
    fun diagnosticsHandlesKotlinRawStringsAndNestedCommentsWithoutFalsePositives() {
        val validKotlin = """
            package com.example
            /* outer /* inner */ comment */
            fun demo(): String {
                val raw = ${"\"\"\""}
                    Unbalanced { [ ( inside raw string
                ${"\"\"\""}
                val ch = '"'
                return raw + ch
            }
        """.trimIndent()
        assertTrue(EditorHelpers.scanDiagnostics(validKotlin, "kt").isEmpty())

        val brokenKotlin = "fun broken() { val x = (1 + 2 }"
        val issues = EditorHelpers.scanDiagnostics(brokenKotlin, "kt")
        assertTrue(issues.any { it.second.contains("Unexpected '}'") || it.second.contains("Missing closing ')'") })
    }
}
