package com.grocerylistbuilder.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.grocerylistbuilder.android.ui.RecipeResult

/** Per-recipe breakdown (mirrors app.py's "Per-recipe breakdown" expander). */
@Composable
fun RecipeBreakdownSection(results: List<RecipeResult>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Per-recipe breakdown", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        results.forEach { result -> RecipeBreakdownCard(result) }
    }
}

@Composable
private fun RecipeBreakdownCard(result: RecipeResult) {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(modifier = Modifier.clickable { expanded = !expanded }.padding(12.dp)) {
            Text(result.name, style = MaterialTheme.typography.titleSmall)
            val source = if (result.content.ingredientLines.isNotEmpty()) "structured recipe data" else "structured from raw text"
            val bits = mutableListOf("${result.ingredients.size} ingredients", source)
            result.content.servings?.let { bits.add(0, "serves $it") }
            Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                result.ingredients.forEach { ingredient -> Text("• ${ingredient.name}", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
