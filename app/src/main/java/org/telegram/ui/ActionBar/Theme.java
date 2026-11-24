package org.telegram.ui.ActionBar;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;

public class Theme {
    public static final int ACTION_BAR_WHITE_SELECTOR_COLOR = 0x40FFFFFF;
    public static final int key_chat_editMediaButton = 1;

    public interface ResourcesProvider {
        default int getColor(int key) {
            return Theme.getColor(key);
        }
    }

    public static int getColor(int key) {
        if (key == key_chat_editMediaButton) {
            return 0xFF4DA3FF;
        }
        return Color.WHITE;
    }

    public static int getColor(int key, ResourcesProvider resourcesProvider) {
        return resourcesProvider != null ? resourcesProvider.getColor(key) : getColor(key);
    }

    public static Drawable createSelectorDrawable(int color) {
        return createSelectorDrawable(color, 1);
    }

    public static Drawable createSelectorDrawable(int color, int maskType) {
        ShapeDrawable mask = new ShapeDrawable(new OvalShape());
        mask.getPaint().setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(color), null, mask);
    }
}
