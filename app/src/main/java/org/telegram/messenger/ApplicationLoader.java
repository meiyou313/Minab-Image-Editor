package org.telegram.messenger;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public class ApplicationLoader {
    public static volatile Context applicationContext;
    public static volatile Handler applicationHandler;

    public static void init(Context context) {
        applicationContext = context.getApplicationContext();
        applicationHandler = new Handler(Looper.getMainLooper());
        AndroidUtilities.init(applicationContext);
    }
}
