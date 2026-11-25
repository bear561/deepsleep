package com.deepsleep.chat.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.deepsleep.chat.domain.model.Attachment
import com.deepsleep.chat.domain.model.TextLimits
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AttachmentRepository(
    private val context: Context,
) {
    suspend fun importText(uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "附件.txt"
        var size = 0L
        resolver.query(
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

    private fun guessMime(name: String): String = when {
        name.endsWith(".md", true) || name.endsWith(".markdown", true) -> "text/markdown"
        else -> "text/plain"
    }
}