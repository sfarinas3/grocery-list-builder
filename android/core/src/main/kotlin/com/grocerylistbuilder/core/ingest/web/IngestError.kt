package com.grocerylistbuilder.core.ingest.web

/** Raised when a URL yields no usable recipe content (mirrors grocery/ingest/web.py IngestError). */
class IngestError(message: String, cause: Throwable? = null) : Exception(message, cause)
