package com.telephoto.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.telegram.messenger.FileLog
import java.io.File
import java.io.FileOutputStream

/**
 * Manages:
 * 1) Auto-Draft recovery (persists current edited & original photo safely in app internal storage)
 * 2) Recent Edited Photos history (up to 8 quick-reopen snapshots on Landing screen)
 */
object DraftManager {

    private const val MAX_RECENTS = 8

    private fun draftDir(context: Context): File = File(context.filesDir, "drafts").apply {
        if (!exists()) mkdirs()
    }

    private fun recentsDir(context: Context): File = File(context.filesDir, "recents").apply {
        if (!exists()) mkdirs()
    }

    fun saveAutoDraft(context: Context, edited: Bitmap, original: Bitmap?) {
        try {
            val dir = draftDir(context)
            writeAtomicJpeg(File(dir, "current_draft.jpg"), edited, 92)
            if (original != null) {
                writeAtomicJpeg(File(dir, "original_draft.jpg"), original, 90)
            }
        } catch (t: Throwable) {
            FileLog.e("DraftManager.saveAutoDraft failed", t)
        }
    }

    fun hasDraft(context: Context): Boolean {
        val f = File(draftDir(context), "current_draft.jpg")
        return f.exists() && f.length() > 0
    }

    fun loadDraft(context: Context): Pair<Bitmap, Bitmap>? {
        return try {
            val dir = draftDir(context)
            val curFile = File(dir, "current_draft.jpg")
            if (!curFile.exists()) return null
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            val cur = BitmapFactory.decodeFile(curFile.absolutePath, opts) ?: return null
            val origFile = File(dir, "original_draft.jpg")
            val orig = if (origFile.exists()) {
                BitmapFactory.decodeFile(origFile.absolutePath, opts) ?: cur.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                cur.copy(Bitmap.Config.ARGB_8888, false)
            }
            cur to orig
        } catch (t: Throwable) {
            FileLog.e("DraftManager.loadDraft failed", t)
            null
        }
    }

    fun addRecentEdit(context: Context, bitmap: Bitmap) {
        try {
            val dir = recentsDir(context)
            val file = File(dir, "recent_${System.currentTimeMillis()}.jpg")
            writeAtomicJpeg(file, bitmap, 90)
            val all = dir.listFiles()?.filter { it.name.endsWith(".jpg") }
                ?.sortedByDescending { it.lastModified() } ?: return
            all.drop(MAX_RECENTS).forEach { it.delete() }
        } catch (t: Throwable) {
            FileLog.e("DraftManager.addRecentEdit failed", t)
        }
    }

    fun listRecentFiles(context: Context): List<File> {
        val dir = recentsDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") && it.length() > 0 }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_RECENTS) ?: emptyList()
    }

    fun decodeThumbnail(file: File, targetSize: Int = 240): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > targetSize * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeFullRecent(file: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeAtomicJpeg(target: File, bitmap: Bitmap, quality: Int) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
