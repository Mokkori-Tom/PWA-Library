package io.github.mokkori_tom.pwalibrary.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "apps",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["manifest_id"]),
        Index(value = ["zip_sha256"])
    ]
)
data class AppEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Stable per-app identifier, generated once at first import and kept across
     * updates. The mini-app's web origin is derived from this, so changing it
     * would orphan the app's localStorage / IndexedDB.
     */
    val uuid: String,

    /** `id` (or `name`) from manifest.json. Used to recognise re-imports as updates. */
    @ColumnInfo(name = "manifest_id")
    val manifestId: String? = null,

    val name: String,

    /**
     * The name the zip declared for itself, untouched by any renaming.
     *
     * Update matching compares against this. A relative manifest id is only
     * distinctive when paired with a name, and pairing it with a name the user
     * has since changed would stop recognising the app's own updates.
     */
    @ColumnInfo(name = "import_name", defaultValue = "''")
    val importName: String = "",

    /** The user set [name]. An import must not overwrite it. */
    @ColumnInfo(name = "name_is_custom", defaultValue = "0")
    val nameIsCustom: Boolean = false,

    @ColumnInfo(name = "short_name")
    val shortName: String = "",

    val description: String = "",

    /** Absolute path of the extracted icon PNG, or null to fall back to a generated one. */
    @ColumnInfo(name = "icon_path")
    val iconPath: String? = null,

    /** The user chose the icon at [iconPath]. An import must not overwrite it. */
    @ColumnInfo(name = "icon_is_custom", defaultValue = "0")
    val iconIsCustom: Boolean = false,

    /** Entry point relative to the app root, e.g. "index.html" or "sub/start.html". */
    @ColumnInfo(name = "start_url")
    val startUrl: String = "index.html",

    /** manifest.json `display`: fullscreen | standalone | minimal-ui | browser */
    @ColumnInfo(name = "display_mode")
    val displayMode: String = "standalone",

    /** manifest.json `theme_color` as #RRGGBB, or null. */
    @ColumnInfo(name = "theme_color")
    val themeColor: String? = null,

    @ColumnInfo(name = "zip_file_path")
    val zipFilePath: String,

    @ColumnInfo(name = "app_dir_path")
    val appDirPath: String,

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long = 0,

    /**
     * SHA-256 of the imported zip. Identity of last resort: a zip with no
     * manifest carries nothing else to recognise it by, so re-importing one
     * this app exported would otherwise add a second copy every time.
     */
    @ColumnInfo(name = "zip_sha256")
    val zipSha256: String? = null,

    /** Non-null once the user has pinned this app to the launcher. */
    @ColumnInfo(name = "shortcut_id")
    val shortcutId: String? = null,

    /**
     * Set when an update replaced the files. The next launch routes through the
     * reset page to clear Service Worker caches bound to this app's origin,
     * which otherwise keep serving the previous bundle.
     */
    @ColumnInfo(name = "needs_storage_reset", defaultValue = "0")
    val needsStorageReset: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    val version: String = "1.0",

    val category: String = "",

    /** JSON array. Normalise into a join table if Phase 4 search needs it. */
    val tags: String = "[]"
)
