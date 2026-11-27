package com.pocketapkstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * Interactive native Android 2D Saturation/Value + Hue visual color picker.
 */
class HsvColorPickerView(context: Context) : View(context) {

    var onColorChanged: ((String) -> Unit)? = null

    private var hue = 186f
    private var sat = 0.57f
    private var value = 0.56f

    private val svRect = RectF()
    private val hueRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val thumbOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xAA000000.toInt()
    }
    private var draggingTarget = 0 // 1 = SV box, 2 = Hue bar

    fun setHexColor(hex: String) {
        val hsv = ApkDesignerColorUtils.hexToHsv(hex)
        hue = hsv.h
        sat = hsv.s
        value = hsv.v
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredH = (166 * resources.displayMetrics.density).toInt()
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        val pad = 4f * density
        val hueH = 22f * density
        val gap = 10f * density
        svRect.set(pad, pad, w - pad, h - pad - hueH - gap)
        hueRect.set(pad, h - pad - hueH, w - pad, h - pad)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = 8f * density

        // 1. Hue base color + horizontal white->hue gradient
        val pureHueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        paint.shader = LinearGradient(
            svRect.left, svRect.top, svRect.right, svRect.top,
            Color.WHITE, pureHueColor, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(svRect, radius, radius, paint)

        // 2. Vertical transparent->black value overlay
        paint.shader = LinearGradient(
            svRect.left, svRect.top, svRect.left, svRect.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(svRect, radius, radius, paint)
        paint.shader = null

        // 3. Hue rainbow bar
        val rainbow = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        paint.shader = LinearGradient(
            hueRect.left, hueRect.top, hueRect.right, hueRect.top,
            rainbow, null, Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(hueRect, radius, radius, paint)
        paint.shader = null

        // 4. SV thumb
        val svX = svRect.left + sat * svRect.width()
        val svY = svRect.top + (1f - value) * svRect.height()
        canvas.drawCircle(svX, svY, 8f * density, thumbOuter)
        canvas.drawCircle(svX, svY, 6.5f * density, thumbStroke)

        // 5. Hue thumb
        val hueX = hueRect.left + (hue / 360f) * hueRect.width()
        val hueY = hueRect.centerY()
        canvas.drawCircle(hueX, hueY, 8.5f * density, thumbOuter)
        canvas.drawCircle(hueX, hueY, 6.5f * density, thumbStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingTarget = when {
                    hueRect.contains(event.x, event.y) || event.y >= hueRect.top - 12f -> 2
                    else -> 1
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingTarget != 0) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateFromTouch(event.x, event.y)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingTarget = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float, y: Float) {
        if (draggingTarget == 1 && svRect.width() > 0f && svRect.height() > 0f) {
            sat = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            value = (1f - (y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        } else if (draggingTarget == 2 && hueRect.width() > 0f) {
            hue = (((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f)) * 360f
        }
        invalidate()
        onColorChanged?.invoke(ApkDesignerColorUtils.hsvToHex(hue, sat, value))
    }
}
