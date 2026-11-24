# Upstream Attribution & License Compliance

This repository (**TelePhoto-Editor**) extracts and adapts the standalone photo editing engine directly from the official open-source **Telegram for Android** codebase (`DrKLO/Telegram`), licensed under the **GNU General Public License v2.0** (see [`LICENSE`](./LICENSE)).

## Upstream Reference

- **Repository:** https://github.com/DrKLO/Telegram
- **Upstream Version:** Telegram for Android `13.2.2 (7303)`
- **Upstream Commit SHA:** `21f7d109da6ab3506c9e9577f98c6a38771ab335`
- **Upstream Commit Date:** `2026-11-12T02:43:03Z`

## Extracted Components

1. **OpenGL ES 2.0 Photo Filter & Color Grading Engine** (`org.telegram.ui.Components`):
   - `FilterShaders.java` (Auto-Enhance, Exposure, Contrast, Saturation, Warmth, Fade, Tint Shadows/Highlights, Highlights, Shadows, Vignette, Grain, Sharpen, RGB/Luma Curves, Radial/Linear Blur, Soften Skin)
   - `FilterGLThread.java` (Dedicated EGL/OpenGL ES 2.0 rendering thread & bitmap export)
   - `PhotoEditorSeekBar.java`, `PhotoFilterBlurControl.java`, `PhotoFilterCurvesControl.java`
2. **Crop, Free-Rotate & Perspective Transform Engine** (`org.telegram.ui.Components.Crop`):
   - `CropView.java`, `CropAreaView.java`, `CropGestureDetector.java`, `CropRotationWheel.java`, `CropState.java`, `CropTransform.java`
3. **GPU-Accelerated Paint, Brush, Shape Detection & Undo Engine** (`org.telegram.ui.Components.Paint`):
   - `Painting.java`, `RenderView.java`, `Render.java`, `RenderState.java`, `Shader.java`, `ShaderSet.java`, `Texture.java`, `Brush.java`, `GLMatrix.java`, `Input.java`, `ShapeInput.java`, `Shape.java`, `ShapeDetector.java`, `Path.java`, `Point.java`, `Slice.java`, `Swatch.java`, `UndoStore.java`, `Utils.java`, plus `assets/shapes.dat` and brush textures
4. **Native Image Processing Functions** (`app/src/main/cpp/image_native.cpp`):
   - `Java_org_telegram_messenger_Utilities_calcCDT` and `Java_org_telegram_messenger_Utilities_stackBlurBitmap` extracted from `TMessagesProj/jni/image.cpp`
5. **Core Utility Primitives**:
   - `DispatchQueue.java`, `CubicBezierInterpolator.java`, `Size.java`
