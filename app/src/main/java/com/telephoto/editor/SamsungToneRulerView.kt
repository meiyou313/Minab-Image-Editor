package com.telephoto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import org.telegram.messenger.AndroidUtilities
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Samsung Gallery-style vertical tick-mark Tone Ruler Slider:
 * - Clean evenly spaced vertical tick marks across the track
 * - Highlighted active range between zero and current value
 * - Crisp center zero tick and smooth draggable white indicator pill
 * - Subtle haptic tick when crossing zero; double-tap to reset to 0
 */
class SamsungToneRulerView(context: Context) : View(context) {

    var minValue: Int = -100
        private set
    var maxValue: Int = 100
        private set
    var currentValue: Int = 0
        private set

    var onValueChanged: ((Int) -> Unit)? = null

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = AndroidUtilities.dp(1.4f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val activeTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppTheme.accent
        strokeWidth = AndroidUtilities.dp(1.6f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val zeroTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = AndroidUtilities.dp(2.0f).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(AndroidUtilities.dp(4f).toFloat(), 0f, AndroidUtilities.dp(1f).toFloat(), 0x66000000)
    }
    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = AppTheme.accent
        strokeWidth = AndroidUtilities.dp(2f).toFloat()
    }

    private var dragging = false
    private val tickCount = 41
    private val sidePadding = AndroidUtilities.dp(22f).toFloat()

    private val doubleTapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val zeroTarget = 0.coerceIn(minValue, maxValue)
                if (currentValue != zeroTarget) {
                    currentValue = zeroTarget
                    AndroidUtilities.vibrateCursor(this@SamsungToneRulerView)
                    onValueChanged?.invoke(currentValue)
                    invalidate()
                }
                return true
            }
        }
    )

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setRangeAndValue(min: Int, max: Int, value: Int) {
        minValue = min
        maxValue = max
        currentValue = value.coerceIn(min, max)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        doubleTapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                updateFromTouchX(event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    updateFromTouchX(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouchX(x: Float) {
        val trackW = (width - sidePadding * 2f).coerceAtLeast(1f)
        val frac = ((x - sidePadding) / trackW).coerceIn(0f, 1f)
        var next = (minValue + frac * (maxValue - minValue)).roundToInt()
        // Gentle magnetic snap to 0 when crossing near zero in bidirectional ranges
        if (minValue < 0 && maxValue > 0 && abs(next) <= 2 && abs(currentValue) > 2) {
            next = 0
        }
        if (next != currentValue) {
            if (next == 0) {
                AndroidUtilities.vibrateCursor(this)
            }
            currentValue = next
            onValueChanged?.invoke(currentValue)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= sidePadding * 2f || h <= 0f) return

        val trackLeft = sidePadding
        val trackRight = w - sidePadding
        val trackW = trackRight - trackLeft
        val cy = h / 2f

        val range = (maxValue - minValue).coerceAtLeast(1).toFloat()
        val valFrac = ((currentValue - minValue) / range).coerceIn(0f, 1f)
        val zeroFrac = ((0 - minValue) / range).coerceIn(0f, 1f)

        val valX = trackLeft + valFrac * trackW
        val zeroX = trackLeft + zeroFrac * trackW
        val activeMinX = minOf(zeroX, valX)
        val activeMaxX = maxOf(zeroX, valX)

        tickPaint.color = if (AppTheme.isDark) 0x55FFFFFF else 0x44000000
        zeroTickPaint.color = if (AppTheme.isDark) 0xCCFFFFFF.toInt() else 0xAA000000.toInt()

        val shortHalf = AndroidUtilities.dp(5.5f).toFloat()
        val majorHalf = AndroidUtilities.dp(8.5f).toFloat()

        for (i in 0 until tickCount) {
            val f = i.toFloat() / (tickCount - 1).toFloat()
            val tx = trackLeft + f * trackW
            val isZeroTick = abs(tx - zeroX) < (trackW / tickCount) * 0.6f
            val isMajor = (i % 5 == 0)
            val halfH = when {
                isZeroTick -> majorHalf + AndroidUtilities.dp(2f)
                isMajor -> majorHalf
                else -> shortHalf
            }
            val inActiveRange = currentValue != 0 && tx >= activeMinX - 1f && tx <= activeMaxX + 1f
            val paint = when {
                inActiveRange -> activeTickPaint
                isZeroTick -> zeroTickPaint
                else -> tickPaint
            }
            canvas.drawLine(tx, cy - halfH, tx, cy + halfH, paint)
        }

        // Draggable indicator thumb (Samsung clean glowing dot)
        val radius = AndroidUtilities.dp(if (dragging) 9.5f else 8f).toFloat()
        canvas.drawCircle(valX, cy, radius, thumbPaint)
        canvas.drawCircle(valX, cy, radius, thumbStrokePaint)
    }
}
