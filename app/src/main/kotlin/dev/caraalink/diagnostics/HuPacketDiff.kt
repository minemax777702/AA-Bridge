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
        return "len ${before.size}→${after.size}; changed aligned bytes=$count; " +
            changes.joinToString(", ") + if (count > changes.size) "; +${count - changes.size} more" else ""
    }
}
