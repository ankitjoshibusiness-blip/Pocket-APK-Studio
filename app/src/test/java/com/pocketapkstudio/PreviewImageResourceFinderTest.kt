package com.pocketapkstudio

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewImageResourceFinderTest {
    private val root = createTempDir(prefix = "preview-image-resources-")

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun findsVectorAndRasterResourcesAndPrefersXmlDrawable() {
        val drawable = File(root, "app/src/main/res/drawable").apply { mkdirs() }
        File(drawable, "brand_mark.png").writeBytes(byteArrayOf(1, 2, 3))
        File(drawable, "brand_mark.xml").writeText(
            """<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="24dp" android:height="24dp" />"""
        )

        val found = PreviewImageResourceFinder.findResources(root, "brand_mark")

        assertEquals(listOf("brand_mark.xml", "brand_mark.png"), found.map { it.name })
    }

    @Test
    fun findsMipmapVariantsInNestedAndroidModules() {
        val mipmap = File(root, "feature/profile/src/main/res/mipmap-hdpi").apply { mkdirs() }
        File(mipmap, "profile_photo.webp").writeBytes(byteArrayOf(4, 5, 6))

        val found = PreviewImageResourceFinder.findResources(root, "profile_photo")

        assertEquals(1, found.size)
        assertEquals("profile_photo.webp", found.single().name)
    }

    @Test
    fun ignoresFilesOutsideResourceFoldersAndGeneratedBuildTrees() {
        File(root, "assets").mkdirs()
        File(root, "assets/preview_icon.png").writeBytes(byteArrayOf(1))
        val generated = File(root, "app/build/generated/res/drawable").apply { mkdirs() }
        File(generated, "preview_icon.png").writeBytes(byteArrayOf(2))

        val found = PreviewImageResourceFinder.findResources(root, "preview_icon")

        assertTrue(found.isEmpty())
    }

    @Test
    fun rejectsBlankOrPathLikeResourceNames() {
        assertTrue(PreviewImageResourceFinder.findResources(root, "").isEmpty())
        assertTrue(PreviewImageResourceFinder.findResources(root, "../icon").isEmpty())
        assertTrue(PreviewImageResourceFinder.findResources(root, "drawable/icon").isEmpty())
    }
}
