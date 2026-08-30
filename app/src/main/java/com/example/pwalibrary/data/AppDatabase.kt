package com.example.pwalibrary.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AppEntity::class, FolderGrant::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appDao(): AppDao

    abstract fun folderGrantDao(): FolderGrantDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apps ADD COLUMN import_name TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE apps ADD COLUMN name_is_custom INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE apps ADD COLUMN icon_is_custom INTEGER NOT NULL DEFAULT 0")
                // Nothing could rename an app before this version, so every
                // existing row's name is the one its zip declared. Without the
                // backfill, update matching on a relative manifest id would
                // compare against an empty string and stop recognising apps it
                // has always recognised.
                db.execSQL("UPDATE apps SET import_name = name")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Must match Room's generated schema exactly, or validation on
                // the next open fails.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_grants` (" +
                        "`id` TEXT NOT NULL, `app_uuid` TEXT NOT NULL, " +
                        "`tree_uri` TEXT NOT NULL, `display_name` TEXT NOT NULL, " +
                        "`granted_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folder_grants_app_uuid` " +
                        "ON `folder_grants` (`app_uuid`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE apps ADD COLUMN zip_sha256 TEXT")
                // Name must match what Room generates for @Index, or the schema
                // validation on the next open fails.
                db.execSQL("CREATE INDEX IF NOT EXISTS index_apps_zip_sha256 ON apps(zip_sha256)")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE apps ADD COLUMN needs_storage_reset INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pwa_library.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { instance = it }
        }
    }
}
