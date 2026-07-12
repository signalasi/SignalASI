package com.signalasi.chat

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

data class WhisperModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeLabel: String,
    val bundled: Boolean = false
)

data class WhisperModelDownloadState(
    val status: Int,
    val progress: Int = 0
)

object WhisperModelManager {
    private const val PREFS = "signalasi_whisper_models"
    private const val KEY_DOWNLOAD_PREFIX = "download_id_"
    private const val MIRROR_ROOT = "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main"

    val models = listOf(
        WhisperModel("tiny", "Tiny", "ggml-tiny.bin", "75 MB"),
        WhisperModel("base", "Base", "ggml-base.bin", "142 MB", bundled = true),
        WhisperModel("small", "Small", "ggml-small.bin", "466 MB"),
        WhisperModel("medium", "Medium", "ggml-medium.bin", "1.5 GB"),
        WhisperModel("large", "Large", "ggml-large-v3.bin", "3.1 GB")
    )

    fun model(id: String): WhisperModel = models.firstOrNull { it.id == id } ?: models[1]

    fun downloadedFile(context: Context, model: WhisperModel): File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
        "signalasi-asr/${model.fileName}"
    )

    fun isAvailable(context: Context, model: WhisperModel): Boolean {
        if (model.bundled) return true
        val file = downloadedFile(context, model)
        return file.isFile && file.length() > 1_000_000L &&
            downloadState(context, model).status == DownloadManager.STATUS_SUCCESSFUL
    }

    fun enqueue(context: Context, model: WhisperModel): Long {
        require(!model.bundled) { "Bundled models do not need downloading" }
        val current = downloadState(context, model)
        if (current.status == DownloadManager.STATUS_PENDING || current.status == DownloadManager.STATUS_RUNNING ||
            current.status == DownloadManager.STATUS_PAUSED) {
            return downloadId(context, model)
        }
        downloadedFile(context, model).delete()
        val request = DownloadManager.Request(Uri.parse("$MIRROR_ROOT/${model.fileName}"))
            .setTitle("SignalASI ASR - ${model.displayName}")
            .setDescription(model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "signalasi-asr/${model.fileName}"
            )
        val id = (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_DOWNLOAD_PREFIX + model.id, id)
            .apply()
        return id
    }

    fun downloadState(context: Context, model: WhisperModel): WhisperModelDownloadState {
        if (model.bundled) return WhisperModelDownloadState(DownloadManager.STATUS_SUCCESSFUL, 100)
        val id = downloadId(context, model)
        if (id <= 0L) return WhisperModelDownloadState(0)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return runCatching {
            manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                if (!cursor.moveToFirst()) return@use WhisperModelDownloadState(0)
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val progress = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                WhisperModelDownloadState(status, progress)
            }
        }.getOrDefault(WhisperModelDownloadState(0))
    }

    fun delete(context: Context, model: WhisperModel) {
        if (model.bundled) return
        val id = downloadId(context, model)
        if (id > 0L) {
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        }
        downloadedFile(context, model).delete()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_DOWNLOAD_PREFIX + model.id)
            .apply()
    }

    private fun downloadId(context: Context, model: WhisperModel): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_DOWNLOAD_PREFIX + model.id, -1L)
}
