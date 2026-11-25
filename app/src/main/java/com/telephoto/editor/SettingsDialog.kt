package com.telephoto.editor

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R

/**
 * Settings dialog for Dark/Light Mode and 20 World Languages (default English).
 */
object SettingsDialog {

    fun show(context: Context, onSettingsChanged: () -> Unit) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

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
            text = AppLocale.t("settings")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
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
        header.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(38f), AndroidUtilities.dp(38f)))
        root.addView(header)

        // Theme section
        val themeLabel = TextView(context).apply {
            text = AppLocale.t("theme")
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            setPadding(0, AndroidUtilities.dp(14f), 0, AndroidUtilities.dp(8f))
        }
        root.addView(themeLabel)

        val themeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun makeThemeBtn(mode: AppTheme.Mode, label: String): TextView {
            val selected = AppTheme.currentMode == mode
            return TextView(context).apply {
                text = label
                setTextColor(if (selected) Color.WHITE else AppTheme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(11f), AndroidUtilities.dp(12f), AndroidUtilities.dp(11f))
                background = GradientDrawable().apply {
                    setColor(if (selected) AppTheme.accent else AppTheme.bgElevated)
                    cornerRadius = AndroidUtilities.dp(14f).toFloat()
                }
                setOnClickListener {
                    if (AppTheme.currentMode != mode) {
                        AppTheme.setMode(context, mode)
                        dialog.dismiss()
                        onSettingsChanged()
                    }
                }
            }
        }

        themeRow.addView(
            makeThemeBtn(AppTheme.Mode.DARK, "🌙  ${AppLocale.t("dark_mode")}"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = AndroidUtilities.dp(8f)
            }
        )
        themeRow.addView(
            makeThemeBtn(AppTheme.Mode.LIGHT, "☀  ${AppLocale.t("light_mode")}"),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(themeRow)

        // Language section (20 major world languages, default English)
        val langLabel = TextView(context).apply {
            text = "${AppLocale.t("language")} (${AppLocale.SUPPORTED_LANGUAGES.size})"
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            setPadding(0, AndroidUtilities.dp(16f), 0, AndroidUtilities.dp(8f))
        }
        root.addView(langLabel)

        val langList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        AppLocale.SUPPORTED_LANGUAGES.forEach { lang ->
            val selected = lang.code == AppLocale.currentCode
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(11f), AndroidUtilities.dp(14f), AndroidUtilities.dp(11f))
                background = GradientDrawable().apply {
                    setColor(if (selected) 0x262AABEE else AppTheme.bgElevated)
                    cornerRadius = AndroidUtilities.dp(12f).toFloat()
                    if (selected) {
                        setStroke(AndroidUtilities.dp(1.2f), AppTheme.accent)
                    }
                }
                setOnClickListener {
                    if (AppLocale.currentCode != lang.code) {
                        AppLocale.setLanguage(context, lang.code)
                        dialog.dismiss()
                        onSettingsChanged()
                    }
                }
            }

            val nameTv = TextView(context).apply {
                text = if (lang.nativeName == lang.englishName) lang.nativeName else "${lang.nativeName}  (${lang.englishName})"
                setTextColor(if (selected) AppTheme.accentText else AppTheme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = if (selected) AndroidUtilities.bold() else null
            }
            val codeTv = TextView(context).apply {
                text = if (selected) "✓  ${lang.code.uppercase()}" else lang.code.uppercase()
                setTextColor(if (selected) AppTheme.accent else AppTheme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = AndroidUtilities.bold()
            }
            row.addView(nameTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(codeTv)

            langList.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = AndroidUtilities.dp(6f)
                }
            )
        }

        val scroll = ScrollView(context).apply {
            addView(langList)
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        dialog.setContentView(root)
        val dm = context.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.92f).toInt(),
            (dm.heightPixels * 0.78f).toInt()
        )
        dialog.show()
    }
}
