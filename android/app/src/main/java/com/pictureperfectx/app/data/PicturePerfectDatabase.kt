package com.pictureperfectx.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PhotoEntity::class], version = 2, exportSchema = true)
abstract class PicturePerfectDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        /**
         * v2 adds the companion-DNG URI for RAW captures. Nullable, so the column needs no default
         * and existing rows simply read as "not a RAW shot". Migrating rather than falling back
         * destructively keeps everyone's gallery index intact.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE photos ADD COLUMN rawUri TEXT")
            }
        }

        @Volatile
        private var instance: PicturePerfectDatabase? = null

        fun get(context: Context): PicturePerfectDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PicturePerfectDatabase::class.java,
                    "picture_perfect.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
