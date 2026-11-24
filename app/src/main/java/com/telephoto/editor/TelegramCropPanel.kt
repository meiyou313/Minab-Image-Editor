package com.telephoto.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import org.telegram.ui.Components.Crop.CropRotationWheel
import org.telegram.ui.Components.Crop.CropView

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

        val bottomBarHeight = AndroidUtilities.dp(132f)

        cropView = CropView(context).apply {
            setBottomPadding(bottomBarHeight.toFloat())
            setTopPadding(AndroidUtilities.dp(12f).toFloat())
        }
        addView(cropView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val bottomContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6101010.toInt())
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
            setPadding(AndroidUtilities.dp(16f), 0, AndroidUtilities.dp(16f), AndroidUtilities.dp(8f))
        }

        val cancelButton = makeActionButton(context.getString(R.string.action_cancel), 0xFFE0E0E0.toInt()) {
            cropView.stop()
            onCancel()
        }

        resetButton = makeActionButton(context.getString(R.string.action_reset), 0xFF4DA3FF.toInt()) {
            cropView.reset()
            rotationWheel.reset(true)
        }.apply {
            alpha = 0.45f
            isEnabled = false
        }

        val doneButton = makeActionButton(context.getString(R.string.action_done), 0xFF4DA3FF.toInt(), bold = true) {
            val cropped = cropView.getCroppedBitmap() ?: sourceBitmap
            cropView.stop()
            onDone(cropped)
        }

        actionBar.addView(cancelButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(48f), 1f))
        actionBar.addView(resetButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(48f), 1f))
        actionBar.addView(doneButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(48f), 1f))

        bottomContainer.addView(
            actionBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(56f)
            )
        )

        addView(
            bottomContainer,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        cropView.setListener(object : CropView.CropViewListener {
            override fun onChange(reset: Boolean) {
                resetButton.isEnabled = !reset
                resetButton.alpha = if (reset) 0.45f else 1.0f
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
            cropView.show()
        }
    }

    private fun makeActionButton(label: String, color: Int, bold: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            if (bold) typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }
}
