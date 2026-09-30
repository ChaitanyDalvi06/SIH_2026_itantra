"""
iTantra Reorder Buffer
Ensures packets are delivered in sequence while enforcing a strict timeout
to prevent unbounded head-of-line blocking in walkie-talkie audio.
"""

import time
from typing import Dict, List, Optional
from core_engine.protocol import BinaryFrame


class ReorderBuffer:
    def __init__(self, timeout_ms: int = 400):
        self.timeout_ms = timeout_ms
        self.expected_sequence: Optional[int] = None
        # sequence -> (BinaryFrame, arrival_time_ms)
        self.buffer: Dict[int, tuple[BinaryFrame, float]] = {}

    def push(self, frame: BinaryFrame) -> List[BinaryFrame]:
        """
        Inserts an incoming frame and returns a list of deliverable frames in sequence order.
        """
        now = time.time() * 1000.0
        seq = frame.sequence

        if self.expected_sequence is None:
            self.expected_sequence = seq

        # If it is older than expected, it is a late duplicate or already bypassed packet
        if seq < self.expected_sequence:
            return []

        self.buffer[seq] = (frame, now)
        return self._drain(now)

    def check_timeout(self) -> List[BinaryFrame]:
        """Periodic check for stalled packets beyond timeout window."""
        now = time.time() * 1000.0
        return self._drain(now)

    def _drain(self, now: float) -> List[BinaryFrame]:
        deliverable: List[BinaryFrame] = []

        while self.expected_sequence is not None:
            if self.expected_sequence in self.buffer:
                frame, _ = self.buffer.pop(self.expected_sequence)
                deliverable.append(frame)
                self.expected_sequence += 1
                continue

            # Check if lowest buffered sequence has exceeded the timeout
            if self.buffer:
                lowest_seq = min(self.buffer.keys())
                _, arrival_time = self.buffer[lowest_seq]
                if (now - arrival_time) >= self.timeout_ms:
                    # Timeout expired! Skip missing packets and jump to lowest available
                    self.expected_sequence = lowest_seq
                    continue

            # Blocked waiting for missing packet within timeout window
            break

        return deliverable

    def reset(self, next_seq: Optional[int] = None):
        self.expected_sequence = next_seq
        self.buffer.clear()
