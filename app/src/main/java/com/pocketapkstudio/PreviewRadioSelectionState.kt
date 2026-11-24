package com.pocketapkstudio

/**
 * Local selection state for the source preview's best-effort RadioButton groups.
 * It does not connect to the imported app's Compose state or callbacks.
 */
internal class PreviewRadioSelectionState(initialIndex: Int = -1) {
    private var selectedIndex: Int = initialIndex

    fun select(index: Int, itemCount: Int): Int {
        if (index in 0 until itemCount) selectedIndex = index
        return selectedIndex
    }

    fun isSelected(index: Int): Boolean = index == selectedIndex
}
