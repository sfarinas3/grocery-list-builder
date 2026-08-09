package com.grocerylistbuilder.core.util

import kotlin.math.roundToLong

/** "2.0" -> "2", "1.5" -> "1.5", null -> "" (mirrors app.py `_format_quantity`). */
fun formatQuantity(quantity: Double?): String {
    if (quantity == null) return ""
    val rounded = quantity.roundToLong()
    return if (quantity == rounded.toDouble()) rounded.toString() else quantity.toString()
}
