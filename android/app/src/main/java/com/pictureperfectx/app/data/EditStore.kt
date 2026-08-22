package com.pictureperfectx.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.pictureperfectx.app.layers.EditDocument
import java.io.File
import java.security.MessageDigest

/**
 * The layer stacks behind saved photos.
 *
 * A stack is a blob and the gallery index is a table, so these live as app-private files rather than
 * a column — the database stays small and quick to query whether or not anyone ever reopens an edit.
 * Same reasoning and same shape as `ProxyStore`, including living under `filesDir` rather than the
 * cache: the system evicting one of these would silently turn a re-editable photo into a flat one.
 */
object EditStore {

    private const val TAG = "EditStore"
    private const val DIRECTORY = "edits"
    private const val DRAFTS = "drafts"
    private const val EXTENSION = "ppx"

    /**
     * A filename for the edit in progress on [sourceUri].
     *
     * A URI is full of characters a filename can't hold, and is far too long besides, so it is
     * digested rather than sanitised. Stable across launches — which is the whole requirement, since
     * this is how an unfinished edit is found again tomorrow.
     */
    fun keyFor(sourceUri: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(sourceUri.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Saves the edit in progress on [sourceUri], replacing any earlier one. */
    fun writeDraft(context: Context, sourceUri: String, edit: EditDocument): Boolean = runCatching {
        val directory = File(context.filesDir, DRAFTS).apply { mkdirs() }
        File(directory, "${keyFor(sourceUri)}.$EXTENSION").writeText(EditDocument.encode(edit))
        true
    }.onFailure { Log.e(TAG, "Could not write a draft for $sourceUri", it) }.getOrDefault(false)

    /** The unfinished edit on [sourceUri], if there is one. */
    fun readDraft(context: Context, sourceUri: String): EditDocument? = runCatching {
        val file = File(File(context.filesDir, DRAFTS), "${keyFor(sourceUri)}.$EXTENSION")
        if (file.exists()) EditDocument.decode(file.readText()) else null
    }.onFailure { Log.e(TAG, "Could not read the draft for $sourceUri", it) }.getOrNull()

    /**
     * Forgets the draft on [sourceUri].
     *
     * Called when a save lands: the work has arrived somewhere permanent, and a draft left behind
     * would resurrect an older state the next time this photo was opened.
     */
    fun deleteDraft(context: Context, sourceUri: String) {
        runCatching {
            val file = File(File(context.filesDir, DRAFTS), "${keyFor(sourceUri)}.$EXTENSION")
            if (file.exists()) file.delete()
        }.onFailure { Log.e(TAG, "Could not delete the draft for $sourceUri", it) }
    }

    /**
     * Writes [edit] and returns a URI for it, or null if it couldn't be stored.
     *
     * Null rather than throwing: failing to record the stack must not fail the save. The photo is
     * what the user asked for, and losing the ability to reopen it is the lesser outcome.
     */
    fun write(context: Context, baseName: String, edit: EditDocument): String? = runCatching {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val file = File(directory, "$baseName.$EXTENSION")
        file.writeText(EditDocument.encode(edit))
        Uri.fromFile(file).toString()
    }.onFailure { Log.e(TAG, "Could not write the edit for $baseName", it) }.getOrNull()

    /** Reads the stack at [editUri], or null if it is missing, unreadable or not an edit. */
    fun read(context: Context, editUri: String?): EditDocument? {
        val file = fileFor(context, editUri) ?: return null
        return runCatching {
            if (file.exists()) EditDocument.decode(file.readText()) else null
        }.onFailure { Log.e(TAG, "Could not read the edit at $editUri", it) }.getOrNull()
    }

    /** Deletes the stack behind [editUri]; safe to call with null or a URI pointing elsewhere. */
    fun delete(context: Context, editUri: String?) {
        val file = fileFor(context, editUri) ?: return
        runCatching { if (file.exists()) file.delete() }
            .onFailure { Log.e(TAG, "Could not delete the edit at $editUri", it) }
    }

    /** Resolves [editUri] to a file, refusing anything outside our own directory. */
    private fun fileFor(context: Context, editUri: String?): File? {
        val path = editUri?.let { runCatching { Uri.parse(it).path }.getOrNull() } ?: return null
        return runCatching {
            val file = File(path)
            val directory = File(context.filesDir, DIRECTORY).canonicalPath
            // Only ever touch our own scratch, never something in shared storage.
            if (file.canonicalPath.startsWith(directory)) file else null
        }.getOrNull()
    }
}
