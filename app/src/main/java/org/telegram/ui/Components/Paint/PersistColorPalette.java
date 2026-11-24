package org.telegram.ui.Components.Paint;

public class PersistColorPalette {
    public static final int COLOR_RED = 0xFFFF453A;
    public static final int COLOR_ORANGE = 0xFFFF8A00;
    public static final int COLOR_YELLOW = 0xFFFFD60A;
    public static final int COLOR_GREEN = 0xFF34C759;
    public static final int COLOR_BLUE = 0xFF63E6E2;
    public static final int COLOR_DARK_BLUE = 0xFF0A84FF;
    public static final int COLOR_VIOLET = 0xFFBF5AF2;
    public static final int COLOR_WHITE = 0xFFFFFFFF;
    public static final int COLOR_BLACK = 0xFF000000;

    private static final PersistColorPalette INSTANCE = new PersistColorPalette();

    private boolean fillShapes = false;

    public static PersistColorPalette getInstance(int account) {
        return INSTANCE;
    }

    public boolean getFillShapes() {
        return fillShapes;
    }

    public void setFillShapes(boolean fill) {
        this.fillShapes = fill;
    }
}
