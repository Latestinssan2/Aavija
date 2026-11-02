package com.preet.aavija

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Where a received file is written, and how to KEEP it or THROW IT AWAY.
 *
 * On Android 10+ (API 29) an app cannot write straight into the public
 * Downloads folder. The supported route is MediaStore, which both makes the
 * file show up in the Downloads app and lets us delete our own row when the
 * hash check fails. On older versions we use the plain file path (which needs
 * the user to grant the storage permission once).
 *
 * Nothing here ever writes outside `Downloads/GestureShare` — the filename has
 * already been checked by [FileTransfer.safeName] before a sink is opened.
 */
interface ReceiveSink {
    /** The stream to write the file body into. */
    val output: OutputStream
    /** Keep the file (it passed the hash check). */
    fun commit(): Uri?
    /** Delete the half-written file (a failure). */
    fun abort()
    /** Where it landed, for the UI. */
    val displayPath: String
}

object ReceivedFiles {
    /** The one folder Aavija is allowed to write into. */
    const val SUBDIR = "GestureShare"

    /** Human-readable folder name, e.g. for the UI. */
    const val DISPLAY_FOLDER = "Downloads/$SUBDIR"

    fun open(context: Context, filename: String): ReceiveSink =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreSink(context, filename)
        } else {
            @Suppress("DEPRECATION")
            LegacyFileSink(filename)
        }

    /**
     * Is this something the phone can show in the new photo viewer?
     *
     * Deliberately extension-based and pure, so it is cheap to call on the
     * receive thread and easy to unit-test. MediaStore still owns the real
     * MIME type; this only decides whether to open the full-screen viewer.
     */
    fun isImageFile(filename: String): Boolean =
        mimeTypeFor(filename).startsWith("image/")

    /** A rough MIME type from the extension — MediaStore shows a nicer icon. */
    fun mimeTypeFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "zip" -> "application/zip"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }
}

/**
 * API 29+ route: insert a pending row in MediaStore, stream into it, then
 * clear IS_PENDING so it becomes visible. A failure deletes the row, so a
 * broken transfer never leaves a stray file.
 */
private class MediaStoreSink(context: Context, private val filename: String) : ReceiveSink {

    private val resolver = context.contentResolver
    private var closed = false

    private val uri: Uri = run {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, ReceivedFiles.mimeTypeFor(filename))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/${ReceivedFiles.SUBDIR}",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused to create the file")
    }

    override val output: OutputStream =
        resolver.openOutputStream(uri) ?: throw IOException("could not open the new file")

    override val displayPath: String = "${ReceivedFiles.DISPLAY_FOLDER}/$filename"

    override fun commit(): Uri {
        closeOutput()
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        resolver.update(uri, values, null, null)
        return uri
    }

    override fun abort() {
        closeOutput()
        runCatching { resolver.delete(uri, null, null) }
    }

    private fun closeOutput() {
        if (closed) return
        closed = true
        runCatching { output.close() }
    }
}

/** Pre-Q route: a plain file under the public Downloads folder. */
@Suppress("DEPRECATION")
private class LegacyFileSink(filename: String) : ReceiveSink {

    private val dir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        ReceivedFiles.SUBDIR,
    ).apply { mkdirs() }

    private val file = File(dir, filename)
    private var closed = false

    override val output: OutputStream = FileOutputStream(file)

    override val displayPath: String = file.absolutePath

    override fun commit(): Uri {
        closeOutput()
        // A file:// URI is enough for our own in-app viewer on pre-Q devices.
        return Uri.fromFile(file)
    }

    override fun abort() {
        closeOutput()
        runCatching { file.delete() }
    }

    private fun closeOutput() {
        if (closed) return
        closed = true
        runCatching { output.close() }
    }
}
