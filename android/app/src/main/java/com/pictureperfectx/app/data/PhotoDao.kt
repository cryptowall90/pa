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

    /**
     * The row for a photo, by the URI it is displayed and edited under.
     *
     * `displayUri` isn't a column — a RAW shot is edited through its proxy — so this matches either
     * side, which is what lets the editor answer "does the photo I was handed have a layer stack?"
     * from a bare URI.
     */
    @Query("SELECT * FROM photos WHERE uri = :uri OR proxyUri = :uri ORDER BY createdAt DESC LIMIT 1")
    suspend fun findByUri(uri: String): PhotoEntity?

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun delete(id: Long)
}
