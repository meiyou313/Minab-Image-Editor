package com.telephoto.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.MediaController
import kotlin.math.min

/**
 * Samsung Gallery-inspired Filter Catalog + Custom User Filters:
 * - Exact 12 Samsung Gallery filters (Original, Auto, Warm, Cool, Light, Frosty,
 *   Blossom, Ivory, Soft, KissMe, Grayscale, B&W)
 * - Strength/Intensity scaling (0..100%) that composes non-destructively with manual Tone adjustments
 * - Fast real-photo square thumbnail preview renderer using Android ColorMatrix
 */
object PresetManager {

    private const val PREFS_NAME = "telephoto_presets_prefs"
    private const val KEY_CUSTOM_PRESETS = "custom_presets_json"

    const val ORIGINAL_ID = "samsung_original"

    data class FilterPreset(
        val id: String,
        val name: String,
        val isBuiltIn: Boolean,
        val accentColor: Int,
        val enhance: Float = 0f,
        val brightness: Float = 0f,
        val exposure: Float = 0f,
        val contrast: Float = 0f,
        val highlights: Float = 0f,
        val shadows: Float = 0f,
        val saturation: Float = 0f,
        val tint: Float = 0f,
        val warmth: Float = 0f,
        val sharpen: Float = 0f,
        val definition: Float = 0f,
        val fade: Float = 0f,
        val vignette: Float = 0f,
        val grain: Float = 0f,
        val softenSkin: Float = 0f,
        val tintShadowsColor: Int = 0,
        val tintHighlightsColor: Int = 0
    ) {
        val isOriginal: Boolean get() = id == ORIGINAL_ID

        fun applyTo(state: MediaController.SavedFilterState) {
            applyScaledWithManualTone(state, 100, null)
        }

        /**
         * Combines this filter scaled by [strengthPercent] (0..100) with optional [manualTone]
         * non-destructively into [target].
         */
        fun applyScaledWithManualTone(
            target: MediaController.SavedFilterState,
            strengthPercent: Int,
            manualTone: MediaController.SavedFilterState?
        ) {
            if (manualTone != null) {
                target.copyFrom(manualTone)
            } else {
                target.reset()
            }
            if (isOriginal || strengthPercent <= 0) return
            val s = (strengthPercent / 100f).coerceIn(0f, 1f)

            target.enhanceValue = (target.enhanceValue + enhance * s).coerceIn(0f, 100f)
            target.brightnessValue = (target.brightnessValue + brightness * s).coerceIn(-100f, 100f)
            target.exposureValue = (target.exposureValue + exposure * s).coerceIn(-100f, 100f)
            target.contrastValue = (target.contrastValue + contrast * s).coerceIn(-100f, 100f)
            target.highlightsValue = (target.highlightsValue + highlights * s).coerceIn(-100f, 100f)
            target.shadowsValue = (target.shadowsValue + shadows * s).coerceIn(-100f, 100f)
            target.saturationValue = (target.saturationValue + saturation * s).coerceIn(-100f, 100f)
            target.tintValue = (target.tintValue + tint * s).coerceIn(-100f, 100f)
            target.warmthValue = (target.warmthValue + warmth * s).coerceIn(-100f, 100f)
            target.sharpenValue = (target.sharpenValue + sharpen * s).coerceIn(0f, 100f)
            target.definitionValue = (target.definitionValue + definition * s).coerceIn(0f, 100f)
            target.fadeValue = (target.fadeValue + fade * s).coerceIn(0f, 100f)
            target.vignetteValue = (target.vignetteValue + vignette * s).coerceIn(0f, 100f)
            target.grainValue = (target.grainValue + grain * s).coerceIn(0f, 100f)
            target.softenSkinValue = (target.softenSkinValue + softenSkin * s).coerceIn(0f, 100f)

            if (target.tintShadowsColor == 0 && tintShadowsColor != 0 && s >= 0.35f) {
                target.tintShadowsColor = tintShadowsColor
            }
            if (target.tintHighlightsColor == 0 && tintHighlightsColor != 0 && s >= 0.35f) {
                target.tintHighlightsColor = tintHighlightsColor
            }
        }
    }

