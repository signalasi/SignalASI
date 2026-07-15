package com.signalasi.chat

object AgentAnimatedImageTiming {
    private const val GIF_DELAY_CENTISECONDS = 8

    fun normalizeZeroFrameDelays(source: ByteArray): ByteArray {
        if (!source.isGif()) return source
        var output: ByteArray? = null
        var index = 0
        while (index <= source.size - 8) {
            val isGraphicControlExtension =
                source[index] == 0x21.toByte() &&
                    source[index + 1] == 0xF9.toByte() &&
                    source[index + 2] == 0x04.toByte()
            if (!isGraphicControlExtension) {
                index++
                continue
            }
            val delay = (source[index + 4].toInt() and 0xFF) or
                ((source[index + 5].toInt() and 0xFF) shl 8)
            if (delay == 0) {
                if (output == null) output = source.copyOf()
                output[index + 4] = GIF_DELAY_CENTISECONDS.toByte()
                output[index + 5] = 0
            }
            index += 8
        }
        return output ?: source
    }

    private fun ByteArray.isGif(): Boolean = size >= 6 &&
        this[0] == 'G'.code.toByte() &&
        this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() &&
        this[3] == '8'.code.toByte() &&
        (this[4] == '7'.code.toByte() || this[4] == '9'.code.toByte()) &&
        this[5] == 'a'.code.toByte()
}
