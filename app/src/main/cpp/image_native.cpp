/*
 * Extracted directly from Telegram for Android (DrKLO/Telegram)
 * Source: TMessagesProj/jni/image.cpp (commit 21f7d109da6ab3506c9e9577f98c6a38771ab335)
 * License: GNU General Public License v2.0
 */

#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>

#ifndef MAX
#define MAX(a, b) ((a) > (b) ? (a) : (b))
#endif
#ifndef MIN
#define MIN(a, b) ((a) < (b) ? (a) : (b))
#endif
#define SQUARE(i) ((i) * (i))

static constexpr uint32_t PGPhotoEnhanceHistogramBins = 256;
static constexpr uint32_t PGPhotoEnhanceSegments = 4;

extern "C" {

JNIEXPORT void JNICALL
Java_org_telegram_messenger_Utilities_nativeCalcCDT(
        JNIEnv *env, jclass clazz,
        jobject hsvBuffer, jint width, jint height,
        jobject buffer, jobject calcBuffer) {
    if (!hsvBuffer || !buffer || !calcBuffer || width <= 0 || height <= 0) {
        return;
    }

    float imageWidth = static_cast<float>(width);
    float imageHeight = static_cast<float>(height);
    float _clipLimit = 1.25f;

    uint32_t totalSegments = PGPhotoEnhanceSegments * PGPhotoEnhanceSegments;
    uint32_t tileArea = static_cast<uint32_t>(
            floorf(imageWidth / PGPhotoEnhanceSegments) *
            floorf(imageHeight / PGPhotoEnhanceSegments));
    if (tileArea == 0) {
        tileArea = 1;
    }
    uint32_t clipLimit = static_cast<uint32_t>(
            MAX(1.0f, _clipLimit * tileArea / static_cast<float>(PGPhotoEnhanceHistogramBins)));
    float scale = 255.0f / static_cast<float>(tileArea);

    auto *bytes = static_cast<unsigned char *>(env->GetDirectBufferAddress(hsvBuffer));
    auto *calcBytes = static_cast<uint32_t *>(env->GetDirectBufferAddress(calcBuffer));
    auto *result = static_cast<unsigned char *>(env->GetDirectBufferAddress(buffer));
    if (!bytes || !calcBytes || !result) {
        return;
    }

    uint32_t *cdfsMin = calcBytes;
    calcBytes += totalSegments;
    uint32_t *cdfsMax = calcBytes;
    calcBytes += totalSegments;
    uint32_t *cdfs = calcBytes;
    calcBytes += totalSegments * PGPhotoEnhanceHistogramBins;
    uint32_t *hist = calcBytes;
    memset(hist, 0, sizeof(uint32_t) * totalSegments * PGPhotoEnhanceHistogramBins);

    float xMul = PGPhotoEnhanceSegments / imageWidth;
    float yMul = PGPhotoEnhanceSegments / imageHeight;

    for (uint32_t i = 0; i < static_cast<uint32_t>(height); i++) {
        uint32_t yOffset = i * static_cast<uint32_t>(width) * 4;
        for (uint32_t j = 0; j < static_cast<uint32_t>(width); j++) {
            uint32_t index = j * 4 + yOffset;
            uint32_t tx = MIN(PGPhotoEnhanceSegments - 1, static_cast<uint32_t>(j * xMul));
            uint32_t ty = MIN(PGPhotoEnhanceSegments - 1, static_cast<uint32_t>(i * yMul));
            uint32_t t = ty * PGPhotoEnhanceSegments + tx;
            hist[t * PGPhotoEnhanceHistogramBins + bytes[index + 2]]++;
        }
    }

    for (uint32_t i = 0; i < totalSegments; i++) {
        if (clipLimit > 0) {
            uint32_t clipped = 0;
            for (uint32_t j = 0; j < PGPhotoEnhanceHistogramBins; j++) {
                if (hist[i * PGPhotoEnhanceHistogramBins + j] > clipLimit) {
                    clipped += hist[i * PGPhotoEnhanceHistogramBins + j] - clipLimit;
                    hist[i * PGPhotoEnhanceHistogramBins + j] = clipLimit;
                }
            }

            uint32_t redistBatch = clipped / PGPhotoEnhanceHistogramBins;
            uint32_t residual = clipped - redistBatch * PGPhotoEnhanceHistogramBins;

            for (uint32_t j = 0; j < PGPhotoEnhanceHistogramBins; j++) {
                hist[i * PGPhotoEnhanceHistogramBins + j] += redistBatch;
                if (j < residual) {
                    hist[i * PGPhotoEnhanceHistogramBins + j]++;
                }
            }
        }
        memcpy(cdfs + i * PGPhotoEnhanceHistogramBins,
               hist + i * PGPhotoEnhanceHistogramBins,
               PGPhotoEnhanceHistogramBins * sizeof(uint32_t));

        uint32_t hMin = PGPhotoEnhanceHistogramBins - 1;
        for (uint32_t j = 0; j < hMin; ++j) {
            if (cdfs[i * PGPhotoEnhanceHistogramBins + j] != 0) {
                hMin = j;
            }
        }

        uint32_t cdf = 0;
        for (uint32_t j = hMin; j < PGPhotoEnhanceHistogramBins; j++) {
            cdf += cdfs[i * PGPhotoEnhanceHistogramBins + j];
            cdfs[i * PGPhotoEnhanceHistogramBins + j] =
                    static_cast<uint8_t>(MIN(255.0f, cdf * scale));
        }

        cdfsMin[i] = cdfs[i * PGPhotoEnhanceHistogramBins + hMin];
        cdfsMax[i] = cdfs[i * PGPhotoEnhanceHistogramBins + PGPhotoEnhanceHistogramBins - 1];
    }

    for (uint32_t j = 0; j < totalSegments; j++) {
        uint32_t yOffset = j * PGPhotoEnhanceHistogramBins * 4;
        for (uint32_t i = 0; i < PGPhotoEnhanceHistogramBins; i++) {
            uint32_t index = i * 4 + yOffset;
            result[index] = static_cast<uint8_t>(cdfs[j * PGPhotoEnhanceHistogramBins + i]);
            result[index + 1] = static_cast<uint8_t>(cdfsMin[j]);
            result[index + 2] = static_cast<uint8_t>(cdfsMax[j]);
            result[index + 3] = 255;
        }
    }
}

JNIEXPORT void JNICALL
Java_org_telegram_messenger_Utilities_nativeStackBlurBitmap(
        JNIEnv *env, jclass clazz, jobject bitmap, jint radius) {
    if (!bitmap || radius < 1) {
        return;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return;
    }

    int w = static_cast<int>(info.width);
    int h = static_cast<int>(info.height);
    int stride = static_cast<int>(info.stride);
    if (w <= 0 || h <= 0) {
        return;
    }

    unsigned char *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, reinterpret_cast<void **>(&pixels)) !=
        ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        return;
    }

    const int wm = w - 1;
    const int hm = h - 1;
    const int wh = w * h;
    const int div = radius + radius + 1;
    const int r1 = radius + 1;
    const int divsum = SQUARE((div + 1) >> 1);

    std::vector<int> stack(static_cast<size_t>(div) * 4, 0);
    std::vector<int> vmin(static_cast<size_t>(MAX(w, h)), 0);

    std::vector<int> r(static_cast<size_t>(wh), 0);
    std::vector<int> g(static_cast<size_t>(wh), 0);
    std::vector<int> b(static_cast<size_t>(wh), 0);
    std::vector<int> a(static_cast<size_t>(wh), 0);

    const size_t dvcount = static_cast<size_t>(256) * static_cast<size_t>(divsum);
    std::vector<int> dv(dvcount);
    for (size_t i = 0; i < dvcount; i++) {
        dv[i] = static_cast<int>(i / divsum);
    }

    int *sir;
    int routsum, goutsum, boutsum, aoutsum;
    int rinsum, ginsum, binsum, ainsum;
    int rsum, gsum, bsum, asum, p, yp;
    int stackpointer;
    int stackstart;
    int rbs;

    int yi = 0;
    for (int y = 0; y < h; y++) {
        ainsum = aoutsum = asum = rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;

        for (int i = -radius; i <= radius; i++) {
            sir = &stack[(i + radius) * 4];
            int offset = y * stride + MIN(wm, MAX(i, 0)) * 4;
            sir[0] = pixels[offset];
            sir[1] = pixels[offset + 1];
            sir[2] = pixels[offset + 2];
            sir[3] = pixels[offset + 3];

            rbs = r1 - abs(i);
            rsum += sir[0] * rbs;
            gsum += sir[1] * rbs;
            bsum += sir[2] * rbs;
            asum += sir[3] * rbs;
            if (i > 0) {
                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];
                ainsum += sir[3];
            } else {
                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];
                aoutsum += sir[3];
            }
        }
        stackpointer = radius;

        for (int x = 0; x < w; x++) {
            r[yi] = dv[rsum];
            g[yi] = dv[gsum];
            b[yi] = dv[bsum];
            a[yi] = dv[asum];

            rsum -= routsum;
            gsum -= goutsum;
            bsum -= boutsum;
            asum -= aoutsum;

            stackstart = stackpointer - radius + div;
            sir = &stack[(stackstart % div) * 4];

            routsum -= sir[0];
            goutsum -= sir[1];
            boutsum -= sir[2];
            aoutsum -= sir[3];

            if (y == 0) {
                vmin[x] = MIN(x + radius + 1, wm);
            }

            int offset = y * stride + vmin[x] * 4;
            sir[0] = pixels[offset];
            sir[1] = pixels[offset + 1];
            sir[2] = pixels[offset + 2];
            sir[3] = pixels[offset + 3];
            rinsum += sir[0];
            ginsum += sir[1];
            binsum += sir[2];
            ainsum += sir[3];

            rsum += rinsum;
            gsum += ginsum;
            bsum += binsum;
            asum += ainsum;

            stackpointer = (stackpointer + 1) % div;
            sir = &stack[(stackpointer % div) * 4];

            routsum += sir[0];
            goutsum += sir[1];
            boutsum += sir[2];
            aoutsum += sir[3];

            rinsum -= sir[0];
            ginsum -= sir[1];
            binsum -= sir[2];
            ainsum -= sir[3];

            yi++;
        }
    }

    for (int x = 0; x < w; x++) {
        ainsum = aoutsum = asum = rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
        yp = -radius * w;
        for (int i = -radius; i <= radius; i++) {
            yi = MAX(0, yp) + x;

            sir = &stack[(i + radius) * 4];
            sir[0] = r[yi];
            sir[1] = g[yi];
            sir[2] = b[yi];
            sir[3] = a[yi];

            rbs = r1 - abs(i);
            rsum += r[yi] * rbs;
            gsum += g[yi] * rbs;
            bsum += b[yi] * rbs;
            asum += a[yi] * rbs;

            if (i > 0) {
                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];
                ainsum += sir[3];
            } else {
                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];
                aoutsum += sir[3];
            }

            if (i < hm) {
                yp += w;
            }
        }
        stackpointer = radius;
        for (int y = 0; y < h; y++) {
            int offset = stride * y + x * 4;
            pixels[offset] = static_cast<unsigned char>(dv[rsum]);
            pixels[offset + 1] = static_cast<unsigned char>(dv[gsum]);
            pixels[offset + 2] = static_cast<unsigned char>(dv[bsum]);
            pixels[offset + 3] = static_cast<unsigned char>(dv[asum]);

            rsum -= routsum;
            gsum -= goutsum;
            bsum -= boutsum;
            asum -= aoutsum;

            stackstart = stackpointer - radius + div;
            sir = &stack[(stackstart % div) * 4];

            routsum -= sir[0];
            goutsum -= sir[1];
            boutsum -= sir[2];
            aoutsum -= sir[3];

            if (x == 0) {
                vmin[y] = MIN(y + r1, hm) * w;
            }
            p = x + vmin[y];

            sir[0] = r[p];
            sir[1] = g[p];
            sir[2] = b[p];
            sir[3] = a[p];

            rinsum += sir[0];
            ginsum += sir[1];
            binsum += sir[2];
            ainsum += sir[3];

            rsum += rinsum;
            gsum += ginsum;
            bsum += binsum;
            asum += ainsum;

            stackpointer = (stackpointer + 1) % div;
            sir = &stack[stackpointer * 4];

            routsum += sir[0];
            goutsum += sir[1];
            boutsum += sir[2];
            aoutsum += sir[3];

            rinsum -= sir[0];
            ginsum -= sir[1];
            binsum -= sir[2];
            ainsum -= sir[3];
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
}

} // extern "C"
