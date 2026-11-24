package com.pocketapkstudio

/**
 * Resolves the appearance used by the source-based Compose preview.
 *
 * This is deliberately a small policy helper: scheme definitions with no explicit
 * palette arguments (for example, darkColorScheme()) still count as real schemes.
 */
internal object ComposePreviewThemeMode {

    fun resolve(
        overrideDark: Boolean?,
        systemDark: Boolean,
        hasLightScheme: Boolean,
        hasDarkScheme: Boolean
    ): Boolean {
        return overrideDark ?: when {
            hasLightScheme && hasDarkScheme -> systemDark
            hasDarkScheme -> true
            else -> false
        }
    }
}
