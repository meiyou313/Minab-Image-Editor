package com.telephoto.editor

import android.content.Intent
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.R
import java.util.ArrayDeque
import java.util.concurrent.Executors

/**
 * Telegram-identical Photo Editor shell (frames 00:03 - 00:14, 00:35, 01:41):
 * - Top bar: `←` Back + Title + Undo/Reset when modified
 * - Full-bleed pure-black photo canvas
 * - Bottom bar: Floating dark rounded pill with [Crop | Draw/Paint | Adjust] icons + Telegram blue circular Save button
 *   (No "Add a caption..." or "SD" messaging buttons per user's instruction)
 */
class MainActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var originalBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null
    private val historyStack = ArrayDeque<Bitmap>()

    private lateinit var rootContainer: FrameLayout
    private lateinit var landingView: LinearLayout
    private lateinit var editorShell: LinearLayout
    private lateinit var previewImageView: ImageView
    private lateinit var undoIconBtn: ImageView
    private lateinit var resetTextBtn: TextView
    private lateinit var saveCircleBtn: ImageView
    private lateinit var activePanelContainer: FrameLayout

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
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            AndroidUtilities.statusBarHeight = 0
            windowInsets
        }
        setContentView(rootContainer)

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

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onDestroy() {
        closeActivePanel()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
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
        landingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(32f), AndroidUtilities.dp(32f), AndroidUtilities.dp(32f), AndroidUtilities.dp(32f))
        }

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

        landingView.addView(title)
        landingView.addView(subtitle)
        landingView.addView(pickBtn)

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

        // === TOP BAR (Exact Telegram PhotoViewer bar: `←` + Title + Undo/Reset) ===
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(AndroidUtilities.dp(8f), AndroidUtilities.dp(4f), AndroidUtilities.dp(14f), AndroidUtilities.dp(4f))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = AndroidUtilities.bold()
            setPadding(AndroidUtilities.dp(10f), 0, 0, 0)
        }

        undoIconBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_tg_undo)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f), AndroidUtilities.dp(10f))
            visibility = View.GONE
            setOnClickListener { performGlobalUndo() }
        }

        resetTextBtn = TextView(this).apply {
            text = getString(R.string.action_reset)
            setTextColor(0xFF4DA3FF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(8f), 0, AndroidUtilities.dp(4f), 0)
            visibility = View.GONE
            setOnClickListener { resetToOriginal() }
        }

        topBar.addView(backBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(46f), AndroidUtilities.dp(46f)))
        topBar.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(undoIconBtn, LinearLayout.LayoutParams(AndroidUtilities.dp(44f), AndroidUtilities.dp(44f)))
        topBar.addView(resetTextBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(44f)))
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

        // === BOTTOM FLOATING PILL + BLUE CIRCLE SAVE BUTTON (frame 00:05) ===
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
        ioExecutor.execute {
            val bmp = BitmapUtils.decodeSampledBitmap(this, uri)
            runOnUiThread {
                if (bmp == null) {
                    Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
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
