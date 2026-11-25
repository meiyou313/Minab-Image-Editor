package com.telephoto.editor

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.R

/**
 * Organized, Telegram-dark Debug Console dialog triggered by the Ladybug icon.
 * Displays fatal crashes, OpenGL/engine errors, breadcrumbs, and device diagnostics
 * with per-entry Copy and Copy All Report buttons.
 */
object DebugConsoleDialog {

    private enum class Tab { ALL, CRASHES, ERRORS, DEVICE }

    fun show(context: Context, onDismissed: (() -> Unit)? = null) {
        CrashLogManager.markCrashesRead()

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        var currentTab = Tab.ALL
        var entries = CrashLogManager.getAllEntries()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF141416.toInt())
                cornerRadius = AndroidUtilities.dp(20f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x28FFFFFF)
            }
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f), AndroidUtilities.dp(14f))
        }

        // === HEADER ===
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bugIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_debug_ladybug)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(10f), 0, AndroidUtilities.dp(8f), 0)
        }
        val titleText = TextView(context).apply {
            text = "Debug Console"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = AndroidUtilities.bold()
        }
        val subText = TextView(context).apply {
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • ${Build.MODEL}"
            setTextColor(0xFF8E8E93.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        titleCol.addView(titleText)
        titleCol.addView(subText)

        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(bugIcon, LinearLayout.LayoutParams(AndroidUtilities.dp(28f), AndroidUtilities.dp(28f)))
        header.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(38f), AndroidUtilities.dp(38f)))
        root.addView(header)

        // === FILTER TABS ===
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(10f))
        }
        val tabButtons = mutableMapOf<Tab, TextView>()

        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(
                contentContainer,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }

        fun updateTabStyles() {
            val crashCount = entries.count { it.level == CrashLogManager.LEVEL_CRASH }
            val errCount = entries.count { it.level != CrashLogManager.LEVEL_CRASH }
            tabButtons[Tab.ALL]?.text = "All (${entries.size})"
            tabButtons[Tab.CRASHES]?.text = "Crashes ($crashCount)"
            tabButtons[Tab.ERRORS]?.text = "Errors ($errCount)"
            tabButtons[Tab.DEVICE]?.text = "Device"

            tabButtons.forEach { (tab, tv) ->
                val active = tab == currentTab
                tv.setTextColor(if (active) Color.WHITE else 0xFF9E9E9E.toInt())
                tv.background = GradientDrawable().apply {
                    setColor(if (active) 0xFF2AABEE.toInt() else 0xFF232327.toInt())
                    cornerRadius = AndroidUtilities.dp(14f).toFloat()
                }
            }
        }

        fun renderContent() {
            contentContainer.removeAllViews()
            updateTabStyles()

            if (currentTab == Tab.DEVICE) {
                contentContainer.addView(buildDeviceInfoCard(context))
                return
            }

            val filtered = when (currentTab) {
                Tab.ALL -> entries
                Tab.CRASHES -> entries.filter { it.level == CrashLogManager.LEVEL_CRASH }
                Tab.ERRORS -> entries.filter { it.level != CrashLogManager.LEVEL_CRASH }
                Tab.DEVICE -> emptyList()
            }

            if (filtered.isEmpty()) {
                val emptyBox = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(42f), AndroidUtilities.dp(16f), AndroidUtilities.dp(42f))
                }
                val emptyTitle = TextView(context).apply {
                    text = if (currentTab == Tab.CRASHES) "No crashes recorded" else "No logs recorded"
                    setTextColor(0xFFCCCCCC.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = AndroidUtilities.bold()
                    gravity = Gravity.CENTER
                }
                val emptySub = TextView(context).apply {
                    text = "If the app ever closes unexpectedly or hits a GL/engine error, full details will appear here automatically."
                    setTextColor(0xFF8E8E93.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    gravity = Gravity.CENTER
                    setPadding(0, AndroidUtilities.dp(6f), 0, 0)
                }
                emptyBox.addView(emptyTitle)
                emptyBox.addView(emptySub)
                contentContainer.addView(emptyBox)
                return
            }

            filtered.forEachIndexed { idx, entry ->
                val card = buildLogCard(context, entry, initiallyExpanded = idx == 0)
                contentContainer.addView(
                    card,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = AndroidUtilities.dp(10f)
                    }
                )
            }
        }

        Tab.entries.forEach { tab ->
            val tv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER
                setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(7f), AndroidUtilities.dp(8f), AndroidUtilities.dp(7f))
                setOnClickListener {
                    currentTab = tab
                    renderContent()
                }
            }
            tabButtons[tab] = tv
            tabRow.addView(
                tv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = AndroidUtilities.dp(5f)
                }
            )
        }
        root.addView(tabRow)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // === BOTTOM ACTION BAR ===
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, AndroidUtilities.dp(12f), 0, 0)
        }
        val clearBtn = TextView(context).apply {
            text = "Clear Logs"
            setTextColor(0xFFFF6961.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(11f), AndroidUtilities.dp(14f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(0x22FF453A)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x55FF453A)
            }
            setOnClickListener {
                CrashLogManager.clearAll()
                entries = emptyList()
                renderContent()
                Toast.makeText(context, "Debug logs cleared", Toast.LENGTH_SHORT).show()
            }
        }
        val copyAllBtn = TextView(context).apply {
            text = "Copy Full Report"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(11f), AndroidUtilities.dp(14f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(0xFF2AABEE.toInt())
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
            setOnClickListener {
                copyToClipboard(context, "TelePhoto Debug Report", CrashLogManager.formatFullReport(context))
            }
        }
        footer.addView(
            clearBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = AndroidUtilities.dp(8f)
            }
        )
        footer.addView(
            copyAllBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
        )
        root.addView(footer)

        renderContent()

        dialog.setContentView(root)
        dialog.setOnDismissListener { onDismissed?.invoke() }
        val dm = context.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.94f).toInt(),
            (dm.heightPixels * 0.84f).toInt()
        )
        dialog.show()
    }

    private fun buildLogCard(
        context: Context,
        entry: CrashLogManager.DebugEntry,
        initiallyExpanded: Boolean
    ): LinearLayout {
        var expanded = initiallyExpanded
        val badgeColor = when (entry.level) {
            CrashLogManager.LEVEL_CRASH -> 0xFFFF453A.toInt()
            CrashLogManager.LEVEL_GL -> 0xFFBF5AF2.toInt()
            CrashLogManager.LEVEL_ERROR -> 0xFFFF9F0A.toInt()
            else -> 0xFF2AABEE.toInt()
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(11f), AndroidUtilities.dp(12f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(0xFF1E1E22.toInt())
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
                setStroke(AndroidUtilities.dp(1f), if (entry.level == CrashLogManager.LEVEL_CRASH) 0x55FF453A else 0x1FFFFFFF)
            }
        }

        // Top row: [LEVEL badge] [time] [Copy]
        val metaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val levelBadge = TextView(context).apply {
            text = if (entry.level == CrashLogManager.LEVEL_CRASH) "FATAL CRASH" else entry.level
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(7f), AndroidUtilities.dp(3f), AndroidUtilities.dp(7f), AndroidUtilities.dp(3f))
            background = GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = AndroidUtilities.dp(6f).toFloat()
            }
        }
        val timeText = TextView(context).apply {
            text = "${entry.timestampText}  •  ${entry.threadName}"
            setTextColor(0xFF9E9E9E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(6f), 0)
        }
        val copyOneBtn = TextView(context).apply {
            text = "Copy"
            setTextColor(0xFF4DA3FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(9f), AndroidUtilities.dp(4f), AndroidUtilities.dp(9f), AndroidUtilities.dp(4f))
            background = GradientDrawable().apply {
                setColor(0x224DA3FF)
                cornerRadius = AndroidUtilities.dp(8f).toFloat()
            }
            setOnClickListener {
                copyToClipboard(context, "TelePhoto ${entry.level}", CrashLogManager.formatEntryForCopy(entry))
            }
        }
        metaRow.addView(levelBadge)
        metaRow.addView(timeText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metaRow.addView(copyOneBtn)
        card.addView(metaRow)

        // Exception title
        val titleView = TextView(context).apply {
            text = entry.title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = AndroidUtilities.bold()
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(4f))
        }
        card.addView(titleView)

        val toggleHint = TextView(context).apply {
            text = if (expanded) "Hide stack trace ▲" else "Show stack trace ▼"
            setTextColor(0xFF4DA3FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, AndroidUtilities.dp(2f), 0, AndroidUtilities.dp(4f))
        }
        card.addView(toggleHint)

        // Expandable details box
        val detailsBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(8f), AndroidUtilities.dp(10f), AndroidUtilities.dp(8f))
            background = GradientDrawable().apply {
                setColor(0xFF0C0C0E.toInt())
                cornerRadius = AndroidUtilities.dp(10f).toFloat()
            }
        }

        if (entry.breadcrumbs.isNotEmpty()) {
            val crumbHeader = TextView(context).apply {
                text = "Recent Actions:"
                setTextColor(0xFF64D2FF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = AndroidUtilities.bold()
            }
            val crumbBody = TextView(context).apply {
                text = entry.breadcrumbs.takeLast(8).joinToString("\n")
                setTextColor(0xFFB8B8C0.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                typeface = Typeface.MONOSPACE
                setPadding(0, AndroidUtilities.dp(2f), 0, AndroidUtilities.dp(8f))
            }
            detailsBox.addView(crumbHeader)
            detailsBox.addView(crumbBody)
        }

        val traceScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
        }
        val traceText = TextView(context).apply {
            text = entry.stackTrace.ifBlank { "(no stack trace)" }
            setTextColor(0xFFE5E5EA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        traceScroll.addView(traceText)
        detailsBox.addView(traceScroll)

        card.addView(detailsBox)

        val toggleClick = View.OnClickListener {
            expanded = !expanded
            detailsBox.visibility = if (expanded) View.VISIBLE else View.GONE
            toggleHint.text = if (expanded) "Hide stack trace ▲" else "Show stack trace ▼"
        }
        titleView.setOnClickListener(toggleClick)
        toggleHint.setOnClickListener(toggleClick)

        return card
    }

    private fun buildDeviceInfoCard(context: Context): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(12f), AndroidUtilities.dp(14f), AndroidUtilities.dp(12f))
            background = GradientDrawable().apply {
                setColor(0xFF1E1E22.toInt())
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
        }

        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val dm = context.resources.displayMetrics

        val rows = listOf(
            "App Version" to "v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})",
            "Application ID" to context.packageName,
            "Signing Cert (SHA-256)" to CrashLogManager.getSigningCertFingerprint(context),
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android OS" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "CPU ABIs" to Build.SUPPORTED_ABIS.joinToString(", "),
            "Display" to "${dm.widthPixels} x ${dm.heightPixels} (${dm.density}x)",
            "Java Heap" to "${usedMb} MB / ${maxMb} MB"
        )

        rows.forEach { (k, v) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, AndroidUtilities.dp(5f), 0, AndroidUtilities.dp(5f))
            }
            val keyTv = TextView(context).apply {
                text = k
                setTextColor(0xFF8E8E93.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            val valTv = TextView(context).apply {
                text = v
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            row.addView(keyTv)
            row.addView(valTv)
            box.addView(row)
        }

        val crumbs = CrashLogManager.getRecentBreadcrumbs()
        if (crumbs.isNotEmpty()) {
            val header = TextView(context).apply {
                text = "Current Session Breadcrumbs"
                setTextColor(0xFF64D2FF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = AndroidUtilities.bold()
                setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(4f))
            }
            val body = TextView(context).apply {
                text = crumbs.joinToString("\n")
                setTextColor(0xFFD0D0D8.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            box.addView(header)
            box.addView(body)
        }

        return box
    }

    private fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
