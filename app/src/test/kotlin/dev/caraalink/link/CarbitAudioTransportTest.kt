package dev.caraalink.link

import org.junit.Assert.*
import org.junit.Test

class CarbitAudioTransportTest {
    private var time = 0L
    private var accept = true
    private val packets = mutableListOf<Pair<Int, ByteArray>>()
    private val audio = CarbitAudioTransport({ cmd, body ->
        if (accept) packets.add(cmd to body.copyOf())
        accept
    }, {}, { time })
    private val pcm = ByteArray(4096) { it.toByte() }

    @Test fun `requires music permission and start ACK before sending PCM`() {
        assertFalse(audio.playMusic(pcm))
        assertTrue(packets.isEmpty())
        audio.updateMusicAllowed(true)
        assertFalse(audio.playMusic(pcm))
        assertEquals(CarbitAudioTransport.START, packets.single().first)
        assertEquals("""{"type":4,"sampleRate":48000,"channel":2,"format":3}""", packets.single().second.toString(Charsets.UTF_8))
        assertFalse(audio.playMusic(pcm))
        audio.onReply(CarbitAudioTransport.START_ACK)
        assertTrue(audio.playMusic(pcm))
        assertEquals(CarbitAudioTransport.MUSIC, packets.last().first)
        assertArrayEquals(pcm, packets.last().second)
        audio.stopPlayback()
        assertEquals(CarbitAudioTransport.STOP, packets.last().first)
        assertEquals("""{"type":4}""", packets.last().second.toString(Charsets.UTF_8))
        assertFalse(audio.playMusic(pcm))
        audio.onReply(CarbitAudioTransport.STOP_ACK)
        assertFalse(audio.playMusic(pcm))
        assertEquals(CarbitAudioTransport.START, packets.last().first)
    }

    @Test fun `timeout and late ACK cannot enable audio until reconnect`() {
        audio.updateMusicAllowed(true)
        audio.playMusic(pcm)
        time = 1001L
        assertFalse(audio.playMusic(pcm))
        assertEquals(CarbitAudioTransport.STOP, packets.last().first)
        audio.onReply(CarbitAudioTransport.START_ACK)
        assertFalse(audio.playMusic(pcm))
        assertEquals(2, packets.size)
        audio.reset()
        assertFalse(audio.playMusic(pcm))
        audio.updateMusicAllowed(true)
        assertFalse(audio.playMusic(pcm))
        audio.onReply(CarbitAudioTransport.START_ACK)
        assertTrue(audio.playMusic(pcm))
    }

    @Test fun `disconnect backpressure and invalid PCM fall back without sending`() {
        audio.updateMusicAllowed(true)
        assertFalse(audio.playMusic(ByteArray(3)))
        assertFalse(audio.playMusic(ByteArray(32768)))
        accept = false
        assertFalse(audio.playMusic(pcm))
        assertTrue(packets.isEmpty())
        accept = true
        audio.playMusic(pcm)
        audio.onReply(CarbitAudioTransport.START_ACK)
        accept = false
        assertFalse(audio.playMusic(pcm))
        accept = true
        assertFalse(audio.playMusic(pcm))
        assertEquals(1, packets.size)
    }

    @Test fun `revoking permission stops playback`() {
        audio.updateMusicAllowed(true)
        audio.playMusic(pcm)
        audio.onReply(CarbitAudioTransport.START_ACK)
        audio.updateMusicAllowed(false)
        assertEquals(CarbitAudioTransport.STOP, packets.last().first)
        assertFalse(audio.playMusic(pcm))
    }