    /**
     * Exact Samsung Gallery Filters sequence observed in the user's screen recording:
     * Original, Auto, Warm, Cool, Light, Frosty, Blossom, Ivory, Soft, KissMe, Grayscale, B&W
     */
    val BUILT_IN_PRESETS: List<FilterPreset> = listOf(
        FilterPreset(
            id = ORIGINAL_ID,
            name = "Original",
            isBuiltIn = true,
            accentColor = 0xFF8E8E93.toInt()
        ),
        FilterPreset(
            id = "samsung_auto",
            name = "Auto",
            isBuiltIn = true,
            accentColor = 0xFFFFD60A.toInt(),
            enhance = 34f,
            contrast = 14f,
            highlights = -12f,
            shadows = 16f,
            saturation = 12f,
            definition = 18f
        ),
        FilterPreset(
            id = "samsung_warm",
            name = "Warm",
            isBuiltIn = true,
            accentColor = 0xFFFF9F0A.toInt(),
            brightness = 6f,
            contrast = 10f,
            saturation = 16f,
            warmth = 44f,
            tint = 8f
        ),
        FilterPreset(
            id = "samsung_cool",
            name = "Cool",
            isBuiltIn = true,
            accentColor = 0xFF5AC8FA.toInt(),
            contrast = 12f,
            highlights = -10f,
            saturation = -6f,
            warmth = -40f,
            tint = -8f
        ),
        FilterPreset(
            id = "samsung_light",
            name = "Light",
            isBuiltIn = true,
            accentColor = 0xFFFFF4D6.toInt(),
            brightness = 28f,
            exposure = 22f,
            contrast = -6f,
            highlights = -10f,
            shadows = 26f
        ),
        FilterPreset(
            id = "samsung_frosty",
            name = "Frosty",
            isBuiltIn = true,
            accentColor = 0xFF8BE4FF.toInt(),
            brightness = 14f,
            highlights = 14f,
            saturation = -22f,
            warmth = -32f,
            tint = -10f,
            fade = 26f
        ),
        FilterPreset(
            id = "samsung_blossom",
            name = "Blossom",
            isBuiltIn = true,
            accentColor = 0xFFFF9BB8.toInt(),
            brightness = 14f,
            saturation = 14f,
            warmth = 18f,
            tint = 30f,
            fade = 14f,
            softenSkin = 25f
        ),
        FilterPreset(
            id = "samsung_ivory",
            name = "Ivory",
            isBuiltIn = true,
            accentColor = 0xFFF2E6D0.toInt(),
            brightness = 16f,
            contrast = -10f,
            shadows = 16f,
            saturation = -16f,
            warmth = 24f,
            fade = 22f
        ),
        FilterPreset(
            id = "samsung_soft",
            name = "Soft",
            isBuiltIn = true,
            accentColor = 0xFFCDB4DB.toInt(),
            contrast = -18f,
            highlights = -16f,
            shadows = 22f,
            saturation = -10f,
            fade = 28f,
            softenSkin = 42f
        ),
        FilterPreset(
            id = "samsung_kissme",
            name = "KissMe",
            isBuiltIn = true,
            accentColor = 0xFFFF5E84.toInt(),
            brightness = 8f,
            contrast = 14f,
            saturation = 24f,
            warmth = 22f,
            tint = 34f,
            vignette = 18f
        ),
        FilterPreset(
            id = "samsung_grayscale",
            name = "Grayscale",
            isBuiltIn = true,
            accentColor = 0xFFAFAFB4.toInt(),
            contrast = 8f,
            shadows = 8f,
            saturation = -100f
        ),
        FilterPreset(
            id = "samsung_bw",
            name = "B&W",
            isBuiltIn = true,
            accentColor = 0xFFE5E5EA.toInt(),
            contrast = 44f,
            highlights = 14f,
            shadows = -18f,
            saturation = -100f,
            definition = 32f
        )
    )

    fun findById(context: Context?, id: String?): FilterPreset {
        if (id.isNullOrBlank()) return BUILT_IN_PRESETS[0]
        val list = if (context != null) getAllPresets(context) else BUILT_IN_PRESETS
        return list.firstOrNull { it.id == id } ?: BUILT_IN_PRESETS[0]
    }

    fun getAllPresets(context: Context): List<FilterPreset> {
        return BUILT_IN_PRESETS + getCustomPresets(context)
    }

