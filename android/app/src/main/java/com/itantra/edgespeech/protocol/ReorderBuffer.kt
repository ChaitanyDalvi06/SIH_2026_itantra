package com.itantra.edgespeech.protocol

class ReorderBuffer(private val timeoutMs: Long = 450L) {

    private var expectedSequence: Int? = null
    private val buffer = mutableMapOf<Int, Pair<BinaryFrame, Long>>()

    @Synchronized
    fun push(frame: BinaryFrame): List<BinaryFrame> {
        val now = System.currentTimeMillis()
        val seq = frame.sequence

        if (expectedSequence == null) {
            expectedSequence = seq
        }

        if (seq < expectedSequence!!) {
            return emptyList()
        }

        buffer[seq] = Pair(frame, now)
        return drain(now)
    }

    @Synchronized
    fun checkTimeout(): List<BinaryFrame> {
        val now = System.currentTimeMillis()
        return drain(now)
    }

    private fun drain(now: Long): List<BinaryFrame> {
        val deliverable = mutableListOf<BinaryFrame>()

        while (expectedSequence != null) {
            val currentExpected = expectedSequence!!
            if (buffer.containsKey(currentExpected)) {
                val (frame, _) = buffer.remove(currentExpected)!!
                deliverable.add(frame)
                expectedSequence = currentExpected + 1
                continue
            }

            if (buffer.isNotEmpty()) {
                val lowestSeq = buffer.keys.minOrNull()!!
                val (_, arrivalTime) = buffer[lowestSeq]!!
                if ((now - arrivalTime) >= timeoutMs) {
                    expectedSequence = lowestSeq
                    continue
                }
            }

            break
        }

        return deliverable
    }

    @Synchronized
    fun reset(nextSeq: Int? = null) {
        expectedSequence = nextSeq
        buffer.clear()
    }
}
