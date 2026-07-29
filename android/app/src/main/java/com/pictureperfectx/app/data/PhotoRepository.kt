package com.pictureperfectx.app.data

import kotlinx.coroutines.flow.Flow

/** Thin repository over [PhotoDao] so the ViewModel never touches Room directly. */
class PhotoRepository(private val dao: PhotoDao) {

    val photos: Flow<List<PhotoEntity>> = dao.observeAll()
    val latest: Flow<PhotoEntity?> = dao.observeLatest()

    suspend fun record(photo: PhotoEntity): Long = dao.insert(photo)

    suspend fun remove(id: Long) = dao.delete(id)
}
