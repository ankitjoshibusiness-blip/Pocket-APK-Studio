package com.pocketapkstudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Local, two-thumb state for the source-only RangeSlider representation.
 * It deliberately does not invoke the imported Compose onValueChange callback.
 */
internal class PreviewRangeSliderState(
    minimum: Float,
    maximum: Float,
    start: Float,
    end: Float
) {
    enum class Thumb { START, END }

    val minimum: Float = minimum.takeIf { it.isFinite() } ?: 0f
    val maximum: Float = maximum.takeIf { it.isFinite() && it > this.minimum } ?: (this.minimum + 1f)

    var startValue: Float
        private set
    var endValue: Float
        private set

    init {
        val first = start.takeIf { it.isFinite() }?.coerceIn(this.minimum, this.maximum) ?: this.minimum
        val second = end.takeIf { it.isFinite() }?.coerceIn(this.minimum, this.maximum) ?: this.maximum
        startValue = minOf(first, second)
        endValue = maxOf(first, second)
    }

    fun nearestThumb(value: Float, preferredOnTie: Thumb? = null): Thumb {
        val safeValue = value.takeIf { it.isFinite() } ?: startValue
        val startDistance = abs(safeValue - startValue)
        val endDistance = abs(safeValue - endValue)
        return when {
            startDistance < endDistance -> Thumb.START
            endDistance < startDistance -> Thumb.END
            else -> preferredOnTie ?: Thumb.START
        }
    }

    /** Moves one thumb without allowing the selected interval to cross itself. */
    fun move(thumb: Thumb, requestedValue: Float): Float {
        val safeValue = requestedValue.takeIf { it.isFinite() }?.coerceIn(minimum, maximum)
            ?: if (thumb == Thumb.START) startValue else endValue
        when (thumb) {
            Thumb.START -> startValue = safeValue.coerceAtMost(endValue)
            Thumb.END -> endValue = safeValue.coerceAtLeast(startValue)
        }
        return if (thumb == Thumb.START) startValue else endValue
    }
}

/**
 * A lightweight, touch-interactive dual-thumb control used only by the approximate source preview.
 * The actual imported app remains the authority for Compose styling, state and callbacks.
 */
internal class PreviewRangeSliderView(
    context: Context,
    minimum: Float,
    maximum: Float,
    start: Float,
    end: Float,
    description: String
) : View(context) {
    private val rangeState = PreviewRangeSliderState(minimum, maximum, start, end)
    private val density = resources.displayMetrics.density
    private val trackThickness = 4f * density
    private val thumbRadius = 9f * density
    private val horizontalInset = 5f * density
    private var activeThumb: PreviewRangeSliderState.Thumb? = null

    private val inactiveTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCBD5E1.toInt()
        style = Paint.Style.STROKE
        strokeWidth = trackThickness
        strokeCap = Paint.Cap.ROUND
    }
    private val activeTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt()
        style = Paint.Style.STROKE
        strokeWidth = trackThickness
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2563EB.toInt()
        style = Paint.Style.FILL
    }

    private val baseDescription = description.ifBlank { "Preview range slider" }

    init {
        isClickable = true
        isFocusable = true
        minimumHeight = (48f * density).roundToInt()
        contentDescription = accessibilityDescription()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (240f * density).roundToInt() + paddingLeft + paddingRight
        val desiredHeight = (48f * density).roundToInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = trackStart()
        val right = trackEnd()
        val centerY = height / 2f
        if (right <= left) return

        val startX = valueToX(rangeState.startValue)
        val endX = valueToX(rangeState.endValue)
        canvas.drawLine(left, centerY, right, centerY, inactiveTrack)
        canvas.drawLine(startX, centerY, endX, centerY, activeTrack)
        drawThumb(canvas, startX, centerY)
        drawThumb(canvas, endX, centerY)
    }

    private fun drawThumb(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, thumbRadius, thumbBorder)
        canvas.drawCircle(x, y, thumbRadius * 0.72f, thumbFill)
    }

    private fun trackStart(): Float = paddingLeft + thumbRadius + horizontalInset

    private fun trackEnd(): Float =
        (width - paddingRight - thumbRadius - horizontalInset).coerceAtLeast(trackStart())

    private fun valueToX(value: Float): Float {
        val span = rangeState.maximum - rangeState.minimum
        val fraction = if (span > 0f) ((value - rangeState.minimum) / span).coerceIn(0f, 1f) else 0f
        return trackStart() + (trackEnd() - trackStart()) * fraction
    }

    private fun xToValue(x: Float): Float {
        val span = rangeState.maximum - rangeState.minimum
        val trackWidth = trackEnd() - trackStart()
        if (span <= 0f || trackWidth <= 0f) return rangeState.startValue
        val fraction = ((x - trackStart()) / trackWidth).coerceIn(0f, 1f)
        return rangeState.minimum + span * fraction
    }

    private fun accessibilityDescription(): String =
        baseDescription + ". Start " + format(rangeState.startValue) +
            ", end " + format(rangeState.endValue) +
            ". Drag either handle to adjust the preview range."

    private fun format(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString()
        else String.format(java.util.Locale.US, "%.2f", value)

    private fun moveActiveThumb(x: Float) {
        val thumb = activeThumb ?: return
        rangeState.move(thumb, xToValue(x))
        contentDescription = accessibilityDescription()
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                activeThumb = rangeState.nearestThumb(xToValue(event.x))
                moveActiveThumb(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeThumb != null) {
                    moveActiveThumb(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activeThumb != null) {
                    moveActiveThumb(event.x)
                    activeThumb = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    announceForAccessibility(accessibilityDescription())
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                activeThumb = null
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
