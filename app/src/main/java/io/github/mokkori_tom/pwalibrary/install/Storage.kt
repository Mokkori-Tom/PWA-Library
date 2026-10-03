package io.github.mokkori_tom.pwalibrary.install

import android.content.Context
import java.io.File

/**
 * Layout under /data/data/<pkg>/files:
 *
 *   apps/<uuid>/        extracted mini-app, served by WebViewAssetLoader
 *   originals/<uuid>.zip  the zip exactly as imported, kept for re-sharing
 *   icons/<uuid>.png    normalised 192px icon derived from the app
 *   icons/<uuid>-custom.png  icon the user picked, when they picked one
 */
object Storage {

    fun appsRoot(context: Context) = File(context.filesDir, "apps").apply { mkdirs() }

    fun appDir(context: Context, uuid: String) = File(appsRoot(context), uuid)

    fun stagingDir(context: Context, uuid: String) = File(appsRoot(context), ".staging-$uuid")

    fun originalsRoot(context: Context) = File(context.filesDir, "originals").apply { mkdirs() }

    fun originalZip(context: Context, uuid: String) = File(originalsRoot(context), "$uuid.zip")

    fun iconsRoot(context: Context) = File(context.filesDir, "icons").apply { mkdirs() }

    fun iconFile(context: Context, uuid: String) = File(iconsRoot(context), "$uuid.png")

    /**
     * The icon the user chose, kept alongside the derived one rather than
     * overwriting it. An import can then keep the derived icon current without
     * touching the choice, and clearing the choice has something to fall back to.
     */
    fun customIconFile(context: Context, uuid: String) =
        File(iconsRoot(context), "$uuid-custom.png")

    /** Staging area for share intents; exposed through FileProvider. */
    fun shareRoot(context: Context) = File(context.cacheDir, "share").apply { mkdirs() }

    fun deleteAllFor(context: Context, uuid: String) {
        appDir(context, uuid).deleteRecursively()
        stagingDir(context, uuid).deleteRecursively()
        originalZip(context, uuid).delete()
        iconFile(context, uuid).delete()
        customIconFile(context, uuid).delete()
    }
}
