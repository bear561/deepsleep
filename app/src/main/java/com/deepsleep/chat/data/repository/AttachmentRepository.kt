package com.deepsleep.chat.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.TextLimits
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AttachmentRepository(
    private val context: Context,
) {
    /**
     * Images are copied into app-private storage because the picked content://
     * URI may stop being readable after the activity (or the process) restarts.
     */
    val imageDir: File
        get() = File(context.filesDir, IMAGE_DIR).also { if (!it.isDirectory) it.mkdirs() }

    fun imageFile(attachment: Attachment): File? {
        val name = attachment.localFile ?: return null
        val file = File(imageDir, name)
        return file.takeIf { it.isFile }
    }

    fun deleteImages(attachments: List<Attachment>) {
        val names = attachments.mapNotNull { it.localFile }
        deleteImageFiles(names)
    }

    fun deleteImageFiles(names: Collection<String>) {
        names.forEach { name ->
            runCatching {
                // Only ever delete inside our own image directory.
                val file = File(imageDir, File(name).name)
                if (file.parentFile == imageDir) file.delete()
            }
        }
    }

    suspend fun importText(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (name, size) = queryNameAndSize(uri, "附件.txt")
        if (size > TextLimits.MAX_ATTACHMENT_BYTES) {
            throw IllegalArgumentException("文件过大，最大支持 2MB 的文本文件")
        }
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > TextLimits.MAX_ATTACHMENT_BYTES) {
                    throw IllegalArgumentException("文件过大，最大支持 2MB 的文本文件")
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IllegalArgumentException("无法读取该文件")
        val mime = resolver.getType(uri) ?: guessMime(name)
        val decodedUtf8 = bytes.toString(Charsets.UTF_8)
        // Chinese text files exported by legacy Windows apps are often GBK.
        val text = if ('\uFFFD' in decodedUtf8) {
            runCatching { bytes.toString(charset("GBK")) }.getOrDefault(decodedUtf8)
        } else {
            decodedUtf8
        }.removePrefix("\uFEFF")
        Attachment(
            id = UUID.randomUUID().toString(),
            name = name,
            mime = mime,
            sizeBytes = bytes.size.toLong(),
            text = text.take(TextLimits.MAX_CHARS_PER_ATTACHMENT),
        )
    }

    /**
     * Copies an album image into private storage, downscaling it to at most
     * [MAX_IMAGE_DIMENSION] px on the long side and re-encoding as JPEG. This
     * keeps base64 requests small enough for low-end phones and cheap APIs.
     */
    suspend fun importImage(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val (rawName, _) = queryNameAndSize(uri, "图片.jpg")
        val displayName = rawName.takeIf { it.isNotBlank() } ?: "图片.jpg"

        // Copy the picked content:// URI to a cache file first. Vendor photo
        // pickers (notably MIUI/HyperOS "安全访问") hand out streams that are
        // not always seekable or may only be valid for a short window, and
        // BitmapFactory/ExifInterface need random access.
        val temp = File(context.cacheDir, "import-${UUID.randomUUID()}.img")
        try {
            copyToTempFile(uri, temp)
            if (temp.length() == 0L) throw IllegalArgumentException("无法读取这张图片")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(
                    TAG,
                    "Image bounds failed: name=$displayName bytes=${temp.length()} " +
                        "mime=${resolver.getType(uri)} uri=$uri",
                )
                throw IllegalArgumentException("无法识别这张图片的格式（可能是 HEIF 或损坏文件）")
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_IMAGE_DIMENSION)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = try {
                BitmapFactory.decodeFile(temp.absolutePath, decodeOptions)
            } catch (t: OutOfMemoryError) {
                throw IllegalArgumentException("图片过大，无法处理，请换一张图片")
            } ?: throw IllegalArgumentException("图片解码失败，请换一张图片")

            val orientation = runCatching {
                ExifInterface(temp.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrNull()

            val jpegBytes = try {
                val rotated = applyOrientation(decoded, orientation)
                val scaled = scaleDown(rotated, MAX_IMAGE_DIMENSION)
                encodeJpeg(scaled)
            } catch (t: OutOfMemoryError) {
                throw IllegalArgumentException("图片过大，无法处理，请换一张图片")
            } finally {
                if (!decoded.isRecycled) decoded.recycle()
            }
            if (jpegBytes.size > TextLimits.MAX_IMAGE_BYTES) {
                throw IllegalArgumentException("图片压缩后仍然过大，请换一张图片")
            }

            val id = UUID.randomUUID().toString()
            val file = File(imageDir, "$id.jpg")
            file.writeBytes(jpegBytes)
            Attachment(
                id = id,
                name = displayName,
                mime = "image/jpeg",
                sizeBytes = jpegBytes.size.toLong(),
                text = "",
                isImage = true,
                localFile = file.name,
            )
        } catch (security: SecurityException) {
            Log.w(TAG, "Image read permission denied for $uri", security)
            throw IllegalArgumentException("读取相册图片失败（权限被系统回收），请重试")
        } finally {
            temp.delete()
        }
    }

    private fun copyToTempFile(uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取这张图片")
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_IMPORT_IMAGE_BYTES) {
                        throw IllegalArgumentException("图片文件过大，请换一张图片")
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun queryNameAndSize(uri: Uri, fallback: String): Pair<String, Long> {
        var name = fallback
        var size = 0L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        return name to size
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        // Decode directly to a bitmap no larger than the target, so even a
        // 108MP photo does not allocate a giant ARGB buffer on a cheap phone.
        while (longest / sample > target) sample *= 2
        return sample
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int?): Bitmap {
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
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap && !bitmap.isRecycled) bitmap.recycle() }
        }.getOrDefault(bitmap)
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / longest
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
            .also { if (it !== bitmap && !bitmap.isRecycled) bitmap.recycle() }
    }

    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        // JPEG has no alpha channel: flatten PNGs onto white instead of black.
        val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flattened).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        if (!bitmap.isRecycled) bitmap.recycle()
        var quality = 88
        var bytes = compress(flattened, quality)
        while (bytes.size > 3_500_000 && quality > 60) {
            quality -= 12
            bytes = compress(flattened, quality)
        }
        if (bytes.size > TextLimits.MAX_IMAGE_BYTES) {
            val ratio = 0.7f
            val smaller = Bitmap.createScaledBitmap(
                flattened,
                (flattened.width * ratio).toInt().coerceAtLeast(1),
                (flattened.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            flattened.recycle()
            bytes = compress(smaller, 80)
            smaller.recycle()
        } else if (!flattened.isRecycled) {
            flattened.recycle()
        }
        return bytes
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }

    private fun guessMime(name: String): String = when {
        name.endsWith(".md", true) || name.endsWith(".markdown", true) -> "text/markdown"
        else -> "text/plain"
    }

    companion object {
        private const val TAG = "AttachmentRepository"
        private const val IMAGE_DIR = "chat-images"
        private const val MAX_IMAGE_DIMENSION = 1600
        private const val MAX_IMPORT_IMAGE_BYTES = 40L * 1024 * 1024
    }
}