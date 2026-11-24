# TelePhoto Editor

A lightweight, standalone Android photo editor powered **directly by the real open-source photo editing engines extracted from [Telegram for Android (`DrKLO/Telegram`)](https://github.com/DrKLO/Telegram)** — with zero third-party wrapper photo-editor libraries and zero messaging/chat/network bloat.

## Extracted Telegram Engines

All core editing pipelines come straight from Telegram for Android (`13.2.2`, commit `21f7d109da6ab3506c9e9577f98c6a38771ab335`):

1. **OpenGL ES 2.0 Photo Filters, Curves & Tilt-Shift Blur**
   - `FilterShaders.java` & `FilterGLThread.java`
   - Real native C++ CLAHE/CDT Auto-Enhance (`calcCDT` extracted from `TMessagesProj/jni/image.cpp`)
   - Full parameter suite: **Enhance, Exposure, Contrast, Saturation, Warmth, Fade, Highlights, Shadows, Vignette, Grain, Sharpen, Soften Skin, Tint Shadows, Tint Highlights**
   - **RGB / Luminance Curves** (`PhotoFilterCurvesControl.java`, Catmull-Rom spline interpolation)
   - **Radial & Linear Tilt-Shift Blur** (`PhotoFilterBlurControl.java`)
   - Press-and-hold preview to compare with original
2. **Crop, Free-Rotate & Aspect Ratio Engine**
   - `CropView.java`, `CropAreaView.java`, `CropRotationWheel.java`, `CropGestureDetector.java`, `CropState.java`, `CropTransform.java`
   - Free rotation wheel (-45° to +45°), 90° rotate, horizontal mirror/flip, and aspect-ratio lock presets
3. **GPU-Accelerated Paint, Brushes, Shapes & Text**
   - `Painting.java`, `RenderView.java`, `ShaderSet.java`, `Brush.java`, `ShapeDetector.java`, `UndoStore.java` + original Telegram `.webp` brush stamp textures & `shapes.dat`
   - Real Telegram GPU brushes: **Pen (Radial), Arrow, Marker (Elliptical), Neon, Blur** (backed by native C++ `stackBlurBitmap` from `image.cpp`), **Eraser**, plus **Circle / Rectangle / Star** shape tools
   - Interactive draggable / scalable / rotatable **Text overlays** (Outlined, Filled, Translucent styles)

## Build & Download APK

A ready-to-use GitHub Actions workflow is included at [`docs/github-workflow-android-build.yml`](docs/github-workflow-android-build.yml) (JDK 17, Android SDK 35, NDK 27 + CMake 3.22, JUnit tests, and `assembleDebug`, uploading `TelePhoto-Editor-debug-apk` as a build artifact).

To enable automatic cloud APK builds on GitHub Actions:
1. Grant workflow push scope (`gh auth refresh -s workflow`) and move `docs/github-workflow-android-build.yml` to `.github/workflows/android-build.yml`, **or**
2. In the GitHub web UI, open **Actions → set up a workflow yourself** and paste the contents of `docs/github-workflow-android-build.yml`.

To build locally (requires JDK 17 + Android SDK 35 + NDK):

```bash
gradle testDebugUnitTest assembleDebug
```

## License & Upstream Attribution

This project is licensed under the **GNU General Public License v2.0** (see [`LICENSE`](./LICENSE)), matching upstream `DrKLO/Telegram`. Full file-level upstream attribution is documented in [`UPSTREAM_ATTRIBUTION.md`](./UPSTREAM_ATTRIBUTION.md).
