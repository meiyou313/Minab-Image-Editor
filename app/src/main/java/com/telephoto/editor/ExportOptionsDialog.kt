package com.telephoto.editor

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Export & Share dialog allowing choice of JPEG / PNG / WebP format, quality slider,
 * live estimated file size, EXIF metadata strip toggle, Save to Gallery, and Share.
 */
object ExportOptionsDialog {

    private val sizeExecutor = Executors.newSingleThreadExecutor()

    fun show(
        context: Context,
        bitmap: Bitmap,
        onSave: (BitmapUtils.ExportFormat, Int) -> Unit,
        onShare: (BitmapUtils.ExportFormat, Int) -> Unit
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        var selectedFormat = BitmapUtils.ExportFormat.JPEG
        var selectedQuality = 95

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = AppLocale.layoutDirection()
            background = GradientDrawable().apply {
                setColor(AppTheme.bgSurface)
                cornerRadius = AndroidUtilities.dp(20f).toFloat()
                setStroke(AndroidUtilities.dp(1f), AppTheme.borderSubtle)
            }
            setPadding(AndroidUtilities.dp(18f), AndroidUtilities.dp(18f), AndroidUtilities.dp(18f), AndroidUtilities.dp(16f))
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(context).apply {
            text = AppLocale.t("export_title")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = AndroidUtilities.bold()
        }
        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_close)
            setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(36f), AndroidUtilities.dp(36f)))
        root.addView(header)

        // Resolution & live estimated size pill
        val infoTv = TextView(context).apply {
            text = "${bitmap.width} × ${bitmap.height}  •  Calculating..."
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(12f))
        }
        root.addView(infoTv)

        fun updateSizeEstimate() {
            val fmt = selectedFormat
            val q = selectedQuality
            infoTv.text = "${bitmap.width} × ${bitmap.height}  •  ..."
            sizeExecutor.execute {
                val bytes = BitmapUtils.estimateSizeBytes(bitmap, fmt, q)
                val sizeStr = if (bytes >= 1024 * 1024) {
                    String.format(Locale.US, "~%.2f MB", bytes / (1024f * 1024f))
                } else {
                    String.format(Locale.US, "~%d KB", (bytes / 1024).coerceAtLeast(1))
                }
                infoTv.post {
                    if (fmt == selectedFormat && q == selectedQuality) {
                        infoTv.text = "${bitmap.width} × ${bitmap.height}  •  ${fmt.label}  •  $sizeStr"
                    }
                }
            }
        }

        // Format segmented row
        val fmtLabel = TextView(context).apply {
            text = AppLocale.t("format")
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            setPadding(0, 0, 0, AndroidUtilities.dp(6f))
        }
        root.addView(fmtLabel)

        val fmtRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(AndroidUtilities.dp(3f), AndroidUtilities.dp(3f), AndroidUtilities.dp(3f), AndroidUtilities.dp(3f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
        }
        val fmtButtons = mutableMapOf<BitmapUtils.ExportFormat, TextView>()
        lateinit var qualitySection: LinearLayout

        fun updateFmtStyles() {
            fmtButtons.forEach { (fmt, tv) ->
                val active = fmt == selectedFormat
                tv.setTextColor(if (active) Color.WHITE else AppTheme.textPrimary)
                tv.background = if (active) {
                    GradientDrawable().apply {
                        setColor(AppTheme.accent)
                        cornerRadius = AndroidUtilities.dp(11f).toFloat()
                    }
                } else null
            }
            qualitySection.visibility = if (selectedFormat == BitmapUtils.ExportFormat.PNG) View.GONE else View.VISIBLE
            updateSizeEstimate()
        }

        BitmapUtils.ExportFormat.entries.forEach { fmt ->
            val tv = TextView(context).apply {
                text = fmt.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER
                maxLines = 1
                setOnClickListener {
                    selectedFormat = fmt
                    updateFmtStyles()
                }
            }
            fmtButtons[fmt] = tv
            fmtRow.addView(tv, LinearLayout.LayoutParams(0, AndroidUtilities.dp(36f), 1f))
        }
        root.addView(fmtRow)

        // Quality slider section
        qualitySection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, AndroidUtilities.dp(14f), 0, AndroidUtilities.dp(4f))
        }
        val qHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val qLabel = TextView(context).apply {
            text = AppLocale.t("quality")
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
        }
        val qVal = TextView(context).apply {
            text = "$selectedQuality%"
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
        }
        qHeader.addView(qLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        qHeader.addView(qVal)

        val qSeek = SeekBar(context).apply {
            max = 50 // 50..100
            progress = selectedQuality - 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    selectedQuality = 50 + p
                    qVal.text = "$selectedQuality%"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    updateSizeEstimate()
                }
            })
        }
        qualitySection.addView(qHeader)
        qualitySection.addView(qSeek)
        root.addView(qualitySection)

        // Strip EXIF toggle (Bitmap re-encoding always strips GPS/camera EXIF cleanly)
        val exifCheck = CheckBox(context).apply {
            text = AppLocale.t("strip_exif")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            isChecked = true
            isEnabled = false
            alpha = 0.9f
        }
        root.addView(
            exifCheck,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = AndroidUtilities.dp(8f)
                bottomMargin = AndroidUtilities.dp(14f)
            }
        )

        // Action buttons: Save to Gallery + Share Photo
        val saveBtn = TextView(context).apply {
            text = AppLocale.t("save_gallery")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
            background = GradientDrawable().apply {
                setColor(AppTheme.accent)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
            setOnClickListener {
                dialog.dismiss()
                onSave(selectedFormat, selectedQuality)
            }
        }
        val shareBtn = TextView(context).apply {
            text = AppLocale.t("share_photo")
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(11f), AndroidUtilities.dp(16f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x442AABEE)
            }
            setOnClickListener {
                dialog.dismiss()
                onShare(selectedFormat, selectedQuality)
            }
        }

        root.addView(saveBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = AndroidUtilities.dp(8f)
        })
        root.addView(shareBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        updateFmtStyles()

        dialog.setContentView(root)
        val dm = context.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }
}
