package com.telephoto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.R
import org.telegram.ui.Components.Paint.Brush
import org.telegram.ui.Components.Paint.Painting
import org.telegram.ui.Components.Paint.PersistColorPalette
import org.telegram.ui.Components.Paint.RenderView
import org.telegram.ui.Components.Paint.UndoStore
import org.telegram.ui.Components.Size
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
class TelegramPaintPanel(
    context: Context,
    private val sourceBitmap: Bitmap,
    private val onCancel: () -> Unit,
    private val onDone: (Bitmap) -> Unit
) : FrameLayout(context) {

    private data class BrushEntry(val label: String, val brush: Brush)

    private val brushes = listOf(
        BrushEntry("Pen", Brush.BRUSHES_LIST[0]),
        BrushEntry("Arrow", Brush.BRUSHES_LIST[1]),
        BrushEntry("Marker", Brush.BRUSHES_LIST[2]),
        BrushEntry("Neon", Brush.BRUSHES_LIST[3]),
        BrushEntry("Blur", Brush.BRUSHES_LIST[4]),
        BrushEntry("Eraser", Brush.BRUSHES_LIST[5]),
        BrushEntry("Circle", Brush.Shape.make(Brush.Shape.SHAPE_TYPE_CIRCLE)),
        BrushEntry("Rect", Brush.Shape.make(Brush.Shape.SHAPE_TYPE_RECTANGLE)),
        BrushEntry("Star", Brush.Shape.make(Brush.Shape.SHAPE_TYPE_STAR))
    )

    private val paletteColors = intArrayOf(
        PersistColorPalette.COLOR_WHITE,
        PersistColorPalette.COLOR_BLACK,
        PersistColorPalette.COLOR_RED,
        PersistColorPalette.COLOR_ORANGE,
        PersistColorPalette.COLOR_YELLOW,
        PersistColorPalette.COLOR_GREEN,
        PersistColorPalette.COLOR_BLUE,
        PersistColorPalette.COLOR_DARK_BLUE,
        PersistColorPalette.COLOR_VIOLET
    )

    private val paintQueue = DispatchQueue("TelegramPaintQueue")
    private val undoStore = UndoStore()
    private val exportExecutor = Executors.newSingleThreadExecutor()

    private val stageContainer: FrameLayout
    private val canvasWrapper: FrameLayout
    private val baseImageView: ImageView
    private val renderView: RenderView
    private val shapeOverlayView: View
    private val textOverlay: TextOverlayContainer

    private val undoButton: TextView
    private val brushChipViews = mutableListOf<TextView>()
    private val colorDotViews = mutableListOf<View>()
    private val sizeSeekBar: SeekBar

    private var selectedBrushIndex = 0
    private var selectedColor = PersistColorPalette.COLOR_RED
    private var brushWeight = 0.25f

    init {
        setBackgroundColor(Color.BLACK)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Top utility bar (Undo / Clear / Add Text)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xE6121212.toInt())
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
        }

        undoButton = makeSmallButton(context.getString(R.string.action_undo), Color.WHITE) {
            if (undoStore.canUndo()) {
                undoStore.undo()
            }
        }.apply {
            alpha = 0.4f
            isEnabled = false
        }

        val clearButton = makeSmallButton(context.getString(R.string.action_clear), 0xFFFF8A80.toInt()) {
            renderView.clearAll()
            textOverlay.clearAll()
            undoStore.reset()
        }

        val addTextButton = makeSmallButton("+ " + context.getString(R.string.action_add_text), 0xFF4DA3FF.toInt()) {
            showAddTextDialog(null)
        }

        topBar.addView(undoButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(40f), 1f))
        topBar.addView(clearButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(40f), 1f))
        topBar.addView(addTextButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(40f), 1f))
        root.addView(topBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Painting canvas stage
        stageContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }
        root.addView(stageContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        canvasWrapper = FrameLayout(context)
        stageContainer.addView(
            canvasWrapper,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )

        baseImageView = ImageView(context).apply {
            setImageBitmap(sourceBitmap)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        canvasWrapper.addView(baseImageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val maxSide = 1600f
        val scale = min(1f, maxSide / max(sourceBitmap.width, sourceBitmap.height).toFloat())
        val paintSize = Size(
            (sourceBitmap.width * scale).roundToInt().coerceAtLeast(64).toFloat(),
            (sourceBitmap.height * scale).roundToInt().coerceAtLeast(64).toFloat()
        )

        val painting = Painting(paintSize, sourceBitmap, 0, null)
        val blurW = (paintSize.width / 4f).roundToInt().coerceAtLeast(32)
        val blurH = (paintSize.height / 4f).roundToInt().coerceAtLeast(32)
        val blurSource = Bitmap.createScaledBitmap(sourceBitmap, blurW, blurH, true)
            .let { if (it.config == Bitmap.Config.ARGB_8888 && it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
        org.telegram.messenger.Utilities.stackBlurBitmap(blurSource, 16)

        renderView = RenderView(context, painting, sourceBitmap, blurSource, null).apply {
            isOpaque = false
            setQueue(paintQueue)
            setUndoStore(undoStore)
        }
        canvasWrapper.addView(renderView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        shapeOverlayView = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                renderView.onDrawForInput(canvas)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                val handled = renderView.onTouch(event)
                invalidate()
                return handled
            }
        }
        canvasWrapper.addView(shapeOverlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        textOverlay = TextOverlayContainer(context).apply {
            onTextDoubleTap = { tv -> showAddTextDialog(tv) }
        }
        canvasWrapper.addView(textOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        renderView.setDelegate(object : RenderView.RenderViewDelegate {
            override fun onBeganDrawing() {}
            override fun onFinishedDrawing(moved: Boolean) {
                shapeOverlayView.invalidate()
            }
            override fun onFirstDraw() {}
            override fun shouldDraw(): Boolean = true
            override fun invalidateInputView() {
                shapeOverlayView.invalidate()
            }
            override fun resetBrush() {
                post { selectBrush(0) }
            }
        })

        undoStore.setDelegate {
            post {
                val can = undoStore.canUndo()
                undoButton.isEnabled = can
                undoButton.alpha = if (can) 1f else 0.4f
            }
        }

        // Bottom controls (Size + Colors + Brushes + Cancel/Done)
        val bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
        }
        root.addView(bottomPanel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Size SeekBar
        val sizeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(6f), AndroidUtilities.dp(16f), AndroidUtilities.dp(2f))
        }
        val sizeLabel = TextView(context).apply {
            text = "Size"
            setTextColor(0xFFB0B0B0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        sizeSeekBar = SeekBar(context).apply {
            max = 100
            progress = 25
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    brushWeight = 0.05f + (progress / 100f) * 0.95f
                    applyBrushSize()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(AndroidUtilities.dp(40f), LayoutParams.WRAP_CONTENT))
        sizeRow.addView(sizeSeekBar, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bottomPanel.addView(sizeRow)

        // Color Palette Row
        val colorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
        }
        paletteColors.forEachIndexed { idx, color ->
            val dot = View(context).apply {
                setOnClickListener { selectColor(idx) }
            }
            val size = AndroidUtilities.dp(26f)
            val lp = LinearLayout.LayoutParams(size, size).apply {
                setMargins(AndroidUtilities.dp(5f), 0, AndroidUtilities.dp(5f), 0)
            }
            colorDotViews.add(dot)
            colorRow.addView(dot, lp)
        }
        bottomPanel.addView(colorRow)

        // Brushes Horizontal Scroll
        val brushScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(6f))
        }
        val brushRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        brushes.forEachIndexed { idx, entry ->
            val chip = TextView(context).apply {
                text = entry.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(7f), AndroidUtilities.dp(12f), AndroidUtilities.dp(7f))
                setOnClickListener { selectBrush(idx) }
            }
            val lp = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
            }
            brushChipViews.add(chip)
            brushRow.addView(chip, lp)
        }
        brushScroll.addView(brushRow)
        bottomPanel.addView(brushScroll)

        // Cancel / Done
        val actionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(4f), AndroidUtilities.dp(16f), AndroidUtilities.dp(10f))
        }
        val cancelBtn = makeSmallButton(context.getString(R.string.action_cancel), 0xFFE0E0E0.toInt()) {
            cleanup()
            onCancel()
        }
        val doneBtn = makeSmallButton(context.getString(R.string.action_done), 0xFF4DA3FF.toInt(), bold = true) {
            bakeAndFinish()
        }
        actionBar.addView(cancelBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(46f), 1f))
        actionBar.addView(doneBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(46f), 1f))
        bottomPanel.addView(actionBar)

        stageContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            layoutCanvasToAspect(right - left, bottom - top)
        }

        selectColor(2) // Telegram default Red
        selectBrush(0) // Pen
    }

    private fun layoutCanvasToAspect(availW: Int, availH: Int) {
        if (availW <= 0 || availH <= 0) return
        val bw = sourceBitmap.width.toFloat().coerceAtLeast(1f)
        val bh = sourceBitmap.height.toFloat().coerceAtLeast(1f)
        val scale = min(availW / bw, availH / bh)
        val targetW = (bw * scale).roundToInt().coerceAtLeast(1)
        val targetH = (bh * scale).roundToInt().coerceAtLeast(1)
        val lp = canvasWrapper.layoutParams as LayoutParams
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW
            lp.height = targetH
            lp.gravity = Gravity.CENTER
            canvasWrapper.layoutParams = lp
        }
    }

    private fun selectBrush(index: Int) {
        selectedBrushIndex = index
        val brush = brushes[index].brush
        renderView.setBrush(brush)
        applyBrushSize()
        brushChipViews.forEachIndexed { idx, chip ->
            val selected = idx == index
            chip.setTextColor(if (selected) Color.BLACK else 0xFFD0D0D0.toInt())
            chip.background = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(16f).toFloat()
                setColor(if (selected) 0xFF4DA3FF.toInt() else 0xFF242424.toInt())
            }
        }
        shapeOverlayView.invalidate()
    }

    private fun selectColor(index: Int) {
        selectedColor = paletteColors[index]
        renderView.setColor(selectedColor)
        colorDotViews.forEachIndexed { idx, dot ->
            val selected = idx == index
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(paletteColors[idx])
                setStroke(
                    AndroidUtilities.dp(if (selected) 3f else 1f),
                    if (selected) 0xFF4DA3FF.toInt() else 0x66FFFFFF
                )
            }
        }
    }

    private fun applyBrushSize() {
        val painting = renderView.painting ?: return
        val base = min(painting.size.width, painting.size.height)
        val sizePx = (base * 0.015f) + (base * 0.11f * brushWeight)
        renderView.setBrushSize(sizePx)
    }

    private fun showAddTextDialog(existingView: TextView?) {
        val input = EditText(context).apply {
            hint = context.getString(R.string.enter_text_hint)
            setText(existingView?.text ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF999999.toInt())
        }
        var currentStyle = TextOverlayContainer.TextStyle.OUTLINED
        val styleButton = TextView(context).apply {
            text = "Style: Outlined (tap to switch)"
            setTextColor(0xFF4DA3FF.toInt())
            setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(4f))
            setOnClickListener {
                currentStyle = when (currentStyle) {
                    TextOverlayContainer.TextStyle.OUTLINED -> TextOverlayContainer.TextStyle.FILLED
                    TextOverlayContainer.TextStyle.FILLED -> TextOverlayContainer.TextStyle.REGULAR
                    TextOverlayContainer.TextStyle.REGULAR -> TextOverlayContainer.TextStyle.OUTLINED
                }
                text = "Style: ${currentStyle.name.lowercase().replaceFirstChar { c -> c.uppercase() }} (tap to switch)"
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = AndroidUtilities.dp(20f)
            setPadding(pad, AndroidUtilities.dp(12f), pad, AndroidUtilities.dp(8f))
            addView(input)
            addView(styleButton)
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.add_text_title))
            .setView(container)
            .setPositiveButton(context.getString(R.string.action_done)) { _, _ ->
                val txt = input.text?.toString()?.trim().orEmpty()
                if (txt.isNotEmpty()) {
                    if (existingView != null) {
                        existingView.text = txt
                        TextOverlayContainer.applyStyle(existingView, selectedColor, currentStyle)
                    } else {
                        textOverlay.addTextEntity(txt, selectedColor, currentStyle)
                    }
                }
            }
            .setNegativeButton(context.getString(R.string.action_cancel), null)

        if (existingView != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                textOverlay.removeView(existingView)
            }
        }
        builder.show()
    }

    private fun bakeAndFinish() {
        exportExecutor.execute {
            val paintOverlayBitmap = renderView.getResultBitmap(false, true)
            post {
                val result = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(result)
                if (paintOverlayBitmap != null) {
                    val dst = Rect(0, 0, result.width, result.height)
                    canvas.drawBitmap(
                        paintOverlayBitmap,
                        null,
                        dst,
                        Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                    )
                    paintOverlayBitmap.recycle()
                }
                if (textOverlay.hasTextEntities()) {
                    textOverlay.renderOntoBitmap(result)
                }
                cleanup()
                onDone(result)
            }
        }
    }

    fun cleanup() {
        renderView.shutdown()
        paintQueue.recycle()
        exportExecutor.shutdownNow()
    }

    private fun makeSmallButton(label: String, color: Int, bold: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (bold) typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }
}
