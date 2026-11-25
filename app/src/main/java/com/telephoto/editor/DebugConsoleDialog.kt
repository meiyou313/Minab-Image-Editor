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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
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
 * Organized Debug Console dialog triggered by the Ladybug icon:
 * - Uniform single-line aligned segmented tabs (All / Crash / Error / Device)
 * - Per-entry Copy AND Save .txt buttons
 * - Multi-select mode to pick specific entries and export/copy only selected logs as .txt
 * - Full Report Copy AND Full Report Save .txt
 * - Dark/Light theme and 20-language localization aware
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
        var selectionMode = false
        val selectedIds = LinkedHashSet<String>()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = AppLocale.layoutDirection()
            background = GradientDrawable().apply {
                setColor(AppTheme.bgSurface)
                cornerRadius = AndroidUtilities.dp(20f).toFloat()
                setStroke(AndroidUtilities.dp(1f), AppTheme.borderSubtle)
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
            setPadding(AndroidUtilities.dp(10f), 0, AndroidUtilities.dp(6f), 0)
        }
        val titleText = TextView(context).apply {
            text = AppLocale.t("debug_console")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
        }
        val subText = TextView(context).apply {
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) • ${Build.MODEL}"
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(titleText)
        titleCol.addView(subText)

        lateinit var selectToggleBtn: TextView
        lateinit var selectionBar: LinearLayout
        lateinit var selectionCountTv: TextView

        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_tg_close)
            setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
            setOnClickListener { dialog.dismiss() }
        }

        val tabButtons = mutableMapOf<Tab, TextView>()
        val contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun currentFilteredEntries(): List<CrashLogManager.DebugEntry> = when (currentTab) {
            Tab.ALL -> entries
            Tab.CRASHES -> entries.filter { it.level == CrashLogManager.LEVEL_CRASH }
            Tab.ERRORS -> entries.filter { it.level != CrashLogManager.LEVEL_CRASH }
            Tab.DEVICE -> emptyList()
        }

        lateinit var renderContent: () -> Unit

        selectToggleBtn = TextView(context).apply {
            text = AppLocale.t("select")
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(6f), AndroidUtilities.dp(10f), AndroidUtilities.dp(6f))
            background = GradientDrawable().apply {
                setColor(0x222AABEE)
                cornerRadius = AndroidUtilities.dp(10f).toFloat()
            }
            setOnClickListener {
                selectionMode = !selectionMode
                if (!selectionMode) selectedIds.clear()
                renderContent()
            }
        }

        header.addView(bugIcon, LinearLayout.LayoutParams(AndroidUtilities.dp(26f), AndroidUtilities.dp(26f)))
        header.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            selectToggleBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = AndroidUtilities.dp(4f)
            }
        )
        header.addView(closeBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(36f), AndroidUtilities.dp(36f)))
        root.addView(header)

        // === UNIFORM SINGLE-LINE ALIGNED SEGMENTED TAB BAR ===
        val tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(3f), AndroidUtilities.dp(3f), AndroidUtilities.dp(3f), AndroidUtilities.dp(3f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
        }
        val tabHeight = AndroidUtilities.dp(34f)
        Tab.entries.forEach { tab ->
            val tv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = AndroidUtilities.bold()
                gravity = Gravity.CENTER
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
                setOnClickListener {
                    currentTab = tab
                    if (tab == Tab.DEVICE) {
                        selectionMode = false
                        selectedIds.clear()
                    }
                    renderContent()
                }
            }
            tabButtons[tab] = tv
            tabContainer.addView(tv, LinearLayout.LayoutParams(0, tabHeight, 1f))
        }
        root.addView(
            tabContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = AndroidUtilities.dp(12f)
                bottomMargin = AndroidUtilities.dp(10f)
            }
        )

        // === MULTI-SELECT ACTION BAR (visible only in Select mode) ===
        selectionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(7f), AndroidUtilities.dp(10f), AndroidUtilities.dp(7f))
            background = GradientDrawable().apply {
                setColor(0x222AABEE)
                cornerRadius = AndroidUtilities.dp(12f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x552AABEE)
            }
        }
        selectionCountTv = TextView(context).apply {
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
        }
        val selectAllBtn = TextView(context).apply {
            text = "All"
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(4f), AndroidUtilities.dp(8f), AndroidUtilities.dp(4f))
            setOnClickListener {
                val visible = currentFilteredEntries()
                if (selectedIds.size == visible.size && visible.isNotEmpty()) {
                    selectedIds.clear()
                } else {
                    selectedIds.clear()
                    visible.forEach { selectedIds.add(it.id) }
                }
                renderContent()
            }
        }
        val copySelectedBtn = TextView(context).apply {
            text = AppLocale.t("copy")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(9f), AndroidUtilities.dp(5f), AndroidUtilities.dp(9f), AndroidUtilities.dp(5f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(8f).toFloat()
            }
            setOnClickListener {
                val chosen = entries.filter { it.id in selectedIds }
                if (chosen.isEmpty()) {
                    Toast.makeText(context, "Select at least one log", Toast.LENGTH_SHORT).show()
                } else {
                    val report = CrashLogManager.formatEntriesReport(context, chosen, "Selected ${chosen.size}")
                    copyToClipboard(context, "Minab Editor Selected Logs", report)
                }
            }
        }
        val saveSelectedBtn = TextView(context).apply {
            text = AppLocale.t("export_selected")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(5f), AndroidUtilities.dp(10f), AndroidUtilities.dp(5f))
            background = GradientDrawable().apply {
                setColor(AppTheme.accent)
                cornerRadius = AndroidUtilities.dp(8f).toFloat()
            }
            setOnClickListener {
                val chosen = entries.filter { it.id in selectedIds }
                if (chosen.isEmpty()) {
                    Toast.makeText(context, "Select at least one log", Toast.LENGTH_SHORT).show()
                } else {
                    val report = CrashLogManager.formatEntriesReport(context, chosen, "Selected ${chosen.size}")
                    saveTxtAndNotify(context, "MinabEditor_Selected", report)
                }
            }
        }
        selectionBar.addView(selectionCountTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        selectionBar.addView(selectAllBtn)
        selectionBar.addView(
            copySelectedBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = AndroidUtilities.dp(4f)
                marginEnd = AndroidUtilities.dp(6f)
            }
        )
        selectionBar.addView(saveSelectedBtn)
        root.addView(
            selectionBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = AndroidUtilities.dp(8f)
            }
        )

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(
                contentContainer,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        fun updateTabStyles() {
            val crashCount = entries.count { it.level == CrashLogManager.LEVEL_CRASH }
            val errCount = entries.count { it.level != CrashLogManager.LEVEL_CRASH }
            tabButtons[Tab.ALL]?.text = "${AppLocale.t("tab_all")} ${entries.size}"
            tabButtons[Tab.CRASHES]?.text = "${AppLocale.t("tab_crashes")} $crashCount"
            tabButtons[Tab.ERRORS]?.text = "${AppLocale.t("tab_errors")} $errCount"
            tabButtons[Tab.DEVICE]?.text = AppLocale.t("tab_device")

            tabButtons.forEach { (tab, tv) ->
                val active = tab == currentTab
                tv.setTextColor(if (active) Color.WHITE else AppTheme.textSecondary)
                tv.background = if (active) {
                    GradientDrawable().apply {
                        setColor(AppTheme.accent)
                        cornerRadius = AndroidUtilities.dp(11f).toFloat()
                    }
                } else {
                    null
                }
            }

            selectToggleBtn.visibility = if (currentTab == Tab.DEVICE || entries.isEmpty()) View.GONE else View.VISIBLE
            selectToggleBtn.text = if (selectionMode) AppLocale.t("done") else AppLocale.t("select")
            selectionBar.visibility = if (selectionMode && currentTab != Tab.DEVICE) View.VISIBLE else View.GONE
            selectionCountTv.text = "${selectedIds.size} selected"
        }

        renderContent = {
            contentContainer.removeAllViews()
            updateTabStyles()

            if (currentTab == Tab.DEVICE) {
                contentContainer.addView(buildDeviceInfoCard(context))
            } else {
                val filtered = currentFilteredEntries()
                if (filtered.isEmpty()) {
                    val emptyBox = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(42f), AndroidUtilities.dp(16f), AndroidUtilities.dp(42f))
                    }
                    val emptyTitle = TextView(context).apply {
                        text = if (currentTab == Tab.CRASHES) "No crashes recorded" else "No logs recorded"
                        setTextColor(AppTheme.textPrimary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        typeface = AndroidUtilities.bold()
                        gravity = Gravity.CENTER
                    }
                    val emptySub = TextView(context).apply {
                        text = "Any crash or GL/engine error is recorded automatically here for copying or .txt export."
                        setTextColor(AppTheme.textSecondary)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                        gravity = Gravity.CENTER
                        setPadding(0, AndroidUtilities.dp(6f), 0, 0)
                    }
                    emptyBox.addView(emptyTitle)
                    emptyBox.addView(emptySub)
                    contentContainer.addView(emptyBox)
                } else {
                    filtered.forEachIndexed { idx, entry ->
                        val card = buildLogCard(
                            context = context,
                            entry = entry,
                            initiallyExpanded = idx == 0 && !selectionMode,
                            selectionMode = selectionMode,
                            isSelected = entry.id in selectedIds,
                            onSelectionToggled = { checked ->
                                if (checked) selectedIds.add(entry.id) else selectedIds.remove(entry.id)
                                selectionCountTv.text = "${selectedIds.size} selected"
                            }
                        )
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
            }
        }

        // === BOTTOM ACTION BAR: Clear | Copy All | Save Full .txt ===
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, AndroidUtilities.dp(12f), 0, 0)
        }
        val clearBtn = TextView(context).apply {
            text = AppLocale.t("clear_logs")
            setTextColor(AppTheme.danger)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(11f), AndroidUtilities.dp(10f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(0x22FF453A)
                cornerRadius = AndroidUtilities.dp(13f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x55FF453A)
            }
            setOnClickListener {
                CrashLogManager.clearAll()
                entries = emptyList()
                selectedIds.clear()
                selectionMode = false
                renderContent()
                Toast.makeText(context, "Debug logs cleared", Toast.LENGTH_SHORT).show()
            }
        }
        val copyAllBtn = TextView(context).apply {
            text = AppLocale.t("copy_all")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(11f), AndroidUtilities.dp(10f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(13f).toFloat()
            }
            setOnClickListener {
                copyToClipboard(context, "Minab Editor Debug Report", CrashLogManager.formatFullReport(context))
            }
        }
        val exportFullBtn = TextView(context).apply {
            text = "${AppLocale.t("export_txt")} (Full)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(11f), AndroidUtilities.dp(10f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(AppTheme.accent)
                cornerRadius = AndroidUtilities.dp(13f).toFloat()
            }
            setOnClickListener {
                saveTxtAndNotify(context, "MinabEditor_FullReport", CrashLogManager.formatFullReport(context))
            }
        }
        footer.addView(
            clearBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.85f).apply {
                marginEnd = AndroidUtilities.dp(6f)
            }
        )
        footer.addView(
            copyAllBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = AndroidUtilities.dp(6f)
            }
        )
        footer.addView(
            exportFullBtn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
        )
        root.addView(footer)

        renderContent()

        dialog.setContentView(root)
        dialog.setOnDismissListener { onDismissed?.invoke() }
        val dm = context.resources.displayMetrics
        dialog.window?.setLayout(
            (dm.widthPixels * 0.94f).toInt(),
            (dm.heightPixels * 0.85f).toInt()
        )
        dialog.show()
    }

    private fun buildLogCard(
        context: Context,
        entry: CrashLogManager.DebugEntry,
        initiallyExpanded: Boolean,
        selectionMode: Boolean,
        isSelected: Boolean,
        onSelectionToggled: (Boolean) -> Unit
    ): LinearLayout {
        var expanded = initiallyExpanded
        val badgeColor = when (entry.level) {
            CrashLogManager.LEVEL_CRASH -> AppTheme.danger
            CrashLogManager.LEVEL_GL -> AppTheme.purple
            CrashLogManager.LEVEL_ERROR -> AppTheme.warning
            else -> AppTheme.accent
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(11f), AndroidUtilities.dp(12f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
                setStroke(
                    AndroidUtilities.dp(1f),
                    when {
                        selectionMode && isSelected -> AppTheme.accent
                        entry.level == CrashLogManager.LEVEL_CRASH -> 0x55FF453A
                        else -> AppTheme.borderSubtle
                    }
                )
            }
        }

        // Top row: [Checkbox?] [LEVEL badge] [time] [Copy] [.txt]
        val metaRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        if (selectionMode) {
            val cb = CheckBox(context).apply {
                isChecked = isSelected
                setOnCheckedChangeListener { _, checked -> onSelectionToggled(checked) }
            }
            metaRow.addView(cb, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val levelBadge = TextView(context).apply {
            text = if (entry.level == CrashLogManager.LEVEL_CRASH) "CRASH" else entry.level
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
            setPadding(AndroidUtilities.dp(7f), AndroidUtilities.dp(3f), AndroidUtilities.dp(7f), AndroidUtilities.dp(3f))
            background = GradientDrawable().apply {
                setColor(badgeColor)
                cornerRadius = AndroidUtilities.dp(6f).toFloat()
            }
        }
        val timeText = TextView(context).apply {
            text = "${entry.timestampText.substringAfter(' ')} • ${entry.threadName}"
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(AndroidUtilities.dp(7f), 0, AndroidUtilities.dp(6f), 0)
        }
        val copyOneBtn = TextView(context).apply {
            text = AppLocale.t("copy")
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(4f), AndroidUtilities.dp(8f), AndroidUtilities.dp(4f))
            background = GradientDrawable().apply {
                setColor(0x224DA3FF)
                cornerRadius = AndroidUtilities.dp(8f).toFloat()
            }
            setOnClickListener {
                copyToClipboard(context, "Minab Editor ${entry.level}", CrashLogManager.formatEntryForCopy(entry))
            }
        }
        val saveOneTxtBtn = TextView(context).apply {
            text = ".txt"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(4f), AndroidUtilities.dp(8f), AndroidUtilities.dp(4f))
            background = GradientDrawable().apply {
                setColor(AppTheme.accent)
                cornerRadius = AndroidUtilities.dp(8f).toFloat()
            }
            setOnClickListener {
                val report = CrashLogManager.formatEntriesReport(context, listOf(entry), "Single ${entry.level}")
                saveTxtAndNotify(context, "MinabEditor_${entry.level}_${entry.id}", report)
            }
        }

        metaRow.addView(levelBadge)
        metaRow.addView(timeText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        metaRow.addView(
            copyOneBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = AndroidUtilities.dp(5f)
            }
        )
        metaRow.addView(saveOneTxtBtn)
        card.addView(metaRow)

        val titleView = TextView(context).apply {
            text = entry.title
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(4f))
        }
        card.addView(titleView)

        val toggleHint = TextView(context).apply {
            text = if (expanded) "Hide stack trace ▲" else "Show stack trace ▼"
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, AndroidUtilities.dp(2f), 0, AndroidUtilities.dp(4f))
        }
        card.addView(toggleHint)

        val detailsBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (expanded) View.VISIBLE else View.GONE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(8f), AndroidUtilities.dp(10f), AndroidUtilities.dp(8f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgCode)
                cornerRadius = AndroidUtilities.dp(10f).toFloat()
            }
        }

        if (entry.breadcrumbs.isNotEmpty()) {
            val crumbHeader = TextView(context).apply {
                text = "Recent Actions:"
                setTextColor(AppTheme.accentText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = AndroidUtilities.bold()
            }
            val crumbBody = TextView(context).apply {
                text = entry.breadcrumbs.takeLast(8).joinToString("\n")
                setTextColor(AppTheme.textSecondary)
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
            setTextColor(AppTheme.textCode)
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
                setColor(AppTheme.bgElevated)
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
            "Theme / Language" to "${AppTheme.currentMode.id} / ${AppLocale.currentCode}",
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
                setTextColor(AppTheme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            val valTv = TextView(context).apply {
                text = v
                setTextColor(AppTheme.textPrimary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
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
                setTextColor(AppTheme.accentText)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = AndroidUtilities.bold()
                setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(4f))
            }
            val body = TextView(context).apply {
                text = crumbs.joinToString("\n")
                setTextColor(AppTheme.textSecondary)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
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
        Toast.makeText(context, AppLocale.t("copied"), Toast.LENGTH_SHORT).show()
    }

    private fun saveTxtAndNotify(context: Context, prefix: String, content: String) {
        val savedPath = CrashLogManager.exportTextToDownloads(context, prefix, content)
        if (savedPath != null) {
            Toast.makeText(context, "${AppLocale.t("saved_txt")}: $savedPath", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Failed to save .txt file", Toast.LENGTH_SHORT).show()
        }
    }
}
