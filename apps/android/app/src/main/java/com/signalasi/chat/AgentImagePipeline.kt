package com.signalasi.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

internal data class AgentTransportImage(
    val bytes: ByteArray,
    val mimeType: String,
    val lossless: Boolean,
    val width: Int = 0,
    val height: Int = 0
) {
    fun transportName(originalName: String): String {
        if (lossless || mimeType != "image/jpeg") return originalName
        val stem = originalName.substringBeforeLast('.', originalName).ifBlank { "image" }
        return "$stem.jpg"
    }
}

internal object AgentImagePipeline {
    const val TARGET_TRANSPORT_BYTES = 300 * 1024

    private const val MAX_TRANSPORT_DIMENSION = 2_400
    private const val MIN_TRANSPORT_DIMENSION = 240
    private const val MIN_TRANSPORT_BUDGET = 12 * 1024
    private const val MIN_JPEG_QUALITY = 35
    private const val MAX_JPEG_QUALITY = 95

    fun loadPreview(context: Context, uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? =
        decodeOriented(context, uri, maxWidth.coerceAtLeast(1), maxHeight.coerceAtLeast(1))

    fun encodeForTransport(
        context: Context,
        attachment: AgentInputAttachment,
        byteLimit: Int = TARGET_TRANSPORT_BYTES
    ): AgentTransportImage? {
        val target = minOf(byteLimit, TARGET_TRANSPORT_BYTES)
        if (target < MIN_TRANSPORT_BUDGET) return null

        readOriginalWithinLimit(context, attachment, target)?.let { original ->
            return AgentTransportImage(
                bytes = original,
                mimeType = attachment.mimeType.ifBlank { "image/jpeg" },
                lossless = true
            )
        }

        var working = decodeOriented(
            context,
            attachment.uri,
            MAX_TRANSPORT_DIMENSION,
            MAX_TRANSPORT_DIMENSION
        ) ?: return null
        working = flattenForJpeg(working)
        try {
            var attempt = 0
            while (attempt < 8) {
                bestJpegWithinLimit(working, target)?.let { bytes ->
                    return AgentTransportImage(bytes, "image/jpeg", false, working.width, working.height)
                }
                if (max(working.width, working.height) <= MIN_TRANSPORT_DIMENSION) break
                val next = scaleBitmap(working, 0.8f)
                if (next === working) break
                working.recycle()
                working = next
                attempt++
            }
            val fallback = encodeJpeg(working, 25)
            return fallback.takeIf { it.size <= target }?.let {
                AgentTransportImage(it, "image/jpeg", false, working.width, working.height)
            }
        } finally {
            working.recycle()
        }
    }

    internal fun sampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        var sample = 1
        while (
            sourceWidth / (sample * 2) >= targetWidth ||
            sourceHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun readOriginalWithinLimit(
        context: Context,
        attachment: AgentInputAttachment,
        limit: Int
    ): ByteArray? {
        if (attachment.sizeBytes > limit) return null
        return runCatching {
            context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    if (output.size() + read > limit) return@use null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.getOrNull()
    }

    private fun decodeOriented(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: return@runCatching null

        val orientation = readOrientation(context, uri)
        val oriented = applyOrientation(bitmap, orientation)
        if (oriented !== bitmap) bitmap.recycle()
        bitmap = oriented

        val scale = minOf(
            1f,
            targetWidth.toFloat() / bitmap.width.coerceAtLeast(1),
            targetHeight.toFloat() / bitmap.height.coerceAtLeast(1)
        )
        if (scale < 0.999f) {
            val scaled = scaleBitmap(bitmap, scale)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        bitmap
    }.getOrNull()

    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun flattenForJpeg(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawBitmap(bitmap, 0f, 0f, null)
            setHasAlpha(false)
        }.also { bitmap.recycle() }
    }

    private fun scaleBitmap(bitmap: Bitmap, factor: Float): Bitmap {
        val width = (bitmap.width * factor).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * factor).roundToInt().coerceAtLeast(1)
        if (width == bitmap.width && height == bitmap.height) return bitmap
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun bestJpegWithinLimit(bitmap: Bitmap, target: Int): ByteArray? {
        var low = MIN_JPEG_QUALITY
        var high = MAX_JPEG_QUALITY
        var best: ByteArray? = null
        while (low <= high) {
            val quality = (low + high) / 2
            val candidate = encodeJpeg(bitmap, quality)
            if (candidate.size <= target) {
                best = candidate
                low = quality + 1
            } else {
                high = quality - 1
            }
        }
        return best
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
}
