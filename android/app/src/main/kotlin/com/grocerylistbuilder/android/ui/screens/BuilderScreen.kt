package com.grocerylistbuilder.android.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.grocerylistbuilder.android.ui.BuilderUiState
import com.grocerylistbuilder.android.ui.DocumentUpload
import com.grocerylistbuilder.android.ui.PhotoUpload
import com.grocerylistbuilder.android.ui.components.DocumentPickerSection
import com.grocerylistbuilder.android.ui.components.PhotoPickerSection
import com.grocerylistbuilder.android.ui.components.UrlInputField

/** The top section — URL input, document/photo pickers, and the Build button (mirrors app.py's inputs). */
@Composable
fun BuilderScreen(
    state: BuilderUiState,
    onUrlsTextChange: (String) -> Unit,
    onAddDocuments: (List<DocumentUpload>) -> Unit,
    onClearDocuments: () -> Unit,
    onAddPhotos: (List<PhotoUpload>) -> Unit,
    onClearPhotos: () -> Unit,
    onBuild: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        UrlInputField(
            value = state.urlsText,
            onValueChange = onUrlsTextChange,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        DocumentPickerSection(documents = state.documents, onAdd = onAddDocuments, onClear = onClearDocuments)

        PhotoPickerSection(photos = state.photos, onAdd = onAddPhotos, onClear = onClearPhotos)

        state.messages.forEach { message ->
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = onBuild, enabled = !state.isBuilding, modifier = Modifier.fillMaxWidth()) {
                Text("Build list")
            }
            if (state.isBuilding) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
