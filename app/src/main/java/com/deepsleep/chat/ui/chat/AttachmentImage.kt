package com.deepsleep.chat.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.deepsleep.chat.domain.model.Attachment
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val IMAGE_DIR = "chat-images"

fun resolveImageFile(context: Context, attachment: Attachment): File? {
    val name = attachment.localFile ?: return null
    return File(File(context.filesDir, IMAGE_DIR), name).takeIf { it.isFile }
}

@Composable
fun LocalAttachmentImage(
    attachment: Attachment,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDecodeSize: Int = 480,
) {
    val context = LocalContext.current
    val file = remember(attachment.localFile) { resolveImageFile(context, attachment) }
    val key = file?.absolutePath
    var image by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }

    LaunchedEffect(key, maxDecodeSize) {
        image = null
        failed = false
        if (file == null) {
            failed = true
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) { decodeSampled(file, maxDecodeSize) }
        if (bitmap == null) failed = true else image = bitmap.asImageBitmap()
    }

    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = attachment.name,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (failed) "图片不可用" else "加载中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AttachmentPreviewDialog(
    attachment: Attachment,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            LocalAttachmentImage(
                attachment = attachment,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp)
                    .heightIn(max = 560.dp),
                contentScale = ContentScale.Fit,
                maxDecodeSize = 1600,
            )
        }
    }
}

private fun decodeSampled(file: File, target: Int): Bitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrNull()
}