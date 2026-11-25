package org.telegram.ui.Components.Paint;

import android.graphics.RectF;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class Slice {
    private final RectF bounds;
    private final int texture;
    private File file;

    public Slice(final ByteBuffer data, int tex, RectF rect, DispatchQueue queue) {
        bounds = rect;
        texture = tex;

        try {
            File outputDir = ApplicationLoader.applicationContext.getCacheDir();
            file = File.createTempFile("paint", ".bin", outputDir);
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (file == null)
            return;

        storeData(data);
    }

    public void cleanResources() {
        if (file != null) {
            file.delete();
            file = null;
        }
    }

    private void storeData(ByteBuffer data) {
        if (data == null || file == null) {
            return;
        }
        try {
            final byte[] input;
            final int offset;
            final int length;
            if (data.hasArray()) {
                input = data.array();
                offset = data.arrayOffset() + data.position();
                length = data.remaining();
            } else {
                ByteBuffer dup = data.duplicate();
                length = dup.remaining();
                input = new byte[length];
                dup.get(input);
                offset = 0;
            }
            if (length <= 0) {
                return;
            }
            FileOutputStream fos = new FileOutputStream(file);

            final Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
            deflater.setInput(input, offset, length);
            deflater.finish();

            byte[] buf = new byte[1024];
            while (!deflater.finished()) {
                int byteCount = deflater.deflate(buf);
                fos.write(buf, 0, byteCount);
            }
            deflater.end();

            fos.close();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public ByteBuffer getData() {
        if (file == null || !file.exists() || file.length() == 0) {
            return null;
        }
        try {
            byte[] input = new byte[1024];
            byte[] output = new byte[1024];
            FileInputStream fin = new FileInputStream(file);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Inflater inflater = new Inflater(true);

            while (true) {
                int numRead = fin.read(input);
                if (numRead != -1) {
                    inflater.setInput(input, 0, numRead);
                }

                int numDecompressed;
                while ((numDecompressed = inflater.inflate(output, 0, output.length)) != 0) {
                    bos.write(output, 0, numDecompressed);
                }

                if (inflater.finished()) {
                    break;
                } else if (inflater.needsInput()) {
                    if (numRead == -1) {
                        break;
                    }
                    continue;
                } else {
                    break;
                }
            }

            inflater.end();
            ByteBuffer result = ByteBuffer.wrap(bos.toByteArray(), 0, bos.size());

            bos.close();
            fin.close();

            return result;
        } catch (Exception e) {
            FileLog.e(e);
        }

        return null;
    }

    public int getX() {
        return (int) bounds.left;
    }

    public int getY() {
        return (int) bounds.top;
    }

    public int getWidth() {
        return (int) bounds.width();
    }

    public int getHeight() {
        return (int) bounds.height();
    }

    public RectF getBounds() {
        return new RectF(bounds);
    }

    public int getTexture() {
        return texture;
    }
}
