package com.telephoto.editor

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R

/**
 * Minab Image Editor Settings dialog:
 * - Debug Console entry (moved cleanly inside Settings with live crash/error count badge)
 * - 20 World Languages ordered by global prominence & user base, with RTL languages grouped at the bottom
 */
object SettingsDialog {

    fun show(
        context: Context,
        onOpenDebugConsole: () -> Unit,
        onSettingsChanged: () -> Unit
    ) {
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

        // === DEBUG CONSOLE CARD (inside Settings!) ===
        val allLogs = CrashLogManager.getAllEntries()
        val crashCount = allLogs.count { it.level == CrashLogManager.LEVEL_CRASH }
        val unreadCrash = CrashLogManager.hasUnreadCrash()

        val debugRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(12f), AndroidUtilities.dp(14f), AndroidUtilities.dp(12f))
            background = GradientDrawable().apply {
                setColor(if (unreadCrash) AppTheme.crashBannerBg else AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
                setStroke(
                    AndroidUtilities.dp(1f),
                    if (unreadCrash) 0x88FF453A.toInt() else AppTheme.borderSubtle
                )
            }
            setOnClickListener {
                dialog.dismiss()
                onOpenDebugConsole()
            }
        }
        val bugIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_debug_ladybug)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val debugTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(8f), 0)
        }
        val debugTitle = TextView(context).apply {
            text = AppLocale.t("debug_console")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = AndroidUtilities.bold()
        }
        val debugSub = TextView(context).apply {
            text = if (allLogs.isEmpty()) {
                AppLocale.t("debug_subtitle_ok")
            } else {
                "$crashCount crashes • ${allLogs.size - crashCount} errors"
            }
            setTextColor(if (unreadCrash) AppTheme.crashBannerText else AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, AndroidUtilities.dp(2f), 0, 0)
        }
        debugTextCol.addView(debugTitle)
        debugTextCol.addView(debugSub)

        val debugChevron = TextView(context).apply {
            text = if (allLogs.isNotEmpty()) "${allLogs.size}  ›" else "›"
            setTextColor(if (unreadCrash) AppTheme.danger else AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = AndroidUtilities.bold()
        }
        debugRow.addView(bugIcon, LinearLayout.LayoutParams(AndroidUtilities.dp(26f), AndroidUtilities.dp(26f)))
        debugRow.addView(debugTextCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        debugRow.addView(debugChevron)
        root.addView(
            debugRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = AndroidUtilities.dp(14f)
            }
        )

        // === LANGUAGE SECTION (20 languages ordered by prominence, RTL at the bottom) ===
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

        var addedRtlHeader = false
        AppLocale.SUPPORTED_LANGUAGES.forEach { lang ->
            if (lang.isRtl && !addedRtlHeader) {
                addedRtlHeader = true
                val rtlHeader = TextView(context).apply {
                    text = AppLocale.t("rtl_languages")
                    setTextColor(AppTheme.textSecondary)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = AndroidUtilities.bold()
                    setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(10f), AndroidUtilities.dp(4f), AndroidUtilities.dp(6f))
                }
                langList.addView(rtlHeader)
            }

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
            (dm.heightPixels * 0.80f).toInt()
        )
        dialog.show()
    }
}
