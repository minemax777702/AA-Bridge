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
}
