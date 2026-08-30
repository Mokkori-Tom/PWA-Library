package com.example.pwalibrary

import android.app.Application
import com.example.pwalibrary.data.AppDatabase
import com.example.pwalibrary.install.ZipInstaller

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
