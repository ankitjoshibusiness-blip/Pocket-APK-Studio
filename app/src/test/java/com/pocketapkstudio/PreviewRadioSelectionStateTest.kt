package com.pocketapkstudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewRadioSelectionStateTest {

    @Test
    fun keepsAtMostOneSelectedItemAfterSelectionChanges() {
        val state = PreviewRadioSelectionState(initialIndex = 0)
        assertTrue(state.isSelected(0))
        assertFalse(state.isSelected(1))

        assertEquals(2, state.select(index = 2, itemCount = 3))
        assertFalse(state.isSelected(0))
        assertFalse(state.isSelected(1))
        assertTrue(state.isSelected(2))
    }

    @Test
    fun supportsAnInitiallyUnselectedGroup() {
        val state = PreviewRadioSelectionState()
        assertFalse(state.isSelected(0))
        assertFalse(state.isSelected(1))

        state.select(index = 1, itemCount = 2)
        assertFalse(state.isSelected(0))
        assertTrue(state.isSelected(1))
    }

    @Test
    fun ignoresInvalidSelectionIndexes() {
        val state = PreviewRadioSelectionState(initialIndex = 1)
        assertEquals(1, state.select(index = -1, itemCount = 3))
        assertEquals(1, state.select(index = 3, itemCount = 3))
        assertTrue(state.isSelected(1))
        assertFalse(state.isSelected(3))
    }
}
