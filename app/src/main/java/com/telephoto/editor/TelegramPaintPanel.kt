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
 * Pixel-identical Telegram Paint & Text mode (frames 00:36 - 01:40):
 * - Top bar: Undo curved arrow icon | Clear All (switches to Clear | Done while typing text)
 * - Left-edge Telegram PaintWeightChooserView (tapered grey wedge slider + center size preview dot)
 * - DRAW pill: Rainbow Color Ring + 6 circular brush icons (Pen, Arrow, Marker, Neon, Blur, Eraser) + `+` 5-Shape popup menu
 * - Active Shape floating controls: Fill / Outline toggle + Place + Cancel
 * - 2-row x 7-column dark floating Color Palette popup with `<` and `+` custom color picker
 * - Inline TEXT bar: Rainbow Color Ring + Alignment + Frame Style `A` + `+` + `Roboto ↕` Font selector dropdown
 * - Bottom row: `✕` | DRAW | TEXT | `✓`
 */
@SuppressLint("ClickableViewAccessibility")
class TelegramPaintPanel(
    context: Context,
    private val sourceBitmap: Bitmap,
    private val onCancel: () -> Unit,
    private val onDone: (Bitmap) -> Unit
) : FrameLayout(context) {

    private enum class PaintTab { DRAW, TEXT }

    private data class BrushSlot(val iconRes: Int, val brush: Brush)

    private val brushSlots = listOf(
        BrushSlot(R.drawable.ic_tg_brush_pen, Brush.BRUSHES_LIST[0]),
        BrushSlot(R.drawable.ic_tg_brush_arrow, Brush.BRUSHES_LIST[1]),
        BrushSlot(R.drawable.ic_tg_brush_marker, Brush.BRUSHES_LIST[2]),
        BrushSlot(R.drawable.ic_tg_brush_neon, Brush.BRUSHES_LIST[3]),
        BrushSlot(R.drawable.ic_tg_brush_blur, Brush.BRUSHES_LIST[4]),
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

    // Per-brush weight memory initialized to each Telegram brush's real defaultWeight
    private val brushWeights = FloatArray(brushSlots.size) { idx ->
        brushSlots[idx].brush.defaultWeight.coerceIn(0.05f, 1.0f)
    }

    private val paintQueue = DispatchQueue("TelegramPaintQueue")
    private val undoStore = UndoStore()
    private val exportExecutor = Executors.newSingleThreadExecutor()

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

    // Active Shape mini floating controls (Fill / Place)
    private val shapeActionBar: LinearLayout
    private val shapeFillToggleBtn: TextView

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

    // Floating popups (Shape menu & Font menu)
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

        val mainColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(mainColumn, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // === TOP BAR (Normal: Undo arrow + Clear All | Typing: Clear + Done) ===
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
            setOnClickListener {
                if (renderView.hasActiveShape()) {
                    renderView.clearShape()
                    selectBrush(selectedBrushIndex)
                    updateShapeActionVisibility()
                } else if (undoStore.canUndo()) {
                    undoStore.undo()
                }
            }
        }
        clearAllBtn = TextView(context).apply {
            text = "Clear All"
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
            text = "Clear"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setOnClickListener { textOverlay.clearActiveOrAll(true) }
        }
        val typingDoneBtn = TextView(context).apply {
            text = "Done"
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
        val paintSize = Size(
            (sourceBitmap.width * scale).roundToInt().coerceAtLeast(64).toFloat(),
            (sourceBitmap.height * scale).roundToInt().coerceAtLeast(64).toFloat()
        )

        val painting = Painting(paintSize, sourceBitmap, 0, null)
        // Upstream Telegram passes a transparent paint layer bitmap to RenderView so strokes & eraser
        // operate cleanly on the overlay while sourceBitmap stays untouched behind it in baseImageView.
        val transparentPaintLayer = Bitmap.createBitmap(
            paintSize.width.toInt(),
            paintSize.height.toInt(),
            Bitmap.Config.ARGB_8888
        )
        CrashLogManager.addBreadcrumb("Paint opened (${paintSize.width.toInt()}x${paintSize.height.toInt()})")
        renderView = RenderView(context, painting, transparentPaintLayer, null, null).apply {
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

        // Telegram's REAL Left-Edge Tapered Wedge Brush/Text Size Chooser!
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

        // Floating Shape Controls (Outline/Fill + Place) shown only while placing a Shape
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
            text = if (PersistColorPalette.getInstance(UserConfig.selectedAccount).fillShapes) "Filled" else "Outline"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
            setOnClickListener {
                val filled = renderView.toggleShapeFill()
                text = if (filled) "Filled" else "Outline"
                shapeOverlayView.invalidate()
            }
        }
        val shapePlaceBtn = TextView(context).apply {
            text = "Place"
            setTextColor(0xFF4DA3FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
            setOnClickListener {
                renderView.commitShape()
                shapeOverlayView.invalidate()
                updateShapeActionVisibility()
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

        renderView.setDelegate(object : RenderView.RenderViewDelegate {
            override fun onBeganDrawing() {
                if (paletteVisible) {
                    post { toggleColorPalette(false) }
                }
            }
            override fun onFinishedDrawing(moved: Boolean) {
                shapeOverlayView.invalidate()
                updateShapeActionVisibility()
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

        // 1) Color Palette Bar (hidden until Rainbow Color Ring tapped)
        colorPaletteBar = TelegramColorPaletteBar(
            context,
            onColorSelected = { color ->
                applySelectedColor(color)
            },
            onClose = {
                toggleColorPalette(false)
            }
        ).apply {
            visibility = View.GONE
        }
        bottomArea.addView(colorPaletteBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 2) DRAW Tool Pill (frame 00:37)
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

        // 3) TEXT Tool Bar (frames 01:05 & 01:10)
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

        // 4) Bottom Navigation Row: `✕` | DRAW | TEXT | `✓`
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
            text = "DRAW"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            setOnClickListener { switchTab(PaintTab.DRAW) }
        }
        textTabLabel = TextView(context).apply {
            text = "TEXT"
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
            setOnClickListener { bakeAndFinish() }
        }

        bottomTabsRow.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f)))
        bottomTabsRow.addView(drawTabLabel, LinearLayout.LayoutParams(0, AndroidUtilities.dp(44f), 1f))
        bottomTabsRow.addView(textTabLabel, LinearLayout.LayoutParams(0, AndroidUtilities.dp(44f), 1f))
        bottomTabsRow.addView(checkBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f)))
        bottomArea.addView(bottomTabsRow)

        // === FLOATING POPUPS (Shape menu & Font dropdown) ===
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

        // Initial defaults
        applySelectedColor(PersistColorPalette.COLOR_RED)
        selectBrush(0)
        switchTab(PaintTab.DRAW)
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
            "Delete Text"
        } else {
            "Clear All"
        }
    }

    private fun updateShapeActionVisibility() {
        val active = renderView.hasActiveShape()
        shapeActionBar.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            val isArrow = renderView.currentBrush is Brush.Shape.Arrow
            shapeFillToggleBtn.visibility = if (isArrow) View.GONE else View.VISIBLE
            val filled = PersistColorPalette.getInstance(UserConfig.selectedAccount).fillShapes
            shapeFillToggleBtn.text = if (filled) "Filled" else "Outline"
        }
        updateUndoState()
    }

    private fun updateUndoState() {
        val can = undoStore.canUndo() || renderView.hasActiveShape()
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

    private fun selectBrush(index: Int) {
        selectedBrushIndex = index
        val brush = brushSlots[index].brush
        CrashLogManager.addBreadcrumb("Selected brush #${index}: ${brush.javaClass.simpleName}")
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

    private fun bakeAndFinish() {
        textOverlay.finishInlineEditing()
        if (renderView.hasActiveShape()) {
            renderView.commitShape()
        }
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
        textOverlay.finishInlineEditing()
        renderView.shutdown()
        paintQueue.recycle()
        exportExecutor.shutdownNow()
    }
}
