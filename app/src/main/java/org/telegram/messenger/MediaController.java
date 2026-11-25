package org.telegram.messenger;

import android.graphics.Matrix;
import android.graphics.PointF;

import org.telegram.ui.Components.PhotoFilterView;

import java.util.ArrayList;

public class MediaController {

    public static class SavedFilterState {
        public float enhanceValue;
        public float brightnessValue;
        public float softenSkinValue;
        public float exposureValue;
        public float contrastValue;
        public float warmthValue;
        public float tintValue;
        public float saturationValue;
        public float fadeValue;
        public int tintShadowsColor;
        public int tintHighlightsColor;
        public float highlightsValue;
        public float shadowsValue;
        public float vignetteValue;
        public float grainValue;
        public int blurType;
        public float sharpenValue;
        public float definitionValue;
        public PhotoFilterView.CurvesToolValue curvesToolValue = new PhotoFilterView.CurvesToolValue();
        public float blurExcludeSize = 0.35f;
        public PointF blurExcludePoint = new PointF(0.5f, 0.5f);
        public float blurExcludeBlurSize = 0.15f;
        public float blurAngle = (float) Math.PI / 2.0f;

        public boolean isEmpty() {
            return enhanceValue == 0
                    && brightnessValue == 0
                    && softenSkinValue == 0
                    && exposureValue == 0
                    && contrastValue == 0
                    && warmthValue == 0
                    && tintValue == 0
                    && saturationValue == 0
                    && fadeValue == 0
                    && tintShadowsColor == 0
                    && tintHighlightsColor == 0
                    && highlightsValue == 0
                    && shadowsValue == 0
                    && vignetteValue == 0
                    && grainValue == 0
                    && blurType == 0
                    && sharpenValue == 0
                    && definitionValue == 0
                    && (curvesToolValue == null || curvesToolValue.shouldBeSkipped());
        }

        public void reset() {
            enhanceValue = 0f;
            brightnessValue = 0f;
            softenSkinValue = 0f;
            exposureValue = 0f;
            contrastValue = 0f;
            warmthValue = 0f;
            tintValue = 0f;
            saturationValue = 0f;
            fadeValue = 0f;
            tintShadowsColor = 0;
            tintHighlightsColor = 0;
            highlightsValue = 0f;
            shadowsValue = 0f;
            vignetteValue = 0f;
            grainValue = 0f;
            blurType = 0;
            sharpenValue = 0f;
            definitionValue = 0f;
            if (curvesToolValue == null) {
                curvesToolValue = new PhotoFilterView.CurvesToolValue();
            } else {
                curvesToolValue.reset();
            }
            blurExcludeSize = 0.35f;
            if (blurExcludePoint == null) {
                blurExcludePoint = new PointF(0.5f, 0.5f);
            } else {
                blurExcludePoint.set(0.5f, 0.5f);
            }
            blurExcludeBlurSize = 0.15f;
            blurAngle = (float) Math.PI / 2.0f;
        }

        public void copyFrom(SavedFilterState other) {
            if (other == null) {
                reset();
                return;
            }
            enhanceValue = other.enhanceValue;
            brightnessValue = other.brightnessValue;
            softenSkinValue = other.softenSkinValue;
            exposureValue = other.exposureValue;
            contrastValue = other.contrastValue;
            warmthValue = other.warmthValue;
            tintValue = other.tintValue;
            saturationValue = other.saturationValue;
            fadeValue = other.fadeValue;
            tintShadowsColor = other.tintShadowsColor;
            tintHighlightsColor = other.tintHighlightsColor;
            highlightsValue = other.highlightsValue;
            shadowsValue = other.shadowsValue;
            vignetteValue = other.vignetteValue;
            grainValue = other.grainValue;
            blurType = other.blurType;
            sharpenValue = other.sharpenValue;
            definitionValue = other.definitionValue;
            blurExcludeSize = other.blurExcludeSize;
            blurExcludeBlurSize = other.blurExcludeBlurSize;
            blurAngle = other.blurAngle;
            if (blurExcludePoint == null) {
                blurExcludePoint = new PointF(0.5f, 0.5f);
            }
            if (other.blurExcludePoint != null) {
                blurExcludePoint.set(other.blurExcludePoint.x, other.blurExcludePoint.y);
            } else {
                blurExcludePoint.set(0.5f, 0.5f);
            }
            if (curvesToolValue == null) {
                curvesToolValue = new PhotoFilterView.CurvesToolValue();
            }
            if (other.curvesToolValue != null) {
                curvesToolValue.activeType = other.curvesToolValue.activeType;
                copyCurve(curvesToolValue.luminanceCurve, other.curvesToolValue.luminanceCurve);
                copyCurve(curvesToolValue.redCurve, other.curvesToolValue.redCurve);
                copyCurve(curvesToolValue.greenCurve, other.curvesToolValue.greenCurve);
                copyCurve(curvesToolValue.blueCurve, other.curvesToolValue.blueCurve);
            } else {
                curvesToolValue.reset();
            }
        }

        private static void copyCurve(PhotoFilterView.CurvesValue dst, PhotoFilterView.CurvesValue src) {
            if (dst == null || src == null) return;
            dst.blacksLevel = src.blacksLevel;
            dst.shadowsLevel = src.shadowsLevel;
            dst.midtonesLevel = src.midtonesLevel;
            dst.highlightsLevel = src.highlightsLevel;
            dst.whitesLevel = src.whitesLevel;
        }
    }

    public static class CropState {
        public float cropPx;
        public float cropPy;
        public float cropScale = 1f;
        public float cropRotate;
        public float cropPw = 1f;
        public float cropPh = 1f;
        public int transformWidth;
        public int transformHeight;
        public int transformRotation;
        public boolean mirrored;

        public float stateScale;
        public float scale;
        public Matrix matrix;
        public int width;
        public int height;
        public boolean freeform;
        public float lockedAspectRatio;
        public boolean initied;

        public CropState clone() {
            CropState state = new CropState();
            state.cropPx = cropPx;
            state.cropPy = cropPy;
            state.cropScale = cropScale;
            state.cropRotate = cropRotate;
            state.cropPw = cropPw;
            state.cropPh = cropPh;
            state.transformWidth = transformWidth;
            state.transformHeight = transformHeight;
            state.transformRotation = transformRotation;
            state.mirrored = mirrored;
            state.stateScale = stateScale;
            state.scale = scale;
            state.matrix = matrix != null ? new Matrix(matrix) : null;
            state.width = width;
            state.height = height;
            state.freeform = freeform;
            state.lockedAspectRatio = lockedAspectRatio;
            state.initied = initied;
            return state;
        }
    }

    public static class MediaEditState {
        public String paintPath;
        public String croppedPaintPath;
        public CropState cropState;
        public SavedFilterState filterState;
        public ArrayList<VideoEditedInfo.MediaEntity> mediaEntities;
        public ArrayList<VideoEditedInfo.MediaEntity> croppedMediaEntities;
    }
}
