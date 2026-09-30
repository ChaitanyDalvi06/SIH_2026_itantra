"""
iTantra Replay Guard & Message Deduplication
Prevents replay attacks and duplicate speech synthesis in lossy/retransmitted network conditions.
"""

from collections import deque
from typing import Set, Tuple


class ReplayGuard:
    def __init__(self, window_size: int = 128, max_recent_messages: int = 256):
        self.window_size = window_size
        self.highest_sequence = 0
        self.seen_sequences: Set[int] = set()
        
        # Bounded LRU-style cache for message IDs
        self.max_recent_messages = max_recent_messages
        self.recent_message_order = deque()
        self.recent_message_set: Set[int] = set()

    def check_sequence(self, sequence: int) -> Tuple[bool, str]:
        """
        Validates packet sequence number using a sliding window.
        Returns: (is_valid, reason)
        """
        if self.highest_sequence == 0 and not self.seen_sequences:
            # First packet in session
            self.highest_sequence = sequence
            self.seen_sequences.add(sequence)
            return True, "INITIAL_SEQUENCE"

        if sequence > self.highest_sequence:
            diff = sequence - self.highest_sequence
            self.highest_sequence = sequence
            self.seen_sequences.add(sequence)
            
            # Prune sequences that fell outside the window
            cutoff = self.highest_sequence - self.window_size
            self.seen_sequences = {s for s in self.seen_sequences if s > cutoff}
            return True, "NEW_HIGHEST_SEQUENCE"

        # sequence <= highest_sequence
        if (self.highest_sequence - sequence) >= self.window_size:
            return False, f"REPLAY_EXPIRED: sequence {sequence} older than window cutoff ({self.highest_sequence - self.window_size})"

        if sequence in self.seen_sequences:
            return False, f"REPLAY_DUPLICATE: sequence {sequence} already processed"

        # Valid out-of-order packet within sliding window
        self.seen_sequences.add(sequence)
        return True, "OUT_OF_ORDER_VALID"

    def check_and_record_message(self, message_id: int) -> bool:
        """
        Checks if a message_id was already processed.
        Returns True if NEW message, False if DUPLICATE message.
        """
        if message_id in self.recent_message_set:
            return False

        self.recent_message_set.add(message_id)
        self.recent_message_order.append(message_id)

        if len(self.recent_message_order) > self.max_recent_messages:
            oldest = self.recent_message_order.popleft()
            self.recent_message_set.discard(oldest)

        return True

    def reset(self):
        """Resets the state on new session establishment."""
        self.highest_sequence = 0
        self.seen_sequences.clear()
        self.recent_message_order.clear()
        self.recent_message_set.clear()
