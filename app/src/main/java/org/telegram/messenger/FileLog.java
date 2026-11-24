package org.telegram.messenger;

import android.util.Log;

public class FileLog {
    private static final String TAG = "TelePhotoEditor";

    public static void d(String message) {
        if (BuildVars.LOGS_ENABLED && message != null) {
            Log.d(TAG, message);
        }
    }

    public static void e(String message) {
        if (message != null) {
            Log.e(TAG, message);
        }
    }

    public static void e(Throwable e) {
        if (e != null) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    public static void e(Throwable e, boolean logToAppCenter) {
        e(e);
    }

    public static void e(String message, Throwable e) {
        Log.e(TAG, message, e);
    }
}
