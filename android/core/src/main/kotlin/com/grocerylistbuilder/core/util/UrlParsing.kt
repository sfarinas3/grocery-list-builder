package com.grocerylistbuilder.core.util

private val SPLIT_PATTERN = Regex("""[\s,]+""")

/**
 * Pull recipe URLs out of free text, separated by newlines, commas, or spaces (mirrors
 * app.py `parse_urls`). Forgiving on purpose — paste a whole block however it's formatted.
 * Keeps only http(s) links and de-duplicates while preserving order.
 */
fun parseUrls(text: String): List<String> {
    val seen = LinkedHashSet<String>()
    for (token in text.trim().split(SPLIT_PATTERN)) {
        if ((token.startsWith("http://") || token.startsWith("https://"))) {
            seen.add(token)
        }
    }
    return seen.toList()
}
