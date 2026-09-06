package com.galaxyssi.chat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.util.Enumeration

/** Private attachment files are stored byte-for-byte; transport encryption is independent. */
internal object AttachmentLocalStore {
    data class Metadata(val plaintextLength: Long)

    fun storeFile(source: File, destination: File) {
        require(source.isFile) { "Attachment source is unavailable" }
        if (source.canonicalFile == destination.canonicalFile) return
        source.inputStream().buffered().use { storeStream(it, source.length(), destination) }
    }

    fun storeBytes(bytes: ByteArray, destination: File) {
        bytes.inputStream().use { storeStream(it, bytes.size.toLong(), destination) }
    }

    fun storeStream(input: InputStream, plaintextLength: Long, destination: File,
                    onPlaintext: ((ByteArray, Int) -> Unit)? = null) {
        require(plaintextLength >= 0L) { "Attachment length is invalid" }
        destination.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        val temporary = File.createTempFile(".attachment-", ".storing", destination.parentFile)
        try {
            FileOutputStream(temporary).use { fileOutput ->
                val output = fileOutput.buffered()
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                try {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        require(count.toLong() <= plaintextLength - copied) { "Attachment source exceeded its declared length" }
                        onPlaintext?.invoke(buffer, count)
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    require(copied == plaintextLength) { "Attachment source was truncated" }
                    output.flush()
                    fileOutput.fd.sync()
                } finally { buffer.fill(0) }
            }
            java.nio.file.Files.move(temporary.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun metadata(file: File): Metadata {
        require(file.isFile) { "Attachment is unavailable" }
        return Metadata(file.length())
    }

    fun isStored(file: File): Boolean = file.isFile
    fun openInput(file: File): InputStream = file.inputStream().buffered()
    fun readBytes(file: File): ByteArray = file.readBytes()
    fun copyTo(file: File, output: OutputStream): Long = openInput(file).use { it.copyTo(output, 64 * 1024) }

    fun openSequence(files: Iterable<File>): InputStream {
        val remaining = files.iterator()
        return SequenceInputStream(object : Enumeration<InputStream> {
            override fun hasMoreElements(): Boolean = remaining.hasNext()
            override fun nextElement(): InputStream = openInput(remaining.next())
        })
    }

}

internal object LocalAttachmentUris {
    private const val AUTHORITY_SUFFIX = ".local-attachments"
    private const val PATH_FILE = "file"
    private val roots = setOf("peer-incoming-attachments-v2", "peer-message-attachments-v2",
        "agent-link-outgoing-attachments-v2", "agent-rich-output-v2", "desktop-artifacts-v2")

    fun forFile(context: Context, file: File, name: String = file.name, mimeType: String = ""): Uri {
        val root = context.filesDir.canonicalFile
        val canonical = file.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "Attachment is outside private storage" }
        require(canonical.isFile) { "Attachment is unavailable" }
        val relative = root.toPath().relativize(canonical.toPath()).toString().replace('\\', '/')
        require(relative.substringBefore('/') in roots) { "Not an attachment directory" }
        val encoded = Base64.encodeToString(relative.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        return Uri.Builder().scheme("content").authority(context.packageName + AUTHORITY_SUFFIX)
            .appendPath(PATH_FILE).appendPath(encoded).appendQueryParameter("name", name.take(160))
            .apply { if (mimeType.isNotBlank()) appendQueryParameter("mime", mimeType.take(160)) }.build()
    }

    fun resolve(context: Context, uri: Uri): File? {
        if (uri.authority != context.packageName + AUTHORITY_SUFFIX || uri.pathSegments.size != 2 ||
            uri.pathSegments.firstOrNull() != PATH_FILE) return null
        val relative = runCatching {
            String(Base64.decode(uri.pathSegments[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val root = context.filesDir.canonicalFile
        val file = runCatching { File(root, relative).canonicalFile }.getOrNull() ?: return null
        if (!file.toPath().startsWith(root.toPath())) return null
        val resolved = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
        return file.takeIf { it.isFile && resolved.substringBefore('/') in roots }
    }
}

class LocalAttachmentContentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Attachments are read-only" }
        val file = LocalAttachmentUris.resolve(requireNotNull(context).applicationContext, uri)
            ?: throw java.io.FileNotFoundException("Attachment is unavailable")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String? = uri.getQueryParameter("mime")
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = LocalAttachmentUris.resolve(context?.applicationContext ?: return null, uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            val row = newRow()
            columns.forEach { column -> row.add(when (column) {
                OpenableColumns.DISPLAY_NAME -> uri.getQueryParameter("name") ?: file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }) }
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
