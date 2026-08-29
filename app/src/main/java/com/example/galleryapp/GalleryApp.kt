package com.example.galleryapp

import android.app.Application
import io.objectbox.BoxStore

// My database needs to be ready before any Activity, Fragment,
// or Service tries to use it. Application.onCreate() runs before any of those.
// Most modern ways of setting up a database are designed to be called "early and often":
// boxStore = MyObjectBox.Builder(...).build() is safe to call repeatedly —
// it just returns the same instance if it already exists.
class GalleryApp : Application() {
    companion object {
        lateinit var boxStore: BoxStore
            private set
    }

    override fun onCreate() {
        super.onCreate()
        // Initialize ObjectBox
        boxStore = MyObjectBox.builder()
            .androidContext(this) // When androidContext is passed to the ObjectBox builder, the database
            // engine is provided with the application's Android Context. ObjectBox uses this context to locate
            // the app's designated internal storage directory.
            .build()
    }
    // (MyObjectBox.builder().androidContext(this).build()) is a single binary file called data.mdb
}
// For accessing boxes, Inside MainActivity.kt, the app grabs references to specific entity boxes so it can interact with the respective tables.
// Checking for Duplicate Processing