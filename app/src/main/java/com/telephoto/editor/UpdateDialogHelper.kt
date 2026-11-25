package com.telephoto.editor

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.R

/**
 * Telegram-dark in-app update dialog displaying version transition, release notes,
 * direct APK download button, and GitHub Releases page shortcut.
 */
object UpdateDialogHelper {

    fun showUpdateDialog(context: Context, release: UpdateChecker.ReleaseInfo) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF161618.toInt())
                cornerRadius = AndroidUtilities.dp(20f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x332AABEE)
            }
            setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(20f), AndroidUtilities.dp(20f), AndroidUtilities.dp(16f))
        }

        // Top badge + title
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val iconCircle = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_update)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2AABEE.toInt())
            }
        }
        val titleBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(12f), 0, 0, 0)
        }
        val titleTv = TextView(context).apply {
            text = "نسخه جدید منتشر شد!"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = AndroidUtilities.bold()
        }
        val verPill = TextView(context).apply {
            text = "v${BuildConfig.VERSION_NAME}  →  ${release.tagName}"
            setTextColor(0xFF64D2FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(0, AndroidUtilities.dp(2f), 0, 0)
        }
        titleBox.addView(titleTv)
        titleBox.addView(verPill)
        header.addView(iconCircle, LinearLayout.LayoutParams(AndroidUtilities.dp(42f), AndroidUtilities.dp(42f)))
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        // Release notes box
        val notesScroll = ScrollView(context).apply {
            background = GradientDrawable().apply {
                setColor(0xFF0E0E10.toInt())
                cornerRadius = AndroidUtilities.dp(12f).toFloat()
            }
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(10f))
        }
        val notesTv = TextView(context).apply {
            text = release.notes.ifBlank { release.title }
            setTextColor(0xFFDCDCE2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setLineSpacing(AndroidUtilities.dp(2f).toFloat(), 1.15f)
        }
        notesScroll.addView(notesTv)
        root.addView(
            notesScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(210f)
            ).apply {
                topMargin = AndroidUtilities.dp(14f)
                bottomMargin = AndroidUtilities.dp(16f)
            }
        )

        // Primary Download APK button
        val downloadBtn = TextView(context).apply {
            text = "دانلود مستقیم APK (${release.tagName})"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(12f))
            background = GradientDrawable().apply {
                setColor(0xFF2AABEE.toInt())
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
            setOnClickListener {
                openUrl(context, release.apkDownloadUrl ?: release.releasePageUrl)
                dialog.dismiss()
            }
        }
        root.addView(
            downloadBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )

        // Secondary row: GitHub Releases + Later
        val secondaryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, AndroidUtilities.dp(8f), 0, 0)
        }
        val githubBtn = TextView(context).apply {
            text = "صفحه GitHub"
            setTextColor(0xFF8AB4F8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(9f), AndroidUtilities.dp(12f), AndroidUtilities.dp(9f))
            setOnClickListener {
                openUrl(context, release.releasePageUrl)
                dialog.dismiss()
            }
        }
        val laterBtn = TextView(context).apply {
            text = "بعداً"
            setTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(9f), AndroidUtilities.dp(12f), AndroidUtilities.dp(9f))
            setOnClickListener { dialog.dismiss() }
        }
        secondaryRow.addView(githubBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        secondaryRow.addView(laterBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(secondaryRow)

        dialog.setContentView(root)
        val dm = context.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    private fun openUrl(context: Context, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(context, "Unable to open link: $url", Toast.LENGTH_LONG).show()
        }
    }
}
