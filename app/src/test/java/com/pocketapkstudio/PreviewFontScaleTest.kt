package com.pocketapkstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewFontScaleTest {

    @Test
    fun defaultPresetIsOneHundredPercent() {
        assertEquals(1, PreviewFontScale.DEFAULT_INDEX)
        assertEquals(1.0f, PreviewFontScale.scale(PreviewFontScale.DEFAULT_INDEX), 0.001f)
        assertEquals("100%", PreviewFontScale.label(PreviewFontScale.DEFAULT_INDEX))
    }

    @Test
    fun invalidIndexesAreClampedToSupportedPresets() {
        assertEquals(0, PreviewFontScale.normalize(-10))
        assertEquals(4, PreviewFontScale.normalize(99))
        assertEquals(0.85f, PreviewFontScale.scale(-10), 0.001f)
        assertEquals(1.50f, PreviewFontScale.scale(99), 0.001f)
    }

    @Test
    fun nextPresetAdvancesAndWrapsToTheSmallestScale() {
        assertEquals(2, PreviewFontScale.nextIndex(PreviewFontScale.DEFAULT_INDEX))
        assertEquals(0, PreviewFontScale.nextIndex(4))
        assertEquals("115%", PreviewFontScale.label(2))
        assertEquals("130%", PreviewFontScale.label(3))
        assertEquals("150%", PreviewFontScale.label(4))
        assertEquals("85%", PreviewFontScale.label(0))
    }

    @Test
    fun everySupportedScaleIsFiniteAndPositive() {
        for (index in 0..4) {
            assertTrue(PreviewFontScale.scale(index).isFinite())
            assertTrue(PreviewFontScale.scale(index) > 0f)
        }
    }
}
