"""
iTantra Offline Persistent Message Queue
SQLite-backed store-and-forward queue with state tracking (CREATED, QUEUED, SENDING, ACKED, FAILED)
and bounded capacity (max 100 messages) to prevent storage exhaustion during disconnected operation.
"""

import sqlite3
import time
from enum import Enum
from typing import List, Optional, Dict, Any


class MessageStatus(str, Enum):
    CREATED = "CREATED"
    QUEUED = "QUEUED"
    SENDING = "SENDING"
    ACKED = "ACKED"
    FAILED = "FAILED"
    EXPIRED = "EXPIRED"


class OfflineMessageQueue:
    def __init__(self, db_path: str = ":memory:", max_messages: int = 100, max_retries: int = 5):
        self.db_path = db_path
        self.max_messages = max_messages
        self.max_retries = max_retries
        self._shared_conn = None
        if self.db_path == ":memory:":
            self._shared_conn = sqlite3.connect(":memory:", check_same_thread=False)
            self._shared_conn.row_factory = sqlite3.Row
        self._init_db()

    def _get_connection(self) -> sqlite3.Connection:
        if self._shared_conn is not None:
            return self._shared_conn
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        conn.row_factory = sqlite3.Row
        return conn

    def _init_db(self):
        with self._get_connection() as conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS outbound_messages (
                    message_id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    sequence INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    language INTEGER NOT NULL,
                    is_alert INTEGER NOT NULL DEFAULT 0,
                    text TEXT NOT NULL,
                    raw_bytes BLOB,
                    status TEXT NOT NULL,
                    retry_count INTEGER DEFAULT 0,
                    created_at REAL NOT NULL,
                    last_attempt REAL
                )
            """)
            conn.commit()

    def enqueue_message(
        self,
        message_id: int,
        session_id: int,
        sequence: int,
        language: int,
        text: str,
        raw_bytes: bytes,
        is_alert: bool = False,
    ) -> bool:
        """Enqueues a newly finalized text message with bounded storage safety check."""
        with self._get_connection() as conn:
            # Check current queued count
            cursor = conn.execute("SELECT COUNT(*) FROM outbound_messages WHERE status IN ('QUEUED', 'SENDING')")
            count = cursor.fetchone()[0]

            if count >= self.max_messages:
                # Evict oldest non-alert queued message to maintain bounded storage
                conn.execute("""
                    DELETE FROM outbound_messages 
                    WHERE message_id = (
                        SELECT message_id FROM outbound_messages 
                        WHERE status = 'QUEUED' AND is_alert = 0 
                        ORDER BY created_at ASC LIMIT 1
                    )
                """)

            now = time.time()
            timestamp = int(now * 1000)
            conn.execute("""
                INSERT OR REPLACE INTO outbound_messages
                (message_id, session_id, sequence, timestamp, language, is_alert, text, raw_bytes, status, retry_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
            """, (
                str(message_id), str(session_id), sequence, timestamp, language,
                1 if is_alert else 0, text, raw_bytes, MessageStatus.QUEUED.value, now
            ))
            conn.commit()
        return True

    def get_pending_messages(self, limit: int = 10) -> List[Dict[str, Any]]:
        """Retrieves messages ready for transmission, sorted by priority (alerts first) then creation time."""
        with self._get_connection() as conn:
            cursor = conn.execute("""
                SELECT * FROM outbound_messages 
                WHERE status IN ('QUEUED', 'SENDING')
                ORDER BY is_alert DESC, created_at ASC
                LIMIT ?
            """, (limit,))
            rows = cursor.fetchall()
            results = []
            for r in rows:
                d = dict(r)
                try:
                    d["message_id"] = int(d["message_id"])
                    d["session_id"] = int(d["session_id"])
                except Exception:
                    pass
                results.append(d)
            return results

    def mark_sending(self, message_id: int):
        with self._get_connection() as conn:
            conn.execute("""
                UPDATE outbound_messages 
                SET status = ?, last_attempt = ?, retry_count = retry_count + 1
                WHERE message_id = ?
            """, (MessageStatus.SENDING.value, time.time(), str(message_id)))
            conn.commit()

    def mark_acknowledged(self, message_id: int):
        with self._get_connection() as conn:
            conn.execute("""
                UPDATE outbound_messages 
                SET status = ? 
                WHERE message_id = ?
            """, (MessageStatus.ACKED.value, str(message_id)))
            conn.commit()

    def mark_retry_or_fail(self, message_id: int):
        with self._get_connection() as conn:
            cursor = conn.execute("SELECT retry_count FROM outbound_messages WHERE message_id = ?", (str(message_id),))
            row = cursor.fetchone()
            if not row:
                return

            retries = row["retry_count"]
            if retries >= self.max_retries:
                new_status = MessageStatus.FAILED.value
            else:
                new_status = MessageStatus.QUEUED.value

            conn.execute("UPDATE outbound_messages SET status = ? WHERE message_id = ?", (new_status, str(message_id)))
            conn.commit()

    def get_stats(self) -> Dict[str, int]:
        with self._get_connection() as conn:
            cursor = conn.execute("SELECT status, COUNT(*) as count FROM outbound_messages GROUP BY status")
            stats = {status.value: 0 for status in MessageStatus}
            for row in cursor.fetchall():
                stats[row["status"]] = row["count"]
            return stats
