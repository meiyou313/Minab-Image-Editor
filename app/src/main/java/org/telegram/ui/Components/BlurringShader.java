package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLContext;

public class BlurringShader {

    public static class BlurManager {
        public int padding = 0;
        private final Object lock = new Object();

        public Object getTextureLock() {
            return lock;
        }

        public int getTexture() {
            return 0;
        }

        public void invalidateFallbackBlur() {
        }

        public EGLContext getParentContext() {
            return EGL10.EGL_NO_CONTEXT;
        }

        public void acquiredContext(EGLContext context) {
        }

        public void destroyedContext(EGLContext context) {
        }

        public void attach(Runnable runnable) {
        }

        public void detach(Runnable runnable) {
        }
    }

    public BlurringShader(FilterGLThread thread) {
    }

    public void setBlurManager(BlurManager manager) {
    }

    public boolean setup(float aspect, boolean isStory, int padding) {
        return false;
    }

    public void draw(float[] matrix, int texture, int w, int h) {
    }

    public Bitmap getBitmap() {
        return null;
    }

    public void updateTransform(Matrix matrix, int w, int h) {
    }

    public void updateGradient(int top, int bottom) {
    }
}
