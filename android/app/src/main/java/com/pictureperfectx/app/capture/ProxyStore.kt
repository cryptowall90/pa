package com.pictureperfectx.app.capture

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * App-private full-quality stand-ins for RAW captures.
 *
 * A DNG written by Android carries a preview of at most 256px (`DngCreator.MAX_THUMBNAIL_DIMENSION`),
 * and phones that can't demosaic RAW have nothing sharper to show. So a RAW-only shot also writes a
 * normal JPEG here — invisible in the phone's gallery, but enough to display and edit the photo at
 * full resolution.
 *
 * These are the app's own scratch files, not the user's photos: they live and die with the gallery
 * entry that points at them, and they sit under `filesDir` rather than the cache so the system
 * can't quietly evict them out from under a photo the user kept.
 */
object ProxyStore {

    private const val TAG = "ProxyStore"
    private const val DIRECTORY = "raw_proxies"

    /** A file to write a capture's proxy into, or null if app storage isn't usable. */
    fun newProxyFile(context: Context, baseName: String): File? = runCatching {
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        File(directory, "$baseName.jpg")
    }.onFailure { Log.e(TAG, "Could not create a proxy file", it) }.getOrNull()

    /** Deletes the proxy behind [proxyUri]; safe to call with null or a non-proxy URI. */
    fun delete(context: Context, proxyUri: String?) {
        val path = proxyUri?.let { runCatching { Uri.parse(it).path }.getOrNull() } ?: return
        runCatching {
            val file = File(path)
            // Only ever remove our own scratch, never something in shared storage.
            if (file.startsWithProxyDirectory(context) && file.exists()) file.delete()
        }.onFailure { Log.e(TAG, "Could not delete proxy $proxyUri", it) }
    }

    private fun File.startsWithProxyDirectory(context: Context): Boolean =
        runCatching {
            canonicalPath.startsWith(File(context.filesDir, DIRECTORY).canonicalPath)
        }.getOrDefault(false)
}
