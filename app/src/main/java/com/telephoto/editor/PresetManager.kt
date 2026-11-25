package com.telephoto.editor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.MediaController

/**
 * Manages built-in photographic presets and user-saved custom Tone/Filter presets.
 */
object PresetManager {

    private const val PREFS_NAME = "telephoto_presets_prefs"
    private const val KEY_CUSTOM_PRESETS = "custom_presets_json"

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
        fun applyTo(state: MediaController.SavedFilterState) {
            state.reset()
            state.enhanceValue = enhance
            state.brightnessValue = brightness
            state.exposureValue = exposure
            state.contrastValue = contrast
            state.highlightsValue = highlights
            state.shadowsValue = shadows
            state.saturationValue = saturation
            state.tintValue = tint
            state.warmthValue = warmth
            state.sharpenValue = sharpen
            state.definitionValue = definition
            state.fadeValue = fade
            state.vignetteValue = vignette
            state.grainValue = grain
            state.softenSkinValue = softenSkin
            state.tintShadowsColor = tintShadowsColor
            state.tintHighlightsColor = tintHighlightsColor
        }
    }

    val BUILT_IN_PRESETS: List<FilterPreset> = listOf(
        FilterPreset(
            id = "builtin_original",
            name = "Original",
            isBuiltIn = true,
            accentColor = 0xFF8E8E93.toInt()
        ),
        FilterPreset(
            id = "builtin_vivid",
            name = "Vivid",
            isBuiltIn = true,
            accentColor = 0xFFFF9F0A.toInt(),
            enhance = 28f,
            contrast = 20f,
            saturation = 32f,
            definition = 25f
        ),
        FilterPreset(
            id = "builtin_cinematic",
            name = "Cinematic",
            isBuiltIn = true,
            accentColor = 0xFF0A84FF.toInt(),
            contrast = 26f,
            highlights = -25f,
            shadows = 18f,
            saturation = -14f,
            warmth = 16f,
            fade = 18f,
            vignette = 32f,
            tintShadowsColor = 0xFF0072BC.toInt()
        ),
        FilterPreset(
            id = "builtin_warm_sunset",
            name = "Warm Sunset",
            isBuiltIn = true,
            accentColor = 0xFFFF6B4A.toInt(),
            brightness = 8f,
            contrast = 14f,
            saturation = 22f,
            warmth = 42f,
            tint = 12f,
            vignette = 18f
        ),
        FilterPreset(
            id = "builtin_cool_matte",
            name = "Cool Matte",
            isBuiltIn = true,
            accentColor = 0xFF64D2FF.toInt(),
            contrast = -10f,
            highlights = -18f,
            shadows = 24f,
            warmth = -28f,
            fade = 32f
        ),
        FilterPreset(
            id = "builtin_bw_classic",
            name = "B&W Classic",
            isBuiltIn = true,
            accentColor = 0xFFD1D1D6.toInt(),
            contrast = 34f,
            saturation = -100f,
            definition = 30f,
            grain = 22f,
            vignette = 24f
        ),
        FilterPreset(
            id = "builtin_portrait",
            name = "Portrait Glow",
            isBuiltIn = true,
            accentColor = 0xFFFF85A2.toInt(),
            brightness = 10f,
            highlights = -15f,
            shadows = 20f,
            warmth = 12f,
            softenSkin = 42f,
            fade = 8f
        )
    )

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
                        name = o.optString("name", "Preset"),
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
