package org.telegram.messenger;

public class LocaleController {
    public static boolean isRTL = false;

    public static String getString(int resId) {
        if (ApplicationLoader.applicationContext != null) {
            try {
                return ApplicationLoader.applicationContext.getString(resId);
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    public static String getString(String key, int resId) {
        return getString(resId);
    }

    public static String formatString(int resId, Object... args) {
        if (ApplicationLoader.applicationContext != null) {
            try {
                return ApplicationLoader.applicationContext.getString(resId, args);
            } catch (Exception ignored) {
            }
        }
        return "";
    }
}
