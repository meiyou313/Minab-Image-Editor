package com.telephoto.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.Components.Crop.CropRotationWheel
import org.telegram.ui.Components.Crop.CropView

/**
 * Pixel-faithful Telegram Crop screen (frames 00:15 - 00:34):
 * - Pure black canvas with Telegram's real CropView & CropRotationWheel
 * - Bottom row: CANCEL (white) | RESET (white, hidden until modified) | CROP (Telegram blue)
 */
class TelegramCropPanel(
    context: Context,
    private val sourceBitmap: Bitmap,
    private val onCancel: () -> Unit,
    private val onDone: (Bitmap) -> Unit
) : FrameLayout(context) {

    private val cropView: CropView
    private val rotationWheel: CropRotationWheel
    private val resetButton: TextView

    init {
        setBackgroundColor(Color.BLACK)

        val bottomBarHeight = AndroidUtilities.dp(128f)

        cropView = CropView(context).apply {
            setBottomPadding(bottomBarHeight.toFloat())
            setTopPadding(AndroidUtilities.dp(10f).toFloat())
        }
        addView(cropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val bottomContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        rotationWheel = CropRotationWheel(context).apply {
            setFreeform(true)
        }
        bottomContainer.addView(
            rotationWheel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(72f)
            )
        )

        val actionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(20f), 0, AndroidUtilities.dp(20f), AndroidUtilities.dp(8f))
        }

        val cancelButton = makeActionButton("CANCEL", Color.WHITE, Gravity.START or Gravity.CENTER_VERTICAL) {
            cropView.stop()
            onCancel()
        }

        resetButton = makeActionButton("RESET", Color.WHITE, Gravity.CENTER) {
            cropView.reset()
            rotationWheel.reset(true)
        }.apply {
            visibility = View.INVISIBLE
        }

        val cropButton = makeActionButton("CROP", 0xFF4DA3FF.toInt(), Gravity.END or Gravity.CENTER_VERTICAL) {
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

        post {
            cropView.setBitmap(sourceBitmap, 0, true, false, null, null, null, null)
            cropView.onShow()
            cropView.show()
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
