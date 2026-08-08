package com.pictureperfectx.app

import android.app.Application
import com.pictureperfectx.app.data.PhotoRepository
import com.pictureperfectx.app.data.PicturePerfectDatabase
import com.pictureperfectx.app.data.UserPrefs

/** App entry point. Owns the singleton database + repository (simple manual DI for a lean app). */
class PicturePerfectApp : Application() {

    val photoRepository: PhotoRepository by lazy {
        PhotoRepository(PicturePerfectDatabase.get(this).photoDao())
    }

    val userPrefs: UserPrefs by lazy { UserPrefs(this) }
}
