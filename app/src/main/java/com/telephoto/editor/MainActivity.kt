package com.telephoto.editor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.R
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Minab Image Editor Main Activity (v1.4.0):
 * - Clean Landing Layout:
 *   Top Bar: Left = App Logo + "Minab Image Editor" | Right = Minimalist Settings icon (contains Debug Console & 20 Languages)
 *   Bottom Bar: Version pill | Check Update button | Dark/Light Mode toggle
 * - Non-Destructive 3-Layer Editing Pipeline:
 *   Layer 1: Cropped/Base Photo
 *   Layer 2: Samsung Gallery Filters (with Strength 0..100) + Tone + Curves + Blur Recipe
 *   Layer 3: Editable Paint Strokes/Arrows/Shapes (with cross-visit Undo history!) + Live Editable Text Entities
 *   Layers remain independent & editable across tool switches until final Save/Share!
 */
class MainActivity : AppCompatActivity() {

    private data class EditorSnapshot(
        val croppedBase: Bitmap,
        val filteredPhoto: Bitmap,
        val filterRecipe: EditLayerState.FilterRecipe,
        val paintLayer: EditLayerState.PaintLayerState
    )

    private val ioExecutor = Executors.newSingleThreadExecutor()

    // Non-destructive 3-layer document state
    private var originalBitmap: Bitmap? = null
    private var croppedBaseBitmap: Bitmap? = null
    private var filteredPhotoBitmap: Bitmap? = null
    private var filterRecipe: EditLayerState.FilterRecipe = EditLayerState.FilterRecipe()
    private var paintLayerState: EditLayerState.PaintLayerState = EditLayerState.PaintLayerState()
    private var displayBitmap: Bitmap? = null
    private val historyStack = ArrayDeque<EditorSnapshot>()

    private lateinit var rootContainer: FrameLayout
    private lateinit var landingView: FrameLayout
    private lateinit var editorShell: LinearLayout
    private lateinit var previewView: ZoomableCompareImageView
    private lateinit var undoIconBtn: ImageView
    private lateinit var resetTextBtn: TextView
    private lateinit var compareBtn: ImageView
    private lateinit var layerBadgeView: TextView
    private lateinit var saveCircleBtn: ImageView
    private lateinit var activePanelContainer: FrameLayout

    // Landing dynamic views
    private lateinit var landingUpdateBanner: LinearLayout
    private lateinit var landingUpdateBannerText: TextView
    private lateinit var landingCrashBanner: LinearLayout
    private lateinit var manualUpdateBtn: TextView
    private lateinit var resumeDraftBtn: TextView
    private lateinit var recentsSection: LinearLayout
    private lateinit var recentsRow: LinearLayout
    private lateinit var landingSettingsDot: View
    private lateinit var editorSettingsDot: View
    private lateinit var bottomUpdateDot: View

    private var availableRelease: UpdateChecker.ReleaseInfo? = null
    private var checkingUpdate = false

    private var activeFilterPanel: TelegramFilterPanel? = null
    private var activePaintPanel: TelegramPaintPanel? = null

