package dev.caraalink.diagnostics

/** Aligned byte differences; no guessing data types, units or CAN identifiers. */
internal object HuPacketDiff {
    fun hex(data: ByteArray, limit: Int): String =
        data.take(limit).joinToString(" ") { "%02X".format(it.toInt() and 255) } +
            if (data.size > limit) " … (${data.size} bytes)" else ""

    fun describe(before: ByteArray, after: ByteArray): String {
        val changes = mutableListOf<String>()
        var count = 0
        for (i in 0 until minOf(before.size, after.size)) {
            if (before[i] != after[i]) {
                count++
                if (changes.size < 32) changes.add("0x%04X:%02X→%02X".format(i, before[i].toInt() and 255, after[i].toInt() and 255))
            }
        }
        val tail = when {
            after.size > before.size -> "; appended@" + before.size + ": " + hex(after.copyOfRange(before.size, after.size), 32)
            before.size > after.size -> "; removed@" + after.size + ": " + hex(before.copyOfRange(after.size, before.size), 32)
            else -> ""
        }
        return "len ${before.size}→${after.size}; changed aligned bytes=$count; " +
            changes.joinToString(", ") + (if (count > changes.size) "; +${count - changes.size} more" else "") + tail
    }
}
