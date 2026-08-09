package com.grocerylistbuilder.android.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.grocerylistbuilder.android.ui.PhotoUpload
import java.io.File

private const val PHOTO_LIMIT = 5

/**
 * Photo capture/upload — accumulate-then-build, same pattern as [DocumentPickerSection], capped
 * lower than documents' 10 (each photo costs an OCR pass, not just a text read). Camera writes
 * to a [FileProvider] URI so the camera app can hand a full-resolution
 * photo back to us without any storage permission; gallery uses the modern Android Photo Picker
 * (no `READ_MEDIA_IMAGES` permission needed either).
 */
@Composable
fun PhotoPickerSection(
    photos: List<PhotoUpload>,
    onAdd: (List<PhotoUpload>) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (success && uri != null) {
            onAdd(listOf(uri.toPhotoUpload(context, "Photo ${photos.size + 1}")))
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val uri = createCaptureUri(context)
            pendingCaptureUri = uri
            cameraLauncher.launch(uri)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PHOTO_LIMIT)) { uris ->
        if (uris.isNotEmpty()) {
            onAdd(uris.mapIndexed { i, uri -> uri.toPhotoUpload(context, "Photo ${photos.size + i + 1}") })
        }
    }

    Card(modifier = modifier.padding(16.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📷 Recipes from photos — ${photos.size}/$PHOTO_LIMIT added")

            if (photos.isNotEmpty()) {
                // A horizontal LazyRow here is fine even though the page's outer container is a
                // vertical Modifier.verticalScroll — the crash lesson from Phase 1's
                // GroceryListEditor was a *vertical* lazy list inside an unbounded-height
                // *vertical* scroll (both unbounded on the same axis). This LazyRow's height is
                // bounded (fixed thumbnail size) and its width comes from the bounded-width outer
                // container — no axis conflict.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    items(photos.size) { i ->
                        val photo = photos[i]
                        val bitmap = remember(photo) { BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size) }
                        bitmap?.let {
                            Image(bitmap = it.asImageBitmap(), contentDescription = photo.displayName, modifier = Modifier.size(64.dp))
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            val uri = createCaptureUri(context)
                            pendingCaptureUri = uri
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = photos.size < PHOTO_LIMIT,
                ) { Text("📸 Camera") }

                Button(
                    onClick = {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    enabled = photos.size < PHOTO_LIMIT,
                ) { Text("🖼️ Gallery") }

                if (photos.isNotEmpty()) {
                    OutlinedButton(onClick = onClear) { Text("🗑️ Clear") }
                }
            }
        }
    }
}

/** cacheDir/images/ matches res/xml/file_paths.xml's <cache-path name="captured_images" path="images/" />. */
private fun createCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun Uri.toPhotoUpload(context: Context, fallbackName: String): PhotoUpload {
    val bytes = context.contentResolver.openInputStream(this)?.use { it.readBytes() } ?: ByteArray(0)
    return PhotoUpload(fallbackName, bytes)
}
