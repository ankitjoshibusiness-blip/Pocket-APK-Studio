package com.pocketapkstudio

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure Kotlin color math and curated color combinations for the native APK Designer.
 * Kept free of android.* imports so it runs directly in fast JVM unit tests.
 */
object ApkDesignerColorUtils {

    data class HsvColor(val h: Float, val s: Float, val v: Float)

    data class ColorCombination(
        val id: String,
        val name: String,
        val background: String,
        val surface: String,
        val primary: String,
        val accent: String,
        val text: String,
        val mutedText: String,
        val stroke: String,
    )

    val QUICK_SWATCHES: List<String> = listOf(
        "#131A20", "#1E2B35", "#2B3742", "#FFFFFF",
        "#3E8790", "#2563EB", "#7C3AED", "#EC4899",
        "#EF4444", "#F59E0B", "#10B981", "#06B6D4",
        "#DCE9E8", "#E6F0F3", "#98AEBA", "#0F172A",
    )

    val COLOR_COMBINATIONS: List<ColorCombination> = listOf(
        ColorCombination(
            id = "pocket-teal",
            name = "Pocket Teal",
            background = "#202A33",
            surface = "#2B3742",
            primary = "#3E8790",
            accent = "#69C1B8",
            text = "#EEF4F6",
            mutedText = "#A8BAC3",
            stroke = "#3A4E5D",
        ),
        ColorCombination(
            id = "material-indigo",
            name = "Material Indigo",
            background = "#0F172A",
            surface = "#1E293B",
            primary = "#6366F1",
            accent = "#38BDF8",
            text = "#F8FAFC",
            mutedText = "#94A3B8",
            stroke = "#334155",
        ),
        ColorCombination(
            id = "sunset-coral",
            name = "Sunset Coral",
            background = "#1C1418",
            surface = "#2B1E24",
            primary = "#F97316",
            accent = "#FB7185",
            text = "#FFF7ED",
            mutedText = "#FDBA74",
            stroke = "#4C2C38",
        ),
        ColorCombination(
            id = "emerald-mint",
            name = "Emerald Mint",
            background = "#0D1B18",
            surface = "#162C27",
            primary = "#10B981",
            accent = "#34D399",
            text = "#ECFDF5",
            mutedText = "#A7F3D0",
            stroke = "#264B43",
        ),
        ColorCombination(
            id = "midnight-gold",
            name = "Midnight Gold",
            background = "#14161D",
            surface = "#202430",
            primary = "#D97706",
            accent = "#FBBF24",
            text = "#FEFCE8",
            mutedText = "#CBD5E1",
            stroke = "#383F52",
        ),
        ColorCombination(
            id = "clean-light",
            name = "Clean Light",
            background = "#F4F7FA",
            surface = "#FFFFFF",
            primary = "#2563EB",
            accent = "#0EA5E9",
            text = "#0F172A",
            mutedText = "#64748B",
            stroke = "#CBD5E1",
        ),
    )

    fun normalizeHex(raw: String?, fallback: String = "#3E8790"): String {
        val cleaned = raw?.trim()?.removePrefix("#")?.uppercase(Locale.US) ?: return fallback
        if (cleaned.equals("TRANSPARENT", ignoreCase = true)) return "transparent"
        if (cleaned.length == 3 && cleaned.all { it in '0'..'9' || it in 'A'..'F' }) {
            return "#${cleaned[0]}${cleaned[0]}${cleaned[1]}${cleaned[1]}${cleaned[2]}${cleaned[2]}"
        }
        if (cleaned.length == 6 && cleaned.all { it in '0'..'9' || it in 'A'..'F' }) {
            return "#$cleaned"
        }
        if (cleaned.length == 8 && cleaned.all { it in '0'..'9' || it in 'A'..'F' }) {
            val alpha = cleaned.substring(0, 2).toIntOrNull(16) ?: 255
            if (alpha == 0) return "transparent"
            return "#${cleaned.substring(2)}"
        }
        return fallback
    }

    fun parseColorInt(raw: String?, fallback: Int = 0xFF3E8790.toInt()): Int {
        if (raw.isNullOrBlank()) return fallback
        val trimmed = raw.trim()
        if (trimmed.equals("transparent", ignoreCase = true)) return 0x00000000
        val hex = trimmed.removePrefix("#")
        return when (hex.length) {
            3 -> {
                val expanded = "${hex[0]}${hex[0]}${hex[1]}${hex[1]}${hex[2]}${hex[2]}"
                val rgb = expanded.toLongOrNull(16) ?: return fallback
                (0xFF000000L or rgb).toInt()
            }
            6 -> {
                val rgb = hex.toLongOrNull(16) ?: return fallback
                (0xFF000000L or rgb).toInt()
            }
            8 -> {
                val argb = hex.toLongOrNull(16) ?: return fallback
                argb.toInt()
            }
            else -> fallback
        }
    }

    fun argbToHex(colorInt: Int): String {
        val alpha = (colorInt ushr 24) and 0xFF
        if (alpha == 0) return "transparent"
        val r = (colorInt ushr 16) and 0xFF
        val g = (colorInt ushr 8) and 0xFF
        val b = colorInt and 0xFF
        return String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    fun hexToHsv(hexInput: String): HsvColor {
        val norm = normalizeHex(hexInput, "#3E8790")
        if (norm == "transparent") return HsvColor(0f, 0f, 0f)
        val r = norm.substring(1, 3).toInt(16) / 255f
        val g = norm.substring(3, 5).toInt(16) / 255f
        val b = norm.substring(5, 7).toInt(16) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC

        val h = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }

        val s = if (maxC == 0f) 0f else delta / maxC
        return HsvColor(h, s, maxC)
    }

    fun hsvToHex(h: Float, s: Float, v: Float): String {
        val hue = ((h % 360f) + 360f) % 360f
        val sat = s.coerceIn(0f, 1f)
        val value = v.coerceIn(0f, 1f)
        val c = value * sat
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = value - c
        val (r1, g1, b1) = when {
            hue < 60f -> Triple(c, x, 0f)
            hue < 120f -> Triple(x, c, 0f)
            hue < 180f -> Triple(0f, c, x)
            hue < 240f -> Triple(0f, x, c)
            hue < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val g = ((g1 + m) * 255f).roundToInt().coerceIn(0, 255)
        val b = ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
        return String.format(Locale.US, "#%02X%02X%02X", r, g, b)
    }

    fun isLightHex(hexInput: String?): Boolean {
        val norm = normalizeHex(hexInput, "#202A33")
        if (norm == "transparent") return false
        val r = norm.substring(1, 3).toInt(16)
        val g = norm.substring(3, 5).toInt(16)
        val b = norm.substring(5, 7).toInt(16)
        val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return lum > 0.62
    }
}
