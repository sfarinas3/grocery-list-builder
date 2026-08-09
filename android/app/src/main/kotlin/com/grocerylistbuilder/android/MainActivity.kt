package com.grocerylistbuilder.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.grocerylistbuilder.android.ui.GroceryBuilderViewModel
import com.grocerylistbuilder.android.ui.GroceryBuilderViewModelFactory
import com.grocerylistbuilder.android.ui.screens.BuilderScreen
import com.grocerylistbuilder.android.ui.screens.GroceryListScreen
import com.grocerylistbuilder.android.ui.theme.GroceryListBuilderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as GroceryApp).container
        setContent {
            GroceryListBuilderTheme {
                GroceryListBuilderApp(viewModel(factory = GroceryBuilderViewModelFactory(container)))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroceryListBuilderApp(viewModel: GroceryBuilderViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("🛒 Grocery List Builder") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()),
        ) {
            BuilderScreen(
                state = state,
                onUrlsTextChange = viewModel::updateUrlsText,
                onAddDocuments = viewModel::addDocuments,
                onClearDocuments = viewModel::clearDocuments,
                onAddPhotos = viewModel::addPhotos,
                onClearPhotos = viewModel::clearPhotos,
                onBuild = viewModel::buildList,
            )

            state.editorItems?.let { items ->
                GroceryListScreen(
                    items = items,
                    recipeResults = state.recipeResults,
                    onItemChange = viewModel::updateItem,
                    onDelete = viewModel::deleteItem,
                    onAddItem = viewModel::addBlankItem,
                )
            }
        }
    }
}
