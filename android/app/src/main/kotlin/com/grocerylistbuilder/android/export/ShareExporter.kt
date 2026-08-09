package com.grocerylistbuilder.android.export

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands the list off to another app instead of sending it from inside this one (replaces
 * app.py's in-app SMTP `send_email` — no mail credentials stored on-device).
 */
object ShareExporter {

    fun share(context: Context, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Grocery List")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(Intent.createChooser(intent, "Share grocery list"))
    }

    /** Jumps straight to the default mail app's compose screen; falls back to the share sheet. */
    fun email(context: Context, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_SUBJECT, "Grocery List")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            share(context, body)
        }
    }
}
