package com.example.pwalibrary.shortcut

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.pwalibrary.R
import com.example.pwalibrary.data.AppEntity
import com.example.pwalibrary.install.IconStore
import com.example.pwalibrary.web.WebAppActivity
import java.io.File

object ShortcutHelper {

    fun shortcutIdFor(uuid: String) = "app-$uuid"

    /** Some launchers (and most work profiles) simply do not support pinning. */
    fun isPinSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Asks the launcher to pin a home screen icon for [app].
     *
     * Returns false when pinning is unsupported. A true result only means the
     * request was accepted — the launcher shows its own confirmation, and there
     * is no callback telling us what the user chose.
     */
    fun requestPin(context: Context, app: AppEntity): Boolean {
        if (!isPinSupported(context)) return false

        val bitmap = IconStore.adaptiveBitmap(app.iconPath?.let { File(it) }, app.name)
        val label = app.shortName.ifBlank { app.name }

        val shortcut = ShortcutInfoCompat.Builder(context, shortcutIdFor(app.uuid))
            .setShortLabel(label.take(20))
            .setLongLabel(app.name.take(40))
            .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
            .setIntent(WebAppActivity.intentFor(context, app.uuid))
            .build()

        return ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
    }

    /**
     * Android gives no way to remove a shortcut the user pinned themselves, so
     * deleting an app can only grey the icon out with an explanation. The user
     * removes it from the home screen manually.
     */
    fun disable(context: Context, uuid: String) {
        runCatching {
            ShortcutManagerCompat.disableShortcuts(
                context,
                listOf(shortcutIdFor(uuid)),
                context.getString(R.string.shortcut_disabled)
            )
        }
        runCatching {
            ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(shortcutIdFor(uuid)))
        }
    }

    /** Refreshes the pinned icon and label after an update. */
    fun refresh(context: Context, app: AppEntity) {
        if (app.shortcutId == null) return
        val bitmap = IconStore.adaptiveBitmap(app.iconPath?.let { File(it) }, app.name)
        val shortcut = ShortcutInfoCompat.Builder(context, shortcutIdFor(app.uuid))
            .setShortLabel(app.shortName.ifBlank { app.name }.take(20))
            .setLongLabel(app.name.take(40))
            .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
            .setIntent(WebAppActivity.intentFor(context, app.uuid))
            .build()
        runCatching { ShortcutManagerCompat.updateShortcuts(context, listOf(shortcut)) }
    }
}
