package com.telephoto.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.MediaController
import org.telegram.messenger.Utilities
import org.telegram.ui.Components.Crop.CropTransform
import org.telegram.ui.Components.FilterShaders
import org.telegram.ui.Components.Paint.UndoStore
import org.telegram.ui.Components.PhotoFilterView
import java.nio.ByteBuffer
import java.util.UUID

class TelegramEngineUnitTest {

    @Test
    fun curvesToolValue_defaultsAndInterpolationAreValid() {
        val curvesTool = PhotoFilterView.CurvesToolValue()
        assertTrue("Fresh CurvesToolValue should be skipped", curvesTool.shouldBeSkipped())

        curvesTool.luminanceCurve.midtonesLevel = 68f
        assertFalse("Modified curve must not be skipped", curvesTool.shouldBeSkipped())

        val points = curvesTool.luminanceCurve.interpolateCurve()
        assertTrue("Interpolated curve must contain spline points", points.size > 200)

        curvesTool.fillBuffer()
        assertEquals(200 * 4, curvesTool.curveBuffer.capacity())
        assertEquals(0, curvesTool.curveBuffer.position())

        curvesTool.reset()
        assertTrue("Reset CurvesToolValue should return to default", curvesTool.shouldBeSkipped())
    }

    @Test
    fun filterShadersDelegate_mapsSavedFilterStateAccurately() {
        val state = MediaController.SavedFilterState()
        assertTrue(state.isEmpty())

        state.enhanceValue = 50f
        state.exposureValue = 40f
        state.contrastValue = 50f
        state.saturationValue = 20f
        state.sharpenValue = 50f
        state.grainValue = 100f
        state.vignetteValue = 60f
        state.fadeValue = 30f
        state.highlightsValue = -20f
        state.shadowsValue = 40f
        state.tintHighlightsColor = 0xFFFF0000.toInt()
        assertFalse(state.isEmpty())

        val delegate = FilterShaders.getFilterShadersDelegate(state)
        assertEquals(0.50f, delegate.enhanceValue, 0.001f)
        assertEquals(0.40f, delegate.exposureValue, 0.001f)
        assertEquals(1.15f, delegate.contrastValue, 0.001f)
        assertEquals(1.21f, delegate.saturationValue, 0.001f)
        assertEquals(0.41f, delegate.sharpenValue, 0.001f)
        assertEquals(0.04f, delegate.grainValue, 0.001f)
        assertEquals(0.60f, delegate.vignetteValue, 0.001f)
        assertEquals(0.30f, delegate.fadeValue, 0.001f)
        assertEquals(0.50f, delegate.tintHighlightsIntensityValue, 0.001f)
        assertEquals(0.00f, delegate.tintShadowsIntensityValue, 0.001f)

        state.reset()
        assertTrue(state.isEmpty())
    }

    @Test
    fun calcCDT_producesValidHistogramEqualizationLut() {
        val width = 16
        val height = 16
        val hsv = ByteBuffer.allocateDirect(width * height * 4)
        for (i in 0 until width * height) {
            hsv.put(0)
            hsv.put(0)
            hsv.put(((i * 255) / (width * height - 1)).toByte())
            hsv.put(255.toByte())
        }
        hsv.position(0)

        val outBuf = ByteBuffer.allocateDirect(16 * 256 * 4)
        val calcBuf = ByteBuffer.allocateDirect(4 * (16 + 16 + 16 * 256 + 16 * 256))

        Utilities.calcCDT(hsv, width, height, outBuf, calcBuf)

        // Verify alpha channel is 255 and CDF max >= CDF min for every tile
        for (tile in 0 until 16) {
            val base = tile * 256 * 4
            val cdfMin = outBuf.get(base + 1).toInt() and 0xFF
            val cdfMax = outBuf.get(base + 2).toInt() and 0xFF
            val alpha = outBuf.get(base + 3).toInt() and 0xFF
            assertEquals(255, alpha)
            assertTrue("cdfMax ($cdfMax) should be >= cdfMin ($cdfMin)", cdfMax >= cdfMin)
        }
    }

    @Test
    fun undoStore_executesRegisteredOperationsInLifoOrder() {
        val store = UndoStore()
        assertFalse(store.canUndo())

        val events = mutableListOf<String>()
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        store.registerUndo(id1) { events.add("first") }
        store.registerUndo(id2) { events.add("second") }

        assertTrue(store.canUndo())
        store.undo()
        assertEquals(listOf("second"), events)
        assertTrue(store.canUndo())

        store.undo()
        assertEquals(listOf("second", "first"), events)
        assertFalse(store.canUndo())
    }

    @Test
    fun cropTransformAndCropState_preserveFields() {
        val transform = CropTransform()
        transform.setViewTransform(
            true, 0.1f, -0.2f, 15f, 90, 1.5f, 1.2f, 0.8f, 0.9f, 0.85f, 10f, 20f, true
        )
        assertTrue(transform.hasViewTransform())
        assertEquals(15f, transform.cropAreaRotation, 0.001f)
        assertEquals(90, transform.orientation)
        assertTrue(transform.isMirrored)

        val state = MediaController.CropState().apply {
            cropScale = 1.4f
            cropRotate = -12.5f
            transformRotation = 90
            mirrored = true
            width = 1080
            height = 1080
            initied = true
        }
        val cloned = state.clone()
        assertNotNull(cloned)
        assertEquals(state.cropScale, cloned.cropScale, 0.001f)
        assertEquals(state.cropRotate, cloned.cropRotate, 0.001f)
        assertEquals(state.transformRotation, cloned.transformRotation)
        assertEquals(state.mirrored, cloned.mirrored)
        assertEquals(state.width, cloned.width)
    }
}
