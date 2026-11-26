package dev.caraalink.link

import dev.caraalink.diagnostics.HuCapture
import dev.caraalink.diagnostics.HuLiveData
import dev.caraalink.diagnostics.RawHuDump
import org.json.JSONObject

/** Receive-only vehicle diagnostics. Sends only SDK-confirmed session/config/heartbeat ACKs.
 * No unsolicited queries, CAN injection, or vehicle control.
 */
internal class InstrumentChannel(private val owner: Any) : AdbTransport.StreamHandler {
    private class State {
        val outer = InstrumentWire.Framer()
        val mcu = InstrumentWire.McuFramer()
        var channel: Int? = null
        var rejected = false
    }
    private val states = HashMap<Int, State>()
    fun listenerStatus(status: String) { HuLiveData.instrumentLink(owner, status) }
    override fun onOpen(stream: MirrorStream) {
        if (states.size >= 4) return
        states[stream.localId] = State()
        HuLiveData.instrumentLink(owner, "accepted")
        RawHuDump.meta("instrument", "open stream=${stream.localId}")
    }

    override fun onClose(stream: MirrorStream) {
        states.remove(stream.localId) ?: return
        HuLiveData.instrumentLink(owner, if (states.values.any { it.channel == InstrumentWire.C2P && !it.rejected })
            "receiving" else if (states.isEmpty()) "closed" else "waiting")
        RawHuDump.meta("instrument", "close stream=${stream.localId}")
    }

    override fun onData(stream: MirrorStream, data: ByteArray) {
        val state = states[stream.localId] ?: return
        if (state.rejected) return
        state.outer.feed(data) { frame ->
            if (!state.rejected) handle(stream, state, frame)
        }
        if (state.outer.failed) {
            state.rejected = true
            HuLiveData.instrumentLink(owner, "invalid-header")
            RawHuDump.meta("instrument", "invalid outer header stream=${stream.localId}")
        }
    }

    private fun handle(stream: MirrorStream, state: State, frame: InstrumentWire.Frame) {
        val cmd = frame.command
        HuCapture.record("Instrument", "RX", "0x" + cmd.toUInt().toString(16).padStart(8, '0'), frame.body)
        HuLiveData.instrumentPacket(owner, cmd, frame.body)
        if (state.channel == null) {
            // CMPServer initial 16-byte handshake: cmd+1, empty body, extra=0.
            if (cmd !in listOf(InstrumentWire.C2P, InstrumentWire.P2C) || frame.body.isNotEmpty() ||
                states.values.any { it !== state && !it.rejected && it.channel == cmd }) {
                state.rejected = true
                HuLiveData.instrumentLink(owner, "unsupported-or-duplicate-channel")
                return
            }
            state.channel = cmd
            reply(stream, InstrumentWire.encode(cmd + 1))
            HuLiveData.instrumentLink(owner, if (cmd == InstrumentWire.C2P) "channel-open" else "p2c-connected")
            return
        }
        if (state.channel != InstrumentWire.C2P) return
        when (cmd) {
            InstrumentWire.CONFIG -> {
                // Original parseConfigInfo copies up to 51 bytes. Avoid interpreting padded/missing fields.
                if (frame.body.size >= 27) {
                    val json = JSONObject()
                    val keys = listOf("CONFIG_PROJECT_TYPE", "CONFIG_MAX_SPEED", "CONFIG_WARNING_SPEED",
                        "CONFIG_MAX_RPM", "CONFIG_WARNING_RPM", "CONFIG_MAX_TEMP_WATER_LEVEL", "CONFIG_WARNING_TEMP_WATER_LEVEL")
                    keys.forEachIndexed { i, key -> json.put(key, frame.body[20 + i].toInt() and 255) }
                    HuLiveData.instrumentFields(owner, "config", json)
                    reply(stream, InstrumentWire.configReply(frame.extra))
                } else RawHuDump.meta("instrument", "short config: ${frame.body.size}")
            }
            InstrumentWire.MXC -> {
                val bad = state.mcu.badCrc
                val discarded = state.mcu.discarded
                state.mcu.feed(frame.body) { mcu ->
                    HuLiveData.instrumentMcu(owner, mcu)
                }
                if (bad != state.mcu.badCrc || discarded != state.mcu.discarded) {
                    RawHuDump.meta("instrument/mcu", "badCRC=${state.mcu.badCrc} discarded=${state.mcu.discarded}")
                    HuLiveData.instrumentFields(owner, "decoder", JSONObject()
                        .put("badCrc", state.mcu.badCrc).put("discarded", state.mcu.discarded))
                }
            }
            InstrumentWire.OVERSPEED -> if (frame.body.isNotEmpty()) HuLiveData.instrumentFields(owner, "config",
                JSONObject().put("CONFIG_OVERSPEED_WARNING", frame.body[0].toInt() and 255))
            InstrumentWire.HEARTBEAT -> if (frame.body.isEmpty()) reply(stream, InstrumentWire.encode(cmd + 1, frame.extra))
        }
    }
    private fun reply(stream: MirrorStream, bytes: ByteArray) {
        if (stream.closed) return
        stream.write(bytes)
        HuCapture.record("Instrument", "TX_BUILT", "reply", bytes)
    }
}
