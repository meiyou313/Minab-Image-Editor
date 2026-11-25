package com.telephoto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MediaController
import org.telegram.messenger.R
import org.telegram.ui.Components.FilterGLThread
import org.telegram.ui.Components.FilterShaders
import org.telegram.ui.Components.PhotoFilterBlurControl
import org.telegram.ui.Components.PhotoFilterCurvesControl
import org.telegram.ui.Components.PhotoFilterView
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Samsung Gallery-inspired Tone Editor + Telegram OpenGL ES 2.0 Filter/Curves/Blur + Custom Presets.
 * - Tone tab: Centered label & value -> Circular vector icon carousel matching Samsung Gallery
 *   (Light Balance/Enhance, Brightness, Exposure, Contrast, Highlights, Shadows, Saturation,
 *    Tint, Temperature, Sharpness, Definition + merged Fade, Vignette, Grain, Soften Skin,
 *    Tint Shadows, Tint Highlights) -> Samsung vertical tick ruler slider!
 * - Presets tab: 6 curated presets + custom user-saved presets (+ Save Current).
 * - Curves tab: Real Telegram 4-channel spline Curves.
 * - Blur tab: Real Telegram Radial / Linear Tilt-Shift Blur.
 * - Non-destructive: Restores and returns SavedFilterState so re-opening Adjust resumes cleanly!
 */
