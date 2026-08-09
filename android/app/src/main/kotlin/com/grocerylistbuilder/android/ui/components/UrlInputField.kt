package com.grocerylistbuilder.android.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Multiline URL paste box (mirrors app.py's `st.text_area("Recipe URLs")`). */
@Composable
fun UrlInputField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().heightIn(min = 120.dp),
        label = { Text("Recipe URLs") },
        placeholder = { Text("Paste one or more links, separated by new lines, commas, or spaces") },
    )
}
