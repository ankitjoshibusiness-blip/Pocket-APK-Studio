package com.pocketapkstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRangeSliderStateTest {

    @Test
    fun keepsInitialValuesInsideCustomRangeAndOrdersThem() {
        val state = PreviewRangeSliderState(
            minimum = 10f,
            maximum = 90f,
            start = 82f,
            end = 24f
        )

        assertEquals(24f, state.startValue, 0.001f)
        assertEquals(82f, state.endValue, 0.001f)
        assertEquals(10f, state.minimum, 0.001f)
        assertEquals(90f, state.maximum, 0.001f)
    }

    @Test
    fun clampsInitialValuesToTheConfiguredRange() {
        val state = PreviewRangeSliderState(
            minimum = 0f,
            maximum = 100f,
            start = -25f,
            end = 150f
        )

        assertEquals(0f, state.startValue, 0.001f)
        assertEquals(100f, state.endValue, 0.001f)
    }

    @Test
    fun startThumbCannotMovePastEndThumb() {
        val state = PreviewRangeSliderState(0f, 100f, 20f, 70f)

        assertEquals(70f, state.move(PreviewRangeSliderState.Thumb.START, 95f), 0.001f)
        assertEquals(70f, state.startValue, 0.001f)
        assertEquals(70f, state.endValue, 0.001f)
    }

    @Test
    fun endThumbCannotMoveBeforeStartThumb() {
        val state = PreviewRangeSliderState(0f, 100f, 20f, 70f)

        assertEquals(20f, state.move(PreviewRangeSliderState.Thumb.END, 5f), 0.001f)
        assertEquals(20f, state.startValue, 0.001f)
        assertEquals(20f, state.endValue, 0.001f)
    }

    @Test
    fun nearestThumbSelectionCanBeKeptStableOnATie() {
        val state = PreviewRangeSliderState(0f, 100f, 20f, 80f)

        assertTrue(state.nearestThumb(50f) == PreviewRangeSliderState.Thumb.START)
        assertTrue(
            state.nearestThumb(50f, PreviewRangeSliderState.Thumb.END) ==
                PreviewRangeSliderState.Thumb.END
        )
        assertTrue(state.nearestThumb(25f) == PreviewRangeSliderState.Thumb.START)
        assertTrue(state.nearestThumb(75f) == PreviewRangeSliderState.Thumb.END)
    }

    @Test
    fun invalidMoveDoesNotInjectNaNIntoThePreviewState() {
        val state = PreviewRangeSliderState(0f, 10f, 2f, 8f)

        assertEquals(2f, state.move(PreviewRangeSliderState.Thumb.START, Float.NaN), 0.001f)
        assertTrue(state.startValue.isFinite())
        assertTrue(state.endValue.isFinite())
    }
}
