package com.telephoto.editor

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Centralized Dark / Light theme palette persisted in SharedPreferences.
 */
object AppTheme {

    enum class Mode(val id: String) {
        DARK("dark"),
        LIGHT("light")
    }

    private const val PREFS_NAME = "telephoto_app_prefs"
    private const val KEY_THEME = "theme_mode"

    @Volatile
    var currentMode: Mode = Mode.DARK
        private set

    fun init(context: Context) {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, Mode.DARK.id)
        currentMode = if (saved == Mode.LIGHT.id) Mode.LIGHT else Mode.DARK
    }

    fun setMode(context: Context, mode: Mode) {
        currentMode = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, mode.id).apply()
    }

    fun toggle(context: Context): Mode {
        val next = if (currentMode == Mode.DARK) Mode.LIGHT else Mode.DARK
        setMode(context, next)
        return next
    }

    val isDark: Boolean get() = currentMode == Mode.DARK

    // Core Surfaces
    val bgPrimary: Int get() = if (isDark) 0xFF000000.toInt() else 0xFFF4F6F9.toInt()
    val bgCanvas: Int get() = if (isDark) 0xFF000000.toInt() else 0xFFE9EDF2.toInt()
    val bgSurface: Int get() = if (isDark) 0xFF141416.toInt() else 0xFFFFFFFF.toInt()
    val bgCard: Int get() = if (isDark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    val bgElevated: Int get() = if (isDark) 0xFF232327.toInt() else 0xFFEDF1F5.toInt()
    val bgCode: Int get() = if (isDark) 0xFF0C0C0E.toInt() else 0xFFF2F4F8.toInt()
    val bgPill: Int get() = if (isDark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

    // Text & Icons
    val textPrimary: Int get() = if (isDark) Color.WHITE else 0xFF111827.toInt()
    val textSecondary: Int get() = if (isDark) 0xFF9E9E9E.toInt() else 0xFF5F6B7A.toInt()
    val textCode: Int get() = if (isDark) 0xFFE5E5EA.toInt() else 0xFF1F2937.toInt()
    val iconTint: Int get() = if (isDark) Color.WHITE else 0xFF1F2937.toInt()
    val borderSubtle: Int get() = if (isDark) 0x26FFFFFF else 0x1F000000

    // Brand & Semantic Accents
    const val accent: Int = 0xFF2AABEE.toInt()
    const val accentText: Int = 0xFF4DA3FF.toInt()
    const val danger: Int = 0xFFFF453A.toInt()
    const val warning: Int = 0xFFFF9F0A.toInt()
    const val purple: Int = 0xFFBF5AF2.toInt()
    const val success: Int = 0xFF34C759.toInt()

    val updateBannerBg: Int get() = if (isDark) 0xFF132635.toInt() else 0xFFE3F4FD.toInt()
    val crashBannerBg: Int get() = if (isDark) 0xFF2C1618.toInt() else 0xFFFFECEB.toInt()
    val crashBannerText: Int get() = if (isDark) 0xFFFFD5D2.toInt() else 0xFFB42318.toInt()

    @Suppress("DEPRECATION")
    fun applySystemBars(activity: Activity) {
        val window = activity.window ?: return
        window.statusBarColor = bgPrimary
        window.navigationBarColor = bgPrimary
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isDark
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }
}
