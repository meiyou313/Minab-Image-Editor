package com.telephoto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MediaController
import org.telegram.messenger.R
import org.telegram.ui.Components.FilterGLThread
import org.telegram.ui.Components.FilterShaders
import org.telegram.ui.Components.PhotoEditorSeekBar
import org.telegram.ui.Components.PhotoFilterBlurControl
import org.telegram.ui.Components.PhotoFilterCurvesControl
import org.telegram.ui.Components.PhotoFilterView
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Telegram-styled Adjust / Filters / Curves / Blur screen backed by Telegram's real OpenGL ES 2.0 FilterGLThread & FilterShaders.
 * Bottom row matches Telegram's exact icon layout: `✕` | Tune | Curves | Blur | `✓`
 */
@SuppressLint("ClickableViewAccessibility")
class TelegramFilterPanel(
    context: Context,
    private val sourceBitmap: Bitmap,
    private val onCancel: () -> Unit,
    private val onDone: (Bitmap) -> Unit
) : FrameLayout(context) {

    private enum class SubTab { TUNE, CURVES, BLUR }

    private data class TuneTool(
        val id: Int,
        val label: String,
        val min: Int,
        val max: Int,
        val isTintShadows: Boolean = false,
        val isTintHighlights: Boolean = false,
        val getter: (MediaController.SavedFilterState) -> Int,
        val setter: (MediaController.SavedFilterState, Int) -> Unit
    ) {
        val isTintColor: Boolean get() = isTintShadows || isTintHighlights
    }

    // Exact upstream Telegram palettes from PhotoEditRadioCell.java
    private val tintShadowColors = intArrayOf(
        0x00000000,
        0xFFFF4D4D.toInt(),
        0xFFF48022.toInt(),
        0xFFFFCD00.toInt(),
        0xFF81D281.toInt(),
        0xFF71C5D6.toInt(),
        0xFF0072BC.toInt(),
        0xFF662D91.toInt()
    )

    private val tintHighlightColors = intArrayOf(
        0x00000000,
        0xFFEF9286.toInt(),
        0xFFEACEA2.toInt(),
        0xFFF2E17C.toInt(),
        0xFFA4EDAE.toInt(),
        0xFF89DCE5.toInt(),
        0xFF2E8BC8.toInt(),
        0xFFCD98E5.toInt()
    )

    private val tuneTools = listOf(
        TuneTool(0, "Enhance", 0, 100, getter = { it.enhanceValue.roundToInt() }, setter = { s, v -> s.enhanceValue = v.toFloat() }),
        TuneTool(1, "Exposure", -100, 100, getter = { it.exposureValue.roundToInt() }, setter = { s, v -> s.exposureValue = v.toFloat() }),
        TuneTool(2, "Contrast", -100, 100, getter = { it.contrastValue.roundToInt() }, setter = { s, v -> s.contrastValue = v.toFloat() }),
        TuneTool(3, "Saturation", -100, 100, getter = { it.saturationValue.roundToInt() }, setter = { s, v -> s.saturationValue = v.toFloat() }),
        TuneTool(4, "Warmth", -100, 100, getter = { it.warmthValue.roundToInt() }, setter = { s, v -> s.warmthValue = v.toFloat() }),
        TuneTool(5, "Fade", 0, 100, getter = { it.fadeValue.roundToInt() }, setter = { s, v -> s.fadeValue = v.toFloat() }),
        TuneTool(6, "Highlights", -100, 100, getter = { it.highlightsValue.roundToInt() }, setter = { s, v -> s.highlightsValue = v.toFloat() }),
        TuneTool(7, "Shadows", -100, 100, getter = { it.shadowsValue.roundToInt() }, setter = { s, v -> s.shadowsValue = v.toFloat() }),
        TuneTool(8, "Vignette", 0, 100, getter = { it.vignetteValue.roundToInt() }, setter = { s, v -> s.vignetteValue = v.toFloat() }),
        TuneTool(9, "Grain", 0, 100, getter = { it.grainValue.roundToInt() }, setter = { s, v -> s.grainValue = v.toFloat() }),
        TuneTool(10, "Sharpen", 0, 100, getter = { it.sharpenValue.roundToInt() }, setter = { s, v -> s.sharpenValue = v.toFloat() }),
        TuneTool(11, "Soften Skin", 0, 100, getter = { it.softenSkinValue.roundToInt() }, setter = { s, v -> s.softenSkinValue = v.toFloat() }),
        TuneTool(12, "Tint Shadows", 0, 0, isTintShadows = true, getter = { it.tintShadowsColor }, setter = { s, v -> s.tintShadowsColor = v }),
        TuneTool(13, "Tint Highlights", 0, 0, isTintHighlights = true, getter = { it.tintHighlightsColor }, setter = { s, v -> s.tintHighlightsColor = v })
    )

    private val filterState = MediaController.SavedFilterState()
    private val exportExecutor = Executors.newSingleThreadExecutor()

    private var activeTab = SubTab.TUNE
    private var selectedToolIndex = 0
    @Volatile
    private var showOriginal = false
    private var glThread: FilterGLThread? = null

    private val previewContainer: FrameLayout
    private val textureView: TextureView
    private val curvesControl: PhotoFilterCurvesControl
    private val blurControl: PhotoFilterBlurControl
    private val resetFloatingBtn: TextView

    private val toolTitleText: TextView
    private val toolValueText: TextView
    private val seekBar: PhotoEditorSeekBar
    private val tintColorRow: LinearLayout
    private val toolChipViews = mutableListOf<TextView>()

    private val tunePanel: LinearLayout
    private val curvesPanel: LinearLayout
    private val blurPanel: LinearLayout
    private val tabIcons = mutableMapOf<SubTab, ImageView>()
    private val curveChannelButtons = mutableListOf<TextView>()
    private val blurModeButtons = mutableListOf<TextView>()

    init {
        setBackgroundColor(Color.BLACK)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Preview stage
        previewContainer = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
        }
        root.addView(previewContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        textureView = TextureView(context).apply {
            setOnTouchListener { _, event ->
                if (activeTab == SubTab.TUNE) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            showOriginal = true
                            glThread?.requestRender(false, true, false)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            showOriginal = false
                            glThread?.requestRender(false, true, false)
                        }
                    }
                    true
                } else {
                    false
                }
            }
        }
        previewContainer.addView(
            textureView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )

        curvesControl = PhotoFilterCurvesControl(context, filterState.curvesToolValue).apply {
            visibility = View.GONE
            setDelegate {
                updateResetVisibility()
                glThread?.requestRender(filterState.blurType != 0, true, false)
            }
        }
        previewContainer.addView(curvesControl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        blurControl = PhotoFilterBlurControl(context).apply {
            visibility = View.GONE
            setDelegate { centerPoint, falloff, size, angle ->
                filterState.blurExcludeSize = size
                filterState.blurExcludePoint = PointF(centerPoint.x, centerPoint.y)
                filterState.blurExcludeBlurSize = falloff
                filterState.blurAngle = angle
                glThread?.requestRender(false, true, false)
            }
        }
        previewContainer.addView(blurControl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Subtle floating Reset button at top-right (visible only when modified)
        resetFloatingBtn = TextView(context).apply {
            text = context.getString(R.string.action_reset)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(7f), AndroidUtilities.dp(14f), AndroidUtilities.dp(7f))
            background = GradientDrawable().apply {
                setColor(0x991C1C1E.toInt())
                cornerRadius = AndroidUtilities.dp(16f).toFloat()
            }
            visibility = View.GONE
            setOnClickListener { resetAllFilters() }
        }
        previewContainer.addView(
            resetFloatingBtn,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = AndroidUtilities.dp(10f)
                rightMargin = AndroidUtilities.dp(12f)
            }
        )

        // Bottom control surface (pure black like Telegram)
        val bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(bottomPanel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // TUNE PANEL
        tunePanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val seekBarBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(18f), AndroidUtilities.dp(6f), AndroidUtilities.dp(18f), AndroidUtilities.dp(2f))
        }
        val labelRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolTitleText = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
        }
        toolValueText = TextView(context).apply {
            setTextColor(0xFF4DA3FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.END
        }
        labelRow.addView(toolTitleText, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        labelRow.addView(toolValueText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        seekBarBox.addView(labelRow)

        seekBar = PhotoEditorSeekBar(context).apply {
            setDelegate { _, progress ->
                val tool = tuneTools[selectedToolIndex]
                if (!tool.isTintColor) {
                    tool.setter(filterState, progress)
                    toolValueText.text = progress.toString()
                    updateChipStates()
                    updateResetVisibility()
                    // Pass updateBlur=true just like upstream PhotoFilterView.java:1273 so blur/skin textures stay fresh
                    glThread?.requestRender(true, true, false)
                }
            }
        }
        seekBarBox.addView(seekBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(40f)))

        tintColorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f))
        }
        seekBarBox.addView(tintColorRow)
        tunePanel.addView(seekBarBox)

        val chipScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(8f))
        }
        val chipRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        tuneTools.forEachIndexed { idx, tool ->
            val chip = TextView(context).apply {
                text = tool.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = AndroidUtilities.bold()
                setPadding(AndroidUtilities.dp(13f), AndroidUtilities.dp(7f), AndroidUtilities.dp(13f), AndroidUtilities.dp(7f))
                setOnClickListener { selectTuneTool(idx) }
            }
            toolChipViews.add(chip)
            chipRow.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
            })
        }
        chipScroll.addView(chipRow)
        tunePanel.addView(chipScroll)
        bottomPanel.addView(tunePanel)

        // CURVES PANEL
        curvesPanel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(14f), AndroidUtilities.dp(16f), AndroidUtilities.dp(14f))
        }
        listOf(
            PhotoFilterView.CurvesToolValue.CurvesTypeLuminance to "All",
            PhotoFilterView.CurvesToolValue.CurvesTypeRed to "Red",
            PhotoFilterView.CurvesToolValue.CurvesTypeGreen to "Green",
            PhotoFilterView.CurvesToolValue.CurvesTypeBlue to "Blue"
        ).forEach { (type, label) ->
            val btn = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER
                setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(8f), AndroidUtilities.dp(14f), AndroidUtilities.dp(8f))
                setOnClickListener {
                    filterState.curvesToolValue.activeType = type
                    curvesControl.invalidate()
                    updateCurveChannelStyles()
                }
            }
            curveChannelButtons.add(btn)
            curvesPanel.addView(btn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        bottomPanel.addView(curvesPanel)

        // BLUR PANEL (Upstream PhotoFilterView.java:660-684: mode 1 -> control type 1 Radial; mode 2 -> control type 0 Linear!)
        blurPanel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(14f), AndroidUtilities.dp(16f), AndroidUtilities.dp(14f))
        }
        listOf(
            0 to context.getString(R.string.blur_off),
            1 to context.getString(R.string.blur_radial),
            2 to context.getString(R.string.blur_linear)
        ).forEach { (mode, label) ->
            val btn = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER
                setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(8f), AndroidUtilities.dp(14f), AndroidUtilities.dp(8f))
                setOnClickListener {
                    filterState.blurType = mode
                    if (mode == 0) {
                        blurControl.visibility = View.GONE
                    } else {
                        // Upstream mapping: PhotoFilterBlurControl type 1 = Radial, type 0 = Linear!
                        blurControl.setType(if (mode == 1) 1 else 0)
                        blurControl.visibility = View.VISIBLE
                    }
                    updateBlurModeStyles()
                    updateResetVisibility()
                    glThread?.requestRender(true, true, false)
                }
            }
            blurModeButtons.add(btn)
            blurPanel.addView(btn, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        bottomPanel.addView(blurPanel)

        // Telegram Bottom Row: `✕` | Tune Icon | Curves Icon | Blur Icon | `✓`
        val bottomRow = LinearLayout(context).apply {
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
        bottomRow.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f)))

        listOf(
            SubTab.TUNE to R.drawable.ic_tg_tune,
            SubTab.CURVES to R.drawable.ic_tg_curves,
            SubTab.BLUR to R.drawable.ic_tg_blur_drop
        ).forEach { (tab, iconRes) ->
            val iv = ImageView(context).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
                setOnClickListener { switchTab(tab) }
            }
            tabIcons[tab] = iv
            bottomRow.addView(iv, LinearLayout.LayoutParams(0, AndroidUtilities.dp(46f), 1f))
        }

        val checkBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_check)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            setOnClickListener { bakeAndFinish() }
        }
        bottomRow.addView(checkBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f)))
        bottomPanel.addView(bottomRow)

        setupTextureListener()
        selectTuneTool(0)
        switchTab(SubTab.TUNE)
        updateCurveChannelStyles()
        updateBlurModeStyles()

        previewContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            layoutTextureToAspect(right - left, bottom - top)
        }
    }

    private fun rebuildTintSwatches(tool: TuneTool) {
        tintColorRow.removeAllViews()
        val colors = if (tool.isTintShadows) tintShadowColors else tintHighlightColors
        val current = tool.getter(filterState)
        colors.forEach { color ->
            val selected = color == current
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (color == 0) 0xFF2C2C2E.toInt() else color)
                    setStroke(
                        AndroidUtilities.dp(if (selected) 3f else 1.5f),
                        when {
                            selected -> 0xFF4DA3FF.toInt()
                            color == 0 -> 0xFF888888.toInt()
                            else -> 0x88FFFFFF.toInt()
                        }
                    )
                }
                setOnClickListener {
                    tool.setter(filterState, color)
                    toolValueText.text = if (color == 0) "Off" else "On"
                    rebuildTintSwatches(tool)
                    updateChipStates()
                    updateResetVisibility()
                    glThread?.requestRender(filterState.blurType != 0, true, false)
                }
            }
            val size = AndroidUtilities.dp(28f)
            tintColorRow.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                setMargins(AndroidUtilities.dp(5f), 0, AndroidUtilities.dp(5f), 0)
            })
        }
    }

    private fun resetAllFilters() {
        filterState.reset()
        blurControl.reset()
        blurControl.visibility = View.GONE
        curvesControl.invalidate()
        selectTuneTool(selectedToolIndex)
        updateCurveChannelStyles()
        updateBlurModeStyles()
        updateResetVisibility()
        glThread?.requestRender(true, true, false)
    }

    private fun updateResetVisibility() {
        resetFloatingBtn.visibility = if (filterState.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun layoutTextureToAspect(availW: Int, availH: Int) {
        if (availW <= 0 || availH <= 0) return
        val bw = sourceBitmap.width.toFloat().coerceAtLeast(1f)
        val bh = sourceBitmap.height.toFloat().coerceAtLeast(1f)
        val scale = min(availW / bw, availH / bh)
        val targetW = (bw * scale).roundToInt().coerceAtLeast(1)
        val targetH = (bh * scale).roundToInt().coerceAtLeast(1)

        val lp = textureView.layoutParams as LayoutParams
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW
            lp.height = targetH
            lp.gravity = Gravity.CENTER
            textureView.layoutParams = lp
        }
        val offsetX = (availW - targetW) / 2f
        val offsetY = (availH - targetH) / 2f
        curvesControl.setActualArea(offsetX, offsetY, targetW.toFloat(), targetH.toFloat())
        blurControl.setActualAreaSize(targetW.toFloat(), targetH.toFloat())
    }

    private fun setupTextureListener() {
        val baseDelegate = FilterShaders.getFilterShadersDelegate(filterState)
        // Upstream PhotoFilterView.java:1168 returns (showOriginal || filtersEmpty) to avoid base 0.11 sharpen when untouched
        val wrappedDelegate = object : FilterShaders.FilterShadersDelegate by baseDelegate {
            override fun shouldShowOriginal(): Boolean = showOriginal || filterState.isEmpty()
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (glThread == null) {
                    val thread = FilterGLThread(
                        surface,
                        sourceBitmap,
                        0,
                        false,
                        null,
                        false,
                        null,
                        width,
                        height
                    )
                    thread.setFilterGLThreadDelegate(wrappedDelegate)
                    thread.setSurfaceTextureSize(width, height)
                    thread.requestRender(true, true, false)
                    glThread = thread
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                glThread?.setSurfaceTextureSize(width, height)
                glThread?.requestRender(false, true, false)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                glThread?.shutdown()
                glThread = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    private fun switchTab(tab: SubTab) {
        activeTab = tab
        tunePanel.visibility = if (tab == SubTab.TUNE) View.VISIBLE else View.GONE
        curvesPanel.visibility = if (tab == SubTab.CURVES) View.VISIBLE else View.GONE
        blurPanel.visibility = if (tab == SubTab.BLUR) View.VISIBLE else View.GONE

        curvesControl.visibility = if (tab == SubTab.CURVES) View.VISIBLE else View.GONE
        blurControl.visibility = if (tab == SubTab.BLUR && filterState.blurType != 0) View.VISIBLE else View.GONE

        tabIcons.forEach { (t, icon) ->
            icon.setColorFilter(
                if (t == tab) 0xFF4DA3FF.toInt() else 0xFFFFFFFF.toInt(),
                PorterDuff.Mode.SRC_IN
            )
            icon.alpha = if (t == tab) 1.0f else 0.7f
        }
    }

    private fun selectTuneTool(index: Int) {
        selectedToolIndex = index
        val tool = tuneTools[index]
        toolTitleText.text = tool.label
        if (tool.isTintColor) {
            seekBar.visibility = View.GONE
            tintColorRow.visibility = View.VISIBLE
            val current = tool.getter(filterState)
            toolValueText.text = if (current == 0) "Off" else "On"
            rebuildTintSwatches(tool)
        } else {
            tintColorRow.visibility = View.GONE
            seekBar.visibility = View.VISIBLE
            seekBar.setMinMax(tool.min, tool.max)
            val current = tool.getter(filterState)
            seekBar.setProgress(current, false)
            toolValueText.text = current.toString()
        }
        updateChipStates()
    }

    private fun updateChipStates() {
        toolChipViews.forEachIndexed { idx, chip ->
            val tool = tuneTools[idx]
            val activeValue = tool.getter(filterState) != 0
            val selected = idx == selectedToolIndex
            chip.setTextColor(
                when {
                    selected -> Color.BLACK
                    activeValue -> 0xFF4DA3FF.toInt()
                    else -> 0xFFD0D0D0.toInt()
                }
            )
            chip.background = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(16f).toFloat()
                setColor(
                    when {
                        selected -> Color.WHITE
                        activeValue -> 0xFF1A2E47.toInt()
                        else -> 0xFF1C1C1E.toInt()
                    }
                )
            }
        }
    }

    private fun updateCurveChannelStyles() {
        val colors = intArrayOf(Color.WHITE, 0xFFFF5252.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt())
        curveChannelButtons.forEachIndexed { idx, btn ->
            val selected = filterState.curvesToolValue.activeType == idx
            btn.setTextColor(if (selected) colors[idx] else 0xFF757575.toInt())
        }
    }

    private fun updateBlurModeStyles() {
        blurModeButtons.forEachIndexed { idx, btn ->
            val selected = filterState.blurType == idx
            btn.setTextColor(if (selected) 0xFF4DA3FF.toInt() else 0xFF9E9E9E.toInt())
        }
    }

    private fun bakeAndFinish() {
        val thread = glThread
        if (thread == null || filterState.isEmpty()) {
            cleanup()
            onDone(sourceBitmap)
            return
        }
        exportExecutor.execute {
            val rendered = thread.texture ?: sourceBitmap
            post {
                cleanup()
                onDone(rendered)
            }
        }
    }

    fun cleanup() {
        glThread?.shutdown()
        glThread = null
        exportExecutor.shutdownNow()
    }
}
