package com.pocketapkstudio

import com.pocketapkstudio.ApkDesignerModels.CanvasAlign
import com.pocketapkstudio.ApkDesignerModels.ElementKind
import com.pocketapkstudio.ApkDesignerModels.ResizeHandle
import com.pocketapkstudio.ApkDesignerModels.ScreenCategory
import com.pocketapkstudio.ApkDesignerModels.TextAlignMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkDesignerAndImportTest {

    @Test
    fun colorUtilsNormalizesHexAndRoundTripsHsv() {
        assertEquals("#AABBCC", ApkDesignerColorUtils.normalizeHex("#abc"))
        assertEquals("#3E8790", ApkDesignerColorUtils.normalizeHex("FF3E8790"))
        assertEquals("transparent", ApkDesignerColorUtils.normalizeHex("00FFFFFF"))

        val hsv = ApkDesignerColorUtils.hexToHsv("#3E8790")
        val back = ApkDesignerColorUtils.hsvToHex(hsv.h, hsv.s, hsv.v)
        assertEquals("#3E8790", back)

        assertTrue(ApkDesignerColorUtils.COLOR_COMBINATIONS.size >= 6)
        ApkDesignerColorUtils.COLOR_COMBINATIONS.forEach { combo ->
            assertTrue(combo.background.startsWith("#"))
            assertTrue(combo.primary.startsWith("#"))
            assertTrue(combo.text.startsWith("#"))
        }
    }

    @Test
    fun designerModelsSnapResizeAlignThemeAndExportCode() {
        val screen = ApkDesignerModels.createStarterScreen("dashboard")
        assertTrue(screen.elements.size >= 8)

        val btn = ApkDesignerModels.createDefaultElement(ElementKind.BUTTON, 40, 120)
        // Center X for width=200 on canvas=360 is x=80
        val snapped = ApkDesignerModels.moveElementWithSnap(screen, btn, 77, 121, snapEnabled = true)
        assertEquals(80, snapped.x)
        assertEquals(180, snapped.guideX)

        val resized = ApkDesignerModels.resizeElementFromHandle(btn, ResizeHandle.NW, 180, 30)
        assertEquals(24, resized.width)
        assertTrue(resized.height >= 16)

        val centered = ApkDesignerModels.alignElementInCanvas(screen, btn, CanvasAlign.CENTER_H)
        assertEquals(80, centered.x)

        val lightTheme = ApkDesignerColorUtils.COLOR_COMBINATIONS.first { it.id == "clean-light" }
        val themed = ApkDesignerModels.applyColorCombination(screen, lightTheme)
        assertEquals(lightTheme.background, themed.backgroundColor)
        assertTrue(themed.elements.any { it.kind == ElementKind.BUTTON && it.fillColor == lightTheme.primary })

        val styledHeading = ApkDesignerModels.createDefaultElement(ElementKind.HEADING, 20, 40)
            .copy(text = "Studio Title", textAlign = TextAlignMode.RIGHT)
        val customScreen = screen.copy(elements = listOf(styledHeading, btn))
        val compose = ApkDesignerModels.generateComposeCode(customScreen)
        assertTrue(compose.contains("TextAlign.End"))
        assertTrue(compose.contains("Studio Title"))
        assertTrue(compose.contains("ButtonDefaults.buttonColors"))

        val xml = ApkDesignerModels.generateXmlLayout(customScreen)
        assertTrue(xml.contains("android:gravity=\"end|center_vertical\""))
        assertTrue(xml.contains("Studio Title"))
    }

    @Test
    fun fullAppImporterExtractsXmlComposeAndDialogs() {
        val colorsXml = FullAppImportEngine.SourceFileEntry(
            path = "app/src/main/res/values/colors.xml",
            content = "<resources><color name=\"brand_bg\">#18222B</color><color name=\"brand_btn\">#3E8790</color></resources>"
        )
        val stringsXml = FullAppImportEngine.SourceFileEntry(
            path = "app/src/main/res/values/strings.xml",
            content = "<resources><string name=\"hero_title\">Pocket Studio</string></resources>"
        )
        val layoutXml = FullAppImportEngine.SourceFileEntry(
            path = "app/src/main/res/layout/activity_welcome.xml",
            content = """
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:background="@color/brand_bg">
                    <TextView android:text="@string/hero_title" android:textSize="22sp" android:gravity="center" />
                    <Button android:text="Launch" android:background="@color/brand_btn" />
                </LinearLayout>
            """.trimIndent()
        )
        val xmlBundle = FullAppImportEngine.importAllScreensFromFile(layoutXml, listOf(colorsXml, stringsXml, layoutXml))
        assertEquals(1, xmlBundle.screens.size)
        val xmlScreen = xmlBundle.screens.first()
        assertEquals("#18222B", xmlScreen.backgroundColor)
        assertTrue(xmlScreen.elements.any { it.text == "Pocket Studio" && it.textAlign == TextAlignMode.CENTER })
        assertTrue(xmlScreen.elements.any { it.kind == ElementKind.BUTTON && it.fillColor == "#3E8790" })

        val composeFile = FullAppImportEngine.SourceFileEntry(
            path = "app/src/main/java/com/demo/AppScreens.kt",
            content = """
                package com.demo
                @Composable
                fun HomeScreen() {
                    Column(modifier = Modifier.background(Color(0xFF141C24))) {
                        Text("Dashboard Ready", fontSize = 20.sp, color = Color.White)
                        Button(onClick = {}) { Text("Build Now") }
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("Confirm Export") },
                            text = { Text("Save generated APK?") },
                            confirmButton = { Button(onClick = {}) { Text("Export") } },
                            dismissButton = { TextButton(onClick = {}) { Text("Cancel") } }
                        )
                    }
                }
            """.trimIndent()
        )
        val composeBundle = FullAppImportEngine.importAllScreensFromFile(composeFile)
        assertEquals(2, composeBundle.screens.size)
        assertEquals("Home", composeBundle.screens[0].name)
        assertEquals(ScreenCategory.DIALOG, composeBundle.screens[1].screenCategory)
        assertEquals("Dialog: Confirm Export", composeBundle.screens[1].name)
    }

    @Test
    fun fullAppImporterExtractsRealNativeMainActivityScreensAndDialogs() {
        val candidates = listOf(
            File("src/main/java/com/pocketapkstudio/MainActivity.kt"),
            File("app/src/main/java/com/pocketapkstudio/MainActivity.kt"),
        )
        val mainFile = candidates.firstOrNull { it.isFile }
        assertNotNull("Expected MainActivity.kt on disk", mainFile)

        val entry = FullAppImportEngine.SourceFileEntry(
            path = "app/src/main/java/com/pocketapkstudio/MainActivity.kt",
            content = mainFile!!.readText(),
        )
        val bundle = FullAppImportEngine.importAllScreensFromFile(entry)
        val names = bundle.screens.map { it.name }

        assertTrue("Expected Home tab in $names", names.contains("Home"))
        assertTrue("Expected Workspace tab in $names", names.contains("Workspace"))
        assertTrue("Expected APK Tools tab in $names", names.contains("APK Tools"))
        assertTrue("Expected App Preview tab in $names", names.contains("App Preview"))
        assertTrue("Expected Settings tab in $names", names.contains("Settings"))
        assertTrue("Expected Environment Setup page in $names", names.contains("Environment Setup"))
        assertTrue("Expected Quick Start dialog in $names", names.any { it.contains("Quick Start") })
        assertTrue("Expected Documentation dialog in $names", names.any { it.contains("Documentation") })

        val home = bundle.screens.first { it.name == "Home" }
        assertEquals("#202A33", home.backgroundColor)
        assertTrue(home.elements.any { it.text.contains("APK Designer Canvas") })
        assertTrue(home.elements.any { it.text == "WORKSPACE" })
        assertTrue(home.elements.any { it.kind == ElementKind.BOTTOM_NAV })
        assertFalse(bundle.screens.any { it.name == "Dialog: Dialog Window" })
    }
}
