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
 * Samsung Gallery-inspired Filters + Tone Editor powered by Telegram OpenGL ES 2.0 Engine:
 * - Filters tab: Exact Samsung Gallery UI (centered filter name, fixed `+` custom filter button,
 *   scrollable real-photo square thumbnails for Original, Auto, Warm, Cool, Light, Frosty,
 *   Blossom, Ivory, Soft, KissMe, Grayscale, B&W + custom filters, strength number overlaid on
 *   selected thumbnail, and Samsung vertical tick-mark intensity slider 0..100 underneath).
 * - Tone tab: Samsung circular vector icon carousel + vertical tick ruler slider merged with
 *   Telegram GPU controls.
 * - Curves & Blur tabs: Real Telegram 4-channel Curves & Radial/Linear Tilt-Shift Blur.
 * - Non-destructive: Composes Filter * Strength + Manual Tone cleanly, AND floats Layer 3
 *   Paint/Text overlay preview cleanly above the OpenGL canvas without baking or tinting it!
 */
@SuppressLint("ClickableViewAccessibility")
class TelegramFilterPanel(
    context: Context,
    private val sourceBitmap: Bitmap,
    initialRecipe: EditLayerState.FilterRecipe = EditLayerState.FilterRecipe(),
    initialTab: InitialTab = InitialTab.FILTERS,
    private val paintOverlayPreview: Bitmap? = null,
    private val onCancel: () -> Unit,
    private val onDone: (Bitmap, EditLayerState.FilterRecipe) -> Unit
) : FrameLayout(context) {

    enum class InitialTab { FILTERS, TUNE, CURVES, BLUR }
    private enum class SubTab { FILTERS, TUNE, CURVES, BLUR }

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

    private var selectedFilterId: String = initialRecipe.selectedFilterId
    private var filterStrength: Int = initialRecipe.filterStrength.coerceIn(0, 100)
    private val manualToneState = MediaController.SavedFilterState().apply {
        copyFrom(initialRecipe.manualTone)
    }
    private val effectiveFilterState = MediaController.SavedFilterState()

    private val workerExecutor = Executors.newSingleThreadExecutor()
    private val thumbCache = mutableMapOf<String, Bitmap>()
    private var tinySquareBase: Bitmap? = null

    private var activeTab = when (initialTab) {
        InitialTab.FILTERS -> SubTab.FILTERS
        InitialTab.TUNE -> SubTab.TUNE
        InitialTab.CURVES -> SubTab.CURVES
        InitialTab.BLUR -> SubTab.BLUR
    }
    private var selectedToolIndex = 1 // Brightness default
    @Volatile
    private var showOriginal = false
    private var glThread: FilterGLThread? = null

    private val previewContainer: FrameLayout
    private val textureView: TextureView
    private val paintOverlayView: ImageView
    private val curvesControl: PhotoFilterCurvesControl
    private val blurControl: PhotoFilterBlurControl
    private val resetFloatingBtn: TextView
    private val originalCompareBadge: TextView

    // Samsung Filters UI
    private val filtersPanel: LinearLayout
    private val filterNameText: TextView
    private val filterThumbRow: LinearLayout
    private val filterStrengthRuler: SamsungToneRulerView
    private val filterCardViews = mutableMapOf<String, FilterCardHolder>()

    private data class FilterCardHolder(
        val borderView: FrameLayout,
        val thumbImage: ImageView,
        val strengthOverlay: TextView
    )

    // Samsung Tone UI
    private val tunePanel: LinearLayout
    private val toolTitleText: TextView
    private val toolValueText: TextView
    private val rulerSlider: SamsungToneRulerView
    private val tintColorRow: LinearLayout
    private val toolCircleFrames = mutableListOf<FrameLayout>()
    private val toolCircleIcons = mutableListOf<ImageView>()
    private val toolCircleDots = mutableListOf<View>()

    private val curvesPanel: LinearLayout
    private val blurPanel: LinearLayout
    private val tabIcons = mutableMapOf<SubTab, ImageView>()
    private val curveChannelButtons = mutableListOf<TextView>()
    private val blurModeButtons = mutableListOf<TextView>()

    init {
        setBackgroundColor(AppTheme.bgCanvas)
        layoutDirection = AppLocale.layoutDirection()
        recomputeEffectiveState()

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
                if (activeTab == SubTab.FILTERS || activeTab == SubTab.TUNE) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            showOriginal = true
                            originalCompareBadge.visibility = View.VISIBLE
                            paintOverlayView.visibility = View.INVISIBLE
                            glThread?.requestRender(false, true, false)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            showOriginal = false
                            originalCompareBadge.visibility = View.GONE
                            if (paintOverlayPreview != null) {
                                paintOverlayView.visibility = View.VISIBLE
                            }
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

        // Non-destructive Layer 3 overlay (arrows, strokes & text stay sharp on top while grading!)
        paintOverlayView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            isClickable = false
            isFocusable = false
            if (paintOverlayPreview != null) {
                setImageBitmap(paintOverlayPreview)
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
        previewContainer.addView(
            paintOverlayView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )

        curvesControl = PhotoFilterCurvesControl(context, manualToneState.curvesToolValue).apply {
            visibility = View.GONE
            setDelegate {
                recomputeEffectiveState()
                updateResetVisibility()
                glThread?.requestRender(effectiveFilterState.blurType != 0, true, false)
            }
        }
        previewContainer.addView(curvesControl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        blurControl = PhotoFilterBlurControl(context).apply {
            visibility = View.GONE
            setDelegate { centerPoint, falloff, size, angle ->
                manualToneState.blurExcludeSize = size
                manualToneState.blurExcludePoint = PointF(centerPoint.x, centerPoint.y)
                manualToneState.blurExcludeBlurSize = falloff
                manualToneState.blurAngle = angle
                recomputeEffectiveState()
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

        // === 1. SAMSUNG GALLERY-STYLE FILTERS PANEL ===
        filtersPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(4f))
        }

        filterNameText = TextView(context).apply {
            text = "Original"
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(16f), 0, AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        }
        filtersPanel.addView(filterNameText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Fixed `+` square button on start + scrollable real-photo thumbnail strip (exact Samsung Gallery layout!)
        val filterStripContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(8f), AndroidUtilities.dp(4f))
        }

        val thumbSize = AndroidUtilities.dp(56f)
        val addCustomFilterBtn = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(if (AppTheme.isDark) 0xFF2C2C2E.toInt() else 0xFFE5E7EB.toInt())
                cornerRadius = AndroidUtilities.dp(10f).toFloat()
            }
            setOnClickListener { promptSaveCustomFilter() }
        }
        val plusIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_plus)
            setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(15f), AndroidUtilities.dp(15f), AndroidUtilities.dp(15f), AndroidUtilities.dp(15f))
        }
        addCustomFilterBtn.addView(plusIcon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val filterScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        filterThumbRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterScroll.addView(filterThumbRow)

        filterStripContainer.addView(
            addCustomFilterBtn,
            LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                marginEnd = AndroidUtilities.dp(10f)
            }
        )
        filterStripContainer.addView(filterScroll, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        filtersPanel.addView(filterStripContainer)

        // Samsung vertical tick-mark intensity slider (0..100) right below the filter thumbnails
        filterStrengthRuler = SamsungToneRulerView(context).apply {
            setRangeAndValue(0, 100, filterStrength)
            onValueChanged = { nextStrength ->
                filterStrength = nextStrength
                updateFilterSelectionVisuals()
                recomputeEffectiveState()
                updateResetVisibility()
                glThread?.requestRender(true, true, false)
            }
        }
        filtersPanel.addView(
            filterStrengthRuler,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, AndroidUtilities.dp(42f)).apply {
                leftMargin = AndroidUtilities.dp(6f)
                rightMargin = AndroidUtilities.dp(6f)
            }
        )
        bottomPanel.addView(filtersPanel)

        // === 2. SAMSUNG-STYLE TONE PANEL ===
        tunePanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(4f))
        }

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
                tool.setter(manualToneState, 0)
                recomputeEffectiveState()
                selectToneTool(selectedToolIndex)
                updateResetVisibility()
                glThread?.requestRender(true, true, false)
            }
        }
        headerRow.addView(toolTitleText)
        headerRow.addView(toolValueText)
        tunePanel.addView(headerRow)

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

        val sliderHost = FrameLayout(context).apply {
            setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(2f), AndroidUtilities.dp(6f), AndroidUtilities.dp(4f))
        }
        rulerSlider = SamsungToneRulerView(context).apply {
            onValueChanged = { progress ->
                val tool = toneTools[selectedToolIndex]
                if (!tool.isTintColor) {
                    tool.setter(manualToneState, progress)
                    recomputeEffectiveState()
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
                    manualToneState.curvesToolValue.activeType = type
                    recomputeEffectiveState()
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
                    manualToneState.blurType = mode
                    recomputeEffectiveState()
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

        // === BOTTOM TAB ROW: `✕` | Filters (Samsung 3-circles) | Tone | Curves | Blur | `✓` ===
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
            SubTab.FILTERS to R.drawable.ic_samsung_filters,
            SubTab.TUNE to R.drawable.ic_tone_brightness,
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
        rebuildSamsungFiltersStrip()
        selectToneTool(selectedToolIndex)
        switchTab(activeTab)
        updateCurveChannelStyles()
        updateBlurModeStyles()
        updateResetVisibility()
        if (manualToneState.blurType != 0) {
            blurControl.setType(if (manualToneState.blurType == 1) 1 else 0)
        }

        previewContainer.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            layoutTextureToAspect(right - left, bottom - top)
        }
    }

    private fun recomputeEffectiveState() {
        val preset = PresetManager.findById(context, selectedFilterId)
        preset.applyScaledWithManualTone(effectiveFilterState, filterStrength, manualToneState)
    }

    private fun rebuildSamsungFiltersStrip() {
        filterThumbRow.removeAllViews()
        filterCardViews.clear()
        val presets = PresetManager.getAllPresets(context)
        val cardSize = AndroidUtilities.dp(56f)

        presets.forEach { preset ->
            val cardFrame = FrameLayout(context).apply {
                clipToOutline = true
                setOnClickListener {
                    if (selectedFilterId != preset.id) {
                        selectedFilterId = preset.id
                        if (!preset.isOriginal && filterStrength == 0) {
                            filterStrength = 100
                        }
                        filterStrengthRuler.setRangeAndValue(0, 100, filterStrength)
                        recomputeEffectiveState()
                        updateFilterSelectionVisuals()
                        updateResetVisibility()
                        glThread?.requestRender(true, true, false)
                    }
                }
                if (!preset.isBuiltIn) {
                    setOnLongClickListener {
                        AlertDialog.Builder(context)
                            .setTitle(preset.name)
                            .setMessage("Delete this custom filter?")
                            .setPositiveButton("Delete") { _, _ ->
                                PresetManager.deleteCustomPreset(context, preset.id)
                                if (selectedFilterId == preset.id) {
                                    selectedFilterId = PresetManager.ORIGINAL_ID
                                    recomputeEffectiveState()
                                    glThread?.requestRender(true, true, false)
                                }
                                rebuildSamsungFiltersStrip()
                            }
                            .setNegativeButton(AppLocale.t("cancel"), null)
                            .show()
                        true
                    }
                }
            }

            val thumbIv = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                thumbCache[preset.id]?.let { setImageBitmap(it) }
            }

            val strengthOverlay = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER
                setBackgroundColor(0x66000000)
                visibility = View.GONE
            }

            val borderOverlay = FrameLayout(context)

            cardFrame.addView(thumbIv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cardFrame.addView(strengthOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            cardFrame.addView(borderOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

            filterCardViews[preset.id] = FilterCardHolder(borderOverlay, thumbIv, strengthOverlay)

            filterThumbRow.addView(
                cardFrame,
                LinearLayout.LayoutParams(cardSize, cardSize).apply {
                    marginEnd = AndroidUtilities.dp(5f)
                }
            )
        }

        updateFilterSelectionVisuals()
        renderFilterThumbnailsAsync(presets)
    }

    private fun renderFilterThumbnailsAsync(presets: List<PresetManager.FilterPreset>) {
        workerExecutor.execute {
            val base = tinySquareBase ?: PresetManager.createSquareThumbBase(sourceBitmap, 120).also {
                tinySquareBase = it
            }
            presets.forEach { preset ->
                if (!thumbCache.containsKey(preset.id)) {
                    val bmp = PresetManager.renderFilterThumbnail(base, preset)
                    thumbCache[preset.id] = bmp
                    post {
                        filterCardViews[preset.id]?.thumbImage?.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    private fun updateFilterSelectionVisuals() {
        val currentPreset = PresetManager.findById(context, selectedFilterId)
        filterNameText.text = currentPreset.name
        val showStrength = !currentPreset.isOriginal
        filterStrengthRuler.visibility = if (showStrength) View.VISIBLE else View.INVISIBLE
        if (showStrength) {
            filterStrengthRuler.setRangeAndValue(0, 100, filterStrength)
        }

        val borderRadius = AndroidUtilities.dp(9f).toFloat()
        filterCardViews.forEach { (id, holder) ->
            val selected = id == selectedFilterId
            val isOrig = id == PresetManager.ORIGINAL_ID
            holder.borderView.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = borderRadius
                if (selected) {
                    setStroke(AndroidUtilities.dp(2.5f), if (AppTheme.isDark) Color.WHITE else AppTheme.accent)
                } else {
                    setStroke(AndroidUtilities.dp(1f), AppTheme.borderSubtle)
                }
            }
            (holder.borderView.parent as? FrameLayout)?.background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = borderRadius
            }
            if (selected && !isOrig) {
                holder.strengthOverlay.text = filterStrength.toString()
                holder.strengthOverlay.visibility = View.VISIBLE
            } else {
                holder.strengthOverlay.visibility = View.GONE
            }
        }
    }

    private fun promptSaveCustomFilter() {
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
                    val saved = PresetManager.saveCustomPreset(context, name, effectiveFilterState)
                    selectedFilterId = saved.id
                    filterStrength = 100
                    manualToneState.reset()
                    recomputeEffectiveState()
                    rebuildSamsungFiltersStrip()
                    Toast.makeText(context, "${AppLocale.t("preset_saved")}: ${saved.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(AppLocale.t("cancel"), null)
            .show()
    }

    private fun rebuildTintSwatches(tool: ToneTool) {
        tintColorRow.removeAllViews()
        val colors = if (tool.isTintShadows) tintShadowColors else tintHighlightColors
        val current = tool.getter(manualToneState)
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
                    tool.setter(manualToneState, color)
                    recomputeEffectiveState()
                    toolValueText.text = if (color == 0) AppLocale.t("blur_off") else "On"
                    rebuildTintSwatches(tool)
                    updateCircleStates()
                    updateResetVisibility()
                    glThread?.requestRender(effectiveFilterState.blurType != 0, true, false)
                }
            }
            val size = AndroidUtilities.dp(28f)
            tintColorRow.addView(dot, LinearLayout.LayoutParams(size, size).apply {
                setMargins(AndroidUtilities.dp(5f), 0, AndroidUtilities.dp(5f), 0)
            })
        }
    }

    private fun resetAllFilters() {
        selectedFilterId = PresetManager.ORIGINAL_ID
        filterStrength = 100
        manualToneState.reset()
        recomputeEffectiveState()
        blurControl.reset()
        blurControl.visibility = View.GONE
        curvesControl.invalidate()
        updateFilterSelectionVisuals()
        selectToneTool(selectedToolIndex)
        updateCurveChannelStyles()
        updateBlurModeStyles()
        updateResetVisibility()
        glThread?.requestRender(true, true, false)
    }

    private fun updateResetVisibility() {
        val empty = (selectedFilterId == PresetManager.ORIGINAL_ID || filterStrength == 0) && manualToneState.isEmpty()
        resetFloatingBtn.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun layoutTextureToAspect(availW: Int, availH: Int) {
        if (availW <= 0 || availH <= 0) return
        val bw = sourceBitmap.width.toFloat().coerceAtLeast(1f)
        val bh = sourceBitmap.height.toFloat().coerceAtLeast(1f)
        val scale = min(availW / bw, availH / bh)
        val targetW = (bw * scale).roundToInt().coerceAtLeast(1)
        val targetH = (bh * scale).roundToInt().coerceAtLeast(1)

        listOf(textureView, paintOverlayView).forEach { v ->
            val lp = v.layoutParams as LayoutParams
            if (lp.width != targetW || lp.height != targetH) {
                lp.width = targetW
                lp.height = targetH
                lp.gravity = Gravity.CENTER
                v.layoutParams = lp
            }
        }
        val offsetX = (availW - targetW) / 2f
        val offsetY = (availH - targetH) / 2f
        curvesControl.setActualArea(offsetX, offsetY, targetW.toFloat(), targetH.toFloat())
        blurControl.setActualAreaSize(targetW.toFloat(), targetH.toFloat())
    }

    private fun setupTextureListener() {
        val baseDelegate = FilterShaders.getFilterShadersDelegate(effectiveFilterState)
        val wrappedDelegate = object : FilterShaders.FilterShadersDelegate by baseDelegate {
            override fun shouldShowOriginal(): Boolean = showOriginal || effectiveFilterState.isEmpty()
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
        filtersPanel.visibility = if (tab == SubTab.FILTERS) View.VISIBLE else View.GONE
        tunePanel.visibility = if (tab == SubTab.TUNE) View.VISIBLE else View.GONE
        curvesPanel.visibility = if (tab == SubTab.CURVES) View.VISIBLE else View.GONE
        blurPanel.visibility = if (tab == SubTab.BLUR) View.VISIBLE else View.GONE

        curvesControl.visibility = if (tab == SubTab.CURVES) View.VISIBLE else View.GONE
        blurControl.visibility = if (tab == SubTab.BLUR && manualToneState.blurType != 0) View.VISIBLE else View.GONE

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
            val current = tool.getter(manualToneState)
            toolValueText.text = if (current == 0) AppLocale.t("blur_off") else "On"
            rebuildTintSwatches(tool)
        } else {
            tintColorRow.visibility = View.GONE
            rulerSlider.visibility = View.VISIBLE
            val current = tool.getter(manualToneState)
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
            val modified = tool.getter(manualToneState) != 0
            val selected = idx == selectedToolIndex
            val icon = toolCircleIcons[idx]
            val dot = toolCircleDots[idx]

            frame.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when {
                        selected -> AppTheme.toneCircleSelectedBg
                        else -> AppTheme.toneCircleBg
                    }
                )
                setStroke(
                    AndroidUtilities.dp(if (selected) 2.2f else 1.2f),
                    when {
                        selected -> AppTheme.toneCircleSelectedStroke
                        modified -> AppTheme.accent
                        else -> AppTheme.toneCircleStroke
                    }
                )
            }
            icon.setColorFilter(
                when {
                    selected -> AppTheme.accentText
                    modified -> AppTheme.accent
                    else -> AppTheme.iconTint
                },
                PorterDuff.Mode.SRC_IN
            )
            dot.visibility = if (modified) View.VISIBLE else View.GONE
        }
    }

    private fun updateCurveChannelStyles() {
        val activeIdx = when (manualToneState.curvesToolValue.activeType) {
            PhotoFilterView.CurvesToolValue.CurvesTypeLuminance -> 0
            PhotoFilterView.CurvesToolValue.CurvesTypeRed -> 1
            PhotoFilterView.CurvesToolValue.CurvesTypeGreen -> 2
            PhotoFilterView.CurvesToolValue.CurvesTypeBlue -> 3
            else -> 0
        }
        val activeColors = intArrayOf(AppTheme.textPrimary, 0xFFFF5252.toInt(), 0xFF4CAF50.toInt(), 0xFF4DA3FF.toInt())
        curveChannelButtons.forEachIndexed { idx, btn ->
            val active = idx == activeIdx
            btn.setTextColor(if (active) activeColors[idx] else AppTheme.textSecondary)
            btn.background = if (active) {
                GradientDrawable().apply {
                    setColor(AppTheme.bgElevated)
                    cornerRadius = AndroidUtilities.dp(14f).toFloat()
                }
            } else {
                null
            }
        }
    }

    private fun updateBlurModeStyles() {
        blurModeButtons.forEachIndexed { idx, btn ->
            val active = idx == manualToneState.blurType
            btn.setTextColor(if (active) Color.WHITE else AppTheme.textSecondary)
            btn.background = if (active) {
                GradientDrawable().apply {
                    setColor(AppTheme.accent)
                    cornerRadius = AndroidUtilities.dp(14f).toFloat()
                }
            } else {
                null
            }
        }
    }

    private fun bakeAndFinish() {
        recomputeEffectiveState()
        val finalRecipe = EditLayerState.FilterRecipe(
            selectedFilterId = selectedFilterId,
            filterStrength = filterStrength,
            manualTone = MediaController.SavedFilterState().apply { copyFrom(manualToneState) }
        )

        val thread = glThread
        if (thread == null || effectiveFilterState.isEmpty()) {
            cleanup()
            onDone(sourceBitmap, finalRecipe)
            return
        }
        workerExecutor.execute {
            val out = thread.getTexture()
            post {
                val result = out ?: sourceBitmap
                cleanup()
                onDone(result, finalRecipe)
            }
        }
    }

    fun cleanup() {
        glThread?.shutdown()
        glThread = null
        workerExecutor.shutdownNow()
        paintOverlayView.setImageDrawable(null)
        filterCardViews.values.forEach { it.thumbImage.setImageDrawable(null) }
        thumbCache.values.forEach { if (!it.isRecycled) it.recycle() }
        thumbCache.clear()
        tinySquareBase?.let { if (!it.isRecycled) it.recycle() }
        tinySquareBase = null
        paintOverlayPreview?.let { if (!it.isRecycled) it.recycle() }
    }
}
