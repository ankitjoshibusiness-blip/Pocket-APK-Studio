package com.pocketapkstudio

/** Supported text-size presets for generated source-preview views. */
internal object PreviewFontScale {
    private val factors = listOf(0.85f, 1.0f, 1.15f, 1.30f, 1.50f)

    const val DEFAULT_INDEX = 1

    fun normalize(index: Int): Int = index.coerceIn(factors.indices)

    fun nextIndex(index: Int): Int = (normalize(index) + 1) % factors.size

    fun scale(index: Int): Float = factors[normalize(index)]

    fun label(index: Int): String = "${(scale(index) * 100f).toInt()}%"
}
