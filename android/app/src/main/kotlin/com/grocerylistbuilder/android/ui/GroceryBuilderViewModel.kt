package com.grocerylistbuilder.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.grocerylistbuilder.android.di.AppContainer
import com.grocerylistbuilder.core.aggregate.Combiner
import com.grocerylistbuilder.core.config.Config
import com.grocerylistbuilder.core.ingest.web.IngestError
import com.grocerylistbuilder.core.models.GroceryItem
import com.grocerylistbuilder.core.models.Ingredient
import com.grocerylistbuilder.core.models.RecipeContent
import com.grocerylistbuilder.core.pipeline.Pipeline
import com.grocerylistbuilder.core.util.parseUrls
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One successfully processed recipe source (URL or document) — mirrors app.py's `results` tuples. */
data class RecipeResult(val name: String, val content: RecipeContent, val ingredients: List<Ingredient>)

/** An uploaded document held in memory until "Build list" runs (mirrors `st.session_state.docs`). */
data class DocumentUpload(val filename: String, val bytes: ByteArray)

/** A captured/picked recipe photo held in memory until "Build list" runs. */
data class PhotoUpload(val displayName: String, val bytes: ByteArray)

data class BuilderUiState(
    val urlsText: String = "",
    val documents: List<DocumentUpload> = emptyList(),
    val photos: List<PhotoUpload> = emptyList(),
    val isBuilding: Boolean = false,
    val messages: List<String> = emptyList(),
    val editorItems: List<GroceryItem>? = null, // null until the first successful build
    val recipeResults: List<RecipeResult> = emptyList(),
    val buildId: Int = 0,
)

private const val DOC_LIMIT = 10

// Lower than DOC_LIMIT — each photo costs an OCR pass, not just a text read.
private const val PHOTO_LIMIT = 5

/**
 * Holds build/session state (mirrors `st.session_state` in app.py). Orchestrates
 * [Pipeline]/[Combiner] the same way `run_pipeline`/`run_document`/the "Build list" button
 * handler do in the Python app.
 */
class GroceryBuilderViewModel(private val container: AppContainer) : ViewModel() {

    private val _uiState = MutableStateFlow(BuilderUiState())
    val uiState: StateFlow<BuilderUiState> = _uiState.asStateFlow()

    fun updateUrlsText(text: String) {
        _uiState.update { it.copy(urlsText = text) }
    }

    fun addDocuments(newDocs: List<DocumentUpload>) {
        _uiState.update { state ->
            val room = DOC_LIMIT - state.documents.size
            state.copy(documents = state.documents + newDocs.take(room.coerceAtLeast(0)))
        }
    }

    fun clearDocuments() {
        _uiState.update { it.copy(documents = emptyList()) }
    }

    fun addPhotos(newPhotos: List<PhotoUpload>) {
        _uiState.update { state ->
            val room = PHOTO_LIMIT - state.photos.size
            state.copy(photos = state.photos + newPhotos.take(room.coerceAtLeast(0)))
        }
    }

    fun clearPhotos() {
        _uiState.update { it.copy(photos = emptyList()) }
    }

    fun dismissMessages() {
        _uiState.update { it.copy(messages = emptyList()) }
    }

    fun updateItem(index: Int, updated: GroceryItem) {
        _uiState.update { state ->
            val items = state.editorItems?.toMutableList() ?: return@update state
            items[index] = updated
            state.copy(editorItems = items)
        }
    }

    fun addBlankItem() {
        _uiState.update { state ->
            state.copy(editorItems = (state.editorItems ?: emptyList()) + GroceryItem(name = ""))
        }
    }

    fun deleteItem(index: Int) {
        _uiState.update { state ->
            val items = state.editorItems?.toMutableList() ?: return@update state
            items.removeAt(index)
            state.copy(editorItems = items)
        }
    }

    fun toggleChecked(index: Int, checked: Boolean) {
        _uiState.value.editorItems?.getOrNull(index)?.let { updateItem(index, it.copy(checked = checked)) }
    }

    fun buildList() {
        val state = _uiState.value
        val urls = parseUrls(state.urlsText)
        if (urls.isEmpty() && state.documents.isEmpty() && state.photos.isEmpty()) {
            _uiState.update {
                it.copy(messages = it.messages + "Paste a recipe URL, or add a document or photo, to build a list.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBuilding = true, messages = emptyList()) }
            val results = mutableListOf<RecipeResult>()
            val messages = mutableListOf<String>()

            val extractor = container.extractor

            for (url in urls) {
                try {
                    val (content, ingredients) = Pipeline.processUrl(url, container.webFetcher, extractor)
                    if (ingredients.isEmpty()) {
                        messages += "No ingredients found at $url — the page may be unsupported."
                    } else {
                        results += RecipeResult(content.title.ifEmpty { url }, content, ingredients)
                    }
                } catch (e: IngestError) {
                    messages += e.message.orEmpty()
                }
            }

            for (doc in state.documents) {
                try {
                    val (content, ingredients) =
                        Pipeline.processDocument(doc.bytes, doc.filename, container.documentTextExtractor, extractor)
                    if (ingredients.isEmpty()) {
                        messages += "No ingredients found in ${doc.filename} — it may not contain a recognizable recipe."
                    } else {
                        results += RecipeResult(doc.filename, content, ingredients)
                    }
                } catch (e: Exception) {
                    messages += "Couldn't read ${doc.filename}: ${e.message}"
                }
            }

            for (photo in state.photos) {
                try {
                    val (content, ingredients) =
                        Pipeline.processImage(photo.bytes, photo.displayName, container.ocrTextExtractor, extractor)
                    if (ingredients.isEmpty()) {
                        messages += "No ingredients read from ${photo.displayName} — try a clearer photo."
                    } else {
                        results += RecipeResult(photo.displayName, content, ingredients)
                    }
                } catch (e: Exception) {
                    messages += "Couldn't read ${photo.displayName}: ${e.message}"
                }
            }

            val order = Config.CATEGORIES.withIndex().associate { (i, c) -> c to i }
            val groceryList = Combiner.combine(results.map { it.name to it.ingredients })
            val sortedItems = groceryList.items.sortedWith(
                compareBy({ order[it.category] ?: order.size }, { it.name.lowercase() }),
            )

            _uiState.update {
                it.copy(
                    isBuilding = false,
                    messages = messages,
                    editorItems = if (results.isNotEmpty()) sortedItems else it.editorItems,
                    recipeResults = if (results.isNotEmpty()) results else it.recipeResults,
                    buildId = if (results.isNotEmpty()) it.buildId + 1 else it.buildId,
                )
            }
        }
    }
}

class GroceryBuilderViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GroceryBuilderViewModel(container) as T
}
