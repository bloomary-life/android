package life.bloomary.app.features.editor.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.bloomary.app.shared.ui.AppStyle

/**
 * Image attachment half of the editor, the Android counterpart of
 * ios/Features/Editor/UI/PostImageEditorView.swift. The iOS view is a full
 * caption-overlay editor rendering to a 1080x1350 canvas; the Android port so
 * far covers pick → attach → preview → remove, which this file owns.
 */

/**
 * Photo picker launcher mirroring the iOS PhotosPicker hand-off: the chosen
 * image is loaded off the main thread and delivered as a [PostImageAttachment].
 */
@Composable
fun rememberPostImagePicker(
    onAttachment: (PostImageAttachment) -> Unit,
): ManagedActivityResultLauncher<PickVisualMediaRequest, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                loadPostImage(context, uri)?.let(onAttachment)
            }
        }
    }
}

/** Attached-image preview with the trailing remove button. */
@Composable
fun AttachedImagePreview(
    data: ByteArray?,
    filename: String?,
    onRemove: () -> Unit,
) {
    val bitmap = remember(data) {
        data?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    } ?: return
    Box(modifier = Modifier.fillMaxWidth()) {
        Image(
            bitmap = bitmap,
            contentDescription = filename,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.FillWidth,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(AppStyle.panel.copy(alpha = 0.9f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "✕", color = AppStyle.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

data class PostImageAttachment(
    val data: ByteArray,
    val filename: String,
    val mimeType: String,
)

private suspend fun loadPostImage(context: Context, uri: Uri): PostImageAttachment? = withContext(Dispatchers.IO) {
    val bytes = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    } ?: return@withContext null
    val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
    val extension = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }
    PostImageAttachment(
        data = bytes,
        filename = "bloomary-linkedin-image.$extension",
        mimeType = mimeType,
    )
}
