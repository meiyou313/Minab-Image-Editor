package com.telephoto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import org.telegram.messenger.AndroidUtilities
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Telegram-identical on-canvas inline editable Text overlay container (frames 01:01 - 01:40).
 * Features:
 * - Inline EditText directly on canvas (supports Persian RTL & English)
 * - Dashed rounded selection frame + left/right blue-bordered white circular handles
 * - 4 Telegram text styles: Filled, Outlined, Semi-transparent Pill, Regular
 * - 3 alignments (Center / Left / Right) & 6 Telegram fonts
 * - Single-finger drag, pinch scale, two-finger rotate, and handle drag scale/rotate
 */
class TextOverlayContainer(context: Context) : FrameLayout(context) {

    enum class TextStyle { FILLED, OUTLINED, TRANSLUCENT, REGULAR }

    enum class FontChoice(val displayName: String, val typeface: Typeface) {
        ROBOTO("Roboto", Typeface.create("sans-serif", Typeface.BOLD)),
        ITALIC("Italic", Typeface.create("sans-serif", Typeface.BOLD_ITALIC)),
        SERIF("Serif", Typeface.create("serif", Typeface.BOLD)),
        CONDENSED("Condensed", Typeface.create("sans-serif-condensed", Typeface.BOLD)),
        MONO("Mono-serif", Typeface.create("monospace", Typeface.BOLD)),
        MERRIWEATHER("Merriweather", Typeface.create("serif", Typeface.NORMAL))
    }

    var onEntitySelected: ((TelegramTextEntityView?) -> Unit)? = null
    var onEditingChanged: ((Boolean) -> Unit)? = null
    var interactionEnabled: Boolean = true

