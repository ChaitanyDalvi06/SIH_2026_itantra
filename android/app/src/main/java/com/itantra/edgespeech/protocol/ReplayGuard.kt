package com.itantra.edgespeech.protocol

class ReplayGuard(
    private val windowSize: Int = 128,
    private val maxRecentMessages: Int = 256
) {
    private var highestSequence: Int = 0
    private val seenSequences = mutableSetOf<Int>()
    private val recentMessageOrder = ArrayDeque<Long>()
    private val recentMessageSet = mutableSetOf<Long>()

    @Synchronized
    fun checkSequence(sequence: Int): Pair<Boolean, String> {
        if (highestSequence == 0 && seenSequences.isEmpty()) {
            highestSequence = sequence
            seenSequences.add(sequence)
            return Pair(true, "INITIAL_SEQUENCE")
        }

        if (sequence > highestSequence) {
            highestSequence = sequence
            seenSequences.add(sequence)

            val cutoff = highestSequence - windowSize
            seenSequences.removeAll { it <= cutoff }
            return Pair(true, "NEW_HIGHEST")
        }

        if ((highestSequence - sequence) >= windowSize) {
            return Pair(false, "REPLAY_EXPIRED: sequence $sequence older than window cutoff")
        }

        if (seenSequences.contains(sequence)) {
            return Pair(false, "REPLAY_DUPLICATE: sequence $sequence already processed")
        }

        seenSequences.add(sequence)
        return Pair(true, "OUT_OF_ORDER_VALID")
    }

    @Synchronized
    fun checkAndRecordMessage(messageId: Long): Boolean {
        if (recentMessageSet.contains(messageId)) {
            return false
        }

        recentMessageSet.add(messageId)
        recentMessageOrder.addLast(messageId)

        if (recentMessageOrder.size > maxRecentMessages) {
            val oldest = recentMessageOrder.removeFirst()
            recentMessageSet.remove(oldest)
        }

        return true
    }

    @Synchronized
    fun reset() {
        highestSequence = 0
        seenSequences.clear()
        recentMessageOrder.clear()
        recentMessageSet.clear()
    }
}
