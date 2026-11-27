package com.pocketapkstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import com.pocketapkstudio.ApkDesignerModels.DesignerElement
import com.pocketapkstudio.ApkDesignerModels.DesignerScreen
import com.pocketapkstudio.ApkDesignerModels.ElementKind
import com.pocketapkstudio.ApkDesignerModels.ResizeHandle
import com.pocketapkstudio.ApkDesignerModels.TextAlignMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-fidelity Figma-style Android Canvas View for APK Designer AND Interactive Live Preview.
 * Handles touch drag, 8-point resize handles, smart snap guides, photo filter matrices,
 * multi-tab bottom nav rendering, and 2x PNG bitmap export.
 */
class ApkDesignerCanvasView(context: Context) : View(context) {

    var screen: DesignerScreen = ApkDesignerModels.createStarterScreen("dashboard")
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var selectedId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    var zoom: Float = 0.88f
        set(value) {
            field = value.coerceIn(0.35f, 1.8f)
            requestLayout()
            invalidate()
        }

    var showGrid: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var snapToGrid: Boolean = true
    var interactivePreviewOnly: Boolean = false

    var onSelectElement: ((String?) -> Unit)? = null
    var onElementUpdated: ((DesignerElement, Boolean) -> Unit)? = null
    var onInteractiveTap: ((DesignerElement, String?) -> Unit)? = null

    private var guideX: Int? = null
    private var guideY: Int? = null

    private var dragMode: Int = 0 // 0=none, 1=move, 2=resize
    private var activeHandle: ResizeHandle? = null
    private var dragElementStart: DesignerElement? = null
    private var downCanvasX = 0f
    private var downCanvasY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var hasDragged = false

