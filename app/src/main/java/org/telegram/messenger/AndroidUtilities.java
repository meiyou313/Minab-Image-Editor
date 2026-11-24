package org.telegram.messenger;

import android.content.Context;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class AndroidUtilities {
    public static float density = 1f;
    public static Point displaySize = new Point(1080, 2400);
    public static DisplayMetrics displayMetrics = new DisplayMetrics();
    public static int statusBarHeight = 0;
    public static float touchSlop = 8f;

    public static void init(Context context) {
        if (context == null) return;
        displayMetrics = context.getResources().getDisplayMetrics();
        density = displayMetrics.density;
        displaySize.x = displayMetrics.widthPixels;
        displaySize.y = displayMetrics.heightPixels;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            statusBarHeight = context.getResources().getDimensionPixelSize(resId);
        }
    }

    public static int dp(float value) {
        if (value == 0) {
            return 0;
        }
        return (int) Math.ceil(density * value);
    }

    public static float dpf2(float value) {
        if (value == 0) {
            return 0;
        }
        return density * value;
    }

    public static float lerp(float a, float b, float f) {
        return a + f * (b - a);
    }

    public static double lerp(double a, double b, float f) {
        return a + f * (b - a);
    }

    public static int lerp(int a, int b, float f) {
        return (int) (a + f * (b - a));
    }

    public static void lerp(RectF a, RectF b, float f, RectF dst) {
        if (dst == null) return;
        dst.set(
                lerp(a.left, b.left, f),
                lerp(a.top, b.top, f),
                lerp(a.right, b.right, f),
                lerp(a.bottom, b.bottom, f)
        );
    }

    public static void runOnUIThread(Runnable runnable) {
        runOnUIThread(runnable, 0);
    }

    public static void runOnUIThread(Runnable runnable, long delay) {
        if (ApplicationLoader.applicationHandler == null) {
            runnable.run();
            return;
        }
        if (delay == 0) {
            ApplicationLoader.applicationHandler.post(runnable);
        } else {
            ApplicationLoader.applicationHandler.postDelayed(runnable, delay);
        }
    }

    public static void cancelRunOnUIThread(Runnable runnable) {
        if (ApplicationLoader.applicationHandler != null) {
            ApplicationLoader.applicationHandler.removeCallbacks(runnable);
        }
    }

    public static int getPhotoSize() {
        return getPhotoSize(true);
    }

    public static int getPhotoSize(boolean highQuality) {
        return highQuality ? 2560 : 1280;
    }

    public static Typeface bold() {
        return Typeface.DEFAULT_BOLD;
    }

    public static String readRes(int rawResId) {
        if (ApplicationLoader.applicationContext == null) {
            return "";
        }
        try (InputStream is = ApplicationLoader.applicationContext.getResources().openRawResource(rawResId);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        }
    }

    public static boolean copyFile(File sourceFile, File destFile) throws IOException {
        if (sourceFile.equals(destFile)) {
            return true;
        }
        if (!destFile.exists()) {
            destFile.createNewFile();
        }
        try (FileInputStream in = new FileInputStream(sourceFile);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            return true;
        }
    }

    public static void makeAccessibilityAnnouncement(CharSequence text) {
        // No-op in minimal standalone shim
    }

    public static boolean vibrateCursor(View view) {
        if (view != null) {
            try {
                return view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public static boolean isTablet() {
        return false;
    }
}
