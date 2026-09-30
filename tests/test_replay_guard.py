"""
Unit tests for Replay Guard and Deduplication
"""

import unittest
from core_engine.replay_guard import ReplayGuard


class TestReplayGuard(unittest.TestCase):
    def setUp(self):
        self.guard = ReplayGuard(window_size=64, max_recent_messages=128)

    def test_in_order_sequences(self):
        for seq in range(1, 20):
            valid, reason = self.guard.check_sequence(seq)
            self.assertTrue(valid, f"Sequence {seq} should be valid")

    def test_duplicate_sequence_rejection(self):
        self.guard.check_sequence(10)
        self.guard.check_sequence(11)
        valid, reason = self.guard.check_sequence(10)
        self.assertFalse(valid)
        self.assertIn("REPLAY_DUPLICATE", reason)

    def test_expired_old_sequence_rejection(self):
        # Advance sequence past window cutoff
        self.guard.check_sequence(1)
        self.guard.check_sequence(200)  # window is 64, cutoff is 200 - 64 = 136

        valid, reason = self.guard.check_sequence(50)  # Old sequence
        self.assertFalse(valid)
        self.assertIn("REPLAY_EXPIRED", reason)

    def test_out_of_order_within_window_accepted(self):
        self.guard.check_sequence(100)
        valid, _ = self.guard.check_sequence(95)  # 5 behind highest, within 64 window
        self.assertTrue(valid)

        # Trying 95 again must be rejected
        valid2, _ = self.guard.check_sequence(95)
        self.assertFalse(valid2)

    def test_message_id_deduplication(self):
        msg_id = 0x9988776655443322
        self.assertTrue(self.guard.check_and_record_message(msg_id))
        # Second time should return False (duplicate)
        self.assertFalse(self.guard.check_and_record_message(msg_id))


if __name__ == "__main__":
    unittest.main()
