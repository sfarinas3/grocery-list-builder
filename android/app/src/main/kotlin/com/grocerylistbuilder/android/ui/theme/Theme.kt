package com.grocerylistbuilder.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = GroceryGreen40,
    secondary = GroceryGreenGrey40,
    tertiary = GroceryAmber40,
)

private val DarkColors = darkColorScheme(
    primary = GroceryGreen80,
    secondary = GroceryGreenGrey80,
    tertiary = GroceryAmber80,
)

@Composable
fun GroceryListBuilderTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
