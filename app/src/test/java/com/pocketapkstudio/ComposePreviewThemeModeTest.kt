package com.pocketapkstudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposePreviewThemeModeTest {

    @Test
    fun explicitPreviewOverrideTakesPrecedence() {
        assertTrue(
            ComposePreviewThemeMode.resolve(
                overrideDark = true,
                systemDark = false,
                hasLightScheme = true,
                hasDarkScheme = true
            )
        )
        assertFalse(
            ComposePreviewThemeMode.resolve(
                overrideDark = false,
                systemDark = true,
                hasLightScheme = false,
                hasDarkScheme = true
            )
        )
    }

    @Test
    fun followsSystemWhenBothColorSchemesExist() {
        assertTrue(
            ComposePreviewThemeMode.resolve(
                overrideDark = null,
                systemDark = true,
                hasLightScheme = true,
                hasDarkScheme = true
            )
        )
        assertFalse(
            ComposePreviewThemeMode.resolve(
                overrideDark = null,
                systemDark = false,
                hasLightScheme = true,
                hasDarkScheme = true
            )
        )
    }

    @Test
    fun detectsDefaultDarkSchemeWithoutCustomPaletteArguments() {
        assertTrue(
            ComposePreviewThemeMode.resolve(
                overrideDark = null,
                systemDark = false,
                hasLightScheme = false,
                hasDarkScheme = true
            )
        )
    }

    @Test
    fun doesNotForceDarkWhenOnlyLightSchemeExists() {
        assertFalse(
            ComposePreviewThemeMode.resolve(
                overrideDark = null,
                systemDark = true,
                hasLightScheme = true,
                hasDarkScheme = false
            )
        )
    }

    @Test
    fun defaultsToLightWhenNoSchemeIsDeclared() {
        assertFalse(
            ComposePreviewThemeMode.resolve(
                overrideDark = null,
                systemDark = true,
                hasLightScheme = false,
                hasDarkScheme = false
            )
        )
    }
}
