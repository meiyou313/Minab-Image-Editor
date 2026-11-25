# Minab Image Editor

A lightweight, standalone Android photo editor powered **directly by the real open-source photo editing engines extracted from [Telegram for Android (`DrKLO/Telegram`)](https://github.com/DrKLO/Telegram)** — enhanced with Samsung Gallery-style Filters & Tone controls, non-destructive layered Paint/Text/Filter editing, 20 world languages, Dark/Light themes, and pro export tools (GPLv2).

## Highlights

1. **Non-Destructive Layered Architecture**
   - **Layer 1 (Base/Cropped Photo)**, **Layer 2 (Filters + Tone + Curves + Blur Recipe)**, and **Layer 3 (Editable Paint Strokes, Arrows, Shapes & Text Entities)** remain separate until final export.
   - Draw arrows, shapes, or text, switch to Filters or Tone, and return to Draw anytime to undo, erase, move, edit, or delete your strokes and text without baking them into the photo!
2. **Samsung Gallery-Style Filters & Tone Engine (OpenGL ES 2.0)**
   - **12 Live-Thumbnail Filters** (`Original`, `Auto`, `Warm`, `Cool`, `Light`, `Frosty`, `Blossom`, `Ivory`, `Soft`, `KissMe`, `Grayscale`, `B&W`) with `+` custom filter saving and a vertical tick-mark **Intensity (`0–100`)** ruler slider.
   - **Full Tone Suite** (`Light Balance`, `Brightness`, `Exposure`, `Contrast`, `Highlights`, `Shadows`, `Saturation`, `Tint`, `Temperature`, `Sharpness`, `Definition`, `Fade`, `Vignette`, `Grain`, `Soften Skin`, `Tint Shadows`, `Tint Highlights`), **4-Channel Curves**, and **Radial/Linear Tilt-Shift Blur**.
3. **GPU Paint, Brushes, Shapes, Eyedropper, Mosaic & Inline Text**
   - Real Telegram GPU brushes (`Pen`, `Arrow`, `Marker`, `Neon`, `Blur`, `Mosaic`, `Eraser`), 5 shapes, live color Eyedropper with loupe, and draggable/rotatable/scalable inline Text overlays.
4. **Crop, Free-Rotate & Quick Aspect-Ratio Strip**
   - Free rotation wheel (-45° to +45°), 90° rotate, horizontal mirror, and quick one-tap ratio presets (`Free`, `Original`, `1:1`, `4:3`, `3:4`, `9:12`, `3:2`, `2:3`, `16:9`, `9:16`, `4:5`, `5:4`, `5:7`).
5. **Pro Tools, Themes, 20 Languages & Diagnostics**
   - Dark & Light Mode, 20 world languages (English default + automatic RTL), Hold-to-Compare & Pinch-to-Zoom (`1×–5×`), `JPEG`/`PNG`/`WebP` Export & Share dialog, Auto-Draft recovery, Recent Edits strip, GitHub Release Update Checker, and Debug Console with `.txt` export inside Settings.

## Download APK

Download the latest signed APK directly from [GitHub Releases](https://github.com/meiyou313/Minab-Image-Editor/releases/latest).

## License & Upstream Attribution

This project is licensed under the **GNU General Public License v2.0** (see [`LICENSE`](./LICENSE)), matching upstream `DrKLO/Telegram`. Full file-level upstream attribution is documented in [`UPSTREAM_ATTRIBUTION.md`](./UPSTREAM_ATTRIBUTION.md).
