package com.telephoto.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Telegram-identical Photo Editor shell:
 * - Landing screen with photo picker, in-app update banner, manual Check Update button,
 *   crash recovery notice, and Ladybug Debug Console button.
 * - Editor shell: Top bar (`←` + Title + Undo/Reset + Update + Ladybug Debug)
 * - Full-bleed pure-black photo canvas
 * - Bottom bar: Floating dark rounded pill with [Crop | Draw/Paint | Adjust] icons + Telegram blue circular Save button
 */
class MainActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var originalBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null
    private val historyStack = ArrayDeque<Bitmap>()

    private lateinit var rootContainer: FrameLayout
    private lateinit var landingView: FrameLayout
    private lateinit var editorShell: LinearLayout
    private lateinit var previewImageView: ImageView
    private lateinit var undoIconBtn: ImageView
    private lateinit var resetTextBtn: TextView
    private lateinit var saveCircleBtn: ImageView
    private lateinit var activePanelContainer: FrameLayout

    // Update & Debug UI references
    private lateinit var landingUpdateBanner: LinearLayout
    private lateinit var landingUpdateBannerText: TextView
    private lateinit var landingCrashBanner: LinearLayout
    private lateinit var manualUpdateBtn: TextView
    private lateinit var landingDebugDot: View
    private lateinit var editorDebugDot: View
    private lateinit var editorUpdateDot: View

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

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activePanelContainer.visibility == View.VISIBLE) {
                    closeActivePanel()
                } else if (editorShell.visibility == View.VISIBLE) {
                    launchPhotoPicker()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        requestNotificationPermissionIfNeeded()
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
    }

    override fun onDestroy() {
        closeActivePanel()
        ioExecutor.shutdownNow()
        super.onDestroy()
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
                    title = intent.getStringExtra(UpdateChecker.EXTRA_RELEASE_TITLE) ?: "TelePhoto Editor $tag",
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
            setBackgroundColor(Color.BLACK)
        }

        // Top utility bar (Version badge on left | Check Update + Ladybug Debug on right)
        val topUtilBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(AndroidUtilities.dp(16f), AndroidUtilities.dp(12f), AndroidUtilities.dp(14f), AndroidUtilities.dp(8f))
        }

        val versionPill = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setTextColor(0xFF9E9E9E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(5f), AndroidUtilities.dp(10f), AndroidUtilities.dp(5f))
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadius = AndroidUtilities.dp(12f).toFloat()
            }
        }

        manualUpdateBtn = TextView(this).apply {
            text = "بررسی آپدیت"
            setTextColor(0xFF64D2FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(7f), AndroidUtilities.dp(12f), AndroidUtilities.dp(7f))
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadius = AndroidUtilities.dp(14f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0x442AABEE)
            }
            setOnClickListener { runManualUpdateCheck() }
        }

        val (debugBtnFrame, debugDot) = makeLadybugButton()
        landingDebugDot = debugDot

        topUtilBar.addView(versionPill)
        topUtilBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topUtilBar.addView(
            manualUpdateBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = AndroidUtilities.dp(8f)
            }
        )
        topUtilBar.addView(debugBtnFrame, LinearLayout.LayoutParams(AndroidUtilities.dp(42f), AndroidUtilities.dp(42f)))

        landingView.addView(
            topUtilBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        // Center content column
        val centerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(28f), AndroidUtilities.dp(68f), AndroidUtilities.dp(28f), AndroidUtilities.dp(32f))
        }

        // In-app Update Notification Banner (visible whenever a new GitHub Release exists)
        landingUpdateBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(11f), AndroidUtilities.dp(14f), AndroidUtilities.dp(11f))
            background = GradientDrawable().apply {
                setColor(0xFF132635.toInt())
                cornerRadius = AndroidUtilities.dp(16f).toFloat()
                setStroke(AndroidUtilities.dp(1f), 0xFF2AABEE.toInt())
            }
            setOnClickListener {
                availableRelease?.let { UpdateDialogHelper.showUpdateDialog(this@MainActivity, it) }
            }
        }
        val bannerIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_update)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        landingUpdateBannerText = TextView(this).apply {
            text = "نسخه جدید در دسترس است!"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(10f), 0, AndroidUtilities.dp(8f), 0)
        }
        val bannerAction = TextView(this).apply {
            text = "مشاهده"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(5f), AndroidUtilities.dp(10f), AndroidUtilities.dp(5f))
            background = GradientDrawable().apply {
                setColor(0xFF2AABEE.toInt())
                cornerRadius = AndroidUtilities.dp(10f).toFloat()
            }
        }
        landingUpdateBanner.addView(bannerIcon, LinearLayout.LayoutParams(AndroidUtilities.dp(22f), AndroidUtilities.dp(22f)))
        landingUpdateBanner.addView(landingUpdateBannerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        landingUpdateBanner.addView(bannerAction)

        // Crash Recovery Banner (shown only after an unexpected crash)
        landingCrashBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(AndroidUtilities.dp(14f), AndroidUtilities.dp(10f), AndroidUtilities.dp(14f), AndroidUtilities.dp(10f))
            background = GradientDrawable().apply {
                setColor(0xFF2C1618.toInt())
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
            text = "گزارش کرش قبلی ذخیره شد — برای مشاهده و کپی لمس کنید"
            setTextColor(0xFFFFD5D2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(10f), 0, 0, 0)
        }
        landingCrashBanner.addView(crashIcon, LinearLayout.LayoutParams(AndroidUtilities.dp(24f), AndroidUtilities.dp(24f)))
        landingCrashBanner.addView(crashText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = getString(R.string.landing_subtitle)
            setTextColor(0xFF9E9E9E.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(32f))
        }

        val pickBtn = TextView(this).apply {
            text = getString(R.string.pick_photo)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(28f), AndroidUtilities.dp(14f), AndroidUtilities.dp(28f), AndroidUtilities.dp(14f))
            background = GradientDrawable().apply {
                setColor(0xFF2AABEE.toInt())
                cornerRadius = AndroidUtilities.dp(26f).toFloat()
            }
            setOnClickListener { launchPhotoPicker() }
        }

        centerCol.addView(
            landingUpdateBanner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = AndroidUtilities.dp(12f)
            }
        )
        centerCol.addView(
            landingCrashBanner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = AndroidUtilities.dp(20f)
            }
        )
        centerCol.addView(title)
        centerCol.addView(subtitle)
        centerCol.addView(pickBtn)

        landingView.addView(
            centerCol,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        rootContainer.addView(
            landingView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun buildEditorShell() {
        editorShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        // === TOP BAR (`←` + Title + Undo + Reset + Update + Ladybug Debug) ===
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(AndroidUtilities.dp(6f), AndroidUtilities.dp(4f), AndroidUtilities.dp(8f), AndroidUtilities.dp(4f))
        }

        val backBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_back)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            setOnClickListener { launchPhotoPicker() }
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17.5f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(6f), 0, 0, 0)
        }

        undoIconBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_undo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(9f), AndroidUtilities.dp(9f), AndroidUtilities.dp(9f), AndroidUtilities.dp(9f))
            visibility = View.GONE
            setOnClickListener { performGlobalUndo() }
        }

        resetTextBtn = TextView(this).apply {
            text = getString(R.string.action_reset)
            setTextColor(0xFF4DA3FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(4f), 0)
            visibility = View.GONE
            setOnClickListener { resetToOriginal() }
        }

        val (updateBtnFrame, updDot) = makeIconWithBadge(R.drawable.ic_tg_update, 0xFF2AABEE.toInt()) {
            val rel = availableRelease
            if (rel != null) {
                UpdateDialogHelper.showUpdateDialog(this, rel)
            } else {
                runManualUpdateCheck()
            }
        }
        editorUpdateDot = updDot

        val (debugBtnFrame, dbgDot) = makeLadybugButton()
        editorDebugDot = dbgDot

        topBar.addView(backBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(42f), AndroidUtilities.dp(44f)))
        topBar.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(undoIconBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(40f), AndroidUtilities.dp(42f)))
        topBar.addView(resetTextBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(42f)))
        topBar.addView(updateBtnFrame, LinearLayout.LayoutParams(AndroidUtilities.dp(40f), AndroidUtilities.dp(42f)))
        topBar.addView(debugBtnFrame, LinearLayout.LayoutParams(AndroidUtilities.dp(40f), AndroidUtilities.dp(42f)))
        editorShell.addView(topBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(54f)))

        // === FULL-BLEED IMAGE PREVIEW ===
        previewImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        editorShell.addView(
            previewImageView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // === BOTTOM FLOATING PILL + BLUE CIRCLE SAVE BUTTON ===
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(AndroidUtilities.dp(20f), AndroidUtilities.dp(12f), AndroidUtilities.dp(16f), AndroidUtilities.dp(16f))
        }

        val toolPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(12f), 0)
            background = GradientDrawable().apply {
                setColor(0xFF1C1C1E.toInt())
                cornerRadius = AndroidUtilities.dp(28f).toFloat()
            }
        }

        val cropIcon = makePillIcon(R.drawable.ic_tg_crop) { openCropMode() }
        val drawIcon = makePillIcon(R.drawable.ic_tg_draw) { openPaintMode() }
        val tuneIcon = makePillIcon(R.drawable.ic_tg_tune) { openAdjustMode() }

        toolPill.addView(cropIcon, LinearLayout.LayoutParams(0, AndroidUtilities.dp(50f), 1f))
        toolPill.addView(drawIcon, LinearLayout.LayoutParams(0, AndroidUtilities.dp(50f), 1f))
        toolPill.addView(tuneIcon, LinearLayout.LayoutParams(0, AndroidUtilities.dp(50f), 1f))

        saveCircleBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_save)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f), AndroidUtilities.dp(12f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2AABEE.toInt())
            }
            setOnClickListener { saveCurrentImage() }
        }

        bottomRow.addView(
            toolPill,
            LinearLayout.LayoutParams(0, AndroidUtilities.dp(52f), 1f).apply {
                rightMargin = AndroidUtilities.dp(14f)
            }
        )
        bottomRow.addView(
            saveCircleBtn,
            LinearLayout.LayoutParams(AndroidUtilities.dp(50f), AndroidUtilities.dp(50f))
        )
        editorShell.addView(bottomRow)

        rootContainer.addView(
            editorShell,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun makeLadybugButton(): Pair<FrameLayout, View> {
        return makeIconWithBadge(R.drawable.ic_debug_ladybug, 0xFFFF3B30.toInt()) {
            openDebugConsole()
        }
    }

    private fun makeIconWithBadge(
        iconRes: Int,
        badgeColor: Int,
        onClick: () -> Unit
    ): Pair<FrameLayout, View> {
        val frame = FrameLayout(this).apply {
            setOnClickListener { onClick() }
        }
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f), AndroidUtilities.dp(8f))
        }
        val dot = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(badgeColor)
                setStroke(AndroidUtilities.dp(1.2f), Color.BLACK)
            }
        }
        frame.addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(
            dot,
            FrameLayout.LayoutParams(AndroidUtilities.dp(9f), AndroidUtilities.dp(9f), Gravity.TOP or Gravity.END).apply {
                topMargin = AndroidUtilities.dp(6f)
                rightMargin = AndroidUtilities.dp(6f)
            }
        )
        return frame to dot
    }

    private fun openDebugConsole() {
        DebugConsoleDialog.show(this) {
            refreshDebugBadges()
        }
    }

    private fun refreshDebugBadges() {
        val hasCrash = CrashLogManager.hasUnreadCrash()
        val dotVis = if (hasCrash) View.VISIBLE else View.GONE
        landingDebugDot.visibility = dotVis
        editorDebugDot.visibility = dotVis
        landingCrashBanner.visibility = dotVis
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
        manualUpdateBtn.text = "در حال بررسی..."
        manualUpdateBtn.alpha = 0.7f
        Toast.makeText(this, "در حال بررسی آخرین نسخه از GitHub...", Toast.LENGTH_SHORT).show()

        UpdateChecker.checkForUpdateAsync(
            context = this,
            postSystemNotification = true,
            forceNotificationRepeat = true
        ) { result ->
            checkingUpdate = false
            manualUpdateBtn.text = "بررسی آپدیت"
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
                        "شما آخرین نسخه (v${result.currentVersion}) را دارید ✓",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is UpdateChecker.CheckResult.Error -> {
                    Toast.makeText(
                        this,
                        "خطا در بررسی آپدیت: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun applyUpdateAvailableUi(release: UpdateChecker.ReleaseInfo) {
        landingUpdateBannerText.text = "نسخه جدید ${release.tagName} منتشر شد!"
        landingUpdateBanner.visibility = View.VISIBLE
        editorUpdateDot.visibility = View.VISIBLE
    }

    private fun makePillIcon(iconRes: Int, onClick: () -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(13f), AndroidUtilities.dp(13f), AndroidUtilities.dp(13f), AndroidUtilities.dp(13f))
            setOnClickListener { onClick() }
        }
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
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                CrashLogManager.addBreadcrumb("Loaded bitmap ${bmp.width}x${bmp.height} (${bmp.config})")
                closeActivePanel()
                clearHistory()
                originalBitmap = bmp.copy(Bitmap.Config.ARGB_8888, false)
                currentBitmap = bmp
                previewImageView.setImageBitmap(currentBitmap)
                landingView.visibility = View.GONE
                editorShell.visibility = View.VISIBLE
                updateTopBarState()
            }
        }
    }

    private fun openCropMode() {
        val bmp = currentBitmap ?: return
        CrashLogManager.addBreadcrumb("Opened Crop mode")
        val panel = TelegramCropPanel(
            this,
            bmp,
            onCancel = { closeActivePanel() },
            onDone = { cropped ->
                commitEditedBitmap(cropped)
                closeActivePanel()
            }
        )
        showPanel(panel)
    }

    private fun openAdjustMode() {
        val bmp = currentBitmap ?: return
        CrashLogManager.addBreadcrumb("Opened Adjust/Filter mode")
        val panel = TelegramFilterPanel(
            this,
            bmp,
            onCancel = { closeActivePanel() },
            onDone = { filtered ->
                commitEditedBitmap(filtered)
                closeActivePanel()
            }
        )
        activeFilterPanel = panel
        showPanel(panel)
    }

    private fun openPaintMode() {
        val bmp = currentBitmap ?: return
        CrashLogManager.addBreadcrumb("Opened Draw/Paint mode")
        val panel = TelegramPaintPanel(
            this,
            bmp,
            onCancel = { closeActivePanel() },
            onDone = { painted ->
                commitEditedBitmap(painted)
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
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
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

    private fun commitEditedBitmap(next: Bitmap) {
        if (next === currentBitmap) return
        currentBitmap?.let { prev ->
            if (historyStack.size >= 8) {
                historyStack.removeLast()
            }
            historyStack.addFirst(prev)
        }
        currentBitmap = next
        previewImageView.setImageBitmap(next)
        updateTopBarState()
    }

    private fun performGlobalUndo() {
        if (historyStack.isEmpty()) return
        currentBitmap = historyStack.removeFirst()
        previewImageView.setImageBitmap(currentBitmap)
        updateTopBarState()
    }

    private fun resetToOriginal() {
        val orig = originalBitmap ?: return
        clearHistory()
        currentBitmap = orig.copy(Bitmap.Config.ARGB_8888, false)
        previewImageView.setImageBitmap(currentBitmap)
        updateTopBarState()
    }

    private fun clearHistory() {
        historyStack.clear()
    }

    private fun updateTopBarState() {
        val canUndo = historyStack.isNotEmpty()
        undoIconBtn.visibility = if (canUndo) View.VISIBLE else View.GONE
        resetTextBtn.visibility = if (canUndo) View.VISIBLE else View.GONE
    }

    private fun saveCurrentImage() {
        val bmp = currentBitmap ?: return
        CrashLogManager.addBreadcrumb("Saving image ${bmp.width}x${bmp.height}")
        saveCircleBtn.isEnabled = false
        saveCircleBtn.alpha = 0.6f
        ioExecutor.execute {
            val saved = BitmapUtils.saveToGallery(this, bmp)
            runOnUiThread {
                saveCircleBtn.isEnabled = true
                saveCircleBtn.alpha = 1f
                Toast.makeText(
                    this,
                    if (saved != null) R.string.saved_to_gallery else R.string.save_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
