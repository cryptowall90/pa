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
    /**
     * App-private full-resolution stand-in for a RAW capture. A DNG's embedded preview is capped at
     * 256px, so without this a phone that can't demosaic RAW has nothing sharp to show or edit.
     */
    val proxyUri: String? = null,
    /**
     * The photo this one was edited *from*, for a shot the Perfect Editor produced.
     *
     * Always the original rather than the previous export: editing an edited photo re-points at the
     * same source, so the chain stays one link long however many times it is revised, and every
     * revision starts from pixels that have only been through JPEG once.
     */
    val sourceUri: String? = null,
    /** The layer stack behind this photo, as an `EditStore` file. See [isEdited]. */
    val editUri: String? = null,
) {
    /** True when the shot wrote a DNG — drives the RAW badge in the gallery. */
    val isRaw: Boolean get() = rawUri != null

    /** True when this photo can be reopened with its layers rather than as flat pixels. */
    val isEdited: Boolean get() = editUri != null && sourceUri != null

    /**
     * True when the DNG *is* the photo. RAW-only captures have no JPEG, so [uri] points at the DNG
     * and the gallery has to fall back to the file's embedded preview to show anything.
     */
    val isRawOnly: Boolean get() = rawUri != null && rawUri == uri

    /** The sharpest image available for display and editing. */
    val displayUri: String get() = proxyUri ?: uri
}