    @Test fun `speech streams have distinct types and serialized control acknowledgments`() {
        val tts = CarbitAudioTransport.Stream.TTS
        val vr = CarbitAudioTransport.Stream.VR
        val talkie = CarbitAudioTransport.Stream.TALKIE
        audio.updateCapabilities(setOf(tts, vr, talkie))
        assertFalse(audio.play(tts, pcm))
        assertEquals("""{"type":1,"sampleRate":16000,"channel":1,"format":3}""", packets.single().second.toString(Charsets.UTF_8))
        assertFalse(audio.play(vr, pcm))
        assertEquals(1, packets.size)
        audio.onReply(CarbitAudioTransport.STOP_ACK)
        assertFalse(audio.play(tts, pcm))
        audio.onReply(CarbitAudioTransport.START_ACK)
        assertTrue(audio.play(tts, pcm))
        assertEquals(0x60000000, packets.last().first)
        assertFalse(audio.play(vr, pcm))
        audio.onReply(CarbitAudioTransport.START_ACK)
        assertTrue(audio.play(vr, pcm))
        assertEquals(0x60000010, packets.last().first)
        assertFalse(audio.play(talkie, pcm))
        audio.onReply(CarbitAudioTransport.START_ACK)
        assertTrue(audio.play(talkie, pcm))
        assertEquals(0x60000020, packets.last().first)
        audio.stopStream(tts)
        assertEquals("""{"type":1}""", packets.last().second.toString(Charsets.UTF_8))
        audio.onReply(CarbitAudioTransport.STOP_ACK)
        assertTrue(audio.play(vr, pcm))
    }

    @Test fun `cancelled start stops after ACK without sending audio`() {
        audio.updateMusicAllowed(true)
        audio.startStream(CarbitAudioTransport.Stream.MUSIC)
        audio.stopPlayback()
        assertFalse(audio.playMusic(pcm))
        audio.onReply(CarbitAudioTransport.START_ACK)
        assertEquals(CarbitAudioTransport.STOP, packets.last().first)
        assertFalse(audio.playMusic(pcm))
        audio.onReply(CarbitAudioTransport.STOP_ACK)
        assertFalse(audio.playMusic(pcm))
        assertEquals(CarbitAudioTransport.START, packets.last().first)
    }

    @Test fun `microphone needs capability ACK and valid frames`() {
        assertFalse(audio.requestMic())
        audio.updateMicAllowed(true)
        assertTrue(audio.requestMic())
        assertEquals(CarbitAudioTransport.MIC_START, packets.single().first)
        assertEquals("""{"sampleRate":16000,"channel":1,"format":3}""", packets.single().second.toString(Charsets.UTF_8))
        audio.onMicData(pcm)
        assertNull(audio.pollMic(0))
        audio.onReply(CarbitAudioTransport.MIC_START_ACK)
        audio.onMicData(ByteArray(3))
        assertNull(audio.pollMic(0))
        audio.onMicData(pcm)
        assertArrayEquals(pcm, audio.pollMic(0))
        audio.stopMic()
        assertEquals(CarbitAudioTransport.MIC_STOP, packets.last().first)
        assertEquals(0, packets.last().second.size)
        audio.onMicData(pcm)
        assertNull(audio.pollMic(0))
        audio.onReply(CarbitAudioTransport.MIC_STOP_ACK)
        assertTrue(audio.requestMic())
    }

    @Test fun `mic queue bounded and reset discards recorded bytes`() {
        audio.updateMicAllowed(true)
        audio.requestMic()
        audio.onReply(CarbitAudioTransport.MIC_START_ACK)
        repeat(20) { audio.onMicData(byteArrayOf(it.toByte(), 0)) }
        assertEquals((12..19).toList(), (0 until 8).map { audio.pollMic(0)!![0].toInt() })
        assertNull(audio.pollMic(0))
        audio.onMicData(pcm)
        audio.reset()
        assertNull(audio.pollMic(0))
        assertFalse(audio.requestMic())
    }

    @Test fun `late mic ACK cannot revive cancelled request`() {
        audio.updateMicAllowed(true)
        audio.requestMic()
        audio.stopMic()
        audio.onReply(CarbitAudioTransport.MIC_START_ACK)
        audio.onMicData(pcm)
        assertNull(audio.pollMic(0))
        assertFalse(audio.requestMic())
    }
}
