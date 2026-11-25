package com.telephoto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities

/**
 * Interactive editor preview supporting:
 * - Pinch-to-Zoom (1.0x .. 5.0x) and pan while zoomed
 * - Double-tap to toggle 1.0x <-> 2.5x zoom
 * - Hold-to-Compare (long press when unzoomed or external Compare button hold) to preview Original photo
 */
class ZoomableCompareImageView(context: Context) : FrameLayout(context) {

    private val imageView: ImageView
    private val badgeView: TextView

    private var editedBitmap: Bitmap? = null
    private var originalBitmap: Bitmap? = null
    private var comparing = false
    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isPanning = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setComparing(false)
                zoomScale = (zoomScale * detector.scaleFactor).coerceIn(1f, 5f)
                clampPan()
                applyTransform()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                setComparing(false)
                if (zoomScale > 1.05f) {
                    resetZoom()
                } else {
                    zoomScale = 2.5f
                    applyTransform()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (zoomScale <= 1.05f && originalBitmap != null && originalBitmap !== editedBitmap) {
                    setComparing(true)
                }
            }
        }
    )

    init {
        clipChildren = true
        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        addView(imageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        badgeView = TextView(context).apply {
            text = AppLocale.t("original")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(6f), AndroidUtilities.dp(14f), AndroidUtilities.dp(6f))
            background = GradientDrawable().apply {
                setColor(0xBB000000.toInt())
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
        }
        addView(
            badgeView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = AndroidUtilities.dp(12f)
            }
        )
    }

    fun setBitmaps(edited: Bitmap?, original: Bitmap?) {
        editedBitmap = edited
        originalBitmap = original
        comparing = false
        badgeView.visibility = View.GONE
        badgeView.text = AppLocale.t("original")
        imageView.setImageBitmap(edited)
        resetZoom()
    }

    fun setComparing(showOriginal: Boolean) {
        val canCompare = originalBitmap != null && editedBitmap != null
        comparing = showOriginal && canCompare
        imageView.setImageBitmap(if (comparing) originalBitmap else editedBitmap)
        badgeView.visibility = if (comparing) View.VISIBLE else View.GONE
    }

    fun resetZoom() {
        zoomScale = 1f
        panX = 0f
        panY = 0f
        applyTransform()
    }

    private fun clampPan() {
        val maxDx = (width * (zoomScale - 1f)) / 2f
        val maxDy = (height * (zoomScale - 1f)) / 2f
        panX = panX.coerceIn(-maxDx, maxDx)
        panY = panY.coerceIn(-maxDy, maxDy)
    }

    private fun applyTransform() {
        imageView.scaleX = zoomScale
        imageView.scaleY = zoomScale
        imageView.translationX = panX
        imageView.translationY = panY
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isPanning = zoomScale > 1.02f
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning && !scaleDetector.isInProgress && event.pointerCount == 1 && zoomScale > 1.02f) {
                    panX += event.x - lastX
                    panY += event.y - lastY
                    clampPan()
                    applyTransform()
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPanning = false
                if (comparing) {
                    setComparing(false)
                }
            }
        }
        return true
    }
}
