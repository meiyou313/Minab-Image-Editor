package com.telephoto.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.telegram.messenger.MediaController
import org.telegram.messenger.Utilities
import org.telegram.ui.Components.Crop.CropTransform
import org.telegram.ui.Components.FilterShaders
import org.telegram.ui.Components.Paint.Brush
import org.telegram.ui.Components.Paint.PersistColorPalette
import org.telegram.ui.Components.Paint.Shader
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
        assertEquals(15f, transform.rotation, 0.001f)
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

    @Test
    fun savedFilterStateReset_preservesCurvesReferenceInPlace() {
        val state = MediaController.SavedFilterState()
        val originalCurvesRef = state.curvesToolValue
        val originalBlurPointRef = state.blurExcludePoint

        state.curvesToolValue.redCurve.midtonesLevel = 72f
        state.blurType = 2
        state.blurExcludePoint.set(0.2f, 0.8f)
        assertFalse(state.isEmpty())

        state.reset()
        assertTrue(state.isEmpty())
        assertSame("reset() must keep CurvesToolValue reference for PhotoFilterCurvesControl", originalCurvesRef, state.curvesToolValue)
        assertSame("reset() must keep blurExcludePoint reference", originalBlurPointRef, state.blurExcludePoint)
        assertTrue(state.curvesToolValue.shouldBeSkipped())
    }

    @Test
    fun telegramBrushesAndFillPalette_areConfiguredProperly() {
        assertEquals(6, Brush.BRUSHES_LIST.size)
        for (brush in Brush.BRUSHES_LIST) {
            assertTrue("Brush default weight should be in (0, 1]", brush.defaultWeight in 0.01f..1.0f)
            assertTrue("Brush scale should be positive", brush.scale > 0f)
        }
        val palette = PersistColorPalette.getInstance(0)
        palette.fillShapes = false
        assertFalse(palette.fillShapes)
        palette.fillShapes = true
        assertTrue(palette.fillShapes)
        palette.fillShapes = false
    }

    @Test
    fun updateChecker_comparesSemanticVersionsCorrectly() {
        assertTrue(UpdateChecker.isNewerVersion("v1.2.1", "1.2.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.3.0", "1.2.9"))
        assertTrue(UpdateChecker.isNewerVersion("v2.0.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewerVersion("v1.2.0", "1.2.0"))
        assertFalse(UpdateChecker.isNewerVersion("v1.1.9", "1.2.0"))
        assertFalse(UpdateChecker.isNewerVersion("v1.1.1", "1.2.0"))
        assertEquals(listOf(1, 2, 0), UpdateChecker.parseSemVer("v1.2.0-beta1"))
    }

    @Test
    fun crashLogManager_formatsReadableCopyableReportAndBreadcrumbs() {
        CrashLogManager.addBreadcrumb("Opened Paint mode")
        CrashLogManager.addBreadcrumb("Selected brush: Arrow")
        val crumbs = CrashLogManager.getRecentBreadcrumbs()
        assertTrue(crumbs.any { it.contains("Opened Paint mode") })
        assertTrue(crumbs.any { it.contains("Selected brush: Arrow") })

        val sample = CrashLogManager.DebugEntry(
            id = "abc12345",
            timestampMs = 1700000000000L,
            timestampText = "2026-11-25 14:00:00",
            level = CrashLogManager.LEVEL_CRASH,
            threadName = "CanvasInternal",
            tag = "UncaughtException",
            title = "NullPointerException: sample",
            stackTrace = "java.lang.NullPointerException: sample\n\tat org.telegram.Test.run(Test.java:1)",
            breadcrumbs = listOf("[14:00:00.000] Selected brush: Arrow"),
            appVersion = "v1.2.0 (5)",
            deviceSummary = "TestPhone"
        )
        val formatted = CrashLogManager.formatEntryForCopy(sample)
        assertTrue(formatted.contains("[CRASH] NullPointerException: sample"))
        assertTrue(formatted.contains("CanvasInternal"))
        assertTrue(formatted.contains("Selected brush: Arrow"))
    }

    @Test
    fun paintShader_missingUniformReturnsMinusOneInsteadOfNpe() {
        val shader = Shader("", "", arrayOf("inPosition"), arrayOf("mvpMatrix"))
        assertEquals(-1, shader.getUniform("mvpMatrix"))
        assertEquals(-1, shader.getUniform("unknownUniform"))
    }

    @Test
    fun appLocale_has20LanguagesOrderedWithRtlAtBottom() {
        assertEquals(20, AppLocale.SUPPORTED_LANGUAGES.size)
        assertEquals("en", AppLocale.SUPPORTED_LANGUAGES.first().code)
        assertEquals("en", AppLocale.currentCode)
        assertEquals("Minab Image Editor", AppLocale.t("app_name"))
        assertEquals("Pick Photo from Gallery", AppLocale.t("pick_photo"))

        // Verify all RTL languages (ar, ur, fa) are placed strictly at the end of the list
        val firstRtlIdx = AppLocale.SUPPORTED_LANGUAGES.indexOfFirst { it.isRtl }
        assertEquals("RTL languages must begin at index 17 (last 3 entries)", 17, firstRtlIdx)
        for (i in 0 until firstRtlIdx) {
            assertFalse("Entry $i must be LTR", AppLocale.SUPPORTED_LANGUAGES[i].isRtl)
        }
        for (i in firstRtlIdx until AppLocale.SUPPORTED_LANGUAGES.size) {
            assertTrue("Entry $i must be RTL", AppLocale.SUPPORTED_LANGUAGES[i].isRtl)
        }
        assertEquals(listOf("ar", "ur", "fa"), AppLocale.SUPPORTED_LANGUAGES.drop(firstRtlIdx).map { it.code })
    }

    @Test
    fun samsungFiltersAndNonDestructiveRecipe_scaleAndComposeAccurately() {
        val expectedNames = listOf(
            "Original", "Auto", "Warm", "Cool", "Light", "Frosty",
            "Blossom", "Ivory", "Soft", "KissMe", "Grayscale", "B&W"
        )
        assertEquals(expectedNames, PresetManager.BUILT_IN_PRESETS.map { it.name })

        val src = MediaController.SavedFilterState().apply {
            brightnessValue = 50f
            tintValue = -40f
            definitionValue = 60f
            contrastValue = 20f
        }
        assertFalse(src.isEmpty())
        val delegate = FilterShaders.getFilterShadersDelegate(src)
        assertEquals(0.175f, delegate.brightnessValue, 0.001f)
        assertEquals(-0.40f, delegate.tintValue, 0.001f)
        assertTrue("Definition should enhance sharpness", delegate.sharpenValue > 0.30f)

        val dst = MediaController.SavedFilterState()
        dst.copyFrom(src)
        assertEquals(50f, dst.brightnessValue, 0.001f)
        assertEquals(-40f, dst.tintValue, 0.001f)
        assertEquals(60f, dst.definitionValue, 0.001f)
        assertEquals(20f, dst.contrastValue, 0.001f)

        // Verify Samsung Warm filter at 50% strength combines cleanly with manual Contrast +20
        val manualTone = MediaController.SavedFilterState().apply { contrastValue = 20f }
        val recipe = EditLayerState.FilterRecipe(
            selectedFilterId = "samsung_warm",
            filterStrength = 50,
            manualTone = manualTone
        )
        assertFalse(recipe.isEmpty())
        val effective = recipe.computeEffectiveState(null)
        assertEquals(22f, effective.warmthValue, 0.01f) // 44 * 0.5
        assertEquals(25f, effective.contrastValue, 0.01f) // 20 manual + 10 * 0.5

        // Verify UpdateChecker points to renamed Minab-Image-Editor repository
        assertTrue(UpdateChecker.LATEST_RELEASE_API.contains("meiyou313/Minab-Image-Editor"))
    }

    @Test
    fun crashLogManager_formatsSelectedSubsetReportCleanly() {
        val e1 = CrashLogManager.DebugEntry(
            id = "e1", timestampMs = 1L, timestampText = "2026-11-25 14:30:00",
            level = CrashLogManager.LEVEL_ERROR, threadName = "main", tag = "Filter",
            title = "Error One", stackTrace = "trace1", breadcrumbs = emptyList(),
            appVersion = "v1.4.0 (7)", deviceSummary = "Test"
        )
        val report = CrashLogManager.formatEntriesReport(null, listOf(e1), "Selected 1")
        assertTrue(report.contains("Minab Image Editor"))
        assertTrue(report.contains("Selected 1"))
        assertTrue(report.contains("Error One"))
        assertTrue(report.contains("Entries   : 1"))
    }
}
