package com.signalasi.chat

import android.content.Context
import android.media.MediaPlayer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MicrosoftEdgeTts(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private var player: MediaPlayer? = null

    fun speak(text: String, voice: String, onDone: (Boolean, String?) -> Unit) {
        if (text.isBlank()) {
            onDone(true, null)
            return
        }
        Thread {
            val result = runCatching {
                val audio = synthesize(text, voice)
                playAudio(audio)
            }
            if (result.isSuccess) {
                onDone(true, null)
            } else {
                onDone(false, result.exceptionOrNull()?.message)
            }
        }.start()
    }

    fun stop() {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
    }

    fun shutdown() {
        stop()
        client.dispatcher.executorService.shutdown()
    }

    private fun synthesize(text: String, voice: String): ByteArray {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var failure: Throwable? = null
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
            "&ConnectionId=$requestId"
        val request = Request.Builder()
            .url(url)
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("User-Agent", "Mozilla/5.0 SignalASI Android")
            .build()

        val webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(speechConfigMessage(requestId))
                webSocket.send(ssmlMessage(requestId, text, voice))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                val headerEnd = findHeaderEnd(raw)
                val payload = if (headerEnd >= 0) raw.copyOfRange(headerEnd, raw.size) else raw
                if (payload.isNotEmpty()) audio.write(payload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end", ignoreCase = true)) {
                    done.countDown()
                    webSocket.close(1000, "done")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure = t
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }
        })

        if (!done.await(30, TimeUnit.SECONDS)) {
            webSocket.cancel()
            error("Microsoft TTS timeout")
        }
        failure?.let { throw it }
        val data = audio.toByteArray()
        if (data.isEmpty()) error("Microsoft TTS returned empty audio")
        return data
    }

    private fun playAudio(audio: ByteArray) {
        val file = File(context.cacheDir, "signalasi_tts_${System.currentTimeMillis()}.mp3")
        file.writeBytes(audio)
        val latch = CountDownLatch(1)
        val mp = MediaPlayer()
        player = mp
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            file.delete()
            latch.countDown()
        }
        mp.setOnErrorListener { mediaPlayer, _, _ ->
            mediaPlayer.release()
            if (player === mediaPlayer) player = null
            file.delete()
            latch.countDown()
            true
        }
        mp.prepare()
        mp.start()
        latch.await(90, TimeUnit.SECONDS)
    }

    private fun speechConfigMessage(requestId: String): String {
        return "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n" +
            "X-RequestId:$requestId\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":false,"wordBoundaryEnabled":false},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
    }

    private fun ssmlMessage(requestId: String, text: String, voice: String): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        val ssml = """<speak version="1.0" xml:lang="zh-CN"><voice name="$voice">$escaped</voice></speak>"""
        return "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "Path:ssml\r\n" +
            "X-RequestId:$requestId\r\n\r\n" +
            ssml
    }

    private fun timestamp(): String =
        java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US))

    private fun findHeaderEnd(raw: ByteArray): Int {
        for (i in 0 until raw.size - 3) {
            if (raw[i] == '\r'.code.toByte() &&
                raw[i + 1] == '\n'.code.toByte() &&
                raw[i + 2] == '\r'.code.toByte() &&
                raw[i + 3] == '\n'.code.toByte()
            ) {
                return i + 4
            }
        }
        return -1
    }
}
