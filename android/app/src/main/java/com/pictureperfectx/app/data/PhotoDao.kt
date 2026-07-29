package com.pictureperfectx.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Insert
    suspend fun insert(photo: PhotoEntity): Long

    @Query("SELECT * FROM photos ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(): Flow<PhotoEntity?>

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun delete(id: Long)
}
