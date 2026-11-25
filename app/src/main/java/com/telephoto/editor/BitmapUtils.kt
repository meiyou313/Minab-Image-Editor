package com.telephoto.editor

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import org.telegram.messenger.FileLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object BitmapUtils {

    private const val MAX_DIMENSION = 2560

    enum class ExportFormat(val label: String, val extension: String, val mimeType: String) {
        JPEG("JPEG", "jpg", "image/jpeg"),
        PNG("PNG", "png", "image/png"),
        WEBP("WebP", "webp", "image/webp")
    }

    fun compressFormatFor(format: ExportFormat): Bitmap.CompressFormat {
        return when (format) {
            ExportFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ExportFormat.PNG -> Bitmap.CompressFormat.PNG
            ExportFormat.WEBP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
            }
        }
    }

    fun estimateSizeBytes(bitmap: Bitmap, format: ExportFormat, quality: Int): Long {
        return try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(compressFormatFor(format), quality.coerceIn(10, 100), baos)
            baos.size().toLong()
        } catch (_: Throwable) {
            0L
        }
    }

    fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val maxSide = max(srcW, srcH)
                    if (maxSide > MAX_DIMENSION) {
                        val scale = MAX_DIMENSION.toFloat() / maxSide.toFloat()
                        val targetW = (srcW * scale).roundToInt().coerceAtLeast(1)
                        val targetH = (srcH * scale).roundToInt().coerceAtLeast(1)
                        decoder.setTargetSize(targetW, targetH)
                    }
                }
                if (decoded != null) {
                    return if (decoded.config == Bitmap.Config.ARGB_8888) {
                        decoded
                    } else {
                        decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
                    }
                }
            } catch (e: Throwable) {
                FileLog.e("ImageDecoder failed, falling back to BitmapFactory", e)
            }
        }

        return decodeWithBitmapFactory(context, uri)
    }

    private fun decodeWithBitmapFactory(context: Context, uri: Uri): Bitmap? {
        try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val openedBounds = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOpts)
                true
            } ?: false
            if (!openedBounds) return null

            val srcW = boundsOpts.outWidth
            val srcH = boundsOpts.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            var sampleSize = 1
            while (max(srcW / sampleSize, srcH / sampleSize) > MAX_DIMENSION * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val orientation = try {
                context.contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }

            val maxSide = max(decoded.width, decoded.height)
            if (maxSide > MAX_DIMENSION) {
                val scale = MAX_DIMENSION.toFloat() / maxSide.toFloat()
                matrix.postScale(scale, scale)
            }

            if (matrix.isIdentity) {
                return if (decoded.config == Bitmap.Config.ARGB_8888) decoded
                else decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
            }

            val transformed = Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, matrix, true
            )
            if (transformed !== decoded) {
                decoded.recycle()
            }
            return transformed
        } catch (e: Throwable) {
            FileLog.e("BitmapFactory decode failed", e)
            return null
        }
    }

    fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat = ExportFormat.JPEG,
        quality: Int = 95
    ): Uri? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "TelePhoto_$timestamp.${format.extension}"
        val compressFmt = compressFormatFor(format)
        val q = quality.coerceIn(10, 100)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/TelePhotoEditor"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(compressFmt, q, out)) {
                        throw IllegalStateException("Bitmap compress failed")
                    }
                } ?: throw IllegalStateException("OutputStream null")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                FileLog.e("saveToGallery failed", e)
                resolver.delete(uri, null, null)
                null
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "TelePhotoEditor"
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, displayName)
            try {
                FileOutputStream(file).use { out ->
                    bitmap.compress(compressFmt, q, out)
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.TITLE, displayName)
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                FileLog.e("saveToGallery legacy failed", e)
                null
            }
        }
    }

    fun exportToShareUri(
        context: Context,
        bitmap: Bitmap,
        format: ExportFormat = ExportFormat.JPEG,
        quality: Int = 95
    ): Uri? {
        return try {
            val dir = File(context.cacheDir, "shared_exports")
            if (!dir.exists()) dir.mkdirs()
            // Clean up older shared temp files
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(dir, "TelePhoto_$timestamp.${format.extension}")
            FileOutputStream(outFile).use { out ->
                bitmap.compress(compressFormatFor(format), quality.coerceIn(10, 100), out)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        } catch (t: Throwable) {
            FileLog.e("exportToShareUri failed", t)
            null
        }
    }

    fun findLatestGalleryPhotoUri(context: Context): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}
