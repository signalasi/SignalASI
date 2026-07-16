package com.signalasi.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** App-owned destination used only by debug device tests for ContentResolver tools. */
class AgentNativeToolTestContentProvider : ContentProvider() {
    private val root: File by lazy {
        File(requireNotNull(context).cacheDir, "native-tool-test-content").apply { mkdirs() }
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = resolve(uri)
        file.parentFile?.mkdirs()
        val flags = when {
            'w' in mode -> ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_TRUNCATE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (resolve(uri).delete()) 1 else 0

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = MatrixCursor(arrayOf("_display_name", "_size")).apply {
        val file = resolve(uri)
        addRow(arrayOf(file.name, file.takeIf(File::exists)?.length() ?: 0L))
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private fun resolve(uri: Uri): File {
        val name = uri.lastPathSegment.orEmpty()
        require(name.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Invalid test content name" }
        return File(root, name)
    }
}