    var selectedEntity: TelegramTextEntityView? = null
        private set

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!interactionEnabled) return false
        return super.dispatchTouchEvent(ev)
    }

    fun isAnyEditing(): Boolean = selectedEntity?.isInlineEditing == true

    fun deleteSelectedEntity() {
        val target = selectedEntity ?: return
        target.endInlineEditing()
        selectedEntity = null
        removeView(target)
        onEditingChanged?.invoke(false)
        onEntitySelected?.invoke(null)
    }

    fun addInlineText(
        color: Int = Color.WHITE,
        style: TextStyle = TextStyle.FILLED,
        font: FontChoice = FontChoice.ROBOTO,
        alignment: Int = Gravity.CENTER
    ): TelegramTextEntityView {
        val entity = TelegramTextEntityView(context).apply {
            textColorValue = color
            textStyle = style
            fontChoice = font
            textAlignmentGravity = alignment
            applyVisuals()
        }
        val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(entity, lp)
        selectEntity(entity, openKeyboard = true)
        return entity
    }

    fun selectEntity(entity: TelegramTextEntityView?, openKeyboard: Boolean = false) {
        if (selectedEntity !== entity) {
            selectedEntity?.isSelectedEntity = false
            selectedEntity?.endInlineEditing()
            selectedEntity = entity
        }
        entity?.let {
            it.bringToFront()
            it.isSelectedEntity = true
            if (openKeyboard) {
                it.beginInlineEditing()
            }
        }
        onEntitySelected?.invoke(entity)
    }

    fun finishInlineEditing() {
        selectedEntity?.endInlineEditing()
        // Remove empty placeholder boxes if user left them blank
        val toRemove = mutableListOf<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TelegramTextEntityView ?: continue
            if (child.editText.text?.toString()?.trim().isNullOrEmpty()) {
                toRemove.add(child)
            }
        }
        toRemove.forEach {
            if (it === selectedEntity) selectedEntity = null
            removeView(it)
        }
        onEditingChanged?.invoke(false)
    }

    fun clearActiveOrAll(onlyActiveIfEditing: Boolean) {
        val active = selectedEntity
        if (onlyActiveIfEditing && active != null && active.isInlineEditing) {
            active.editText.setText("")
            return
        }
        selectedEntity = null
        removeAllViews()
        onEntitySelected?.invoke(null)
    }

    fun hasTextEntities(): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TelegramTextEntityView ?: continue
            if (!child.editText.text?.toString()?.trim().isNullOrEmpty()) return true
        }
        return false
    }

    fun deselectAll() {
        finishInlineEditing()
        selectedEntity?.isSelectedEntity = false
        selectedEntity = null
        onEntitySelected?.invoke(null)
    }

    fun renderOntoBitmap(target: Bitmap) {
        if (!hasTextEntities() || width <= 0 || height <= 0) return
        val prevSelected = selectedEntity
        for (i in 0 until childCount) {
            (getChildAt(i) as? TelegramTextEntityView)?.apply {
                isSelectedEntity = false
                editText.isCursorVisible = false
                if (editText.text?.toString()?.trim().isNullOrEmpty()) {
                    visibility = View.INVISIBLE
                }
            }
        }
        val canvas = Canvas(target)
        val sx = target.width.toFloat() / width.toFloat()
        val sy = target.height.toFloat() / height.toFloat()
        canvas.save()
        canvas.scale(sx, sy)
        draw(canvas)
        canvas.restore()

        for (i in 0 until childCount) {
            (getChildAt(i) as? TelegramTextEntityView)?.visibility = View.VISIBLE
        }
        prevSelected?.isSelectedEntity = true
    }

    fun renderTransparentOverlayBitmap(targetWidth: Int, targetHeight: Int): Bitmap? {
        if (!hasTextEntities() || width <= 0 || height <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return null
        }
        val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        renderOntoBitmap(out)
        return out
    }

    fun exportEntitySnapshots(): List<EditLayerState.TextEntitySnapshot> {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val list = mutableListOf<EditLayerState.TextEntitySnapshot>()
        for (i in 0 until childCount) {
            val child = getChildAt(i) as? TelegramTextEntityView ?: continue
            val txt = child.editText.text?.toString()?.trim() ?: ""
            if (txt.isEmpty()) continue
            list.add(
                EditLayerState.TextEntitySnapshot(
                    text = txt,
                    color = child.textColorValue,
                    style = child.textStyle,
                    font = child.fontChoice,
                    alignment = child.textAlignmentGravity,
                    baseTextSizeSp = child.baseTextSizeSp,
                    normOffsetX = child.translationX / w,
                    normOffsetY = child.translationY / h,
                    scale = child.scaleX,
                    rotation = child.rotation
                )
            )
        }
        return list
    }

    fun restoreEntitySnapshots(snapshots: List<EditLayerState.TextEntitySnapshot>) {
        clearActiveOrAll(false)
        if (snapshots.isEmpty()) return
        val applyTranslations = {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            for (i in 0 until minOf(childCount, snapshots.size)) {
                val child = getChildAt(i) as? TelegramTextEntityView ?: continue
                val snap = snapshots[i]
                child.translationX = snap.normOffsetX * w
                child.translationY = snap.normOffsetY * h
            }
        }
        snapshots.forEach { snap ->
            val entity = TelegramTextEntityView(context).apply {
                textColorValue = snap.color
                textStyle = snap.style
                fontChoice = snap.font
                textAlignmentGravity = snap.alignment
                baseTextSizeSp = snap.baseTextSizeSp
                editText.setText(snap.text)
                scaleX = snap.scale
                scaleY = snap.scale
                rotation = snap.rotation
                applyVisuals()
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            addView(entity, lp)
        }
        if (width > 0 && height > 0) {
            applyTranslations()
        } else {
            addOnLayoutChangeListener(object : OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View?, l: Int, t: Int, r: Int, b: Int,
                    ol: Int, ot: Int, or: Int, ob: Int
                ) {
                    if (r - l > 0 && b - t > 0) {
                        removeOnLayoutChangeListener(this)
                        applyTranslations()
                    }
                }
            })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class TelegramTextEntityView(context: Context) : FrameLayout(context) {

        val editText: EditText
        var textColorValue: Int = Color.WHITE
        var textStyle: TextStyle = TextStyle.FILLED
        var fontChoice: FontChoice = FontChoice.ROBOTO
        var textAlignmentGravity: Int = Gravity.CENTER
        var baseTextSizeSp: Float = 28f
        var isInlineEditing: Boolean = false
            private set

        var isSelectedEntity: Boolean = false
            set(value) {
                field = value
                invalidate()
            }

        private val frameInset = AndroidUtilities.dp(10f).toFloat()
        private val handleRadius = AndroidUtilities.dp(5.5f).toFloat()
        private val selectionRect = RectF()

        private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = AndroidUtilities.dp(1.6f).toFloat()
            pathEffect = DashPathEffect(
                floatArrayOf(AndroidUtilities.dp(8f).toFloat(), AndroidUtilities.dp(5f).toFloat()),
                0f
            )
        }
        private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF2AABEE.toInt()
        }
        private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = AndroidUtilities.dp(1.8f).toFloat()
        }

        private var lastRawX = 0f
        private var lastRawY = 0f
        private var lastAngle = 0f
        private var isRotating = false
        private var draggingHandle = false
        private var handleStartDist = 1f
        private var handleStartScale = 1f
        private var handleStartAngle = 0f
        private var handleStartRotation = 0f
        private var moved = false
        private var downTime = 0L

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val next = (scaleX * detector.scaleFactor).coerceIn(0.35f, 4.5f)
                    scaleX = next
                    scaleY = next
                    return true
                }
            }
        )

        init {
            setWillNotDraw(false)
            clipChildren = false
            clipToPadding = false
            val pad = AndroidUtilities.dp(14f)
            setPadding(pad, pad, pad, pad)

            editText = EditText(context).apply {
                hint = "Add text"
                setHintTextColor(0x99FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp)
                gravity = Gravity.CENTER
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                maxWidth = (AndroidUtilities.displaySize.x * 0.82f).toInt().coerceAtLeast(AndroidUtilities.dp(220f))
                minWidth = AndroidUtilities.dp(90f)
                setPadding(
                    AndroidUtilities.dp(14f),
                    AndroidUtilities.dp(7f),
                    AndroidUtilities.dp(14f),
                    AndroidUtilities.dp(7f)
                )
                isFocusable = false
                isFocusableInTouchMode = false
                isCursorVisible = false
                background = null
            }
            addView(editText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            applyVisuals()
        }

        fun applyVisuals() {
            editText.typeface = fontChoice.typeface
            editText.gravity = textAlignmentGravity
            editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseTextSizeSp)

            when (textStyle) {
                TextStyle.FILLED -> {
                    val isLight = isLightColor(textColorValue)
                    editText.setTextColor(if (isLight) Color.BLACK else Color.WHITE)
                    editText.setHintTextColor(if (isLight) 0x66000000 else 0x88FFFFFF.toInt())
                    editText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    editText.background = GradientDrawable().apply {
                        setColor(textColorValue)
                        cornerRadius = AndroidUtilities.dp(12f).toFloat()
                    }
                }
                TextStyle.OUTLINED -> {
                    editText.setTextColor(textColorValue)
                    editText.setHintTextColor(0x88FFFFFF.toInt())
                    editText.background = null
                    editText.setShadowLayer(
                        AndroidUtilities.dp(4f).toFloat(),
                        0f,
                        AndroidUtilities.dp(1f).toFloat(),
                        if (isLightColor(textColorValue)) Color.BLACK else Color.WHITE
                    )
                }
                TextStyle.TRANSLUCENT -> {
                    editText.setTextColor(textColorValue)
                    editText.setHintTextColor(0x88FFFFFF.toInt())
                    editText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                    editText.background = GradientDrawable().apply {
                        setColor(0x99000000.toInt())
                        cornerRadius = AndroidUtilities.dp(12f).toFloat()
                    }
                }
                TextStyle.REGULAR -> {
                    editText.setTextColor(textColorValue)
                    editText.setHintTextColor(0x88FFFFFF.toInt())
                    editText.background = null
                    editText.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                }
            }
            invalidate()
        }

        fun beginInlineEditing() {
            isInlineEditing = true
            editText.isFocusable = true
            editText.isFocusableInTouchMode = true
            editText.isCursorVisible = true
            editText.requestFocus()
            editText.setSelection(editText.text?.length ?: 0)
            post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
            onEditingChanged?.invoke(true)
        }

        fun endInlineEditing() {
            if (!isInlineEditing) return
            isInlineEditing = false
            editText.isCursorVisible = false
            editText.clearFocus()
            editText.isFocusable = false
            editText.isFocusableInTouchMode = false
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(editText.windowToken, 0)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (isSelectedEntity) {
                selectionRect.set(
                    frameInset,
                    frameInset,
                    width - frameInset,
                    height - frameInset
                )
                val corner = AndroidUtilities.dp(10f).toFloat()
                canvas.drawRoundRect(selectionRect, corner, corner, dashPaint)

                val cy = height / 2f
                // Left blue handle
                canvas.drawCircle(frameInset, cy, handleRadius, handleFillPaint)
                canvas.drawCircle(frameInset, cy, handleRadius, handleStrokePaint)
                // Right blue handle
                canvas.drawCircle(width - frameInset, cy, handleRadius, handleFillPaint)
                canvas.drawCircle(width - frameInset, cy, handleRadius, handleStrokePaint)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            return !isInlineEditing
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isInlineEditing) return super.onTouchEvent(event)

            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectEntity(this, openKeyboard = false)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    moved = false
                    downTime = System.currentTimeMillis()

                    val cy = height / 2f
                    val hitTol = AndroidUtilities.dp(24f).toFloat()
                    val nearLeft = hypot((event.x - frameInset).toDouble(), (event.y - cy).toDouble()) < hitTol
                    val nearRight = hypot((event.x - (width - frameInset)).toDouble(), (event.y - cy).toDouble()) < hitTol
                    if (isSelectedEntity && (nearLeft || nearRight)) {
                        draggingHandle = true
                        val loc = IntArray(2)
                        getLocationOnScreen(loc)
                        val cx = loc[0] + (width * scaleX) / 2f
                        val centerY = loc[1] + (height * scaleY) / 2f
                        handleStartDist = max(20f, hypot((event.rawX - cx).toDouble(), (event.rawY - centerY).toDouble()).toFloat())
                        handleStartScale = scaleX
                        handleStartAngle = Math.toDegrees(atan2((event.rawY - centerY).toDouble(), (event.rawX - cx).toDouble())).toFloat()
                        handleStartRotation = rotation
                    } else {
                        draggingHandle = false
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount >= 2) {
                        draggingHandle = false
                        isRotating = true
                        lastAngle = pointerAngle(event)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingHandle && event.pointerCount == 1) {
                        moved = true
                        val loc = IntArray(2)
                        getLocationOnScreen(loc)
                        val cx = loc[0] + (width * scaleX) / 2f
                        val centerY = loc[1] + (height * scaleY) / 2f
                        val dist = max(20f, hypot((event.rawX - cx).toDouble(), (event.rawY - centerY).toDouble()).toFloat())
                        val nextScale = (handleStartScale * (dist / handleStartDist)).coerceIn(0.35f, 4.5f)
                        scaleX = nextScale
                        scaleY = nextScale
                        val curAngle = Math.toDegrees(atan2((event.rawY - centerY).toDouble(), (event.rawX - cx).toDouble())).toFloat()
                        rotation = handleStartRotation + (curAngle - handleStartAngle)
                    } else if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        if (hypot(dx.toDouble(), dy.toDouble()) > AndroidUtilities.dp(3f)) {
                            moved = true
                        }
                        translationX += dx
                        translationY += dy
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    } else if (event.pointerCount >= 2 && isRotating) {
                        moved = true
                        val angle = pointerAngle(event)
                        rotation += (angle - lastAngle)
                        lastAngle = angle
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && System.currentTimeMillis() - downTime < 280L) {
                        beginInlineEditing()
                    }
                    draggingHandle = false
                    isRotating = false
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_UP -> {
                    draggingHandle = false
                    isRotating = false
                }
            }
            return true
        }

        private fun pointerAngle(event: MotionEvent): Float {
            val dx = event.getX(1) - event.getX(0)
            val dy = event.getY(1) - event.getY(0)
            return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        }

        private fun isLightColor(color: Int): Boolean {
            val luma = Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114
            return luma > 165
        }
    }
}
