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
    /** MediaStore URI of the DNG this shot produced, or null for a plain JPEG capture. */
    val rawUri: String? = null,
) {
    /** True when the shot wrote a DNG — drives the RAW badge in the gallery. */
    val isRaw: Boolean get() = rawUri != null

    /**
     * True when the DNG *is* the photo. RAW-only captures have no JPEG, so [uri] points at the DNG
     * and the gallery has to fall back to the file's embedded preview to show anything.
     */
    val isRawOnly: Boolean get() = rawUri != null && rawUri == uri
}
