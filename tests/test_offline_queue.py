"""
Unit tests for Offline Persistent Queue
"""

import unittest
from core_engine.offline_queue import OfflineMessageQueue, MessageStatus


class TestOfflineQueue(unittest.TestCase):
    def setUp(self):
        self.queue = OfflineMessageQueue(db_path=":memory:", max_messages=5, max_retries=2)

    def test_enqueue_and_retrieve(self):
        self.queue.enqueue_message(
            message_id=101, session_id=1, sequence=1, language=0,
            text="Test message 1", raw_bytes=b"raw1", is_alert=False
        )
        pending = self.queue.get_pending_messages()
        self.assertEqual(len(pending), 1)
        self.assertEqual(pending[0]["text"], "Test message 1")
        self.assertEqual(pending[0]["status"], MessageStatus.QUEUED.value)

    def test_alert_priority_ordering(self):
        # Insert normal message first
        self.queue.enqueue_message(1, 1, 1, 0, "Normal 1", b"", is_alert=False)
        # Insert emergency alert second
        self.queue.enqueue_message(2, 1, 2, 0, "EMERGENCY ALERT", b"", is_alert=True)

        pending = self.queue.get_pending_messages()
        # Alert must be sorted to the front!
        self.assertEqual(pending[0]["message_id"], 2)
        self.assertEqual(pending[0]["is_alert"], 1)

    def test_bounded_capacity_eviction(self):
        # Max capacity is 5
        for i in range(1, 10):
            self.queue.enqueue_message(i, 1, i, 0, f"Msg {i}", b"", is_alert=False)

        pending = self.queue.get_pending_messages(limit=20)
        self.assertLessEqual(len(pending), 5)

    def test_retry_and_failure_lifecycle(self):
        self.queue.enqueue_message(99, 1, 1, 0, "Retry msg", b"")
        self.queue.mark_sending(99)
        self.queue.mark_retry_or_fail(99)  # retry 1 -> status back to QUEUED
        self.queue.mark_sending(99)
        self.queue.mark_retry_or_fail(99)  # retry 2 -> reached max_retries=2 -> FAILED

        stats = self.queue.get_stats()
        self.assertEqual(stats.get(MessageStatus.FAILED.value, 0), 1)


if __name__ == "__main__":
    unittest.main()
