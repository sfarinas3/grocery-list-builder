package com.grocerylistbuilder.android

import android.app.Application
import com.grocerylistbuilder.android.di.AppContainer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class GroceryApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // pdfbox-android needs its font/resource assets unpacked once before first use
        // (grocery/ingest/documents.py's pypdf needs no such step — an Android-only wrinkle).
        PDFBoxResourceLoader.init(applicationContext)
        container = AppContainer(applicationContext)
    }
}
