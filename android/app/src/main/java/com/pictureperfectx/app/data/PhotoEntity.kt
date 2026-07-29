package com.pictureperfectx.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata for one captured photo. The image bytes live in MediaStore; this table is the app's
 * own index over them — enough to build a gallery and remember which look produced each shot.
 */
@Entity(tableName = "photos")
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val filterId: String,
    val filterName: String,
    val lensFacing: String,
    val width: Int,
    val height: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
