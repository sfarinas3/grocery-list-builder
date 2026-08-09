package com.grocerylistbuilder.core.extract.crf

/** Mirrors Python's `Token`+`TokenFeatures` dataclasses (`ingredient_parser/dataclasses.py`),
 * flattened into one class since nothing in the Kotlin port needs them split. */
data class Token(
    val index: Int,
    val text: String,
    val featText: String,
    val posTag: String,
    val stem: String,
    val shape: String,
    val isCapitalised: Boolean,
    val isUnit: Boolean,
    val isPunc: Boolean,
    val isAmbiguousUnit: Boolean,
)
