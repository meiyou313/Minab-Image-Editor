package org.telegram.messenger;

public class VideoEditedInfo {
    public static class MediaEntity {
        public static final byte TYPE_STICKER = 0;
        public static final byte TYPE_TEXT = 1;

        public byte type;
        public float x;
        public float y;
        public float rotation;
        public float width;
        public float height;
        public float textViewX;
        public float textViewY;
        public int viewWidth;
        public int viewHeight;

        public MediaEntity copy() {
            MediaEntity e = new MediaEntity();
            e.type = type;
            e.x = x;
            e.y = y;
            e.rotation = rotation;
            e.width = width;
            e.height = height;
            e.textViewX = textViewX;
            e.textViewY = textViewY;
            e.viewWidth = viewWidth;
            e.viewHeight = viewHeight;
            return e;
        }
    }
}
