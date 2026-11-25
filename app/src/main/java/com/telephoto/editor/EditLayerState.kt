package com.telephoto.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.telegram.messenger.MediaController
import java.io.ByteArrayOutputStream

/**
 * Non-destructive 3-layer document model for Minab Image Editor:
 * - Layer 1: Base / Cropped Photo Bitmap
 * - Layer 2: FilterRecipe (Samsung Filter ID + Strength 0..100 + Manual Tone/Curves/Blur)
 * - Layer 3: PaintLayerState (Transparent GPU strokes/arrows/shapes PNG + per-stroke Undo PNG stack
 *            + live editable TextEntitySnapshots + cached transparent Text PNG)
 *
 * Layers stay completely editable and independent across tool switches until final Save/Export!
 */
object EditLayerState {

    const val MAX_STROKE_UNDO_SNAPSHOTS = 20

    data class FilterRecipe(
        val selectedFilterId: String = PresetManager.ORIGINAL_ID,
        val filterStrength: Int = 100,
        val manualTone: MediaController.SavedFilterState = MediaController.SavedFilterState()
    ) {
        fun isEmpty(): Boolean {
            return (selectedFilterId == PresetManager.ORIGINAL_ID || filterStrength <= 0) &&
                manualTone.isEmpty()
        }

        fun deepCopy(): FilterRecipe {
            val toneCopy = MediaController.SavedFilterState()
            toneCopy.copyFrom(manualTone)
            return FilterRecipe(
                selectedFilterId = selectedFilterId,
                filterStrength = filterStrength.coerceIn(0, 100),
                manualTone = toneCopy
            )
        }

        fun computeEffectiveState(context: Context?): MediaController.SavedFilterState {
            val out = MediaController.SavedFilterState()
            val preset = PresetManager.findById(context, selectedFilterId)
            preset.applyScaledWithManualTone(out, filterStrength, manualTone)
            return out
        }
    }

    data class TextEntitySnapshot(
        val text: String,
        val color: Int,
        val style: TextOverlayContainer.TextStyle,
        val font: TextOverlayContainer.FontChoice,
        val alignment: Int,
        val baseTextSizeSp: Float,
        val normOffsetX: Float,
        val normOffsetY: Float,
        val scale: Float,
        val rotation: Float
    )

    data class PaintLayerState(
        val strokeLayerPng: ByteArray? = null,
        val strokeUndoPngStack: List<ByteArray> = emptyList(),
        val textEntities: List<TextEntitySnapshot> = emptyList(),
        val textLayerPng: ByteArray? = null
    ) {
        val hasStrokes: Boolean get() = strokeLayerPng != null && strokeLayerPng.isNotEmpty()
        val hasText: Boolean get() = textEntities.isNotEmpty()
        val hasContent: Boolean get() = hasStrokes || hasText

        fun shallowCopy(): PaintLayerState {
            return PaintLayerState(
                strokeLayerPng = strokeLayerPng,
                strokeUndoPngStack = strokeUndoPngStack.toList(),
                textEntities = textEntities.toList(),
                textLayerPng = textLayerPng
            )
        }
    }

    /**
     * Checks if [bitmap] contains any visible non-transparent pixel.
     */
    fun hasAnyVisiblePixel(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val row = IntArray(w)
        // Sample every 2nd row first for speed, then verify remaining rows if empty
        for (y in 0 until h step 2) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (p in row) {
                if ((p ushr 24) > 2) return true
            }
        }
        for (y in 1 until h step 2) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            for (p in row) {
                if ((p ushr 24) > 2) return true
            }
        }
        return false
    }

    /**
     * Losslessly encodes a transparent RGBA layer to compact PNG bytes,
     * returning null if the bitmap is completely transparent.
     */
    fun encodeTransparentLayerOrNull(bitmap: Bitmap?): ByteArray? {
        if (bitmap == null || bitmap.isRecycled) return null
        if (!hasAnyVisiblePixel(bitmap)) return null
        return encodePngBytes(bitmap)
    }

    fun encodePngBytes(bitmap: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }

    fun decodeMutableArgb(pngBytes: ByteArray?): Bitmap? {
        if (pngBytes == null || pngBytes.isEmpty()) return null
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val decoded = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts) ?: return null
            if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Composites Layer 3 (strokes + text) onto a transparent overlay bitmap matching [width] x [height],
     * ideal for floating over the live OpenGL filter preview in TelegramFilterPanel.
     */
    fun buildCombinedOverlayBitmap(
        paintState: PaintLayerState?,
        width: Int,
        height: Int
    ): Bitmap? {
        if (paintState == null || !paintState.hasContent || width <= 0 || height <= 0) return null
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val dst = Rect(0, 0, width, height)
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        decodeMutableArgb(paintState.strokeLayerPng)?.let { strokeBmp ->
            canvas.drawBitmap(strokeBmp, null, dst, drawPaint)
            strokeBmp.recycle()
        }
        decodeMutableArgb(paintState.textLayerPng)?.let { textBmp ->
            canvas.drawBitmap(textBmp, null, dst, drawPaint)
            textBmp.recycle()
        }
        return out
    }

    /**
     * Composites Layer 2 ([filteredBase]) + Layer 3 ([paintState]) non-destructively into a new Bitmap.
     * Leaves [filteredBase] completely untouched!
     */
    fun compositePhotoWithPaintLayer(
        filteredBase: Bitmap,
        paintState: PaintLayerState?
    ): Bitmap {
        if (paintState == null || !paintState.hasContent) {
            return filteredBase
        }
        val out = filteredBase.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val dst = Rect(0, 0, out.width, out.height)
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        decodeMutableArgb(paintState.strokeLayerPng)?.let { strokeBmp ->
            canvas.drawBitmap(strokeBmp, null, dst, drawPaint)
            strokeBmp.recycle()
        }
        decodeMutableArgb(paintState.textLayerPng)?.let { textBmp ->
            canvas.drawBitmap(textBmp, null, dst, drawPaint)
            textBmp.recycle()
        }
        return out
    }
}
