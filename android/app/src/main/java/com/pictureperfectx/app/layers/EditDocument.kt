package com.pictureperfectx.app.layers

import com.pictureperfectx.app.capture.ImageGeometry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A whole edit, ready to be written down: how the photo is framed, and the stack applied to it.
 *
 * The layer engine has always stored *descriptions* rather than pixels — that is what makes undo a
 * stack of snapshots and what makes the preview and the export agree. This is the same property
 * cashed in one more time: a description can be saved, so an edit no longer has to end when the
 * photo is exported.
 */
@Serializable
data class EditDocument(
    val geometry: ImageGeometry = ImageGeometry(),
    val document: Document = Document(),
    /**
     * What wrote this, stamped by [encode]. Zero means a file from before stamping existed.
     *
     * Defaults aren't written out, so this deliberately defaults to something [encode] never uses —
     * a version equal to the default would be omitted from every file and tell us nothing.
     */
    val version: Int = 0,
) {
    companion object {
        const val VERSION = 1

        /**
         * `ignoreUnknownKeys` and leaning on defaults are what make a saved edit survive the app
         * being updated: a stack written by a build that had one more slider still opens, and one
         * written by an older build fills the gap from the layer's own default rather than failing.
         */
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        fun encode(edit: EditDocument): String = json.encodeToString(edit.copy(version = VERSION))

        /**
         * Reads an edit back, or null if the text isn't one.
         *
         * Null rather than an exception because of what the caller does with it: a corrupt or
         * future-version stack should open the photo with no layers and a notice, not take the
         * editor down.
         */
        fun decode(text: String): EditDocument? =
            runCatching { json.decodeFromString<EditDocument>(text) }.getOrNull()
    }
}
