package com.pictureperfectx.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PhotoEntity::class], version = 1, exportSchema = true)
abstract class PicturePerfectDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var instance: PicturePerfectDatabase? = null

        fun get(context: Context): PicturePerfectDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PicturePerfectDatabase::class.java,
                    "picture_perfect.db",
                ).build().also { instance = it }
            }
    }
}
