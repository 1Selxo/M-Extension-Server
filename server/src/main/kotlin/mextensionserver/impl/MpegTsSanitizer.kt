package mextensionserver.impl

internal object MpegTsSanitizer {
    private const val PACKET_SIZE = 188
    private const val SYNC_BYTE = 0x47.toByte()
    private const val MIN_SYNC_RUN = 4
    private const val MAX_RESYNC_DISTANCE = 8 * 1024

    fun repair(data: ByteArray): ByteArray {
        if (data.size < PACKET_SIZE * MIN_SYNC_RUN) return data

        // Some extension CDNs prepend a complete 1x1 PNG (and sometimes the
        // tail of a partial TS packet) before every otherwise-valid segment.
        // Find the first stable packet grid instead of letting media players
        // identify the whole response as an image.
        val firstSync =
            if (data[0] == SYNC_BYTE) {
                0
            } else {
                findNextSyncRun(data, 0)
            }
        if (firstSync < 0) return data
        val input = if (firstSync == 0) data else data.copyOfRange(firstSync, data.size)
        if (isAligned(input)) return input

        val packets = mutableListOf<ByteArray>()
        val discardUntilPayloadStart = mutableSetOf<Int>()
        var cursor = 0
        var repaired = firstSync > 0
        while (cursor + PACKET_SIZE <= input.size) {
            val hasCompleteNextPacket = cursor + (PACKET_SIZE * 2) <= input.size
            if (input[cursor] == SYNC_BYTE &&
                (!hasCompleteNextPacket || input[cursor + PACKET_SIZE] == SYNC_BYTE)
            ) {
                val packet = input.copyOfRange(cursor, cursor + PACKET_SIZE)
                val pid = packet.pid()
                if (pid !in discardUntilPayloadStart || packet.isPayloadStart()) {
                    discardUntilPayloadStart.remove(pid)
                    packets += packet
                } else {
                    repaired = true
                }
                cursor += PACKET_SIZE
                continue
            }

            // The injected block can begin inside the current packet. Do not
            // preserve that packet merely because its leading sync byte is
            // intact. Remove its whole PES unit so a truncated AAC frame is
            // never forwarded to the decoder.
            if (input[cursor] == SYNC_BYTE && cursor + 3 < input.size) {
                val damagedPid = input.pidAt(cursor)
                discardCurrentPes(packets, damagedPid)
                discardUntilPayloadStart += damagedPid
            }
            val nextSync = findNextSyncRun(input, cursor + 1)
            if (nextSync < 0) return data
            repaired = true
            cursor = nextSync
        }

        val result = ByteArray(packets.size * PACKET_SIZE)
        packets.forEachIndexed { index, packet ->
            packet.copyInto(result, index * PACKET_SIZE)
        }
        return if (repaired && isAligned(result)) result else data
    }

    private fun discardCurrentPes(
        packets: MutableList<ByteArray>,
        pid: Int,
    ) {
        var reachedPayloadStart = false
        for (index in packets.lastIndex downTo 0) {
            val packet = packets[index]
            if (packet.pid() != pid) continue
            packets.removeAt(index)
            if (packet.isPayloadStart()) {
                reachedPayloadStart = true
                break
            }
        }
        if (!reachedPayloadStart) {
            packets.removeAll { it.pid() == pid }
        }
    }

    private fun ByteArray.pid(): Int = pidAt(0)

    private fun ByteArray.pidAt(offset: Int): Int = ((this[offset + 1].toInt() and 0x1F) shl 8) or (this[offset + 2].toInt() and 0xFF)

    private fun ByteArray.isPayloadStart(): Boolean = (this[1].toInt() and 0x40) != 0

    private fun isAligned(data: ByteArray): Boolean {
        if (data.isEmpty() || data.size % PACKET_SIZE != 0) return false
        return (data.indices step PACKET_SIZE).all { data[it] == SYNC_BYTE }
    }

    private fun findNextSyncRun(
        data: ByteArray,
        start: Int,
    ): Int {
        val lastCandidate =
            minOf(
                data.size - (PACKET_SIZE * MIN_SYNC_RUN),
                start + MAX_RESYNC_DISTANCE,
            )
        for (candidate in start..lastCandidate) {
            if ((0 until MIN_SYNC_RUN).all { data[candidate + (it * PACKET_SIZE)] == SYNC_BYTE }) {
                return candidate
            }
        }
        return -1
    }
}