    fun getCustomPresets(context: Context): List<FilterPreset> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_PRESETS, null) ?: return emptyList()
        return deserializePresets(raw)
    }

    fun saveCustomPreset(context: Context, name: String, state: MediaController.SavedFilterState): FilterPreset {
        val preset = FilterPreset(
            id = "custom_${System.currentTimeMillis()}",
            name = name.trim().take(28),
            isBuiltIn = false,
            accentColor = AppTheme.accent,
            enhance = state.enhanceValue,
            brightness = state.brightnessValue,
            exposure = state.exposureValue,
            contrast = state.contrastValue,
            highlights = state.highlightsValue,
            shadows = state.shadowsValue,
            saturation = state.saturationValue,
            tint = state.tintValue,
            warmth = state.warmthValue,
            sharpen = state.sharpenValue,
            definition = state.definitionValue,
            fade = state.fadeValue,
            vignette = state.vignetteValue,
            grain = state.grainValue,
            softenSkin = state.softenSkinValue,
            tintShadowsColor = state.tintShadowsColor,
            tintHighlightsColor = state.tintHighlightsColor
        )
        val list = getCustomPresets(context).toMutableList()
        list.add(0, preset)
        saveCustomList(context, list)
        return preset
    }

    fun deleteCustomPreset(context: Context, id: String) {
        val list = getCustomPresets(context).filterNot { it.id == id }
        saveCustomList(context, list)
    }

    /**
     * Creates a square center-cropped base thumbnail (~120x120) for ultra-fast filter preview cards.
     */
    fun createSquareThumbBase(source: Bitmap, sizePx: Int = 120): Bitmap {
        val sw = source.width.coerceAtLeast(1)
        val sh = source.height.coerceAtLeast(1)
        val crop = min(sw, sh)
        val left = (sw - crop) / 2
        val top = (sh - crop) / 2
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(
            source,
            Rect(left, top, left + crop, top + crop),
            Rect(0, 0, sizePx, sizePx),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return out
    }

    /**
     * Renders an accurate ColorMatrix preview thumbnail of [preset] on the user's actual photo thumbnail.
     */
    fun renderFilterThumbnail(squareBase: Bitmap, preset: FilterPreset): Bitmap {
        if (preset.isOriginal) {
            return squareBase.copy(Bitmap.Config.ARGB_8888, false)
        }
        val out = Bitmap.createBitmap(squareBase.width, squareBase.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cm = ColorMatrix()

        // 1) Saturation
        val sat = (1f + preset.saturation / 100f).coerceIn(0f, 2.2f)
        val satMatrix = ColorMatrix().apply { setSaturation(sat) }
        cm.postConcat(satMatrix)

        // 2) Contrast + Brightness + Exposure + Fade + Warmth + Tint
        val contrast = 1f + (preset.contrast + preset.definition * 0.25f) / 140f
        val offsetBase = (preset.brightness * 0.9f + preset.exposure * 0.8f + preset.shadows * 0.25f)
        val fadeLift = preset.fade * 0.45f
        val cShift = (1f - contrast) * 128f + offsetBase + fadeLift

        val w = preset.warmth / 100f
        val t = preset.tint / 100f
        val rScale = (contrast * (1f + w * 0.16f + t * 0.08f)).coerceIn(0.4f, 2.0f)
        val gScale = (contrast * (1f + w * 0.03f - t * 0.12f)).coerceIn(0.4f, 2.0f)
        val bScale = (contrast * (1f - w * 0.18f + t * 0.10f)).coerceIn(0.4f, 2.0f)

        val toneMatrix = ColorMatrix(
            floatArrayOf(
                rScale, 0f, 0f, 0f, cShift + w * 12f + t * 8f,
                0f, gScale, 0f, 0f, cShift - t * 8f,
                0f, 0f, bScale, 0f, cShift - w * 14f + t * 8f,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(toneMatrix)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }
        canvas.drawBitmap(squareBase, 0f, 0f, paint)
        return out
    }

    private fun saveCustomList(context: Context, list: List<FilterPreset>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_PRESETS, serializePresets(list)).apply()
    }

    internal fun serializePresets(list: List<FilterPreset>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("accent", p.accentColor)
                put("enhance", p.enhance.toDouble())
                put("brightness", p.brightness.toDouble())
                put("exposure", p.exposure.toDouble())
                put("contrast", p.contrast.toDouble())
                put("highlights", p.highlights.toDouble())
                put("shadows", p.shadows.toDouble())
                put("saturation", p.saturation.toDouble())
                put("tint", p.tint.toDouble())
                put("warmth", p.warmth.toDouble())
                put("sharpen", p.sharpen.toDouble())
                put("definition", p.definition.toDouble())
                put("fade", p.fade.toDouble())
                put("vignette", p.vignette.toDouble())
                put("grain", p.grain.toDouble())
                put("softenSkin", p.softenSkin.toDouble())
                put("tintShadows", p.tintShadowsColor)
                put("tintHighlights", p.tintHighlightsColor)
            })
        }
        return arr.toString()
    }

    internal fun deserializePresets(raw: String): List<FilterPreset> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<FilterPreset>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    FilterPreset(
                        id = o.optString("id", "custom_$i"),
                        name = o.optString("name", "Filter"),
                        isBuiltIn = false,
                        accentColor = o.optInt("accent", AppTheme.accent),
                        enhance = o.optDouble("enhance", 0.0).toFloat(),
                        brightness = o.optDouble("brightness", 0.0).toFloat(),
                        exposure = o.optDouble("exposure", 0.0).toFloat(),
                        contrast = o.optDouble("contrast", 0.0).toFloat(),
                        highlights = o.optDouble("highlights", 0.0).toFloat(),
                        shadows = o.optDouble("shadows", 0.0).toFloat(),
                        saturation = o.optDouble("saturation", 0.0).toFloat(),
                        tint = o.optDouble("tint", 0.0).toFloat(),
                        warmth = o.optDouble("warmth", 0.0).toFloat(),
                        sharpen = o.optDouble("sharpen", 0.0).toFloat(),
                        definition = o.optDouble("definition", 0.0).toFloat(),
                        fade = o.optDouble("fade", 0.0).toFloat(),
                        vignette = o.optDouble("vignette", 0.0).toFloat(),
                        grain = o.optDouble("grain", 0.0).toFloat(),
                        softenSkin = o.optDouble("softenSkin", 0.0).toFloat(),
                        tintShadowsColor = o.optInt("tintShadows", 0),
                        tintHighlightsColor = o.optInt("tintHighlights", 0)
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }
}
