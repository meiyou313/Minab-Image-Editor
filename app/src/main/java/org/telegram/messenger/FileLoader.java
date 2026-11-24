package org.telegram.messenger;

import java.io.File;

public class FileLoader {
    public static final int MEDIA_DIR_CACHE = 4;

    public static File getDirectory(int type) {
        if (ApplicationLoader.applicationContext != null) {
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "editor_cache");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            return dir;
        }
        return new File(System.getProperty("java.io.tmpdir", "/tmp"));
    }
}
