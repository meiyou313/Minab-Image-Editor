package com.telephoto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.atan2

class TextOverlayContainer(context: Context) : FrameLayout(context) {

    enum class TextStyle { OUTLINED, FILLED, REGULAR }

    var onTextDoubleTap: ((TextView) -> Unit)? = null

    fun addTextEntity(text: String, color: Int, style: TextStyle = TextStyle.OUTLINED): TextView {
        val tv = createTextEntityView(text, color, style)
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(tv, lp)
        return tv
    }

    fun clearAll() {
        removeAllViews()
    }

    fun hasTextEntities(): Boolean = childCount > 0

    fun renderOntoBitmap(target: Bitmap) {
        if (childCount == 0 || width <= 0 || height <= 0) return
        val canvas = Canvas(target)
        val sx = target.width.toFloat() / width.toFloat()
        val sy = target.height.toFloat() / height.toFloat()
        canvas.save()
        canvas.scale(sx, sy)
        draw(canvas)
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createTextEntityView(text: String, color: Int, style: TextStyle): TextView {
        val tv = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val padH = (14 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            applyStyle(this, color, style)
        }

        var lastX = 0f
        var lastY = 0f
        var lastAngle = 0f
        var isRotating = false
        var lastTapTime = 0L

        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val next = (tv.scaleX * detector.scaleFactor).coerceIn(0.4f, 4.5f)
                    tv.scaleX = next
                    tv.scaleY = next
                    return true
                }
            }
        )

        tv.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    v.bringToFront()
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 280L) {
                        onTextDoubleTap?.invoke(tv)
                    }
                    lastTapTime = now
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        isRotating = true
                        lastAngle = pointerAngle(event)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = event.rawX - lastX
                        val dy = event.rawY - lastY
                        v.translationX += dx
                        v.translationY += dy
                        lastX = event.rawX
                        lastY = event.rawY
                    } else if (event.pointerCount >= 2 && isRotating) {
                        val angle = pointerAngle(event)
                        v.rotation += (angle - lastAngle)
                        lastAngle = angle
                    }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isRotating = false
                }
            }
            true
        }
        return tv
    }

    private fun pointerAngle(event: MotionEvent): Float {
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    companion object {
        fun applyStyle(tv: TextView, color: Int, style: TextStyle) {
            when (style) {
                TextStyle.OUTLINED -> {
                    tv.setTextColor(color)
                    tv.background = null
                    tv.setShadowLayer(6f, 0f, 2f, Color.BLACK)
                }
                TextStyle.FILLED -> {
                    val isLight = (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) > 165
                    tv.setTextColor(if (isLight) Color.BLACK else Color.WHITE)
                    tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    tv.background = GradientDrawable().apply {
                        setColor(color)
                        cornerRadius = 12f * tv.resources.displayMetrics.density
                    }
                }
                TextStyle.REGULAR -> {
                    tv.setTextColor(color)
                    tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    tv.background = GradientDrawable().apply {
                        setColor(0x99000000.toInt())
                        cornerRadius = 12f * tv.resources.displayMetrics.density
                    }
                }
            }
        }
    }
}