    private val bitmapCache = LinkedHashMap<String, Bitmap>()
    private val density get() = resources.displayMetrics.density
    private val scalePx get() = density * zoom

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x16FFFFFF
        strokeWidth = 1f
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CC9C0.toInt()
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CC9C0.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3E8790.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE3E8790.toInt() }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (screen.width * scalePx).roundToInt().coerceAtLeast(120)
        val h = (screen.height * scalePx).roundToInt().coerceAtLeast(240)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawScreenContent(canvas, scalePx, includeEditorChrome = !interactivePreviewOnly)
    }

    fun renderToBitmap(exportScale: Float = 2f): Bitmap {
        val w = (screen.width * exportScale).roundToInt().coerceAtLeast(1)
        val h = (screen.height * exportScale).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawScreenContent(c, exportScale, includeEditorChrome = false)
        return bmp
    }

    private fun drawScreenContent(canvas: Canvas, s: Float, includeEditorChrome: Boolean) {
        val bgInt = ApkDesignerColorUtils.parseColorInt(screen.backgroundColor, 0xFF202A33.toInt())
        canvas.drawColor(bgInt)

        if (includeEditorChrome && showGrid) {
            val step = 16f * s
            val maxW = screen.width * s
            val maxH = screen.height * s
            var gx = step
            while (gx < maxW) {
                canvas.drawLine(gx, 0f, gx, maxH, gridPaint)
                gx += step
            }
            var gy = step
            while (gy < maxH) {
                canvas.drawLine(0f, gy, maxW, gy, gridPaint)
                gy += step
            }
        }

        // Draw all visible elements in z-order
        for (el in screen.elements) {
            if (!el.visible) continue
            drawElement(canvas, el, s)
        }

        if (!includeEditorChrome) return

        // Smart snap guides
        guideX?.let { gx ->
            canvas.drawLine(gx * s, 0f, gx * s, screen.height * s, guidePaint)
        }
        guideY?.let { gy ->
            canvas.drawLine(0f, gy * s, screen.width * s, gy * s, guidePaint)
        }

        // Selection box + 8 resize handles + dimension badge
        val sel = screen.elements.find { it.id == selectedId && it.visible } ?: return
        val rect = RectF(sel.x * s, sel.y * s, (sel.x + sel.width) * s, (sel.y + sel.height) * s)
        canvas.drawRect(rect, selectPaint)

        val handleRadius = max(4.5f, 4.5f * density)
        for ((_, pt) in handlePoints(rect)) {
            canvas.drawRect(
                pt.first - handleRadius,
                pt.second - handleRadius,
                pt.first + handleRadius,
                pt.second + handleRadius,
                handleFill
            )
            canvas.drawRect(
                pt.first - handleRadius,
                pt.second - handleRadius,
                pt.first + handleRadius,
                pt.second + handleRadius,
                handleBorder
            )
        }

        // Floating dimension pill above selected element
        val badgeLabel = "${sel.name.take(18)} · ${sel.width}×${sel.height}"
        badgeText.textSize = 10f * density
        val tw = badgeText.measureText(badgeLabel)
        val padH = 6f * density
        val badgeH = 18f * density
        val bx = rect.left.coerceIn(4f, max(4f, width - tw - padH * 2 - 4f))
        val by = if (rect.top > badgeH + 8f) rect.top - badgeH - 6f else rect.bottom + 6f
        val bRect = RectF(bx, by, bx + tw + padH * 2, by + badgeH)
        canvas.drawRoundRect(bRect, 5f * density, 5f * density, badgeBg)
        canvas.drawText(badgeLabel, bx + padH, by + badgeH - 5f * density, badgeText)
    }

    private fun drawElement(canvas: Canvas, el: DesignerElement, s: Float) {
        val rect = RectF(el.x * s, el.y * s, (el.x + el.width) * s, (el.y + el.height) * s)
        val radius = el.borderRadius * s
        val alphaFactor = (el.opacity.coerceIn(0, 100) / 100f)

        val saveCount = if (alphaFactor < 0.99f) {
            canvas.saveLayerAlpha(rect, (alphaFactor * 255).roundToInt())
        } else -1

        when (el.kind) {
            ElementKind.BOTTOM_NAV -> drawBottomNav(canvas, el, rect, radius, s)
            ElementKind.SWITCH -> drawSwitch(canvas, el, rect, s)
            ElementKind.PROGRESS -> drawProgress(canvas, el, rect, s)
            ElementKind.IMAGE -> drawImageElement(canvas, el, rect, radius, s)
            else -> drawStandardBoxAndText(canvas, el, rect, radius, s)
        }

        if (saveCount >= 0) canvas.restoreToCount(saveCount)
    }

    private fun drawStandardBoxAndText(
        canvas: Canvas,
        el: DesignerElement,
        rect: RectF,
        radius: Float,
        s: Float,
    ) {
        if (el.fillColor != "transparent") {
            fillPaint.color = ApkDesignerColorUtils.parseColorInt(el.fillColor, 0xFF2B3742.toInt())
            fillPaint.style = Paint.Style.FILL
            if (el.kind == ElementKind.CIRCLE) {
                canvas.drawOval(rect, fillPaint)
            } else {
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
            }
        }

        if (el.strokeWidth > 0 && el.strokeColor != "transparent") {
            strokePaint.color = ApkDesignerColorUtils.parseColorInt(el.strokeColor, 0xFF3A4E5D.toInt())
            strokePaint.strokeWidth = max(1f, el.strokeWidth * s)
            if (el.kind == ElementKind.CIRCLE) {
                canvas.drawOval(rect, strokePaint)
            } else {
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
            }
        }

        val displayText = when (el.kind) {
            ElementKind.INPUT -> el.text.ifBlank { el.placeholder.ifBlank { "Enter value..." } }
            ElementKind.DIVIDER -> ""
            else -> el.text
        }
        if (displayText.isBlank()) return

        val textColorInt = when {
            el.kind == ElementKind.INPUT && el.text.isBlank() -> 0xFF7A909E.toInt()
            else -> ApkDesignerColorUtils.parseColorInt(el.textColor, 0xFFEEF4F6.toInt())
        }

        textPaint.color = textColorInt
        textPaint.textSize = el.fontSize * s
        textPaint.typeface = Typeface.create(
            Typeface.SANS_SERIF,
            if (el.bold) Typeface.BOLD else Typeface.NORMAL
        )

        val padH = when (el.kind) {
            ElementKind.INPUT, ElementKind.TOP_BAR -> 12f * s
            ElementKind.BUTTON, ElementKind.OUTLINED_BUTTON -> 8f * s
            else -> 4f * s
        }
        val availW = max(8, (rect.width() - padH * 2).roundToInt())
        val alignment = when (el.textAlign) {
            TextAlignMode.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignMode.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            TextAlignMode.LEFT -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, textPaint, availW)
            .setAlignment(alignment)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.clipRect(rect)
        val ty = rect.top + max(0f, (rect.height() - layout.height) / 2f)
        canvas.translate(rect.left + padH, ty)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawBottomNav(
        canvas: Canvas,
        el: DesignerElement,
        rect: RectF,
        radius: Float,
        s: Float,
    ) {
        fillPaint.color = ApkDesignerColorUtils.parseColorInt(el.fillColor, 0xFF273640.toInt())
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        if (el.strokeWidth > 0) {
            strokePaint.color = ApkDesignerColorUtils.parseColorInt(el.strokeColor, 0xFF354957.toInt())
            strokePaint.strokeWidth = max(1f, el.strokeWidth * s)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }

        val tabs = el.text.split("|").map { it.trim() }.filter { it.isNotEmpty() }.take(6)
        if (tabs.isEmpty()) return

        val pad = 5f * s
        val gap = 4f * s
        val tabW = (rect.width() - pad * 2 - gap * (tabs.size - 1)) / tabs.size
        val tabH = rect.height() - pad * 2

        tabs.forEachIndexed { idx, raw ->
            val isActive = if (el.placeholder.isNotBlank()) raw == el.placeholder else idx == 0
            val tx = rect.left + pad + idx * (tabW + gap)
            val ty = rect.top + pad
            val tabRect = RectF(tx, ty, tx + tabW, ty + tabH)
            fillPaint.color = if (isActive) 0xFF568B91.toInt() else 0xFF344653.toInt()
            canvas.drawRoundRect(tabRect, 10f * s, 10f * s, fillPaint)

            val parts = raw.split(Regex("\\s+"))
            val icon = if (parts.size > 1) parts.first() else ""
            val caption = if (parts.size > 1) parts.drop(1).joinToString(" ") else raw

            textPaint.color = 0xFFEEF4F6.toInt()
            textPaint.textAlign = Paint.Align.CENTER
            if (icon.isNotEmpty()) {
                textPaint.textSize = 12f * s
                textPaint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(icon, tabRect.centerX(), tabRect.centerY() - 2f * s, textPaint)
                textPaint.textSize = 9.5f * s
                canvas.drawText(caption.take(10), tabRect.centerX(), tabRect.centerY() + 12f * s, textPaint)
            } else {
                textPaint.textSize = 10.5f * s
                textPaint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(caption.take(10), tabRect.centerX(), tabRect.centerY() + 4f * s, textPaint)
            }
            textPaint.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawSwitch(canvas: Canvas, el: DesignerElement, rect: RectF, s: Float) {
        textPaint.color = ApkDesignerColorUtils.parseColorInt(el.textColor, 0xFFEEF4F6.toInt())
        textPaint.textSize = el.fontSize * s
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, if (el.bold) Typeface.BOLD else Typeface.NORMAL)
        canvas.drawText(el.text, rect.left + 4f * s, rect.centerY() + (el.fontSize * s * 0.35f), textPaint)

        val trackW = 44f * s
        val trackH = 24f * s
        val trackRect = RectF(
            rect.right - trackW - 4f * s,
            rect.centerY() - trackH / 2f,
            rect.right - 4f * s,
            rect.centerY() + trackH / 2f
        )
        fillPaint.color = if (el.checked) ApkDesignerColorUtils.parseColorInt(el.fillColor, 0xFF3E8790.toInt()) else 0xFF3A4B58.toInt()
        canvas.drawRoundRect(trackRect, trackH / 2f, trackH / 2f, fillPaint)

        val thumbR = 9f * s
        val thumbCx = if (el.checked) trackRect.right - trackH / 2f else trackRect.left + trackH / 2f
        fillPaint.color = Color.WHITE
        canvas.drawCircle(thumbCx, trackRect.centerY(), thumbR, fillPaint)
    }

    private fun drawProgress(canvas: Canvas, el: DesignerElement, rect: RectF, s: Float) {
        val r = rect.height() / 2f
        fillPaint.color = ApkDesignerColorUtils.parseColorInt(el.strokeColor, 0xFF2B3B47.toInt())
        canvas.drawRoundRect(rect, r, r, fillPaint)
        val frac = el.progress.coerceIn(0, 100) / 100f
        val activeRect = RectF(rect.left, rect.top, rect.left + rect.width() * frac, rect.bottom)
        fillPaint.color = ApkDesignerColorUtils.parseColorInt(el.fillColor, 0xFF3E8790.toInt())
        canvas.drawRoundRect(activeRect, r, r, fillPaint)
    }

    private fun drawImageElement(canvas: Canvas, el: DesignerElement, rect: RectF, radius: Float, s: Float) {
        val bmp = el.imageUri?.let { loadCachedBitmap(it) }
        if (bmp != null) {
            val clipPath = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(clipPath)
            val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val cm = buildPhotoColorMatrix(el.filters)
            imgPaint.colorFilter = ColorMatrixColorFilter(cm)

            // Center-crop scaling
            val scale = max(rect.width() / bmp.width, rect.height() / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val left = rect.left + (rect.width() - dw) / 2f
            val top = rect.top + (rect.height() - dh) / 2f
            canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), imgPaint)
            canvas.restore()

            if (el.strokeWidth > 0 && el.strokeColor != "transparent") {
                strokePaint.color = ApkDesignerColorUtils.parseColorInt(el.strokeColor, 0xFF4C6A7B.toInt())
                strokePaint.strokeWidth = max(1f, el.strokeWidth * s)
                canvas.drawRoundRect(rect, radius, radius, strokePaint)
            }
        } else {
            drawStandardBoxAndText(canvas, el, rect, radius, s)
        }
    }

    private fun buildPhotoColorMatrix(f: ApkDesignerModels.PhotoFilters): ColorMatrix {
        val combined = ColorMatrix()
        val sat = if (f.grayscale) 0f else f.saturation / 100f
        combined.setSaturation(sat)

        val b = (f.brightness - 100) * 2.55f
        val c = f.contrast / 100f
        val t = (1f - c) * 128f + b
        val bcMatrix = ColorMatrix(
            floatArrayOf(
                c, 0f, 0f, 0f, t,
                0f, c, 0f, 0f, t,
                0f, 0f, c, 0f, t,
                0f, 0f, 0f, 1f, 0f
            )
        )
        combined.postConcat(bcMatrix)

        if (f.sepia) {
            val sepia = ColorMatrix(
                floatArrayOf(
                    0.393f, 0.769f, 0.189f, 0f, 0f,
                    0.349f, 0.686f, 0.168f, 0f, 0f,
                    0.272f, 0.534f, 0.131f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            combined.postConcat(sepia)
        }
        return combined
    }

    private fun loadCachedBitmap(uriString: String): Bitmap? {
        bitmapCache[uriString]?.let { return it }
        return runCatching {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeStream(stream, null, opts)?.also { bmp ->
                    if (bitmapCache.size > 16) {
                        val firstKey = bitmapCache.keys.firstOrNull()
                        if (firstKey != null) bitmapCache.remove(firstKey)
                    }
                    bitmapCache[uriString] = bmp
                }
            }
        }.getOrNull()
    }

    private fun handlePoints(rect: RectF): List<Pair<ResizeHandle, Pair<Float, Float>>> = listOf(
        ResizeHandle.NW to (rect.left to rect.top),
        ResizeHandle.N to (rect.centerX() to rect.top),
        ResizeHandle.NE to (rect.right to rect.top),
        ResizeHandle.E to (rect.right to rect.centerY()),
        ResizeHandle.SE to (rect.right to rect.bottom),
        ResizeHandle.S to (rect.centerX() to rect.bottom),
        ResizeHandle.SW to (rect.left to rect.bottom),
        ResizeHandle.W to (rect.left to rect.centerY()),
    )

    private fun hitTestHandle(px: Float, py: Float): ResizeHandle? {
        val sel = screen.elements.find { it.id == selectedId && it.visible && !it.locked } ?: return null
        val s = scalePx
        val rect = RectF(sel.x * s, sel.y * s, (sel.x + sel.width) * s, (sel.y + sel.height) * s)
        val hitR = max(18f, 16f * density)
        for ((handle, pt) in handlePoints(rect)) {
            if (abs(px - pt.first) <= hitR && abs(py - pt.second) <= hitR) return handle
        }
        return null
    }

    private fun hitTestElement(cx: Float, cy: Float): DesignerElement? {
        for (i in screen.elements.indices.reversed()) {
            val el = screen.elements[i]
            if (!el.visible) continue
            if (cx >= el.x && cx <= el.x + el.width && cy >= el.y && cy <= el.y + el.height) {
                return el
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val s = scalePx
        if (s <= 0f) return false
        val cx = event.x / s
        val cy = event.y / s

        if (interactivePreviewOnly) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val hit = hitTestElement(cx, cy) ?: return false
                var tabLabel: String? = null
                if (hit.kind == ElementKind.BOTTOM_NAV) {
                    val tabs = hit.text.split("|").map { it.trim() }.filter { it.isNotEmpty() }.take(6)
                    if (tabs.isNotEmpty()) {
                        val rel = ((cx - hit.x) / max(1, hit.width)).coerceIn(0f, 0.999f)
                        tabLabel = tabs[(rel * tabs.size).toInt().coerceIn(0, tabs.lastIndex)]
                    }
                }
                onInteractiveTap?.invoke(hit, tabLabel)
                return true
            }
            return event.actionMasked == MotionEvent.ACTION_DOWN
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.x
                downRawY = event.y
                downCanvasX = cx
                downCanvasY = cy
                hasDragged = false

                val handle = hitTestHandle(event.x, event.y)
                if (handle != null) {
                    val sel = screen.elements.find { it.id == selectedId }
                    if (sel != null && !sel.locked) {
                        dragMode = 2
                        activeHandle = handle
                        dragElementStart = sel
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                val hit = hitTestElement(cx, cy)
                if (hit != null) {
                    selectedId = hit.id
                    onSelectElement?.invoke(hit.id)
                    if (!hit.locked) {
                        dragMode = 1
                        dragElementStart = hit
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        dragMode = 0
                    }
                    invalidate()
                    return true
                } else {
                    selectedId = null
                    onSelectElement?.invoke(null)
                    dragMode = 0
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val startEl = dragElementStart ?: return false
                if (dragMode == 0) return false
                val distPx = abs(event.x - downRawX) + abs(event.y - downRawY)
                if (distPx > 4f) hasDragged = true
                if (!hasDragged) return true

                parent?.requestDisallowInterceptTouchEvent(true)
                val dx = (cx - downCanvasX).roundToInt()
                val dy = (cy - downCanvasY).roundToInt()

                if (dragMode == 1) {
                    val snapped = ApkDesignerModels.moveElementWithSnap(
                        screen = screen,
                        element = startEl,
                        targetX = startEl.x + dx,
                        targetY = startEl.y + dy,
                        snapEnabled = snapToGrid,
                    )
                    guideX = snapped.guideX
                    guideY = snapped.guideY
                    val moved = startEl.copy(x = snapped.x, y = snapped.y)
                    onElementUpdated?.invoke(moved, false)
                } else if (dragMode == 2 && activeHandle != null) {
                    val resized = ApkDesignerModels.resizeElementFromHandle(startEl, activeHandle!!, dx, dy)
                    guideX = null
                    guideY = null
                    onElementUpdated?.invoke(resized, false)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val startEl = dragElementStart
                if (hasDragged && startEl != null) {
                    val latest = screen.elements.find { it.id == startEl.id }
                    if (latest != null) {
                        onElementUpdated?.invoke(latest, true)
                    }
                }
                dragMode = 0
                activeHandle = null
                dragElementStart = null
                guideX = null
                guideY = null
                hasDragged = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
