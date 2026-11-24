package org.telegram.messenger;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

public class Utilities {

    private static volatile boolean nativeLoaded = false;

    static {
        try {
            System.loadLibrary("tgeditor");
            nativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            nativeLoaded = false;
        }
    }

    public interface Callback<T> {
        void run(T arg);
    }

    public interface Callback2<T, T2> {
        void run(T arg, T2 arg2);
    }

    public static float clamp(float value, float maxValue, float minValue) {
        if (Float.isNaN(value)) {
            return minValue;
        }
        if (value > maxValue) {
            return maxValue;
        } else if (value < minValue) {
            return minValue;
        }
        return value;
    }

    public static int clamp(int value, int maxValue, int minValue) {
        if (value > maxValue) {
            return maxValue;
        } else if (value < minValue) {
            return minValue;
        }
        return value;
    }

    public static double clamp(double value, double maxValue, double minValue) {
        if (Double.isNaN(value)) {
            return minValue;
        }
        if (value > maxValue) {
            return maxValue;
        } else if (value < minValue) {
            return minValue;
        }
        return value;
    }

    public static void calcCDT(ByteBuffer hsvBuffer, int width, int height, ByteBuffer buffer, ByteBuffer calcBuffer) {
        if (nativeLoaded) {
            nativeCalcCDT(hsvBuffer, width, height, buffer, calcBuffer);
        } else {
            calcCDTJavaFallback(hsvBuffer, width, height, buffer);
        }
    }

    public static void stackBlurBitmap(Bitmap bitmap, int radius) {
        if (nativeLoaded) {
            nativeStackBlurBitmap(bitmap, radius);
        }
    }

    private static native void nativeCalcCDT(ByteBuffer hsvBuffer, int width, int height, ByteBuffer buffer, ByteBuffer calcBuffer);

    private static native void nativeStackBlurBitmap(Bitmap bitmap, int radius);

    private static void calcCDTJavaFallback(ByteBuffer hsvBuffer, int width, int height, ByteBuffer buffer) {
        if (hsvBuffer == null || buffer == null || width <= 0 || height <= 0) {
            return;
        }
        final int bins = 256;
        final int segments = 4;
        final int totalSegments = segments * segments;
        int tileArea = Math.max(1, (int) (Math.floor(width / (float) segments) * Math.floor(height / (float) segments)));
        int clipLimit = Math.max(1, (int) (1.25f * tileArea / (float) bins));
        float scale = 255.0f / (float) tileArea;

        int[] hist = new int[totalSegments * bins];
        int[] cdfs = new int[totalSegments * bins];
        int[] cdfsMin = new int[totalSegments];
        int[] cdfsMax = new int[totalSegments];

        float xMul = segments / (float) width;
        float yMul = segments / (float) height;

        for (int i = 0; i < height; i++) {
            int yOffset = i * width * 4;
            for (int j = 0; j < width; j++) {
                int index = j * 4 + yOffset;
                int tx = Math.min(segments - 1, (int) (j * xMul));
                int ty = Math.min(segments - 1, (int) (i * yMul));
                int t = ty * segments + tx;
                int v = hsvBuffer.get(index + 2) & 0xFF;
                hist[t * bins + v]++;
            }
        }

        for (int i = 0; i < totalSegments; i++) {
            int base = i * bins;
            int clipped = 0;
            for (int j = 0; j < bins; j++) {
                if (hist[base + j] > clipLimit) {
                    clipped += hist[base + j] - clipLimit;
                    hist[base + j] = clipLimit;
                }
            }
            int redistBatch = clipped / bins;
            int residual = clipped - redistBatch * bins;
            for (int j = 0; j < bins; j++) {
                hist[base + j] += redistBatch + (j < residual ? 1 : 0);
            }
            System.arraycopy(hist, base, cdfs, base, bins);

            int hMin = bins - 1;
            for (int j = 0; j < hMin; j++) {
                if (cdfs[base + j] != 0) {
                    hMin = j;
                }
            }
            int cdf = 0;
            for (int j = hMin; j < bins; j++) {
                cdf += cdfs[base + j];
                cdfs[base + j] = Math.min(255, (int) (cdf * scale));
            }
            cdfsMin[i] = cdfs[base + hMin];
            cdfsMax[i] = cdfs[base + bins - 1];
        }

        for (int j = 0; j < totalSegments; j++) {
            int yOffset = j * bins * 4;
            for (int i = 0; i < bins; i++) {
                int index = i * 4 + yOffset;
                buffer.put(index, (byte) cdfs[j * bins + i]);
                buffer.put(index + 1, (byte) cdfsMin[j]);
                buffer.put(index + 2, (byte) cdfsMax[j]);
                buffer.put(index + 3, (byte) 255);
            }
        }
    }
}
