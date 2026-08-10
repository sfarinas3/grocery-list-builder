package com.grocerylistbuilder.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grocerylistbuilder.core.models.GroceryItem
import com.grocerylistbuilder.core.util.formatQuantity

private val CheckboxColumnWidth = 40.dp
private val WarningColumnWidth = 32.dp
private val NameColumnWidth = 176.dp
private val QtyColumnWidth = 64.dp
private val UnitColumnWidth = 76.dp
private val CategoryColumnWidth = 168.dp
private val NotesColumnWidth = 148.dp
private val SourcesColumnWidth = 128.dp
private val DeleteColumnWidth = 48.dp

/**
 * The editable grocery list as a real table (mirrors app.py's `st.data_editor`: Done checkbox,
 * editable Ingredient/Quantity/Unit/Category/Notes, read-only Recipe(s) provenance, a review-flag
 * indicator, and add/delete rows) — column headers, fixed per-column widths shared between the
 * header and every row so cells line up, and horizontal scroll so it stays usable on phone width
 * without cramming every column into it. Replaces an earlier one-card-per-item layout that ate
 * far more vertical space per row than a spreadsheet-style table needs.
 *
 * Delete sits right after the checkbox (before Ingredient), not at the far right past Notes/
 * Recipe(s) — after scrolling right to edit those columns, a delete button all the way back at
 * the start would be one more scroll away from whichever row you're looking at, easy to fire on
 * the wrong row. Checkbox+delete are the two columns you always want in view regardless of scroll
 * position.
 *
 * A plain [Column], not `LazyColumn`, deliberately: the whole page (see `MainActivity`) is one
 * outer `Modifier.verticalScroll` column, and a lazy list can't nest inside a scrollable column
 * (needs a bounded height to lazily lay out, which an unbounded scroll container can't give it —
 * Compose throws at measure time). A grocery list tops out at maybe a few dozen rows, so eagerly
 * composing all of them costs nothing worth optimizing for. The horizontal scroll here is a
 * different axis from that outer vertical one, so there's no such conflict for it.
 */
@Composable
fun GroceryListEditor(
    items: List<GroceryItem>,
    onItemChange: (index: Int, item: GroceryItem) -> Unit,
    onDelete: (index: Int) -> Unit,
    onAddItem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TableHeaderRow()
            HorizontalDivider()
            items.forEachIndexed { index, item ->
                GroceryItemRow(
                    item = item,
                    onChange = { onItemChange(index, it) },
                    onDelete = { onDelete(index) },
                )
                HorizontalDivider()
            }
        }
        OutlinedButton(onClick = onAddItem, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(" Add item")
        }
    }
}

@Composable
private fun TableHeaderRow() {
    Row(
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("", CheckboxColumnWidth)
        HeaderCell("", DeleteColumnWidth)
        HeaderCell("", WarningColumnWidth)
        HeaderCell("Ingredient", NameColumnWidth)
        HeaderCell("Qty", QtyColumnWidth)
        HeaderCell("Unit", UnitColumnWidth)
        HeaderCell("Category", CategoryColumnWidth)
        HeaderCell("Notes", NotesColumnWidth)
        HeaderCell("Recipe(s)", SourcesColumnWidth)
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.width(width).padding(horizontal = 4.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Borderless, minimally-padded text field so a table row stays compact — a full [TextField]'s
 * default padding/label/underline is sized for a form, not a spreadsheet cell. */
@Composable
private fun cellTextFieldColors() = TextFieldDefaults.colors(
    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
)

@Composable
private fun GroceryItemRow(item: GroceryItem, onChange: (GroceryItem) -> Unit, onDelete: () -> Unit) {
    Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Checkbox(
            checked = item.checked,
            onCheckedChange = { onChange(item.copy(checked = it)) },
            modifier = Modifier.width(CheckboxColumnWidth),
        )

        IconButton(onClick = onDelete, modifier = Modifier.width(DeleteColumnWidth)) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete")
        }

        Row(modifier = Modifier.width(WarningColumnWidth), horizontalArrangement = Arrangement.Center) {
            if (item.flags.isNotEmpty()) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = item.flags.joinToString(", "),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        TextField(
            value = item.name,
            onValueChange = { onChange(item.copy(name = it)) },
            modifier = Modifier.width(NameColumnWidth),
            singleLine = true,
            textStyle = cellTextStyle(item.checked),
            colors = cellTextFieldColors(),
        )
        TextField(
            value = formatQuantity(item.quantity),
            onValueChange = { text -> onChange(item.copy(quantity = text.toDoubleOrNull())) },
            modifier = Modifier.width(QtyColumnWidth),
            singleLine = true,
            textStyle = cellTextStyle(item.checked),
            colors = cellTextFieldColors(),
        )
        TextField(
            value = item.unit.orEmpty(),
            onValueChange = { onChange(item.copy(unit = it.ifBlank { null })) },
            modifier = Modifier.width(UnitColumnWidth),
            singleLine = true,
            textStyle = cellTextStyle(item.checked),
            colors = cellTextFieldColors(),
        )
        CategoryDropdown(
            value = item.category,
            onValueChange = { onChange(item.copy(category = it)) },
            modifier = Modifier.width(CategoryColumnWidth),
        )
        TextField(
            value = item.notes,
            onValueChange = { onChange(item.copy(notes = it)) },
            modifier = Modifier.width(NotesColumnWidth),
            singleLine = true,
            textStyle = cellTextStyle(item.checked),
            colors = cellTextFieldColors(),
        )
        Text(
            item.sources.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(SourcesColumnWidth).padding(horizontal = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun cellTextStyle(checked: Boolean): TextStyle =
    if (checked) TextStyle(textDecoration = TextDecoration.LineThrough) else TextStyle.Default
