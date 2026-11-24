package org.telegram.messenger;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

public enum BotWebViewVibrationEffect {
    IMPACT_LIGHT(10, 80),
    IMPACT_MEDIUM(15, 140),
    IMPACT_HEAVY(20, 200),
    IMPACT_RIGID(12, 180),
    IMPACT_SOFT(12, 60),
    SELECTION_CHANGE(6, 70);

    private final long durationMs;
    private final int amplitude;

    BotWebViewVibrationEffect(long durationMs, int amplitude) {
        this.durationMs = durationMs;
        this.amplitude = amplitude;
    }

    public void vibrate() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        try {
            Vibrator vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude));
            } else {
                vibrator.vibrate(durationMs);
            }
        } catch (Exception ignored) {
        }
    }
}
