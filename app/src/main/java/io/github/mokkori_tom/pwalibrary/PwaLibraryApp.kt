package io.github.mokkori_tom.pwalibrary

import android.app.Application
import io.github.mokkori_tom.pwalibrary.data.AppDatabase
import io.github.mokkori_tom.pwalibrary.install.ZipInstaller

class PwaLibraryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Opening the database here keeps the first frame of the library free of
        // disk work; Room still does the real open lazily on the first query.
        AppDatabase.get(this)
        // A prompt that was killed mid-import leaves its staged zip behind.
        ZipInstaller(this).sweepStagedZips()
    }
}
