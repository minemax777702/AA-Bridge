package dev.caraalink.emulator

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/** Synchronous decoder owned exclusively by the video worker. */
internal class VideoDecoder(width: Int, height: Int, surface: Surface) : AutoCloseable {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val info = MediaCodec.BufferInfo()
    private var config = ByteArray(0)
    init {
        try {
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height), surface, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    fun feed(frame: ByteArray) {
        if (frame.isEmpty()) return
        val types = nalTypes(frame)
        if (types.isNotEmpty() && types.all { it == 7 || it == 8 || it == 9 }) {
            config = if (7 in types) frame else config + frame
            if (config.size > 64 * 1024) config = ByteArray(0)
            return
        }
        val payload = if (config.isNotEmpty() && 5 in types) config + frame else frame
        var index = codec.dequeueInputBuffer(20_000)
        while (index < 0) {
            drain()
            index = codec.dequeueInputBuffer(20_000)
            if (Thread.currentThread().isInterrupted) return
        }
        val buffer = checkNotNull(codec.getInputBuffer(index))
        buffer.clear()
        require(buffer.remaining() >= payload.size) { "Decoder input too small" }
        buffer.put(payload)
        codec.queueInputBuffer(index, 0, payload.size, System.nanoTime() / 1000, 0)
        drain()
    }

    private fun drain() {
        var index = codec.dequeueOutputBuffer(info, 0)
        while (index >= 0 || index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (index >= 0) codec.releaseOutputBuffer(index, true)
            index = codec.dequeueOutputBuffer(info, 0)
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        codec.release()
    }

    private fun nalTypes(data: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            val prefix = when {
                data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() -> 3
                i + 4 < data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                    data[i+2] == 0.toByte() && data[i+3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix == 0) i++ else {
                if (i + prefix < data.size) result.add(data[i + prefix].toInt() and 31)
                i += prefix + 1
            }
        }
        return result
    }
}
