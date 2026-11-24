package com.telephoto.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SweepGradient
import android.view.View
import org.telegram.messenger.AndroidUtilities
import kotlin.math.min

/**
 * Telegram's signature circular rainbow-ring color swatch button (frames 00:37 & 01:05).
 */
class TelegramColorRingView(context: Context) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = AndroidUtilities.dp(2.4f).toFloat()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = AndroidUtilities.dp(1.2f).toFloat()
        color = 0x55FFFFFF
    }

    var currentColor: Int = 0xFFFF453A.toInt()
        set(value) {
            field = value
            fillPaint.color = value
            invalidate()
        }

    init {
        fillPaint.color = currentColor
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ringPaint.shader = SweepGradient(
            w / 2f,
            h / 2f,
            intArrayOf(
                0xFFFF453A.toInt(),
                0xFFFF9F0A.toInt(),
                0xFFFFD60A.toInt(),
                0xFF34C759.toInt(),
                0xFF64D2FF.toInt(),
                0xFF0A84FF.toInt(),
                0xFFBF5AF2.toInt(),
                0xFFFF453A.toInt()
            ),
            null
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerR = min(cx, cy) - AndroidUtilities.dp(4f)
        val innerR = outerR - AndroidUtilities.dp(4f)
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        canvas.drawCircle(cx, cy, innerR, fillPaint)
        if (currentColor == Color.BLACK) {
            canvas.drawCircle(cx, cy, innerR, borderPaint)
        }
    }
}
