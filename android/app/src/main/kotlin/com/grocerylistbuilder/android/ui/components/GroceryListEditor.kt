package com.grocerylistbuilder.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.grocerylistbuilder.core.models.GroceryItem
import com.grocerylistbuilder.core.util.formatQuantity

/**
 * The editable grocery list — mirrors app.py's `st.data_editor` table (Done checkbox, editable
 * Ingredient/Quantity/Unit/Category/Notes, read-only Recipe(s) provenance, a review-flag
 * indicator, and add/delete rows). Compose has no built-in data-grid equivalent to
 * `st.data_editor`, so each row is a compact card rather than a literal wide table — better
 * suited to phone width than Streamlit's table layout.
 *
 * A plain [Column], not `LazyColumn`, deliberately: the whole page (see `MainActivity`) is one
 * outer `Modifier.verticalScroll` column mirroring app.py's single scrolling page, and a lazy
 * list can't be nested inside a scrollable column (it needs a bounded height to know how much to
 * lazily lay out, which an unbounded scroll container can't give it — Compose throws at measure
 * time). A grocery list tops out at maybe a few dozen rows, so eagerly composing all of them
 * costs nothing worth optimizing for.
 */
@Composable
fun GroceryListEditor(
    items: List<GroceryItem>,
    onItemChange: (index: Int, item: GroceryItem) -> Unit,
    onDelete: (index: Int) -> Unit,
    onAddItem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { index, item ->
            GroceryItemRow(
                item = item,
                onChange = { onItemChange(index, it) },
                onDelete = { onDelete(index) },
            )
        }
        OutlinedButton(onClick = onAddItem, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(" Add item")
        }
    }
}

@Composable
private fun GroceryItemRow(item: GroceryItem, onChange: (GroceryItem) -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = item.checked, onCheckedChange = { onChange(item.copy(checked = it)) })
                OutlinedTextField(
                    value = item.name,
                    onValueChange = { onChange(item.copy(name = it)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = if (item.checked) {
                        androidx.compose.ui.text.TextStyle(textDecoration = TextDecoration.LineThrough)
                    } else {
                        androidx.compose.ui.text.TextStyle.Default
                    },
                )
                if (item.flags.isNotEmpty()) {
                    Icon(Icons.Filled.Warning, contentDescription = item.flags.joinToString(", "))
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
            }

            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = formatQuantity(item.quantity),
                    onValueChange = { text -> onChange(item.copy(quantity = text.toDoubleOrNull())) },
                    modifier = Modifier.width(80.dp),
                    singleLine = true,
                    label = { Text("Qty") },
                )
                OutlinedTextField(
                    value = item.unit.orEmpty(),
                    onValueChange = { onChange(item.copy(unit = it.ifBlank { null })) },
                    modifier = Modifier.width(90.dp),
                    singleLine = true,
                    label = { Text("Unit") },
                )
                CategoryDropdown(
                    value = item.category,
                    onValueChange = { onChange(item.copy(category = it)) },
                    modifier = Modifier.weight(1f),
                )
            }

            OutlinedTextField(
                value = item.notes,
                onValueChange = { onChange(item.copy(notes = it)) },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                label = { Text("Notes") },
            )

            if (item.sources.isNotEmpty()) {
                Text(
                    item.sources.joinToString(" / "),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
