package com.grocerylistbuilder.core.models

/** The assembled list the user shops from (mirrors grocery/models.py GroceryList). */
data class GroceryList(val items: List<GroceryItem> = emptyList()) {

    /**
     * Items grouped by shopping category, insertion order of categories following first
     * appearance in [items] — computed on access so it can never drift out of sync, same as the
     * Python `by_category` property.
     */
    val byCategory: Map<String, List<GroceryItem>>
        get() = items.groupBy { it.category }
}
