package com.grocerylistbuilder.core.ingest.web

import com.grocerylistbuilder.core.models.RecipeContent
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Fetch a recipe URL and turn it into a [RecipeContent] (mirrors grocery/ingest/web.py `fetch`).
 *
 * Two-tier strategy, best case first:
 *   1. schema.org/Recipe JSON-LD embedded in the page -> clean ingredient lines, no model needed.
 *   2. Readable page text otherwise -> [com.grocerylistbuilder.core.extract.crf.IngredientLineFinder]
 *      plus the Kotlin CRF port pull the lines out of it (see [com.grocerylistbuilder.core.extract.CrfExtractor]).
 *
 * On total failure, throws [IngestError] with a human-readable message so the UI can tell the
 * user to try a different source, rather than failing silently.
 *
 * Unlike `web.py`, no TLS-trust-store workaround is needed: that exists in Python only because
 * this project runs behind a TLS-inspecting corporate proxy whose root CA isn't in `httpx`'s
 * bundled `certifi` store. Android's default TLS stack already validates against the OS
 * certificate store, which a managed device already trusts.
 */
class WebFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun fetch(url: String): RecipeContent = withContext(Dispatchers.IO) {
        val html = fetchHtml(url)
        val document = Jsoup.parse(html, url)

        // Tier 1: structured data. Only trust it if it actually yields ingredient lines.
        val recipe = JsonLdParser.findRecipeInHtml(html)
        if (recipe != null) {
            val lines = RecipeFields.cleanLines(recipe["recipeIngredient"])
            if (lines.isNotEmpty()) {
                return@withContext RecipeContent(
                    sourceUrl = url,
                    title = RecipeFields.asText(recipe["name"]),
                    servings = RecipeFields.parseServings(recipe["recipeYield"]),
                    ingredientLines = lines,
                )
            }
        }

        // Tier 2: readable text fallback.
        val text = extractReadableText(document)
        if (text.isNotEmpty()) {
            return@withContext RecipeContent(sourceUrl = url, rawText = text)
        }

        throw IngestError(
            "Could not extract recipe content from $url. The page may block scraping or " +
                "render with JavaScript — try pasting the recipe manually.",
        )
    }

    /** GET the page as text, following redirects (OkHttp's default); wraps network errors. */
    private fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0 Mobile Safari/537.36",
            )
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IngestError("Failed to fetch $url: HTTP ${response.code}")
                }
                return response.body?.string()
                    ?: throw IngestError("Failed to fetch $url: empty response body")
            }
        } catch (e: IOException) {
            throw IngestError("Failed to fetch $url: ${e.message}", e)
        }
    }

    /**
     * Lightweight main-content heuristic: strip obviously non-content elements and return the
     * body text. Not as precise as Python's `trafilatura`, which does proper boilerplate removal
     * (nav/ads/related-posts scoring) — acceptable for Phase 1, where this text isn't consumed
     * yet (see class doc); revisit if Phase 2's structuring quality demands it.
     */
    private fun extractReadableText(document: Document): String {
        document.select("script, style, noscript, nav, header, footer, svg, form, iframe").remove()
        return document.body().text().trim()
    }
}
