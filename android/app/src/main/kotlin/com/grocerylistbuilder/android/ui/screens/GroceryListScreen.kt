package com.grocerylistbuilder.android.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.grocerylistbuilder.android.export.CsvExporter
import com.grocerylistbuilder.android.export.ShareExporter
import com.grocerylistbuilder.android.export.TextExporter
import com.grocerylistbuilder.android.ui.RecipeResult
import com.grocerylistbuilder.android.ui.components.GroceryListEditor
import com.grocerylistbuilder.android.ui.components.RecipeBreakdownSection
import com.grocerylistbuilder.core.models.GroceryItem

/**
 * The grocery list section — editable table, progress, export/share, per-recipe breakdown, and
 * recipe links (mirrors the second half of app.py, from `st.header("🧾 Grocery list")` down).
 */
@Composable
fun GroceryListScreen(
    items: List<GroceryItem>,
    recipeResults: List<RecipeResult>,
    onItemChange: (Int, GroceryItem) -> Unit,
    onDelete: (Int) -> Unit,
    onAddItem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val recipeLinks = recipeResults.mapNotNull { r -> r.content.sourceUrl?.let { r.name to it } }

    val saveCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.writeText(context, CsvExporter.export(items, recipeLinks))
    }
    val saveTextLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri?.writeText(context, TextExporter.export(items, recipeLinks))
    }

    Column(modifier = modifier) {
        Text("🧾 Grocery list", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))

        GroceryListEditor(
            items = items,
            onItemChange = onItemChange,
            onDelete = onDelete,
            onAddItem = onAddItem,
        )

        val checked = items.count { it.checked }
        val total = items.size
        Column(modifier = Modifier.padding(16.dp)) {
            LinearProgressIndicator(
                progress = { if (total > 0) checked.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("$checked of $total items checked", style = MaterialTheme.typography.bodySmall)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            OutlinedButton(onClick = { saveCsvLauncher.launch("grocery_list.csv") }, modifier = Modifier.weight(1f)) {
                Text("⬇️ CSV")
            }
            OutlinedButton(onClick = { saveTextLauncher.launch("grocery_list.txt") }, modifier = Modifier.weight(1f)) {
                Text("⬇️ Text")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedButton(
                onClick = { ShareExporter.share(context, TextExporter.export(items, recipeLinks)) },
                modifier = Modifier.weight(1f),
            ) { Text("Share") }
            OutlinedButton(
                onClick = { ShareExporter.email(context, TextExporter.export(items, recipeLinks)) },
                modifier = Modifier.weight(1f),
            ) { Text("Email") }
        }
        Text(
            "⚠️ = flagged when built (low confidence, mixed units, or uncategorized).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        RecipeBreakdownSection(results = recipeResults)

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        Text("Recipes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Column(modifier = Modifier.padding(16.dp)) {
            recipeResults.forEach { result ->
                val url = result.content.sourceUrl
                if (url != null) {
                    Text(
                        result.name,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.padding(vertical = 4.dp).clickable { uriHandler.openUri(url) },
                    )
                } else {
                    Text(result.name, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

private fun Uri.writeText(context: android.content.Context, text: String) {
    context.contentResolver.openOutputStream(this)?.use { it.write(text.toByteArray()) }
}
