package com.telephoto.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.Crop.CropRotationWheel
import org.telegram.ui.Components.Crop.CropView

/**
 * Telegram Crop screen with bottom quick-select standard aspect ratio strip
 * (Free, Original, 1:1, 4:3, 3:4, 9:12, 3:2, 2:3, 16:9, 9:16, 4:5, 5:4, 5:7),
 * CropRotationWheel, and CANCEL / RESET / CROP bar.
 */
class TelegramCropPanel(
    context: Context,
    private val sourceBitmap: Bitmap,
    private val onCancel: () -> Unit,
    private val onDone: (Bitmap) -> Unit
) : FrameLayout(context) {

    private data class RatioPreset(val label: String, val ratio: Float, val isOriginal: Boolean = false)

    private val ratioPresets = listOf(
        RatioPreset(AppLocale.t("free"), 0f),
        RatioPreset(AppLocale.t("original"), -1f, isOriginal = true),
        RatioPreset("1:1", 1f),
        RatioPreset("4:3", 4f / 3f),
        RatioPreset("3:4", 3f / 4f),
        RatioPreset("9:12", 9f / 12f),
        RatioPreset("3:2", 3f / 2f),
        RatioPreset("2:3", 2f / 3f),
        RatioPreset("16:9", 16f / 9f),
        RatioPreset("9:16", 9f / 16f),
        RatioPreset("4:5", 4f / 5f),
        RatioPreset("5:4", 5f / 4f),
        RatioPreset("5:7", 5f / 7f)
    )

    private val cropView: CropView
    private val rotationWheel: CropRotationWheel
    private val resetButton: TextView
    private val presetChips = mutableListOf<TextView>()
    private var selectedPresetIndex = 0

    init {
        setBackgroundColor(AppTheme.bgCanvas)
        layoutDirection = AppLocale.layoutDirection()

        val bottomBarHeight = AndroidUtilities.dp(172f)

        cropView = CropView(context).apply {
            setBottomPadding(bottomBarHeight.toFloat())
            setTopPadding(AndroidUtilities.dp(10f).toFloat())
        }
        addView(cropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val bottomContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.bgSurface)
        }

        // 1) Quick-Select Aspect Ratio Strip
        val ratioScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(8f), AndroidUtilities.dp(10f), AndroidUtilities.dp(4f))
        }
        val ratioRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ratioPresets.forEachIndexed { idx, preset ->
            val chip = TextView(context).apply {
                text = preset.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(AndroidUtilities.dp(13f), AndroidUtilities.dp(6f), AndroidUtilities.dp(13f), AndroidUtilities.dp(6f))
                setOnClickListener { selectAspectPreset(idx) }
            }
            presetChips.add(chip)
            ratioRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    AndroidUtilities.dp(32f)
                ).apply {
                    marginEnd = AndroidUtilities.dp(6f)
                }
            )
        }
        ratioScroll.addView(ratioRow)
        bottomContainer.addView(ratioScroll)

        // 2) Telegram CropRotationWheel
        rotationWheel = CropRotationWheel(context).apply {
            setFreeform(true)
            setBackgroundColor(0xFF000000.toInt())
        }
        bottomContainer.addView(
            rotationWheel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(70f)
            )
        )

        // 3) CANCEL / RESET / CROP bar
        val actionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(20f), 0, AndroidUtilities.dp(20f), AndroidUtilities.dp(8f))
        }

        val cancelButton = makeActionButton(AppLocale.t("cancel"), AppTheme.textPrimary, Gravity.START or Gravity.CENTER_VERTICAL) {
            cropView.stop()
            onCancel()
        }

        resetButton = makeActionButton(AppLocale.t("reset"), AppTheme.textPrimary, Gravity.CENTER) {
            cropView.reset()
            rotationWheel.reset(true)
            selectedPresetIndex = 0
            updatePresetChipStyles()
        }.apply {
            visibility = View.INVISIBLE
        }

        val cropButton = makeActionButton(AppLocale.t("crop"), AppTheme.accentText, Gravity.END or Gravity.CENTER_VERTICAL) {
            if (!cropView.isReady) return@makeActionButton
            val cropped = cropView.getCroppedBitmap() ?: sourceBitmap
            cropView.stop()
            onDone(cropped)
        }

        actionBar.addView(cancelButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(48f), 1f))
        actionBar.addView(resetButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(48f), 1f))
        actionBar.addView(cropButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(48f), 1f))

        bottomContainer.addView(
            actionBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(52f)
            )
        )

        addView(
            bottomContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        cropView.setListener(object : CropView.CropViewListener {
            override fun onChange(reset: Boolean) {
                resetButton.visibility = if (reset) View.INVISIBLE else View.VISIBLE
            }

            override fun onUpdate() {}

            override fun onAspectLock(enabled: Boolean) {
                rotationWheel.setAspectLock(enabled)
                if (!enabled) {
                    selectedPresetIndex = 0
                    updatePresetChipStyles()
                }
            }

            override fun onTapUp() {}
        })

        rotationWheel.setListener(object : CropRotationWheel.RotationWheelListener {
            override fun onStart() {
                cropView.onRotationBegan()
            }

            override fun onChange(angle: Float) {
                cropView.setRotation(angle)
            }

            override fun onEnd(angle: Float) {
                cropView.onRotationEnded()
            }

            override fun aspectRatioPressed() {
                cropView.showAspectRatioDialog()
            }

            override fun rotate90Pressed(): Boolean {
                rotationWheel.reset(false)
                return cropView.rotate(-90f)
            }

            override fun mirror(): Boolean {
                return cropView.mirror()
            }
        })

        updatePresetChipStyles()

        post {
            cropView.setBitmap(sourceBitmap, 0, true, false, null, null, null, null)
            cropView.onShow()
            cropView.show()
        }
    }

    private fun selectAspectPreset(index: Int) {
        selectedPresetIndex = index
        val preset = ratioPresets[index]
        val ratio = if (preset.isOriginal) cropView.originalAspectRatio else preset.ratio
        cropView.applyAspectRatioPreset(ratio)
        updatePresetChipStyles()
    }

    private fun updatePresetChipStyles() {
        presetChips.forEachIndexed { idx, chip ->
            val active = idx == selectedPresetIndex
            chip.setTextColor(if (active) Color.WHITE else AppTheme.textPrimary)
            chip.background = GradientDrawable().apply {
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
                setColor(if (active) AppTheme.accent else AppTheme.bgElevated)
            }
        }
    }

    private fun makeActionButton(label: String, color: Int, align: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = AndroidUtilities.bold()
            letterSpacing = 0.04f
            gravity = align
            setOnClickListener { onClick() }
        }
    }
}
