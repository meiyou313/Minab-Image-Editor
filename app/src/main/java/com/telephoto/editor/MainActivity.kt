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

class MainActivity : AppCompatActivity() {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var originalBitmap: Bitmap? = null
    private var currentBitmap: Bitmap? = null
    private val historyStack = ArrayDeque<Bitmap>()

    private lateinit var rootContainer: FrameLayout
    private lateinit var landingView: LinearLayout
    private lateinit var editorShell: LinearLayout
    private lateinit var previewImageView: ImageView
    private lateinit var undoButton: TextView
    private lateinit var resetButton: TextView
    private lateinit var saveButton: TextView
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
            setTextColor(0xFFAAAAAA.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setPadding(0, AndroidUtilities.dp(12f), 0, AndroidUtilities.dp(32f))
        }

        val pickBtn = TextView(this).apply {
            text = getString(R.string.pick_photo)
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setPadding(AndroidUtilities.dp(28f), AndroidUtilities.dp(14f), AndroidUtilities.dp(28f), AndroidUtilities.dp(14f))
            background = GradientDrawable().apply {
                setColor(0xFF4DA3FF.toInt())
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
            visibility = View.GONE
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF141414.toInt())
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(8f), AndroidUtilities.dp(12f), AndroidUtilities.dp(8f))
        }

        val openBtn = makeBarButton(getString(R.string.action_new_photo), Color.WHITE) {
            launchPhotoPicker()
        }
        undoButton = makeBarButton(getString(R.string.action_undo), Color.WHITE) {
            performGlobalUndo()
        }
        resetButton = makeBarButton(getString(R.string.action_reset), 0xFFFFB74D.toInt()) {
            resetToOriginal()
        }
        saveButton = makeBarButton(getString(R.string.action_save), 0xFF4DA3FF.toInt(), bold = true) {
            saveCurrentImage()
        }

        topBar.addView(openBtn, LinearLayout.LayoutParams(0, AndroidUtilities.dp(44f), 1f))
        topBar.addView(undoButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(44f), 1f))
        topBar.addView(resetButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(44f), 1f))
        topBar.addView(saveButton, LinearLayout.LayoutParams(0, AndroidUtilities.dp(44f), 1f))
        editorShell.addView(topBar)

        // Image Preview
        previewImageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        editorShell.addView(
            previewImageView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // Bottom Tool Bar (Crop / Adjust / Paint & Text)
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF141414.toInt())
            setPadding(AndroidUtilities.dp(12f), AndroidUtilities.dp(10f), AndroidUtilities.dp(12f), AndroidUtilities.dp(14f))
        }

        val cropBtn = makeToolPill(getString(R.string.tool_crop)) { openCropMode() }
        val adjustBtn = makeToolPill(getString(R.string.tool_adjust)) { openAdjustMode() }
        val paintBtn = makeToolPill(getString(R.string.tool_paint)) { openPaintMode() }

        val lp = {
            LinearLayout.LayoutParams(0, AndroidUtilities.dp(48f), 1f).apply {
                setMargins(AndroidUtilities.dp(6f), 0, AndroidUtilities.dp(6f), 0)
            }
        }
        bottomBar.addView(cropBtn, lp())
        bottomBar.addView(adjustBtn, lp())
        bottomBar.addView(paintBtn, lp())
        editorShell.addView(bottomBar)

        rootContainer.addView(
            editorShell,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
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
        undoButton.isEnabled = canUndo
        undoButton.alpha = if (canUndo) 1f else 0.4f
        resetButton.isEnabled = canUndo
        resetButton.alpha = if (canUndo) 1f else 0.4f
    }

    private fun saveCurrentImage() {
        val bmp = currentBitmap ?: return
        saveButton.isEnabled = false
        ioExecutor.execute {
            val saved = BitmapUtils.saveToGallery(this, bmp)
            runOnUiThread {
                saveButton.isEnabled = true
                Toast.makeText(
                    this,
                    if (saved != null) R.string.saved_to_gallery else R.string.save_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun makeBarButton(label: String, color: Int, bold: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (bold) typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    private fun makeToolPill(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = AndroidUtilities.bold()
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(0xFF232323.toInt())
                cornerRadius = AndroidUtilities.dp(24f).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }
}
