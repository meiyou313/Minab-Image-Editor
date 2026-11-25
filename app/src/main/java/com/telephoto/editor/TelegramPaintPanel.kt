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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DispatchQueue
import org.telegram.messenger.R
import org.telegram.messenger.UserConfig
import org.telegram.ui.Components.Paint.Brush
import org.telegram.ui.Components.Paint.Painting
import org.telegram.ui.Components.Paint.PersistColorPalette
import org.telegram.ui.Components.Paint.RenderView
import org.telegram.ui.Components.Paint.Swatch
import org.telegram.ui.Components.Paint.UndoStore
import org.telegram.ui.Components.Paint.Views.PaintWeightChooserView
import org.telegram.ui.Components.Size
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Non-destructive GPU Paint & Text mode (Minab Image Editor):
 * - Preserves strokes, arrows, shapes, mosaic, per-stroke Undo history, and live Text entities
 *   as an independent transparent Layer 3 across Filters/Tone switches until final Save!
 * - Re-entering Draw/Text lets you Undo previous arrows/strokes, Erase them cleanly without
 *   affecting the photo, move/edit/delete Text boxes, or Clear All!
 */
@SuppressLint("ClickableViewAccessibility")
class TelegramPaintPanel(
    context: Context,
    private val sourceBitmap: Bitmap,
    initialPaintState: EditLayerState.PaintLayerState? = null,
    private val onCancel: () -> Unit,
    private val onDone: (EditLayerState.PaintLayerState) -> Unit
) : FrameLayout(context) {

    private enum class PaintTab { DRAW, TEXT }

    private data class BrushSlot(val iconRes: Int, val brush: Brush, val isMosaic: Boolean = false)

    private val brushSlots = listOf(
        BrushSlot(R.drawable.ic_tg_brush_pen, Brush.BRUSHES_LIST[0]),
        BrushSlot(R.drawable.ic_tg_brush_arrow, Brush.BRUSHES_LIST[1]),
        BrushSlot(R.drawable.ic_tg_brush_marker, Brush.BRUSHES_LIST[2]),
        BrushSlot(R.drawable.ic_tg_brush_neon, Brush.BRUSHES_LIST[3]),
        BrushSlot(R.drawable.ic_tg_brush_blur, Brush.BRUSHES_LIST[4], isMosaic = false),
        BrushSlot(R.drawable.ic_tg_brush_mosaic, Brush.BRUSHES_LIST[4], isMosaic = true),
        BrushSlot(R.drawable.ic_tg_brush_eraser, Brush.BRUSHES_LIST[5])
    )

    private data class ShapeOption(val label: String, val iconRes: Int, val shapeType: Int)

    private val shapeOptions = listOf(
        ShapeOption("Circle", R.drawable.photo_circle, Brush.Shape.SHAPE_TYPE_CIRCLE),
        ShapeOption("Rectangle", R.drawable.photo_rectangle, Brush.Shape.SHAPE_TYPE_RECTANGLE),
        ShapeOption("Star", R.drawable.photo_star, Brush.Shape.SHAPE_TYPE_STAR),
        ShapeOption("Bubble", R.drawable.msg_msgbubble, Brush.Shape.SHAPE_TYPE_BUBBLE),
        ShapeOption("Arrow", R.drawable.photo_arrowshape, Brush.Shape.SHAPE_TYPE_ARROW)
    )

    private val brushWeights = FloatArray(brushSlots.size) { idx ->
        brushSlots[idx].brush.defaultWeight.coerceIn(0.05f, 1.0f)
    }

    private val paintQueue = DispatchQueue("TelegramPaintQueue")
    private val undoStore = UndoStore()
    private val exportExecutor = Executors.newSingleThreadExecutor()

    // Cross-visit non-destructive stroke undo history (compact lossless PNG byte arrays; empty array = blank layer)
    private val historyLock = Any()
    private val persistedUndoStack = mutableListOf<ByteArray>()
    private val currentSessionSnapshots = mutableListOf<ByteArray>()
    @Volatile
    private var isClosing = false

    private val stageContainer: FrameLayout
    private val canvasWrapper: FrameLayout
    private val baseImageView: ImageView
    private val renderView: RenderView
    private val shapeOverlayView: View
    private val textOverlay: TextOverlayContainer
    private val weightChooserView: PaintWeightChooserView

    // Top bar
    private val topBarNormal: LinearLayout
    private val topBarTyping: LinearLayout
    private val undoIconBtn: ImageView
    private val clearAllBtn: TextView

    // Active Shape mini floating controls
    private val shapeActionBar: LinearLayout
    private val shapeFillToggleBtn: TextView

    // Eyedropper mode banner & floating color loupe
    private val eyedropperBanner: TextView
    private val eyedropperLoupe: View
    private var eyedropperActive = false

    // Bottom bars
    private val drawPillRow: LinearLayout
    private val textPillRow: LinearLayout
    private val colorPaletteBar: TelegramColorPaletteBar
    private val bottomTabsRow: LinearLayout

    // Controls inside Draw & Text bars
    private val drawColorRing: TelegramColorRingView
    private val textColorRing: TelegramColorRingView
    private val brushButtons = mutableListOf<ImageView>()
    private val alignBtn: ImageView
    private val styleBtn: ImageView
    private val fontPillText: TextView
    private val drawTabLabel: TextView
    private val textTabLabel: TextView

    // Floating popups
    private val popupBackdrop: FrameLayout
    private val shapePopupCard: LinearLayout
    private val fontPopupCard: LinearLayout

    private var activeTab = PaintTab.DRAW
    private var paletteVisible = false
    private var selectedBrushIndex = 0
    private var selectedColor = PersistColorPalette.COLOR_RED
    private var currentSwatch = Swatch(PersistColorPalette.COLOR_RED, 0.5f, brushWeights[0])
    private var currentTextStyle = TextOverlayContainer.TextStyle.FILLED
    private var currentTextFont = TextOverlayContainer.FontChoice.ROBOTO
    private var currentTextAlign = Gravity.CENTER

    init {
        setBackgroundColor(Color.BLACK)

        if (initialPaintState != null) {
            synchronized(historyLock) {
                persistedUndoStack.addAll(initialPaintState.strokeUndoPngStack)
                val initialBytes = initialPaintState.strokeLayerPng ?: ByteArray(0)
                currentSessionSnapshots.add(initialBytes)
            }
        } else {
            synchronized(historyLock) {
                currentSessionSnapshots.add(ByteArray(0))
            }
        }

        val mainColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(mainColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // === TOP BAR ===
        val topBarHost = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }
        topBarNormal = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(4f), AndroidUtilities.dp(16f), AndroidUtilities.dp(4f))
        }
        undoIconBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_undo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            alpha = 0.35f
            isEnabled = false
            setOnClickListener { performPaintUndo() }
        }
        clearAllBtn = TextView(context).apply {
            text = AppLocale.t("clear_all")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setOnClickListener {
                if (activeTab == PaintTab.TEXT && textOverlay.selectedEntity != null) {
                    textOverlay.deleteSelectedEntity()
                    updateWeightChooserForText()
                } else {
                    renderView.clearShape()
                    renderView.clearAll()
                    textOverlay.clearActiveOrAll(false)
                    undoStore.reset()
                    synchronized(historyLock) {
                        persistedUndoStack.clear()
                        currentSessionSnapshots.clear()
                        currentSessionSnapshots.add(ByteArray(0))
                    }
                    updateShapeActionVisibility()
                    updateUndoState()
                }
            }
        }
        topBarNormal.addView(undoIconBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(44f), AndroidUtilities.dp(44f)))
        topBarNormal.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        topBarNormal.addView(clearAllBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(44f)))

        topBarTyping = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(4f), AndroidUtilities.dp(16f), AndroidUtilities.dp(4f))
        }
        val typingClearBtn = TextView(context).apply {
            text = AppLocale.t("clear_logs")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { textOverlay.clearActiveOrAll(true) }
        }
        val typingDoneBtn = TextView(context).apply {
            text = AppLocale.t("done")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setOnClickListener { textOverlay.finishInlineEditing() }
        }
        topBarTyping.addView(typingClearBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(44f)))
        topBarTyping.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        topBarTyping.addView(typingDoneBtn, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(44f)))

        topBarHost.addView(topBarNormal, LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(50f)))
        topBarHost.addView(topBarTyping, LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(50f)))
        mainColumn.addView(topBarHost, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(50f)))

        // === STAGE ===
        stageContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = false
        }
        mainColumn.addView(stageContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        canvasWrapper = FrameLayout(context).apply {
            clipChildren = false
        }
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
        val paintW = (sourceBitmap.width * scale).roundToInt().coerceAtLeast(64)
        val paintH = (sourceBitmap.height * scale).roundToInt().coerceAtLeast(64)
        val paintSize = Size(paintW.toFloat(), paintH.toFloat())

        val painting = Painting(paintSize, sourceBitmap, 0, null)

        // Restore previous transparent stroke layer (if any) directly onto the GPU Paint canvas!
        val initialPaintLayer = Bitmap.createBitmap(paintW, paintH, Bitmap.Config.ARGB_8888)
        EditLayerState.decodeMutableArgb(initialPaintState?.strokeLayerPng)?.let { prevLayer ->
            val c = Canvas(initialPaintLayer)
            c.drawBitmap(
                prevLayer,
                null,
                Rect(0, 0, paintW, paintH),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            )
            prevLayer.recycle()
        }

        CrashLogManager.addBreadcrumb("Paint opened (${paintW}x${paintH}, restored=${initialPaintState?.hasContent == true})")
        renderView = RenderView(context, painting, initialPaintLayer, null, null).apply {
            isOpaque = false
            setQueue(paintQueue)
            setUndoStore(this@TelegramPaintPanel.undoStore)
        }
        canvasWrapper.addView(renderView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        shapeOverlayView = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                renderView.onDrawForInput(canvas)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (eyedropperActive) {
                    handleEyedropperTouch(event)
                    return true
                }
                if (paletteVisible && event.actionMasked == MotionEvent.ACTION_DOWN) {
                    toggleColorPalette(false)
                }
                if (activeTab == PaintTab.TEXT) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        if (textOverlay.isAnyEditing()) {
                            textOverlay.finishInlineEditing()
                        } else {
                            textOverlay.deselectAll()
                            updateWeightChooserForText()
                        }
                    }
                    return true
                }
                val handled = renderView.onTouch(event)
                invalidate()
                updateShapeActionVisibility()
                return handled
            }
        }
        canvasWrapper.addView(shapeOverlayView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        textOverlay = TextOverlayContainer(context).apply {
            interactionEnabled = false
            onEntitySelected = { entity ->
                if (entity != null) {
                    syncTextControlsFromEntity(entity)
                }
                updateClearLabel()
                updateWeightChooserForText()
            }
            onEditingChanged = { editing ->
                topBarNormal.visibility = if (editing) View.GONE else View.VISIBLE
                topBarTyping.visibility = if (editing) View.VISIBLE else View.GONE
                bottomTabsRow.visibility = if (editing) View.GONE else View.VISIBLE
                if (editing) hidePopups()
                updateClearLabel()
            }
        }
        canvasWrapper.addView(textOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        if (initialPaintState != null && initialPaintState.textEntities.isNotEmpty()) {
            textOverlay.restoreEntitySnapshots(initialPaintState.textEntities)
        }

        weightChooserView = PaintWeightChooserView(context).apply {
            setRenderView(renderView)
            setColorSwatch(currentSwatch)
            setMinMax(0.05f, 1.0f)
            setDrawCenter(true)
            setOnUpdate {
                if (activeTab == PaintTab.DRAW) {
                    applyBrushWeight(currentSwatch.brushWeight)
                }
            }
        }
        stageContainer.addView(
            weightChooserView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        // Floating Shape Controls (Outline/Fill + Place)
        shapeActionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(4f), AndroidUtilities.dp(6f), AndroidUtilities.dp(4f))
            background = GradientDrawable().apply {
                setColor(0xD91C1C1E.toInt())
                cornerRadius = AndroidUtilities.dp(20f).toFloat()
            }
        }
        shapeFillToggleBtn = TextView(context).apply {
            text = if (PersistColorPalette.getInstance(UserConfig.selectedAccount).fillShapes) AppLocale.t("filled") else AppLocale.t("outline")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
            setOnClickListener {
                val filled = renderView.toggleShapeFill()
                text = if (filled) AppLocale.t("filled") else AppLocale.t("outline")
                shapeOverlayView.invalidate()
            }
        }
        val shapePlaceBtn = TextView(context).apply {
            text = AppLocale.t("place")
            setTextColor(0xFF4DA3FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
            setOnClickListener {
                renderView.commitShape()
                shapeOverlayView.invalidate()
                updateShapeActionVisibility()
                captureSessionSnapshotAsync()
            }
        }
        shapeActionBar.addView(shapeFillToggleBtn)
        shapeActionBar.addView(shapePlaceBtn)
        stageContainer.addView(
            shapeActionBar,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = AndroidUtilities.dp(10f)
            }
        )

        // Eyedropper hint pill & floating loupe dot
        eyedropperBanner = TextView(context).apply {
            text = AppLocale.t("eyedropper_hint")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(7f), AndroidUtilities.dp(14f), AndroidUtilities.dp(7f))
            background = GradientDrawable().apply {
                setColor(0xDD1C1C1E.toInt())
                cornerRadius = AndroidUtilities.dp(16f).toFloat()
                setStroke(AndroidUtilities.dp(1f), AppTheme.accent)
            }
        }
        stageContainer.addView(
            eyedropperBanner,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = AndroidUtilities.dp(10f)
            }
        )
        eyedropperLoupe = View(context).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(selectedColor)
                setStroke(AndroidUtilities.dp(3.5f), Color.WHITE)
            }
            elevation = AndroidUtilities.dp(8f).toFloat()
        }
        canvasWrapper.addView(
            eyedropperLoupe,
            LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f))
        )

        renderView.setDelegate(object : RenderView.RenderViewDelegate {
            override fun onBeganDrawing() {
                if (paletteVisible) {
                    post { toggleColorPalette(false) }
                }
            }
            override fun onFinishedDrawing(moved: Boolean) {
                shapeOverlayView.invalidate()
                updateShapeActionVisibility()
                if (moved && !renderView.hasActiveShape()) {
                    captureSessionSnapshotAsync()
                }
            }
            override fun onFirstDraw() {}
            override fun shouldDraw(): Boolean = activeTab == PaintTab.DRAW
            override fun invalidateInputView() {
                shapeOverlayView.invalidate()
            }
            override fun resetBrush() {
                post {
                    selectBrush(selectedBrushIndex)
                    updateShapeActionVisibility()
                }
            }
        })

        undoStore.setDelegate {
            post { updateUndoState() }
        }

        // === BOTTOM TOOL AREA ===
        val bottomArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        mainColumn.addView(bottomArea, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        colorPaletteBar = TelegramColorPaletteBar(
            context,
            onColorSelected = { color -> applySelectedColor(color) },
            onClose = { toggleColorPalette(false) },
            onEyedropperRequested = {
                toggleColorPalette(false)
                setEyedropperMode(true)
            }
        ).apply {
            visibility = View.GONE
        }
        bottomArea.addView(colorPaletteBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        drawPillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
        }
        val drawPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(4f), AndroidUtilities.dp(8f), AndroidUtilities.dp(4f))
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadius = AndroidUtilities.dp(26f).toFloat()
            }
        }

        drawColorRing = TelegramColorRingView(context).apply {
            setOnClickListener { toggleColorPalette(true) }
        }
        drawPill.addView(drawColorRing, LinearLayout.LayoutParams(AndroidUtilities.dp(38f), AndroidUtilities.dp(38f)))

        brushSlots.forEachIndexed { idx, slot ->
            val btn = ImageView(context).apply {
                setImageResource(slot.iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(AndroidUtilities.dp(5f), AndroidUtilities.dp(5f), AndroidUtilities.dp(5f), AndroidUtilities.dp(5f))
                setOnClickListener { selectBrush(idx) }
            }
            brushButtons.add(btn)
            drawPill.addView(btn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(40f), 1f))
        }

        val addShapeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_plus)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(7f), AndroidUtilities.dp(7f), AndroidUtilities.dp(7f), AndroidUtilities.dp(7f))
            setOnClickListener { showShapeMenu() }
        }
        drawPill.addView(addShapeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(38f), AndroidUtilities.dp(38f)))

        drawPillRow.addView(drawPill, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(50f)))
        bottomArea.addView(drawPillRow)

        textPillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
        }
        textColorRing = TelegramColorRingView(context).apply {
            setOnClickListener { toggleColorPalette(true) }
        }
        alignBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_align_center)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
            setOnClickListener { cycleTextAlignment() }
        }
        styleBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_text_filled)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
            setOnClickListener { cycleTextStyle() }
        }
        val addNewTextBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_plus)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
            setOnClickListener {
                textOverlay.addInlineText(selectedColor, currentTextStyle, currentTextFont, currentTextAlign)
            }
        }
        val fontPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(6f), AndroidUtilities.dp(10f), AndroidUtilities.dp(6f))
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                setStroke(AndroidUtilities.dp(1f), 0x44FFFFFF)
                cornerRadius = AndroidUtilities.dp(18f).toFloat()
            }
            setOnClickListener { showFontMenu() }
        }
        fontPillText = TextView(context).apply {
            text = currentTextFont.displayName
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
        }
        val fontChevrons = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_chevrons_ud)
            setPadding(AndroidUtilities.dp(4f), 0, 0, 0)
        }
        fontPill.addView(fontPillText)
        fontPill.addView(fontChevrons, LinearLayout.LayoutParams(AndroidUtilities.dp(20f), AndroidUtilities.dp(18f)))

        textPillRow.addView(textColorRing, LinearLayout.LayoutParams(AndroidUtilities.dp(38f), AndroidUtilities.dp(38f)))
        textPillRow.addView(alignBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(42f), AndroidUtilities.dp(42f)))
        textPillRow.addView(styleBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(42f), AndroidUtilities.dp(42f)))
        textPillRow.addView(addNewTextBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(42f), AndroidUtilities.dp(42f)))
        textPillRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        textPillRow.addView(fontPill, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(36f)))
        bottomArea.addView(textPillRow)

        bottomTabsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(2f), AndroidUtilities.dp(12f), AndroidUtilities.dp(8f))
        }
        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            setOnClickListener {
                cleanup()
                onCancel()
            }
        }
        drawTabLabel = TextView(context).apply {
            text = AppLocale.t("draw")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            setOnClickListener { switchTab(PaintTab.DRAW) }
        }
        textTabLabel = TextView(context).apply {
            text = AppLocale.t("text")
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            setOnClickListener { switchTab(PaintTab.TEXT) }
        }
        val checkBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_check)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            setOnClickListener { saveLayerAndFinish() }
        }

        bottomTabsRow.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f)))
        bottomTabsRow.addView(drawTabLabel, LinearLayout.LayoutParams(0, AndroidUtilities.dp(44f), 1f))
        bottomTabsRow.addView(textTabLabel, LinearLayout.LayoutParams(0, AndroidUtilities.dp(44f), 1f))
        bottomTabsRow.addView(checkBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f)))
        bottomArea.addView(bottomTabsRow)

        popupBackdrop = FrameLayout(context).apply {
            visibility = View.GONE
            setOnClickListener { hidePopups() }
        }

        shapePopupCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f))
            background = GradientDrawable().apply {
                setColor(0xF21E1E20.toInt())
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
            elevation = AndroidUtilities.dp(8f).toFloat()
        }
        shapeOptions.forEach { opt ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(11f), AndroidUtilities.dp(24f), AndroidUtilities.dp(11f))
                setOnClickListener {
                    hidePopups()
                    CrashLogManager.addBreadcrumb("Selected shape: ${opt.label}")
                    renderView.setBrush(Brush.Shape.make(opt.shapeType))
                    applyBrushWeight(currentSwatch.brushWeight)
                    updateBrushHighlights(-1)
                    shapeOverlayView.invalidate()
                    updateShapeActionVisibility()
                    updateUndoState()
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(opt.iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
            val label = TextView(context).apply {
                text = opt.label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(AndroidUtilities.dp(14f), 0, 0, 0)
            }
            row.addView(icon, LinearLayout.LayoutParams(AndroidUtilities.dp(22f), AndroidUtilities.dp(22f)))
            row.addView(label)
            shapePopupCard.addView(row)
        }
        popupBackdrop.addView(
            shapePopupCard,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = AndroidUtilities.dp(14f)
                bottomMargin = AndroidUtilities.dp(110f)
            }
        )

        fontPopupCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f))
            background = GradientDrawable().apply {
                setColor(0xF21E1E20.toInt())
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
            elevation = AndroidUtilities.dp(8f).toFloat()
        }
        TextOverlayContainer.FontChoice.entries.forEach { font ->
            val tv = TextView(context).apply {
                text = font.displayName
                typeface = font.typeface
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(11f), AndroidUtilities.dp(28f), AndroidUtilities.dp(11f))
                setOnClickListener {
                    hidePopups()
                    currentTextFont = font
                    fontPillText.text = font.displayName
                    textOverlay.selectedEntity?.let { ent ->
                        ent.fontChoice = font
                        ent.applyVisuals()
                    }
                }
            }
            fontPopupCard.addView(tv)
        }
        popupBackdrop.addView(
            fontPopupCard,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = AndroidUtilities.dp(14f)
                bottomMargin = AndroidUtilities.dp(106f)
            }
        )

        addView(popupBackdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        stageContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            layoutCanvasToAspect(right - left, bottom - top)
        }

        applySelectedColor(PersistColorPalette.COLOR_RED)
        selectBrush(0)
        switchTab(PaintTab.DRAW)
        updateUndoState()
    }

    private fun performPaintUndo() {
        if (renderView.hasActiveShape()) {
            renderView.clearShape()
            selectBrush(selectedBrushIndex)
            updateShapeActionVisibility()
            return
        }
        if (undoStore.canUndo()) {
            undoStore.undo()
            exportExecutor.execute {
                synchronized(historyLock) {
                    if (currentSessionSnapshots.size > 1) {
                        currentSessionSnapshots.removeAt(currentSessionSnapshots.lastIndex)
                    }
                }
            }
            updateUndoState()
            return
        }
        val prevPng: ByteArray? = synchronized(historyLock) {
            if (persistedUndoStack.isNotEmpty()) {
                val popped = persistedUndoStack.removeAt(persistedUndoStack.lastIndex)
                currentSessionSnapshots.clear()
                currentSessionSnapshots.add(popped)
                popped
            } else {
                null
            }
        }
        if (prevPng != null) {
            exportExecutor.execute {
                val bmp = EditLayerState.decodeMutableArgb(prevPng)
                renderView.replaceCanvasBitmap(bmp)
                post { updateUndoState() }
            }
        }
    }

    private fun captureSessionSnapshotAsync() {
        if (isClosing) return
        exportExecutor.execute {
            if (isClosing) return@execute
            try {
                val snap = renderView.snapshotBitmap ?: return@execute
                val png = EditLayerState.encodeTransparentLayerOrNull(snap) ?: ByteArray(0)
                snap.recycle()
                synchronized(historyLock) {
                    currentSessionSnapshots.add(png)
                    while (persistedUndoStack.size + currentSessionSnapshots.size > EditLayerState.MAX_STROKE_UNDO_SNAPSHOTS + 1) {
                        if (persistedUndoStack.isNotEmpty()) {
                            persistedUndoStack.removeAt(0)
                        } else if (currentSessionSnapshots.size > 2) {
                            currentSessionSnapshots.removeAt(0)
                        } else {
                            break
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
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

    private fun switchTab(tab: PaintTab) {
        if (tab == PaintTab.TEXT && renderView.hasActiveShape()) {
            renderView.commitShape()
            shapeOverlayView.invalidate()
            updateShapeActionVisibility()
            captureSessionSnapshotAsync()
        }
        activeTab = tab
        hidePopups()
        toggleColorPalette(false)
        textOverlay.interactionEnabled = (tab == PaintTab.TEXT)

        drawTabLabel.setTextColor(if (tab == PaintTab.DRAW) Color.WHITE else 0xFF8E8E93.toInt())
        textTabLabel.setTextColor(if (tab == PaintTab.TEXT) Color.WHITE else 0xFF8E8E93.toInt())

        if (tab == PaintTab.DRAW) {
            textOverlay.deselectAll()
            drawPillRow.visibility = View.VISIBLE
            textPillRow.visibility = View.GONE
            weightChooserView.setViewHidden(false)
            weightChooserView.setValueOverride(null)
            weightChooserView.setMinMax(0.05f, 1.0f)
            weightChooserView.setDrawCenter(true)
            weightChooserView.setBrushWeight(currentSwatch.brushWeight)
        } else {
            drawPillRow.visibility = View.GONE
            textPillRow.visibility = View.VISIBLE
            weightChooserView.setDrawCenter(false)
            weightChooserView.setMinMax(16f, 64f)
            weightChooserView.setValueOverride(object : PaintWeightChooserView.ValueOverride {
                override fun get(): Float = textOverlay.selectedEntity?.baseTextSizeSp ?: 28f
                override fun set(valSize: Float) {
                    textOverlay.selectedEntity?.let { ent ->
                        ent.baseTextSizeSp = valSize
                        ent.applyVisuals()
                    }
                }
            })
            if (!textOverlay.hasTextEntities()) {
                applySelectedColor(Color.WHITE)
                textOverlay.addInlineText(Color.WHITE, currentTextStyle, currentTextFont, currentTextAlign)
            } else if (textOverlay.selectedEntity == null) {
                (textOverlay.getChildAt(textOverlay.childCount - 1) as? TextOverlayContainer.TelegramTextEntityView)?.let {
                    textOverlay.selectEntity(it, openKeyboard = false)
                }
            }
            updateWeightChooserForText()
        }
        updateClearLabel()
    }

    private fun updateWeightChooserForText() {
        if (activeTab == PaintTab.TEXT) {
            weightChooserView.setViewHidden(textOverlay.selectedEntity == null)
        }
    }

    private fun updateClearLabel() {
        clearAllBtn.text = if (activeTab == PaintTab.TEXT && textOverlay.selectedEntity != null) {
            AppLocale.t("delete_text")
        } else {
            AppLocale.t("clear_all")
        }
    }

    private fun updateShapeActionVisibility() {
        val active = renderView.hasActiveShape()
        shapeActionBar.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            val isArrow = renderView.currentBrush is Brush.Shape.Arrow
            shapeFillToggleBtn.visibility = if (isArrow) View.GONE else View.VISIBLE
            val filled = PersistColorPalette.getInstance(UserConfig.selectedAccount).fillShapes
            shapeFillToggleBtn.text = if (filled) AppLocale.t("filled") else AppLocale.t("outline")
        }
        updateUndoState()
    }

    private fun updateUndoState() {
        val hasPersisted = synchronized(historyLock) { persistedUndoStack.isNotEmpty() }
        val can = undoStore.canUndo() || hasPersisted || renderView.hasActiveShape()
        undoIconBtn.isEnabled = can
        undoIconBtn.alpha = if (can) 1f else 0.35f
    }

    private fun toggleColorPalette(show: Boolean) {
        paletteVisible = show
        hidePopups()
        if (show) {
            colorPaletteBar.setSelectedColor(selectedColor)
            colorPaletteBar.visibility = View.VISIBLE
            drawPillRow.visibility = View.GONE
            textPillRow.visibility = View.GONE
        } else {
            colorPaletteBar.visibility = View.GONE
            drawPillRow.visibility = if (activeTab == PaintTab.DRAW) View.VISIBLE else View.GONE
            textPillRow.visibility = if (activeTab == PaintTab.TEXT) View.VISIBLE else View.GONE
        }
    }

    private fun showShapeMenu() {
        popupBackdrop.visibility = View.VISIBLE
        shapePopupCard.visibility = View.VISIBLE
        fontPopupCard.visibility = View.GONE
    }

    private fun showFontMenu() {
        popupBackdrop.visibility = View.VISIBLE
        fontPopupCard.visibility = View.VISIBLE
        shapePopupCard.visibility = View.GONE
    }

    private fun hidePopups() {
        popupBackdrop.visibility = View.GONE
        shapePopupCard.visibility = View.GONE
        fontPopupCard.visibility = View.GONE
    }

    private fun setEyedropperMode(active: Boolean) {
        eyedropperActive = active
        eyedropperBanner.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) {
            eyedropperLoupe.visibility = View.GONE
        }
        textOverlay.interactionEnabled = !active && (activeTab == PaintTab.TEXT)
    }

    private fun handleEyedropperTouch(event: MotionEvent) {
        val w = shapeOverlayView.width.coerceAtLeast(1)
        val h = shapeOverlayView.height.coerceAtLeast(1)
        val px = ((event.x / w) * sourceBitmap.width).toInt().coerceIn(0, sourceBitmap.width - 1)
        val py = ((event.y / h) * sourceBitmap.height).toInt().coerceIn(0, sourceBitmap.height - 1)
        val sampled = sourceBitmap.getPixel(px, py) or (0xFF shl 24)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                eyedropperLoupe.visibility = View.VISIBLE
                eyedropperLoupe.translationX = (event.x - eyedropperLoupe.width / 2f).coerceIn(0f, (w - eyedropperLoupe.width).toFloat().coerceAtLeast(0f))
                eyedropperLoupe.translationY = (event.y - eyedropperLoupe.height - AndroidUtilities.dp(24f)).coerceIn(0f, (h - eyedropperLoupe.height).toFloat().coerceAtLeast(0f))
                (eyedropperLoupe.background as? GradientDrawable)?.setColor(sampled)
                applySelectedColor(sampled)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                applySelectedColor(sampled)
                setEyedropperMode(false)
            }
        }
    }

    private fun selectBrush(index: Int) {
        setEyedropperMode(false)
        selectedBrushIndex = index
        val slot = brushSlots[index]
        val brush = slot.brush
        renderView.painting.setMosaicMode(slot.isMosaic)
        CrashLogManager.addBreadcrumb("Selected brush #$index: ${if (slot.isMosaic) "Mosaic" else brush.javaClass.simpleName}")
        renderView.setBrush(brush)
        val weight = brushWeights[index]
        currentSwatch.brushWeight = weight
        weightChooserView.setBrushWeight(weight)
        applyBrushWeight(weight)
        updateBrushHighlights(index)
        shapeOverlayView.invalidate()
        updateShapeActionVisibility()
    }

    private fun updateBrushHighlights(selectedIdx: Int) {
        brushButtons.forEachIndexed { idx, btn ->
            val active = idx == selectedIdx
            btn.alpha = if (active) 1f else 0.72f
            btn.background = if (active) {
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x38FFFFFF)
                }
            } else {
                null
            }
        }
    }

    private fun applySelectedColor(color: Int) {
        selectedColor = color
        currentSwatch.color = color
        drawColorRing.currentColor = color
        textColorRing.currentColor = color
        renderView.setColor(color)
        weightChooserView.setColorSwatch(currentSwatch)
        if (activeTab == PaintTab.TEXT) {
            textOverlay.selectedEntity?.let { ent ->
                ent.textColorValue = color
                ent.applyVisuals()
            }
        }
    }

    private fun applyBrushWeight(weight: Float) {
        currentSwatch.brushWeight = weight
        if (renderView.currentBrush !is Brush.Shape && selectedBrushIndex in brushWeights.indices) {
            brushWeights[selectedBrushIndex] = weight
        }
        renderView.setBrushSize(weight)
    }

    private fun cycleTextAlignment() {
        currentTextAlign = when (currentTextAlign) {
            Gravity.CENTER -> Gravity.START
            Gravity.START -> Gravity.END
            else -> Gravity.CENTER
        }
        updateAlignIcon()
        textOverlay.selectedEntity?.let { ent ->
            ent.textAlignmentGravity = currentTextAlign
            ent.applyVisuals()
        }
    }

    private fun updateAlignIcon() {
        alignBtn.setImageResource(
            when (currentTextAlign) {
                Gravity.START -> R.drawable.ic_tg_align_left
                Gravity.END -> R.drawable.ic_tg_align_right
                else -> R.drawable.ic_tg_align_center
            }
        )
    }

    private fun cycleTextStyle() {
        currentTextStyle = when (currentTextStyle) {
            TextOverlayContainer.TextStyle.FILLED -> TextOverlayContainer.TextStyle.OUTLINED
            TextOverlayContainer.TextStyle.OUTLINED -> TextOverlayContainer.TextStyle.TRANSLUCENT
            TextOverlayContainer.TextStyle.TRANSLUCENT -> TextOverlayContainer.TextStyle.REGULAR
            TextOverlayContainer.TextStyle.REGULAR -> TextOverlayContainer.TextStyle.FILLED
        }
        updateStyleIcon()
        textOverlay.selectedEntity?.let { ent ->
            ent.textStyle = currentTextStyle
            ent.applyVisuals()
        }
    }

    private fun updateStyleIcon() {
        styleBtn.setImageResource(
            when (currentTextStyle) {
                TextOverlayContainer.TextStyle.FILLED -> R.drawable.ic_tg_text_filled
                TextOverlayContainer.TextStyle.OUTLINED -> R.drawable.ic_tg_text_frame
                TextOverlayContainer.TextStyle.TRANSLUCENT -> R.drawable.ic_tg_text_regular
                TextOverlayContainer.TextStyle.REGULAR -> R.drawable.ic_tg_text_frame
            }
        )
    }

    private fun syncTextControlsFromEntity(entity: TextOverlayContainer.TelegramTextEntityView) {
        currentTextStyle = entity.textStyle
        currentTextFont = entity.fontChoice
        currentTextAlign = entity.textAlignmentGravity
        selectedColor = entity.textColorValue
        textColorRing.currentColor = entity.textColorValue
        fontPillText.text = entity.fontChoice.displayName
        updateAlignIcon()
        updateStyleIcon()
    }

    /**
     * Exports Layer 3 non-destructively (transparent strokes PNG + per-stroke undo history
     * + live editable text snapshots + cached transparent text PNG) without flattening into the photo!
     */
    private fun saveLayerAndFinish() {
        isClosing = true
        textOverlay.finishInlineEditing()
        if (renderView.hasActiveShape()) {
            renderView.commitShape()
        }
        val textSnapshots = textOverlay.exportEntitySnapshots()
        val textOverlayBmp = textOverlay.renderTransparentOverlayBitmap(sourceBitmap.width, sourceBitmap.height)

        exportExecutor.execute {
            val paintOverlayBitmap = renderView.getResultBitmap(false, true)
            val finalStrokePng = EditLayerState.encodeTransparentLayerOrNull(paintOverlayBitmap)
            paintOverlayBitmap?.recycle()

            val textLayerPng = EditLayerState.encodeTransparentLayerOrNull(textOverlayBmp)
            textOverlayBmp?.recycle()

            val nextUndoStack: List<ByteArray> = synchronized(historyLock) {
                if (finalStrokePng == null) {
                    emptyList()
                } else {
                    val combined = mutableListOf<ByteArray>()
                    combined.addAll(persistedUndoStack)
                    // Add all prior states captured in this session before the final state
                    if (currentSessionSnapshots.size > 1) {
                        combined.addAll(currentSessionSnapshots.dropLast(1))
                    } else if (currentSessionSnapshots.size == 1 && !currentSessionSnapshots[0].contentEquals(finalStrokePng)) {
                        combined.add(currentSessionSnapshots[0])
                    }
                    if (combined.isEmpty()) {
                        combined.add(ByteArray(0))
                    }
                    if (combined.size > EditLayerState.MAX_STROKE_UNDO_SNAPSHOTS) {
                        combined.takeLast(EditLayerState.MAX_STROKE_UNDO_SNAPSHOTS)
                    } else {
                        combined
                    }
                }
            }

            val nextLayerState = EditLayerState.PaintLayerState(
                strokeLayerPng = finalStrokePng,
                strokeUndoPngStack = nextUndoStack,
                textEntities = textSnapshots,
                textLayerPng = textLayerPng
            )

            post {
                cleanup()
                onDone(nextLayerState)
            }
        }
    }

    fun cleanup() {
        isClosing = true
        textOverlay.finishInlineEditing()
        renderView.shutdown()
        paintQueue.recycle()
        exportExecutor.shutdownNow()
    }
}