    private val photoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) loadImageFromUri(uri)
    }

    private val legacyPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) loadImageFromUri(uri)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            availableRelease?.let { UpdateChecker.maybeShowUpdateNotification(this, it, force = false) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidUtilities.init(this)
        AppTheme.applySystemBars(this)

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(AppTheme.bgPrimary)
            layoutDirection = AppLocale.layoutDirection()
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(bars.bottom, ime.bottom)
            view.setPadding(bars.left, bars.top, bars.right, bottomInset)
            AndroidUtilities.statusBarHeight = 0
            windowInsets
        }
        setContentView(rootContainer)
        ViewCompat.requestApplyInsets(rootContainer)

        buildShellViews(showEditor = false)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    activePanelContainer.visibility == View.VISIBLE -> closeActivePanel()
                    editorShell.visibility == View.VISIBLE -> returnToMainMenu()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        requestNotificationPermissionIfNeeded()
        refreshLandingExtras()
        refreshDebugBadges()
        handleIncomingIntent(intent)
        runSilentUpdateCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshDebugBadges()
        if (landingView.visibility == View.VISIBLE) {
            refreshLandingExtras()
        }
    }

    override fun onDestroy() {
        closeActivePanel()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun rebuildUiPreservingState() {
        val wasInEditor = editorShell.visibility == View.VISIBLE && displayBitmap != null
        closeActivePanel()
        AppTheme.applySystemBars(this)
        rootContainer.setBackgroundColor(AppTheme.bgPrimary)
        rootContainer.layoutDirection = AppLocale.layoutDirection()
        buildShellViews(showEditor = wasInEditor)
        if (wasInEditor) {
            previewView.setBitmaps(displayBitmap, originalBitmap)
            updateTopBarState()
        }
        availableRelease?.let { applyUpdateAvailableUi(it) }
        refreshLandingExtras()
        refreshDebugBadges()
    }

    private fun buildShellViews(showEditor: Boolean) {
        rootContainer.removeAllViews()
        buildLandingView()
        buildEditorShell()

        activePanelContainer = FrameLayout(this).apply {
            visibility = View.GONE
        }
        rootContainer.addView(
            activePanelContainer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        landingView.visibility = if (showEditor) View.GONE else View.VISIBLE
        editorShell.visibility = if (showEditor) View.VISIBLE else View.GONE
    }

    private fun returnToMainMenu() {
        closeActivePanel()
        editorShell.visibility = View.GONE
        landingView.visibility = View.VISIBLE
        refreshLandingExtras()
        refreshDebugBadges()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(UpdateChecker.EXTRA_OPEN_UPDATE_DIALOG, false)) {
            val tag = intent.getStringExtra(UpdateChecker.EXTRA_RELEASE_TAG)
            if (!tag.isNullOrBlank()) {
                val release = UpdateChecker.ReleaseInfo(
                    tagName = tag,
                    versionName = tag.removePrefix("v").removePrefix("V"),
                    title = intent.getStringExtra(UpdateChecker.EXTRA_RELEASE_TITLE) ?: "Minab Image Editor $tag",
                    notes = intent.getStringExtra(UpdateChecker.EXTRA_RELEASE_NOTES) ?: "",
                    apkDownloadUrl = intent.getStringExtra(UpdateChecker.EXTRA_RELEASE_APK_URL),
                    releasePageUrl = intent.getStringExtra(UpdateChecker.EXTRA_RELEASE_PAGE_URL)
                        ?: UpdateChecker.RELEASES_PAGE_URL,
                    publishedAt = ""
                )
                availableRelease = release
                applyUpdateAvailableUi(release)
                UpdateDialogHelper.showUpdateDialog(this, release)
            }
        }

        val uri: Uri? = when (intent.action) {
            Intent.ACTION_EDIT -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
        if (uri != null) {
            loadImageFromUri(uri)
        }
    }

    private fun buildLandingView() {
        landingView = FrameLayout(this).apply {
            setBackgroundColor(AppTheme.bgPrimary)
        }

        val landingRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // === TOP BAR: Left = App Logo + "Minab Image Editor" | Right = Minimalist Settings ===
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(AppTheme.bgSurface)
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(10f))
        }

        val topLogo = ImageView(this).apply {
            setImageResource(R.drawable.ic_minab_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val topAppName = TextView(this).apply {
            text = AppLocale.t("app_name")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(AndroidUtilities.dp(10f), 0, AndroidUtilities.dp(8f), 0)
        }

        val (settingsFrame, settingsDot) = makeIconWithBadge(
            iconRes = R.drawable.ic_tg_settings,
            badgeColor = AppTheme.danger,
            tintIcon = true
        ) {
            openSettings()
        }
        landingSettingsDot = settingsDot

        topBar.addView(topLogo, LinearLayout.LayoutParams(AndroidUtilities.dp(30f), AndroidUtilities.dp(30f)))
        topBar.addView(topAppName, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(settingsFrame, LinearLayout.LayoutParams(AndroidUtilities.dp(40f), AndroidUtilities.dp(40f)))
        landingRoot.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Subtle divider under top bar
        landingRoot.addView(
            View(this).apply { setBackgroundColor(AppTheme.borderSubtle) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(0.8f).coerceAtLeast(1))
        )

        // === CENTER SCROLLABLE BODY ===
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(AndroidUtilities.dp(24f), AndroidUtilities.dp(18f), AndroidUtilities.dp(24f), AndroidUtilities.dp(24f))
        }

        // In-app Update Banner
        landingUpdateBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(11f), AndroidUtilities.dp(14f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(AppTheme.updateBannerBg)
                cornerRadius = AndroidUtilities.dp(16f).toFloat()
                setStroke(AndroidUtilities.dp(1f), AppTheme.accent)
            }
            setOnClickListener {
                availableRelease?.let { UpdateDialogHelper.showUpdateDialog(this@MainActivity, it) }
            }
        }
        val bannerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_update)
            setColorFilter(AppTheme.accent)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        landingUpdateBannerText = TextView(this).apply {
            text = AppLocale.t("update_available")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(10f), 0, AndroidUtilities.dp(8f), 0)
        }
        val bannerAction = TextView(this).apply {
            text = AppLocale.t("view")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(5f), AndroidUtilities.dp(10f), AndroidUtilities.dp(5f))
            background = GradientDrawable().apply {
                setColor(AppTheme.accent)
                cornerRadius = AndroidUtilities.dp(10f).toFloat()
            }
        }
        landingUpdateBanner.addView(bannerIcon, LinearLayout.LayoutParams(AndroidUtilities.dp(22f), AndroidUtilities.dp(22f)))
        landingUpdateBanner.addView(landingUpdateBannerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        landingUpdateBanner.addView(bannerAction)

        // Crash Recovery Banner
        landingCrashBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(10f), AndroidUtilities.dp(14f), AndroidUtilities.dp(10f))
            background = GradientDrawable().apply {
                setColor(AppTheme.crashBannerBg)
                cornerRadius = AndroidUtilities.dp(16f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x88FF453A.toInt())
            }
            setOnClickListener { openDebugConsole() }
        }
        val crashIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_debug_ladybug)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        val crashText = TextView(this).apply {
            text = AppLocale.t("crash_banner")
            setTextColor(AppTheme.crashBannerText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(10f), 0, 0, 0)
        }
        landingCrashBanner.addView(crashIcon, LinearLayout.LayoutParams(AndroidUtilities.dp(24f), AndroidUtilities.dp(24f)))
        landingCrashBanner.addView(crashText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Hero Logo + Title
        val heroLogo = ImageView(this).apply {
            setImageResource(R.drawable.ic_minab_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val title = TextView(this).apply {
            text = AppLocale.t("app_name")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(0, AndroidUtilities.dp(14f), 0, 0)
        }
        val subtitle = TextView(this).apply {
            text = AppLocale.t("landing_subtitle")
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(24f))
        }

        // Primary Pick Photo button
        val pickBtn = TextView(this).apply {
            text = AppLocale.t("pick_photo")
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(28f), AndroidUtilities.dp(14f), AndroidUtilities.dp(28f), AndroidUtilities.dp(14f))
            background = GradientDrawable().apply {
                setColor(AppTheme.accent)
                cornerRadius = AndroidUtilities.dp(26f).toFloat()
            }
            setOnClickListener { launchPhotoPicker() }
        }

        // Quick shortcuts row: [Latest Photo] + [Resume Last Edit]
        val shortcutsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, AndroidUtilities.dp(12f), 0, 0)
        }
        val latestPhotoBtn = TextView(this).apply {
            text = AppLocale.t("latest_photo")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(10f), AndroidUtilities.dp(16f), AndroidUtilities.dp(10f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(20f).toFloat()
                setStroke(AndroidUtilities.dp(1f), AppTheme.borderSubtle)
            }
            setOnClickListener { openLatestGalleryPhoto() }
        }
        resumeDraftBtn = TextView(this).apply {
            text = AppLocale.t("resume_draft")
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(10f), AndroidUtilities.dp(16f), AndroidUtilities.dp(10f))
            background = GradientDrawable().apply {
                setColor(0x222AABEE)
                cornerRadius = AndroidUtilities.dp(20f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x662AABEE)
            }
            setOnClickListener { resumeLastDraft() }
        }
        shortcutsRow.addView(
            latestPhotoBtn,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = AndroidUtilities.dp(8f)
            }
        )
        shortcutsRow.addView(resumeDraftBtn)

        // Recent Edits strip
        recentsSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, AndroidUtilities.dp(24f), 0, 0)
        }
        val recentsTitle = TextView(this).apply {
            text = AppLocale.t("recent_photos")
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(0, 0, 0, AndroidUtilities.dp(10f))
        }
        val recentsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        recentsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recentsScroll.addView(recentsRow)
        recentsSection.addView(recentsTitle)
        recentsSection.addView(recentsScroll)

        scrollContent.addView(
            landingUpdateBanner,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = AndroidUtilities.dp(10f)
            }
        )
        scrollContent.addView(
            landingCrashBanner,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = AndroidUtilities.dp(10f)
            }
        )
        scrollContent.addView(
            heroLogo,
            LinearLayout.LayoutParams(AndroidUtilities.dp(92f), AndroidUtilities.dp(92f)).apply {
                topMargin = AndroidUtilities.dp(16f)
            }
        )
        scrollContent.addView(title)
        scrollContent.addView(subtitle)
        scrollContent.addView(pickBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        scrollContent.addView(shortcutsRow)
        scrollContent.addView(recentsSection, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val landingScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(scrollContent)
        }
        landingRoot.addView(landingScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // === BOTTOM UTILITY BAR: [v1.4.0]  |  [Check Update]  |  [🌙 Dark / ☀ Light] ===
        landingRoot.addView(
            View(this).apply { setBackgroundColor(AppTheme.borderSubtle) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(0.8f).coerceAtLeast(1))
        )

        val bottomUtilBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(AppTheme.bgSurface)
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(10f), AndroidUtilities.dp(14f), AndroidUtilities.dp(12f))
        }

        val versionPill = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setTextColor(AppTheme.textSecondary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
            setPadding(AndroidUtilities.dp(11f), AndroidUtilities.dp(7f), AndroidUtilities.dp(11f), AndroidUtilities.dp(7f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
            }
        }

        val updateFrame = FrameLayout(this)
        manualUpdateBtn = TextView(this).apply {
            text = AppLocale.t("check_update")
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(AndroidUtilities.dp(13f), AndroidUtilities.dp(7f), AndroidUtilities.dp(13f), AndroidUtilities.dp(7f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x442AABEE)
            }
            setOnClickListener { runManualUpdateCheck() }
        }
        bottomUpdateDot = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AppTheme.accent)
            }
        }
        updateFrame.addView(manualUpdateBtn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        updateFrame.addView(
            bottomUpdateDot,
            FrameLayout.LayoutParams(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), Gravity.TOP or Gravity.END).apply {
                topMargin = AndroidUtilities.dp(2f)
                rightMargin = AndroidUtilities.dp(2f)
            }
        )

        val themeToggleBtn = TextView(this).apply {
            text = if (AppTheme.isDark) "☀ ${AppLocale.t("light_mode")}" else "🌙 ${AppLocale.t("dark_mode")}"
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(7f), AndroidUtilities.dp(12f), AndroidUtilities.dp(7f))
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
                setStroke(AndroidUtilities.dp(1f), AppTheme.borderSubtle)
            }
            setOnClickListener {
                AppTheme.toggle(this@MainActivity)
                rebuildUiPreservingState()
            }
        }

        bottomUtilBar.addView(versionPill)
        bottomUtilBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottomUtilBar.addView(updateFrame)
        bottomUtilBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bottomUtilBar.addView(themeToggleBtn)
        landingRoot.addView(bottomUtilBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        landingView.addView(landingRoot, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        rootContainer.addView(
            landingView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildEditorShell() {
        editorShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppTheme.bgCanvas)
            visibility = View.GONE
        }

        // === TOP BAR: `←` + Title/LayerBadge + Undo + Reset + Compare + Gallery + Settings ===
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(AppTheme.bgSurface)
            setPadding(AndroidUtilities.dp(4f), AndroidUtilities.dp(4f), AndroidUtilities.dp(6f), AndroidUtilities.dp(4f))
        }

        val backBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_back)
            setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            setOnClickListener { returnToMainMenu() }
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(2f), 0, AndroidUtilities.dp(4f), 0)
        }
        val titleView = TextView(this).apply {
            text = AppLocale.t("app_name")
            setTextColor(AppTheme.textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        layerBadgeView = TextView(this).apply {
            text = "✦ ${AppLocale.t("layers_badge")}"
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = AndroidUtilities.bold()
            maxLines = 1
            visibility = View.GONE
        }
        titleCol.addView(titleView)
        titleCol.addView(layerBadgeView)

        undoIconBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_undo)
            setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(9f), AndroidUtilities.dp(9f), AndroidUtilities.dp(9f), AndroidUtilities.dp(9f))
            visibility = View.GONE
            setOnClickListener { performGlobalUndo() }
        }

        resetTextBtn = TextView(this).apply {
            text = AppLocale.t("reset")
            setTextColor(AppTheme.accentText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(4f), 0, AndroidUtilities.dp(4f), 0)
            visibility = View.GONE
            setOnClickListener { resetToOriginal() }
        }

        compareBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_compare)
            setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(9f), AndroidUtilities.dp(9f), AndroidUtilities.dp(9f), AndroidUtilities.dp(9f))
            visibility = View.GONE
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        previewView.setComparing(true)
                        alpha = 0.6f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        previewView.setComparing(false)
                        alpha = 1.0f
                    }
                }
                true
            }
        }

        val changePhotoBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_gallery)
            setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(9f), AndroidUtilities.dp(9f), AndroidUtilities.dp(9f), AndroidUtilities.dp(9f))
            setOnClickListener { launchPhotoPicker() }
        }

        val (editorSettingsFrame, edSetDot) = makeIconWithBadge(
            iconRes = R.drawable.ic_tg_settings,
            badgeColor = AppTheme.danger,
            tintIcon = true
        ) {
            openSettings()
        }
        editorSettingsDot = edSetDot

        topBar.addView(backBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(40f), AndroidUtilities.dp(42f)))
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(undoIconBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(36f), AndroidUtilities.dp(40f)))
        topBar.addView(resetTextBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(40f)))
        topBar.addView(compareBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(36f), AndroidUtilities.dp(40f)))
        topBar.addView(changePhotoBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(36f), AndroidUtilities.dp(40f)))
        topBar.addView(editorSettingsFrame, LinearLayout.LayoutParams(AndroidUtilities.dp(38f), AndroidUtilities.dp(40f)))
        editorShell.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(52f)))

        // === INTERACTIVE ZOOM + COMPARE IMAGE PREVIEW ===
        previewView = ZoomableCompareImageView(this).apply {
            setBackgroundColor(AppTheme.bgCanvas)
        }
        editorShell.addView(
            previewView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // === BOTTOM FLOATING PILL: Crop | Filters (Samsung) | Tone (Samsung) | Draw/Text + Export ===
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(AppTheme.bgSurface)
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
        }

        val toolPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(8f), 0)
            background = GradientDrawable().apply {
                setColor(AppTheme.bgElevated)
                cornerRadius = AndroidUtilities.dp(28f).toFloat()
                setStroke(AndroidUtilities.dp(1f), AppTheme.borderSubtle)
            }
        }

        val cropIcon = makePillIcon(R.drawable.ic_tg_crop) { openCropMode() }
        val filtersIcon = makePillIcon(R.drawable.ic_samsung_filters) { openFilterOrToneMode(TelegramFilterPanel.InitialTab.FILTERS) }
        val toneIcon = makePillIcon(R.drawable.ic_tone_brightness) { openFilterOrToneMode(TelegramFilterPanel.InitialTab.TUNE) }
        val drawIcon = makePillIcon(R.drawable.ic_tg_draw) { openPaintMode() }

        toolPill.addView(cropIcon, LinearLayout.LayoutParams(0, AndroidUtilities.dp(50f), 1f))
        toolPill.addView(filtersIcon, LinearLayout.LayoutParams(0, AndroidUtilities.dp(50f), 1f))
        toolPill.addView(toneIcon, LinearLayout.LayoutParams(0, AndroidUtilities.dp(50f), 1f))
        toolPill.addView(drawIcon, LinearLayout.LayoutParams(0, AndroidUtilities.dp(50f), 1f))

        saveCircleBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_save)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AppTheme.accent)
            }
            setOnClickListener { openExportDialog() }
        }

        bottomRow.addView(
            toolPill,
            LinearLayout.LayoutParams(0, AndroidUtilities.dp(52f), 1f).apply {
                marginEnd = AndroidUtilities.dp(12f)
            }
        )
        bottomRow.addView(
            saveCircleBtn,
            LinearLayout.LayoutParams(AndroidUtilities.dp(50f), AndroidUtilities.dp(50f))
        )
        editorShell.addView(bottomRow)

        rootContainer.addView(
            editorShell,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    private fun openSettings() {
        SettingsDialog.show(
            context = this,
            onOpenDebugConsole = { openDebugConsole() },
            onSettingsChanged = { rebuildUiPreservingState() }
        )
    }

    private fun makeIconWithBadge(
        iconRes: Int,
        badgeColor: Int,
        tintIcon: Boolean,
        onClick: () -> Unit
    ): Pair<FrameLayout, View> {
        val frame = FrameLayout(this).apply {
            setOnClickListener { onClick() }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            if (tintIcon) setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
        }
        val dot = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(badgeColor)
                setStroke(AndroidUtilities.dp(1.2f), AppTheme.bgSurface)
            }
        }
        frame.addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(
            dot,
            FrameLayout.LayoutParams(AndroidUtilities.dp(8.5f), AndroidUtilities.dp(8.5f), Gravity.TOP or Gravity.END).apply {
                topMargin = AndroidUtilities.dp(6f)
                rightMargin = AndroidUtilities.dp(6f)
            }
        )
        return frame to dot
    }

    private fun makePillIcon(iconRes: Int, onClick: () -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(AppTheme.iconTint)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f))
            setOnClickListener { onClick() }
        }
    }

    private fun openDebugConsole() {
        DebugConsoleDialog.show(this) {
            refreshDebugBadges()
        }
    }

    private fun refreshDebugBadges() {
        val hasCrash = CrashLogManager.hasUnreadCrash()
        val dotVis = if (hasCrash) View.VISIBLE else View.GONE
        landingSettingsDot.visibility = dotVis
        editorSettingsDot.visibility = dotVis
        landingCrashBanner.visibility = dotVis
    }

    private fun refreshLandingExtras() {
        val hasDraft = displayBitmap != null || DraftManager.hasDraft(this)
        resumeDraftBtn.visibility = if (hasDraft) View.VISIBLE else View.GONE

        val recentFiles = DraftManager.listRecentFiles(this)
        if (recentFiles.isEmpty()) {
            recentsSection.visibility = View.GONE
            return
        }
        recentsSection.visibility = View.VISIBLE
        recentsRow.removeAllViews()
        val thumbSize = AndroidUtilities.dp(76f)
        recentFiles.forEach { file ->
            val card = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(AppTheme.bgElevated)
                    cornerRadius = AndroidUtilities.dp(12f).toFloat()
                    setStroke(AndroidUtilities.dp(1f), AppTheme.borderSubtle)
                }
                clipToOutline = true
                setOnClickListener { openRecentFile(file) }
            }
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            card.addView(iv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            recentsRow.addView(
                card,
                LinearLayout.LayoutParams(thumbSize, thumbSize).apply {
                    marginEnd = AndroidUtilities.dp(8f)
                }
            )
            ioExecutor.execute {
                val thumb = DraftManager.decodeThumbnail(file, 200)
                if (thumb != null) {
                    iv.post { iv.setImageBitmap(thumb) }
                }
            }
        }
    }

    private fun openLatestGalleryPhoto() {
        ioExecutor.execute {
            val latestUri = BitmapUtils.findLatestGalleryPhotoUri(this)
            runOnUiThread {
                if (latestUri != null) {
                    loadImageFromUri(latestUri)
                } else {
                    launchPhotoPicker()
                }
            }
        }
    }

    private fun resumeLastDraft() {
        if (displayBitmap != null) {
            landingView.visibility = View.GONE
            editorShell.visibility = View.VISIBLE
            previewView.setBitmaps(displayBitmap, originalBitmap)
            updateTopBarState()
            return
        }
        ioExecutor.execute {
            val loaded = DraftManager.loadDraft(this)
            runOnUiThread {
                if (loaded == null) {
                    Toast.makeText(this, AppLocale.t("load_failed"), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                openBitmapsInEditor(loaded.first, loaded.second)
            }
        }
    }

    private fun openRecentFile(file: File) {
        ioExecutor.execute {
            val bmp = DraftManager.decodeFullRecent(file)
            runOnUiThread {
                if (bmp == null) {
                    Toast.makeText(this, AppLocale.t("load_failed"), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                openBitmapsInEditor(bmp, bmp.copy(Bitmap.Config.ARGB_8888, false))
            }
        }
    }

    private fun openBitmapsInEditor(edited: Bitmap, original: Bitmap) {
        closeActivePanel()
        historyStack.clear()
        originalBitmap = original
        croppedBaseBitmap = edited
        filteredPhotoBitmap = edited
        filterRecipe = EditLayerState.FilterRecipe()
        paintLayerState = EditLayerState.PaintLayerState()
        displayBitmap = edited
        previewView.setBitmaps(edited, original)
        landingView.visibility = View.GONE
        editorShell.visibility = View.VISIBLE
        updateTopBarState()
        saveDraftAsync(edited, original)
    }

    private fun saveDraftAsync(edited: Bitmap, original: Bitmap?) {
        val editedCopy = edited.copy(Bitmap.Config.ARGB_8888, false)
        val origCopy = original?.copy(Bitmap.Config.ARGB_8888, false)
        ioExecutor.execute {
            DraftManager.saveAutoDraft(applicationContext, editedCopy, origCopy)
            editedCopy.recycle()
            origCopy?.recycle()
        }
    }

    private fun runSilentUpdateCheck() {
        UpdateChecker.checkForUpdateAsync(
            context = this,
            postSystemNotification = true,
            forceNotificationRepeat = false
        ) { result ->
            if (isFinishing || isDestroyed) return@checkForUpdateAsync
            if (result is UpdateChecker.CheckResult.UpdateAvailable) {
                availableRelease = result.release
                applyUpdateAvailableUi(result.release)
            }
        }
    }

    private fun runManualUpdateCheck() {
        if (checkingUpdate) return
        checkingUpdate = true
        manualUpdateBtn.text = AppLocale.t("checking_update")
        manualUpdateBtn.alpha = 0.7f

        UpdateChecker.checkForUpdateAsync(
            context = this,
            postSystemNotification = true,
            forceNotificationRepeat = true
        ) { result ->
            checkingUpdate = false
            manualUpdateBtn.text = AppLocale.t("check_update")
            manualUpdateBtn.alpha = 1f
            if (isFinishing || isDestroyed) return@checkForUpdateAsync

            when (result) {
                is UpdateChecker.CheckResult.UpdateAvailable -> {
                    availableRelease = result.release
                    applyUpdateAvailableUi(result.release)
                    UpdateDialogHelper.showUpdateDialog(this, result.release)
                }
                is UpdateChecker.CheckResult.UpToDate -> {
                    Toast.makeText(
                        this,
                        "${AppLocale.t("up_to_date")} (v${result.currentVersion}) ✓",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is UpdateChecker.CheckResult.Error -> {
                    Toast.makeText(
                        this,
                        "Update check error: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun applyUpdateAvailableUi(release: UpdateChecker.ReleaseInfo) {
        landingUpdateBannerText.text = "${AppLocale.t("update_available")} (${release.tagName})"
        landingUpdateBanner.visibility = View.VISIBLE
        bottomUpdateDot.visibility = View.VISIBLE
    }

    private fun launchPhotoPicker() {
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (_: Exception) {
            legacyPickerLauncher.launch("image/*")
        }
    }

    private fun loadImageFromUri(uri: Uri) {
        CrashLogManager.addBreadcrumb("Loading image URI")
        ioExecutor.execute {
            val bmp = BitmapUtils.decodeSampledBitmap(this, uri)
            runOnUiThread {
                if (bmp == null) {
                    Toast.makeText(this, AppLocale.t("load_failed"), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                CrashLogManager.addBreadcrumb("Loaded bitmap ${bmp.width}x${bmp.height}")
                openBitmapsInEditor(bmp, bmp.copy(Bitmap.Config.ARGB_8888, false))
            }
        }
    }

    private fun pushSnapshotForUndo() {
        val base = croppedBaseBitmap ?: return
        val filtered = filteredPhotoBitmap ?: base
        if (historyStack.size >= 10) {
            historyStack.removeLast()
        }
        historyStack.addFirst(
            EditorSnapshot(
                croppedBase = base,
                filteredPhoto = filtered,
                filterRecipe = filterRecipe.deepCopy(),
                paintLayer = paintLayerState.shallowCopy()
            )
        )
    }

    private fun refreshCompositePreview() {
        val filtered = filteredPhotoBitmap ?: croppedBaseBitmap ?: return
        val composited = EditLayerState.compositePhotoWithPaintLayer(filtered, paintLayerState)
        displayBitmap = composited
        previewView.setBitmaps(composited, originalBitmap)
        updateTopBarState()
        saveDraftAsync(composited, originalBitmap)
    }

    private fun openCropMode() {
        val bmp = displayBitmap ?: return
        CrashLogManager.addBreadcrumb("Opened Crop mode")
        val panel = TelegramCropPanel(
            this,
            bmp,
            onCancel = { closeActivePanel() },
            onDone = { cropped ->
                pushSnapshotForUndo()
                croppedBaseBitmap = cropped
                filteredPhotoBitmap = cropped
                filterRecipe = EditLayerState.FilterRecipe()
                paintLayerState = EditLayerState.PaintLayerState()
                refreshCompositePreview()
                closeActivePanel()
            }
        )
        showPanel(panel)
    }

    private fun openFilterOrToneMode(initialTab: TelegramFilterPanel.InitialTab) {
        val base = croppedBaseBitmap ?: return
        CrashLogManager.addBreadcrumb("Opened ${initialTab.name} mode (non-destructive)")
        val overlayPreview = EditLayerState.buildCombinedOverlayBitmap(
            paintState = paintLayerState,
            width = base.width,
            height = base.height
        )
        val panel = TelegramFilterPanel(
            context = this,
            sourceBitmap = base,
            initialRecipe = filterRecipe.deepCopy(),
            initialTab = initialTab,
            paintOverlayPreview = overlayPreview,
            onCancel = { closeActivePanel() },
            onDone = { filtered, nextRecipe ->
                pushSnapshotForUndo()
                filteredPhotoBitmap = filtered
                filterRecipe = nextRecipe
                // Layer 3 (paintLayerState) stays 100% preserved & untouched!
                refreshCompositePreview()
                closeActivePanel()
            }
        )
        activeFilterPanel = panel
        showPanel(panel)
    }

    private fun openPaintMode() {
        val filtered = filteredPhotoBitmap ?: croppedBaseBitmap ?: return
        CrashLogManager.addBreadcrumb("Opened Draw/Paint mode (editable layer=${paintLayerState.hasContent})")
        val panel = TelegramPaintPanel(
            context = this,
            sourceBitmap = filtered,
            initialPaintState = paintLayerState.shallowCopy(),
            onCancel = { closeActivePanel() },
            onDone = { nextPaintLayer ->
                pushSnapshotForUndo()
                paintLayerState = nextPaintLayer
                // Layer 1 (croppedBaseBitmap) and Layer 2 (filterRecipe / filteredPhotoBitmap) stay untouched!
                refreshCompositePreview()
                closeActivePanel()
            }
        )
        activePaintPanel = panel
        showPanel(panel)
    }

    private fun showPanel(panel: View) {
        activePanelContainer.removeAllViews()
        activePanelContainer.addView(
            panel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        activePanelContainer.visibility = View.VISIBLE
    }

    private fun closeActivePanel() {
        activeFilterPanel?.cleanup()
        activeFilterPanel = null
        activePaintPanel?.cleanup()
        activePaintPanel = null
        activePanelContainer.removeAllViews()
        activePanelContainer.visibility = View.GONE
        refreshDebugBadges()
    }

    private fun performGlobalUndo() {
        if (historyStack.isEmpty()) return
        val snap = historyStack.removeFirst()
        croppedBaseBitmap = snap.croppedBase
        filteredPhotoBitmap = snap.filteredPhoto
        filterRecipe = snap.filterRecipe
        paintLayerState = snap.paintLayer
        refreshCompositePreview()
    }

    private fun resetToOriginal() {
        val orig = originalBitmap ?: return
        historyStack.clear()
        val copy = orig.copy(Bitmap.Config.ARGB_8888, false)
        croppedBaseBitmap = copy
        filteredPhotoBitmap = copy
        filterRecipe = EditLayerState.FilterRecipe()
        paintLayerState = EditLayerState.PaintLayerState()
        refreshCompositePreview()
    }

    private fun updateTopBarState() {
        val hasEdits = historyStack.isNotEmpty() || !filterRecipe.isEmpty() || paintLayerState.hasContent
        undoIconBtn.visibility = if (historyStack.isNotEmpty()) View.VISIBLE else View.GONE
        resetTextBtn.visibility = if (hasEdits) View.VISIBLE else View.GONE
        compareBtn.visibility = if (hasEdits && originalBitmap != null) View.VISIBLE else View.GONE
        layerBadgeView.visibility = if (paintLayerState.hasContent) View.VISIBLE else View.GONE
    }

    private fun openExportDialog() {
        val bmp = displayBitmap ?: return
        ExportOptionsDialog.show(
            context = this,
            bitmap = bmp,
            onSave = { format, quality -> performGallerySave(bmp, format, quality) },
            onShare = { format, quality -> performShare(bmp, format, quality) }
        )
    }

    private fun performGallerySave(bmp: Bitmap, format: BitmapUtils.ExportFormat, quality: Int) {
        CrashLogManager.addBreadcrumb("Saving ${format.label} q=$quality (${bmp.width}x${bmp.height})")
        saveCircleBtn.isEnabled = false
        saveCircleBtn.alpha = 0.6f
        ioExecutor.execute {
            val saved = BitmapUtils.saveToGallery(this, bmp, format, quality)
            if (saved != null) {
                DraftManager.addRecentEdit(applicationContext, bmp)
            }
            runOnUiThread {
                saveCircleBtn.isEnabled = true
                saveCircleBtn.alpha = 1f
                Toast.makeText(
                    this,
                    if (saved != null) AppLocale.t("saved_gallery") else AppLocale.t("save_failed"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun performShare(bmp: Bitmap, format: BitmapUtils.ExportFormat, quality: Int) {
        CrashLogManager.addBreadcrumb("Sharing ${format.label} q=$quality")
        ioExecutor.execute {
            val uri = BitmapUtils.exportToShareUri(this, bmp, format, quality)
            if (uri != null) {
                DraftManager.addRecentEdit(applicationContext, bmp)
            }
            runOnUiThread {
                if (uri == null) {
                    Toast.makeText(this, AppLocale.t("save_failed"), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = format.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, AppLocale.t("share_photo")))
            }
        }
    }
}
