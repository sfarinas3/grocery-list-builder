package com.grocerylistbuilder.android.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.grocerylistbuilder.android.ui.DocumentUpload

private const val DOC_LIMIT = 10
private val DOC_MIME_TYPES = arrayOf(
    "application/pdf",
    "text/plain",
    "text/markdown",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

/**
 * Document upload (PDF/Word/text) — accumulate-then-build, same pattern as app.py's
 * `st.session_state.docs`/`doc_round` (add documents, "Clear documents" to reset, capped at
 * [DOC_LIMIT]). Reading via SAF grants read access without broad storage permissions.
 */
@Composable
fun DocumentPickerSection(
    documents: List<DocumentUpload>,
    onAdd: (List<DocumentUpload>) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onAdd(uris.map { it.toDocumentUpload(context) })
    }

    Card(modifier = modifier.padding(16.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("📄 Recipes from documents (PDF, Word, or text) — ${documents.size}/$DOC_LIMIT added")
            documents.forEachIndexed { i, doc -> Text("${i + 1}. ${doc.filename}") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Button(onClick = { launcher.launch(DOC_MIME_TYPES) }, enabled = documents.size < DOC_LIMIT) {
                    Text("➕ Add documents")
                }
                if (documents.isNotEmpty()) {
                    OutlinedButton(onClick = onClear) { Text("🗑️ Clear") }
                }
            }
        }
    }
}

private fun Uri.toDocumentUpload(context: Context): DocumentUpload {
    val resolver = context.contentResolver
    val name = resolver.query(this, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
    } ?: lastPathSegment.orEmpty()
    val bytes = resolver.openInputStream(this)?.use { it.readBytes() } ?: ByteArray(0)
    return DocumentUpload(name, bytes)
}