@SuppressLint("ClickableViewAccessibility")
class TelegramFilterPanel(
    context: Context,
    private val sourceBitmap: Bitmap,
    initialFilterState: MediaController.SavedFilterState? = null,
    private val onCancel: () -> Unit,
    private val onDone: (Bitmap, MediaController.SavedFilterState) -> Unit
) : FrameLayout(context) {

    private enum class SubTab { TUNE, PRESETS, CURVES, BLUR }

    private data class ToneTool(
        val id: Int,
        val labelKey: String,
        val iconRes: Int,
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

    // Exact Samsung Gallery Tone order merged cleanly with existing Telegram tools
    private val toneTools = listOf(
        ToneTool(0, "tool_enhance", R.drawable.ic_tone_light_balance, 0, 100, getter = { it.enhanceValue.roundToInt() }, setter = { s, v -> s.enhanceValue = v.toFloat() }),
        ToneTool(1, "tool_brightness", R.drawable.ic_tone_brightness, -100, 100, getter = { it.brightnessValue.roundToInt() }, setter = { s, v -> s.brightnessValue = v.toFloat() }),
        ToneTool(2, "tool_exposure", R.drawable.ic_tone_exposure, -100, 100, getter = { it.exposureValue.roundToInt() }, setter = { s, v -> s.exposureValue = v.toFloat() }),
        ToneTool(3, "tool_contrast", R.drawable.ic_tone_contrast, -100, 100, getter = { it.contrastValue.roundToInt() }, setter = { s, v -> s.contrastValue = v.toFloat() }),
        ToneTool(4, "tool_highlights", R.drawable.ic_tone_highlights, -100, 100, getter = { it.highlightsValue.roundToInt() }, setter = { s, v -> s.highlightsValue = v.toFloat() }),
        ToneTool(5, "tool_shadows", R.drawable.ic_tone_shadows, -100, 100, getter = { it.shadowsValue.roundToInt() }, setter = { s, v -> s.shadowsValue = v.toFloat() }),
        ToneTool(6, "tool_saturation", R.drawable.ic_tone_saturation, -100, 100, getter = { it.saturationValue.roundToInt() }, setter = { s, v -> s.saturationValue = v.toFloat() }),
        ToneTool(7, "tool_tint", R.drawable.ic_tone_tint, -100, 100, getter = { it.tintValue.roundToInt() }, setter = { s, v -> s.tintValue = v.toFloat() }),
        ToneTool(8, "tool_temperature", R.drawable.ic_tone_temperature, -100, 100, getter = { it.warmthValue.roundToInt() }, setter = { s, v -> s.warmthValue = v.toFloat() }),
        ToneTool(9, "tool_sharpness", R.drawable.ic_tone_sharpness, 0, 100, getter = { it.sharpenValue.roundToInt() }, setter = { s, v -> s.sharpenValue = v.toFloat() }),
        ToneTool(10, "tool_definition", R.drawable.ic_tone_definition, 0, 100, getter = { it.definitionValue.roundToInt() }, setter = { s, v -> s.definitionValue = v.toFloat() }),
        ToneTool(11, "tool_fade", R.drawable.ic_tone_fade, 0, 100, getter = { it.fadeValue.roundToInt() }, setter = { s, v -> s.fadeValue = v.toFloat() }),
        ToneTool(12, "tool_vignette", R.drawable.ic_tone_vignette, 0, 100, getter = { it.vignetteValue.roundToInt() }, setter = { s, v -> s.vignetteValue = v.toFloat() }),
        ToneTool(13, "tool_grain", R.drawable.ic_tone_grain, 0, 100, getter = { it.grainValue.roundToInt() }, setter = { s, v -> s.grainValue = v.toFloat() }),
        ToneTool(14, "tool_skin", R.drawable.ic_tone_skin, 0, 100, getter = { it.softenSkinValue.roundToInt() }, setter = { s, v -> s.softenSkinValue = v.toFloat() }),
        ToneTool(15, "tool_tint_shadows", R.drawable.ic_tone_tint_swatch, 0, 0, isTintShadows = true, getter = { it.tintShadowsColor }, setter = { s, v -> s.tintShadowsColor = v }),
        ToneTool(16, "tool_tint_highlights", R.drawable.ic_tone_tint_swatch, 0, 0, isTintHighlights = true, getter = { it.tintHighlightsColor }, setter = { s, v -> s.tintHighlightsColor = v })
    )

    private val filterState = MediaController.SavedFilterState().apply {
        if (initialFilterState != null) copyFrom(initialFilterState)
    }
    private val exportExecutor = Executors.newSingleThreadExecutor()

    private var activeTab = SubTab.TUNE
    private var selectedToolIndex = 1 // Default to Brightness like Samsung Gallery frame 00:00
    @Volatile
    private var showOriginal = false
    private var glThread: FilterGLThread? = null

    private val previewContainer: FrameLayout
    private val textureView: TextureView
    private val curvesControl: PhotoFilterCurvesControl
    private val blurControl: PhotoFilterBlurControl
    private val resetFloatingBtn: TextView
    private val originalCompareBadge: TextView

    private val toolTitleText: TextView
    private val toolValueText: TextView
    private val rulerSlider: SamsungToneRulerView
    private val tintColorRow: LinearLayout
    private val toolCircleFrames = mutableListOf<FrameLayout>()
    private val toolCircleIcons = mutableListOf<ImageView>()
    private val toolCircleDots = mutableListOf<View>()

    private val tunePanel: LinearLayout
    private val presetsPanel: LinearLayout
    private val presetsRow: LinearLayout
    private val curvesPanel: LinearLayout
    private val blurPanel: LinearLayout
    private val tabIcons = mutableMapOf<SubTab, ImageView>()
    private val curveChannelButtons = mutableListOf<TextView>()
    private val blurModeButtons = mutableListOf<TextView>()
    private var activePresetId: String? = null

    init {
        setBackgroundColor(AppTheme.bgCanvas)
        layoutDirection = AppLocale.layoutDirection()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Preview stage
        previewContainer = FrameLayout(context).apply {
            setBackgroundColor(AppTheme.bgCanvas)
        }
        root.addView(previewContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        textureView = TextureView(context).apply {
            setOnTouchListener { _, event ->
                if (activeTab == SubTab.TUNE || activeTab == SubTab.PRESETS) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            showOriginal = true
                            originalCompareBadge.visibility = View.VISIBLE
                            glThread?.requestRender(false, true, false)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            showOriginal = false
                            originalCompareBadge.visibility = View.GONE
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
                activePresetId = null
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

        originalCompareBadge = TextView(context).apply {
            text = AppLocale.t("original")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
            background = GradientDrawable().apply {
                setColor(0xAA000000.toInt())
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
        }
        previewContainer.addView(
            originalCompareBadge,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = AndroidUtilities.dp(10f)
                leftMargin = AndroidUtilities.dp(12f)
            }
        )

        resetFloatingBtn = TextView(context).apply {
            text = AppLocale.t("reset")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(7f), AndroidUtilities.dp(14f), AndroidUtilities.dp(7f))
            background = GradientDrawable().apply {
                setColor(0xAA1C1C1E.toInt())
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

        // Bottom surface
        val bottomPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.bgSurface)
        }
        root.addView(bottomPanel, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // === 1. SAMSUNG-STYLE TONE PANEL ===
        tunePanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(4f))
        }

        // Centered Active Tone Title + Value (tap value to reset single tool)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(16f), 0, AndroidUtilities.dp(16f), AndroidUtilities.dp(6f))
        }
        toolTitleText = TextView(context).apply {
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = AndroidUtilities.bold()
        }
        toolValueText = TextView(context).apply {
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(8f), 0, 0, 0)
            setOnClickListener {
                val tool = toneTools[selectedToolIndex]
                tool.setter(filterState, 0)
                selectToneTool(selectedToolIndex)
                updateResetVisibility()
                glThread?.requestRender(true, true, false)
            }
        }
        headerRow.addView(toolTitleText)
        headerRow.addView(toolValueText)
        tunePanel.addView(headerRow)

        // Horizontal Circular Tone Icon Carousel (Samsung Gallery style)
        val circleScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(2f), AndroidUtilities.dp(12f), AndroidUtilities.dp(4f))
        }
        val circleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val circleSize = AndroidUtilities.dp(48f)
        toneTools.forEachIndexed { idx, tool ->
            val circleFrame = FrameLayout(context).apply {
                setOnClickListener { selectToneTool(idx) }
            }
            val iv = ImageView(context).apply {
                setImageResource(tool.iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(AndroidUtilities.dp(11f), AndroidUtilities.dp(11f), AndroidUtilities.dp(11f), AndroidUtilities.dp(11f))
            }
            val dot = View(context).apply {
                visibility = View.GONE
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AppTheme.accent)
                }
            }
            circleFrame.addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            circleFrame.addView(
                dot,
                LayoutParams(AndroidUtilities.dp(5f), AndroidUtilities.dp(5f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = AndroidUtilities.dp(3f)
                }
            )
            toolCircleFrames.add(circleFrame)
            toolCircleIcons.add(iv)
            toolCircleDots.add(dot)

            circleRow.addView(
                circleFrame,
                LinearLayout.LayoutParams(circleSize, circleSize).apply {
                    setMargins(AndroidUtilities.dp(6f), AndroidUtilities.dp(2f), AndroidUtilities.dp(6f), AndroidUtilities.dp(2f))
                }
            )
        }
        circleScroll.addView(circleRow)
        tunePanel.addView(circleScroll)

        // Samsung Vertical Tick Ruler Slider (or Tint Swatches when Tint Shadows/Highlights selected)
        val sliderHost = FrameLayout(context).apply {
            setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(2f), AndroidUtilities.dp(6f), AndroidUtilities.dp(4f))
        }
        rulerSlider = SamsungToneRulerView(context).apply {
            onValueChanged = { progress ->
                val tool = toneTools[selectedToolIndex]
                if (!tool.isTintColor) {
                    activePresetId = null
                    tool.setter(filterState, progress)
                    toolValueText.text = formatToneValue(progress)
                    updateCircleStates()
                    updateResetVisibility()
                    glThread?.requestRender(true, true, false)
                }
            }
        }
        sliderHost.addView(rulerSlider, LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(44f)))

        tintColorRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f))
        }
        sliderHost.addView(tintColorRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        tunePanel.addView(sliderHost)
        bottomPanel.addView(tunePanel)

        // === 2. CUSTOM PRESETS PANEL ===
        presetsPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(10f))
        }
        val presetsHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), AndroidUtilities.dp(8f))
        }
        val presetsTitle = TextView(context).apply {
            text = AppLocale.t("presets")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = AndroidUtilities.bold()
        }
        val savePresetBtn = TextView(context).apply {
            text = AppLocale.t("save_preset")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(6f), AndroidUtilities.dp(12f), AndroidUtilities.dp(6f))
            background = GradientDrawable().apply {
                setColor(AppTheme.accent)
                cornerRadius = AndroidUtilities.dp(12f).toFloat()
            }
            setOnClickListener { promptSaveCustomPreset() }
        }
        presetsHeader.addView(presetsTitle, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        presetsHeader.addView(savePresetBtn)
        presetsPanel.addView(presetsHeader)

        val presetsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        presetsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        presetsScroll.addView(presetsRow)
        presetsPanel.addView(presetsScroll)
        bottomPanel.addView(presetsPanel)

        // === 3. CURVES PANEL ===
        curvesPanel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
        }
        listOf(
            PhotoFilterView.CurvesToolValue.CurvesTypeLuminance to AppLocale.t("tab_all"),
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

        // === 4. BLUR PANEL ===
        blurPanel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
        }
        listOf(
            0 to AppLocale.t("blur_off"),
            1 to AppLocale.t("blur_radial"),
            2 to AppLocale.t("blur_linear")
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

        // === BOTTOM TAB ROW: `✕` | Tone | Presets | Curves | Blur | `✓` ===
        val bottomRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(2f), AndroidUtilities.dp(10f), AndroidUtilities.dp(8f))
        }

        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_close)
            setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            setOnClickListener {
                cleanup()
                onCancel()
            }
        }
        bottomRow.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f)))

        listOf(
            SubTab.TUNE to R.drawable.ic_tone_brightness,
            SubTab.PRESETS to R.drawable.ic_tg_presets,
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
            setColorFilter(AppTheme.accentText)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            setOnClickListener { bakeAndFinish() }
        }
        bottomRow.addView(checkBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f)))
        bottomPanel.addView(bottomRow)

        setupTextureListener()
        rebuildPresetsRow()
        selectToneTool(selectedToolIndex)
        switchTab(SubTab.TUNE)
        updateCurveChannelStyles()
        updateBlurModeStyles()
        updateResetVisibility()
        if (filterState.blurType != 0) {
            blurControl.setType(if (filterState.blurType == 1) 1 else 0)
        }

        previewContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            layoutTextureToAspect(right - left, bottom - top)
        }
    }

    private fun rebuildPresetsRow() {
        presetsRow.removeAllViews()
        val presets = PresetManager.getAllPresets(context)
        presets.forEach { preset ->
            val selected = preset.id == activePresetId
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(10f))
                background = GradientDrawable().apply {
                    setColor(if (selected) 0x332AABEE else AppTheme.bgElevated)
                    cornerRadius = AndroidUtilities.dp(14f).toFloat()
                    setStroke(AndroidUtilities.dp(if (selected) 2f else 1f), if (selected) AppTheme.accent else AppTheme.borderSubtle)
                }
                setOnClickListener {
                    activePresetId = preset.id
                    preset.applyTo(filterState)
                    blurControl.reset()
                    blurControl.visibility = View.GONE
                    curvesControl.invalidate()
                    selectToneTool(selectedToolIndex)
                    updateResetVisibility()
                    rebuildPresetsRow()
                    glThread?.requestRender(true, true, false)
                }
                if (!preset.isBuiltIn) {
                    setOnLongClickListener {
                        AlertDialog.Builder(context)
                            .setTitle(preset.name)
                            .setMessage("Delete this custom preset?")
                            .setPositiveButton("Delete") { _, _ ->
                                PresetManager.deleteCustomPreset(context, preset.id)
                                rebuildPresetsRow()
                            }
                            .setNegativeButton(AppLocale.t("cancel"), null)
                            .show()
                        true
                    }
                }
            }
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(preset.accentColor)
                }
            }
            val label = TextView(context).apply {
                text = preset.name
                setTextColor(if (selected) AppTheme.accentText else AppTheme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = AndroidUtilities.bold()
                maxLines = 1
                setPadding(0, AndroidUtilities.dp(6f), 0, 0)
            }
            card.addView(dot, LinearLayout.LayoutParams(AndroidUtilities.dp(20f), AndroidUtilities.dp(20f)))
            card.addView(label)
            presetsRow.addView(
                card,
                LinearLayout.LayoutParams(AndroidUtilities.dp(86f), AndroidUtilities.dp(68f)).apply {
                    marginEnd = AndroidUtilities.dp(8f)
                }
            )
        }
    }

    private fun promptSaveCustomPreset() {
        val input = EditText(context).apply {
            hint = AppLocale.t("preset_name")
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
        }
        AlertDialog.Builder(context)
            .setTitle(AppLocale.t("save_preset"))
            .setView(input)
            .setPositiveButton(AppLocale.t("done")) { _, _ ->
                val name = input.text?.toString()?.trim()
                if (!name.isNullOrEmpty()) {
                    val saved = PresetManager.saveCustomPreset(context, name, filterState)
                    activePresetId = saved.id
                    rebuildPresetsRow()
                    Toast.makeText(context, "${AppLocale.t("preset_saved")}: ${saved.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(AppLocale.t("cancel"), null)
            .show()
    }

    private fun rebuildTintSwatches(tool: ToneTool) {
        tintColorRow.removeAllViews()
        val colors = if (tool.isTintShadows) tintShadowColors else tintHighlightColors
        val current = tool.getter(filterState)
        colors.forEach { color ->
            val selected = color == current
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (color == 0) AppTheme.bgElevated else color)
                    setStroke(
                        AndroidUtilities.dp(if (selected) 3f else 1.5f),
                        when {
                            selected -> AppTheme.accentText
                            color == 0 -> 0xFF888888.toInt()
                            else -> 0x88FFFFFF.toInt()
                        }
                    )
                }
                setOnClickListener {
                    activePresetId = null
                    tool.setter(filterState, color)
                    toolValueText.text = if (color == 0) AppLocale.t("blur_off") else "On"
                    rebuildTintSwatches(tool)
                    updateCircleStates()
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
        activePresetId = null
        filterState.reset()
        blurControl.reset()
        blurControl.visibility = View.GONE
        curvesControl.invalidate()
        selectToneTool(selectedToolIndex)
        updateCurveChannelStyles()
        updateBlurModeStyles()
        rebuildPresetsRow()
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
        presetsPanel.visibility = if (tab == SubTab.PRESETS) View.VISIBLE else View.GONE
        curvesPanel.visibility = if (tab == SubTab.CURVES) View.VISIBLE else View.GONE
        blurPanel.visibility = if (tab == SubTab.BLUR) View.VISIBLE else View.GONE

        curvesControl.visibility = if (tab == SubTab.CURVES) View.VISIBLE else View.GONE
        blurControl.visibility = if (tab == SubTab.BLUR && filterState.blurType != 0) View.VISIBLE else View.GONE

        tabIcons.forEach { (t, icon) ->
            icon.setColorFilter(
                if (t == tab) AppTheme.accentText else AppTheme.iconTint,
                PorterDuff.Mode.SRC_IN
            )
            icon.alpha = if (t == tab) 1.0f else 0.65f
        }
    }

    private fun selectToneTool(index: Int) {
        selectedToolIndex = index
        val tool = toneTools[index]
        toolTitleText.text = AppLocale.t(tool.labelKey)
        if (tool.isTintColor) {
            rulerSlider.visibility = View.GONE
            tintColorRow.visibility = View.VISIBLE
            val current = tool.getter(filterState)
            toolValueText.text = if (current == 0) AppLocale.t("blur_off") else "On"
            rebuildTintSwatches(tool)
        } else {
            tintColorRow.visibility = View.GONE
            rulerSlider.visibility = View.VISIBLE
            val current = tool.getter(filterState)
            rulerSlider.setRangeAndValue(tool.min, tool.max, current)
            toolValueText.text = formatToneValue(current)
        }
        updateCircleStates()
    }

    private fun formatToneValue(value: Int): String = when {
        value > 0 -> "+$value"
        else -> value.toString()
    }

    private fun updateCircleStates() {
        toolCircleFrames.forEachIndexed { idx, frame ->
            val tool = toneTools[idx]
            val modified = tool.getter(filterState) != 0
            val selected = idx == selectedToolIndex
            val icon = toolCircleIcons[idx]
            val dot = toolCircleDots[idx]

            frame.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (selected) AppTheme.bgElevated else Color.TRANSPARENT)
                setStroke(
                    AndroidUtilities.dp(if (selected) 1.8f else 1.1f),
                    when {
                        selected -> AppTheme.textPrimary
                        modified -> AppTheme.accent
                        else -> AppTheme.borderSubtle
                    }
                )
            }
            frame.scaleX = if (selected) 1.07f else 1.0f
            frame.scaleY = if (selected) 1.07f else 1.0f

            if (!tool.isTintColor) {
                icon.setColorFilter(
                    when {
                        selected -> AppTheme.textPrimary
                        modified -> AppTheme.accentText
                        else -> AppTheme.textSecondary
                    },
                    PorterDuff.Mode.SRC_IN
                )
            } else {
                icon.clearColorFilter()
            }
            dot.visibility = if (modified) View.VISIBLE else View.GONE
        }
    }

    private fun updateCurveChannelStyles() {
        val colors = intArrayOf(AppTheme.textPrimary, 0xFFFF5252.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt())
        curveChannelButtons.forEachIndexed { idx, btn ->
            val selected = filterState.curvesToolValue.activeType == idx
            btn.setTextColor(if (selected) colors[idx] else 0xFF757575.toInt())
        }
    }

    private fun updateBlurModeStyles() {
        blurModeButtons.forEachIndexed { idx, btn ->
            val selected = filterState.blurType == idx
            btn.setTextColor(if (selected) AppTheme.accentText else AppTheme.textSecondary)
        }
    }

    private fun bakeAndFinish() {
        val resultState = MediaController.SavedFilterState().apply { copyFrom(filterState) }
        val thread = glThread
        if (thread == null || filterState.isEmpty()) {
            cleanup()
            onDone(sourceBitmap, resultState)
            return
        }
        exportExecutor.execute {
            val rendered = thread.texture ?: sourceBitmap
            post {
                cleanup()
                onDone(rendered, resultState)
            }
        }
    }

    fun cleanup() {
        glThread?.shutdown()
        glThread = null
        exportExecutor.shutdownNow()
    }
}
