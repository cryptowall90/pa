package com.pictureperfectx.app.data

import kotlinx.coroutines.flow.Flow

/** Thin repository over [PhotoDao] so the ViewModel never touches Room directly. */
class PhotoRepository(private val dao: PhotoDao) {

    val photos: Flow<List<PhotoEntity>> = dao.observeAll()
    val latest: Flow<PhotoEntity?> = dao.observeLatest()

    suspend fun record(photo: PhotoEntity): Long = dao.insert(photo)

    /** The gallery row for a photo the editor was handed, or null if it isn't one of ours. */
    suspend fun findByUri(uri: String): PhotoEntity? = dao.findByUri(uri)

    suspend fun remove(id: Long) = dao.delete(id)
}
