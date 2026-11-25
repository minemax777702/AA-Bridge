package dev.caraalink.diagnostics

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Passive comparison of control-message inventories, not proof of missing functionality. */
internal object HuTraceCompare {
    const val MAX_BYTES = 2*1024*1024
    data class Key(val direction: String,val channel: String,val opcode: Int) {
        override fun toString() = "$direction $channel 0x" + opcode.toUInt().toString(16)
    }
    data class Trace(val commands: Map<Key,Set<Int>>, val recognized: Int, val truncated: Boolean)
    data class Difference(val key: Key,val reference: Set<Int>?,val bridge: Set<Int>?)
    private val canonical = Regex("""\b(?:HU\s+)?(RX|TX)\s+(PXC|MIRROR(?:-DATA)?|QD-BIN|QD-JSON|QD-HU)[:\s]+0x([0-9a-f]{1,8})\s+len=(\d+)""",RegexOption.IGNORE_CASE)
    private val pxc = Regex("""\bPXC\s+(C2P|P2C\s+reply|P2C\s+send)\s+0x([0-9a-f]{1,8})\s+len=(\d+)""",RegexOption.IGNORE_CASE)
    private val mirror = Regex("""\bMIRROR\s+ctrl\s+0x([0-9a-f]{1,8})\s+len=(\d+)""",RegexOption.IGNORE_CASE)

    fun read(input: InputStream): Trace {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (bytes.size() <= MAX_BYTES) {
            val n = input.read(buffer,0,minOf(buffer.size, MAX_BYTES+1-bytes.size()))
            if (n <= 0) break
            bytes.write(buffer,0,n)
        }
        val data = bytes.toByteArray()
        return parse(String(data,0,minOf(data.size,MAX_BYTES),Charsets.UTF_8),data.size>MAX_BYTES)
    }

    fun parse(text: String, truncated: Boolean = false): Trace {
        val commands = linkedMapOf<Key,MutableSet<Int>>()
        var recognized = 0
        var inventoryLimited = false
        for (line in text.lineSequence().take(30000)) {
            val match = canonical.find(line)
            val legacy = if (match == null) pxc.find(line) else null
            val mir = if (match == null && legacy == null) mirror.find(line) else null
            val (direction,channel,opcode,length) = when {
                match != null -> listOf(match.groupValues[1],match.groupValues[2],match.groupValues[3],match.groupValues[4])
                legacy != null -> listOf(if (legacy.groupValues[1].endsWith("send",true)) "TX" else "RX","PXC",legacy.groupValues[2],legacy.groupValues[3])
                mir != null -> listOf("RX","MIRROR",mir.groupValues[1],mir.groupValues[2])
                else -> continue
            }
            val code = opcode.toUIntOrNull(16)?.toInt() ?: continue
            val len = length.toIntOrNull()?.takeIf { it in 0..4*1024*1024 } ?: continue
            val ch = channel.uppercase(Locale.ROOT)
            if (ch == "PXC" && ((code and 0xff000000.toInt()) == 0x60000000 ||
                    code in listOf(0x10060,0x10061,0x70000000,0x70000001))) continue
            if (ch == "MIRROR" && code in listOf(0x20,0x40,0x41,0x72)) continue
            val key = Key(direction.uppercase(Locale.ROOT),ch,code)
            if (commands.size >= 512 && key !in commands) { inventoryLimited = true; continue }
            val lengths = commands.getOrPut(key) { linkedSetOf() }
            if (lengths.size < 32) lengths.add(len) else if (len !in lengths) inventoryLimited = true
            recognized++
        }
        return Trace(commands.mapValues { it.value.toSet() },recognized,truncated || inventoryLimited || text.count { it == '\n' } >= 30000)
    }

    fun compare(reference: Trace, bridge: Trace): List<Difference> =
        (reference.commands.keys + bridge.commands.keys).mapNotNull { key ->
            val r = reference.commands[key]; val b = bridge.commands[key]
            if (r == b) null else Difference(key,r,b)
        }.sortedBy { it.key.toString() }
}
