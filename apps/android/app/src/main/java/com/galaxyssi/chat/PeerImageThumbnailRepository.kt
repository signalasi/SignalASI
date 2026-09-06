package com.galaxyssi.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

internal object PeerImageThumbnailPolicy {
    fun cacheIdentity(attachment: PeerChatAttachment): String =
        attachment.uri.ifBlank { attachment.artifactUri }
            .ifBlank { attachment.transferId }
            .ifBlank { attachment.sha256 }
            .ifBlank { "${attachment.name}:${attachment.sizeBytes}" }
}

internal object PeerImageThumbnailRepository {
    private const val CACHE_TTL_MILLIS = 30_000L
    private const val MIN_CACHE_BYTES = 4 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024
    private const val MAX_STORED_THUMBNAIL_BYTES = 100_000
    private const val MIN_JPEG_QUALITY = 42
    private const val MAX_JPEG_QUALITY = 84
    private const val THUMBNAIL_FILE = ".peer-image-thumbnail-v1.sasie"

    private data class CacheEntry(val bitmap: Bitmap, val expiresAt: Long)
    private data class PendingLoad(
        val generation: Long,
        val callbacks: MutableList<(Bitmap?) -> Unit>
    )

    private val generation = AtomicLong(0L)
    private val threadNumber = AtomicInteger(0)
    private val executor = Executors.newFixedThreadPool(2, ThreadFactory { task ->
        Thread(task, "galaxyssi-peer-thumbnail-${threadNumber.incrementAndGet()}").apply {
            isDaemon = true
        }
    })
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingLock = Any()
    private val thumbnailWriteLock = Any()
    private val pending = mutableMapOf<String, PendingLoad>()
    private val cache = object : LruCache<String, CacheEntry>(cacheCapacityBytes()) {
        override fun sizeOf(key: String, value: CacheEntry): Int =
            value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    fun load(
        context: Context,
        attachment: PeerChatAttachment,
        maxWidth: Int,
        maxHeight: Int,
        onResult: (Bitmap?) -> Unit
    ) {
        val original = attachment.resolvedUri(context) ?: run {
            onResult(null)
            return
        }
        val key = cacheKey(attachment, maxWidth, maxHeight)
        cached(key)?.let {
            onResult(it)
            return
        }
        val currentGeneration = generation.get()
        val shouldStart = synchronized(pendingLock) {
            val current = pending[key]
            if (current != null && current.generation == currentGeneration) {
                current.callbacks += onResult
                false
            } else {
                pending[key] = PendingLoad(currentGeneration, mutableListOf(onResult))
                true
            }
        }
        if (!shouldStart) return

        val appContext = context.applicationContext
        executor.execute {
            val encryptedThumbnail = encryptedThumbnailUri(appContext, original)
            var bitmap = AgentImagePipeline.loadPreview(
                appContext,
                encryptedThumbnail ?: original,
                maxWidth.coerceAtLeast(1),
                maxHeight.coerceAtLeast(1)
            )
            if (bitmap == null && encryptedThumbnail != null) {
                encryptedThumbnailFile(appContext, original)?.delete()
                bitmap = AgentImagePipeline.loadPreview(
                    appContext,
                    original,
                    maxWidth.coerceAtLeast(1),
                    maxHeight.coerceAtLeast(1)
                )
            }
            if (bitmap != null && encryptedThumbnail == null) {
                runCatching {
                    persistEncryptedThumbnail(appContext, original, bitmap)
                }.onFailure { error ->
                    Log.w("GalaxySSIPeerImage", "Could not persist encrypted image thumbnail", error)
                }
            }

            val callbacks: List<(Bitmap?) -> Unit>? = synchronized(pendingLock) {
                val current = pending[key]
                if (current?.generation == currentGeneration) {
                    pending.remove(key)?.callbacks
                } else {
                    null
                }
            }
            if (callbacks == null || generation.get() != currentGeneration) {
                bitmap?.recycle()
                return@execute
            }
            bitmap?.let { cache.put(key, CacheEntry(it, expiryTime())) }
            mainHandler.post { callbacks.forEach { it(bitmap) } }
        }
    }

    fun remove(attachments: List<PeerChatAttachment>) {
        val prefixes = attachments.map { PeerImageThumbnailPolicy.cacheIdentity(it) + '\u0000' }.toSet()
        cache.snapshot().keys
            .filter { key -> prefixes.any(key::startsWith) }
            .forEach(cache::remove)
        synchronized(pendingLock) {
            pending.keys.filter { key -> prefixes.any(key::startsWith) }.forEach(pending::remove)
        }
    }

    fun clearRuntimeCache() {
        generation.incrementAndGet()
        cache.evictAll()
        synchronized(pendingLock) { pending.clear() }
    }

    private fun cacheKey(attachment: PeerChatAttachment, maxWidth: Int, maxHeight: Int): String =
        "${PeerImageThumbnailPolicy.cacheIdentity(attachment)}\u0000$maxWidth:$maxHeight"

    private fun cached(key: String): Bitmap? {
        val entry = cache.get(key) ?: return null
        if (entry.expiresAt >= SystemClock.elapsedRealtime()) return entry.bitmap
        cache.remove(key)
        return null
    }

    private fun encryptedThumbnailUri(context: Context, original: Uri): Uri? {
        val thumbnail = encryptedThumbnailFile(context, original)
            ?.takeIf(AttachmentLocalStore::isStored)
            ?: return null
        return LocalAttachmentUris.forFile(
            context,
            thumbnail,
            "thumbnail.jpg",
            "image/jpeg"
        )
    }

    private fun encryptedThumbnailFile(context: Context, original: Uri): File? =
        LocalAttachmentUris.resolve(context, original)?.parentFile?.let { File(it, THUMBNAIL_FILE) }

    private fun persistEncryptedThumbnail(context: Context, original: Uri, bitmap: Bitmap) {
        val destination = encryptedThumbnailFile(context, original) ?: return
        synchronized(thumbnailWriteLock) {
            if (AttachmentLocalStore.isStored(destination)) return
            val bytes = encodeStoredThumbnail(bitmap) ?: return
            try {
                AttachmentLocalStore.storeBytes(bytes, destination)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun encodeStoredThumbnail(bitmap: Bitmap): ByteArray? {
        var working = flattenForJpeg(bitmap)
        try {
            var attempt = 0
            while (attempt < 5) {
                bestJpegWithinLimit(working)?.let { return it }
                val nextWidth = (working.width * 0.82f).roundToInt().coerceAtLeast(1)
                val nextHeight = (working.height * 0.82f).roundToInt().coerceAtLeast(1)
                if (nextWidth == working.width && nextHeight == working.height) break
                val scaled = Bitmap.createScaledBitmap(
                    working,
                    nextWidth,
                    nextHeight,
                    true
                )
                if (scaled === working) break
                if (working !== bitmap && !working.isRecycled) working.recycle()
                working = scaled
                attempt++
            }
            val fallback = encodeJpeg(working, MIN_JPEG_QUALITY)
            return if (fallback.size <= MAX_STORED_THUMBNAIL_BYTES) {
                fallback
            } else {
                fallback.fill(0)
                null
            }
        } finally {
            if (working !== bitmap && !working.isRecycled) working.recycle()
        }
    }

    private fun bestJpegWithinLimit(bitmap: Bitmap): ByteArray? {
        var low = MIN_JPEG_QUALITY
        var high = MAX_JPEG_QUALITY
        var best: ByteArray? = null
        while (low <= high) {
            val quality = (low + high) / 2
            val candidate = encodeJpeg(bitmap, quality)
            if (candidate.size <= MAX_STORED_THUMBNAIL_BYTES) {
                best?.fill(0)
                best = candidate
                low = quality + 1
            } else {
                candidate.fill(0)
                high = quality - 1
            }
        }
        return best
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val output = WipingByteArrayOutputStream()
        return try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
            output.copyBytes()
        } finally {
            output.wipe()
        }
    }

    private fun flattenForJpeg(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawBitmap(bitmap, 0f, 0f, null)
            setHasAlpha(false)
        }
    }

    private fun expiryTime(): Long = SystemClock.elapsedRealtime() + CACHE_TTL_MILLIS

    private fun cacheCapacityBytes(): Int = (Runtime.getRuntime().maxMemory() / 16L)
        .coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong())
        .toInt()

    private class WipingByteArrayOutputStream : ByteArrayOutputStream() {
        fun copyBytes(): ByteArray = toByteArray()

        fun wipe() {
            buf.fill(0)
            reset()
        }
    }
}
