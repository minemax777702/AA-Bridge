package dev.caraalink.emulator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

internal class PcmOutput(rate: Int, channels: Int, speech: Boolean) : AutoCloseable {
    private val queue = ArrayBlockingQueue<ByteArray>(16)
    private val running = AtomicBoolean(true)
    private val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(if (speech) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE else AudioAttributes.USAGE_MEDIA)
            .setContentType(if (speech) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096) * 2)
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    init {
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack initialization failed")
        }
    }
    private val worker = Thread({
        try {
            track.play()
            while (running.get()) {
                val pcm = queue.take()
                var offset = 0
                while (running.get() && offset < pcm.size) {
                    val n = track.write(pcm, offset, pcm.size - offset)
                    if (n <= 0) break
                    offset += n
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }, "HU-audio").apply { isDaemon = true; start() }
    fun feed(pcm: ByteArray) {
        if (!running.get() || pcm.isEmpty()) return
        if (!queue.offer(pcm)) { queue.poll(); queue.offer(pcm) }
    }
    override fun close() {
        running.set(false)
        queue.clear()
        worker.interrupt()
    }
}

internal class MicInput(context: Context, private val send: (ByteArray) -> Unit) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val record: AudioRecord
    init {
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0)
        val candidate = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min.coerceAtLeast(2560))
        try {
            check(candidate.state == AudioRecord.STATE_INITIALIZED)
            candidate.startRecording()
            check(candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            record = candidate
        } catch (e: Exception) {
            candidate.release()
            throw e
        }
    }
    private val worker = Thread({
        val data = ByteArray(640)
        try {
            while (running.get()) {
                val n = record.read(data, 0, data.size)
                if (n < 0) break
                if (n > 0 && running.get()) send(data.copyOf(n))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }, "HU-mic").apply { isDaemon = true; start() }
    override fun close() {
        if (running.compareAndSet(true, false)) {
            runCatching { record.stop() }
            worker.interrupt()
        }
    }
}
