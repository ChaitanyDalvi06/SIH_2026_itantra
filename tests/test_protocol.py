"""
Unit tests for iTantra Binary Protocol
"""

import unittest
from core_engine.protocol import (
    BinaryFrame, PacketType, LanguageCode, MAGIC_BYTES, PROTOCOL_VERSION,
    FLAG_PRIORITY_ALERT, FLAG_ACK_REQUESTED
)


class TestBinaryProtocol(unittest.TestCase):
    def setUp(self):
        self.secret_key = b"0123456789ABCDEF0123456789ABCDEF"

    def test_serialization_and_deserialization(self):
        original_text = "मुझे मदद चाहिए (Emergency Assist)"
        frame = BinaryFrame(
            packet_type=PacketType.TEXT_FINAL,
            session_id=0xAABBCCDDEEFF0011,
            message_id=0x1122334455667788,
            sequence=42,
            language=LanguageCode.HINDI,
            payload=original_text.encode("utf-8"),
            flags=FLAG_ACK_REQUESTED,
        )

        data = frame.serialize(secret_key=self.secret_key)
        self.assertTrue(data.startswith(MAGIC_BYTES))

        parsed = BinaryFrame.deserialize(data, secret_key=self.secret_key)
        self.assertEqual(parsed.magic, MAGIC_BYTES)
        self.assertEqual(parsed.version, PROTOCOL_VERSION)
        self.assertEqual(parsed.packet_type, PacketType.TEXT_FINAL)
        self.assertEqual(parsed.session_id, 0xAABBCCDDEEFF0011)
        self.assertEqual(parsed.message_id, 0x1122334455667788)
        self.assertEqual(parsed.sequence, 42)
        self.assertEqual(parsed.language, LanguageCode.HINDI)
        self.assertEqual(parsed.text, original_text)
        self.assertEqual(parsed.flags, FLAG_ACK_REQUESTED)

    def test_alert_packet_flags(self):
        frame = BinaryFrame(
            packet_type=PacketType.ALERT,
            session_id=1,
            message_id=2,
            sequence=3,
            language=LanguageCode.ENGLISH,
            payload="EVACUATE BUILDING IMMEDIATELY",
            flags=FLAG_PRIORITY_ALERT,
        )
        data = frame.serialize(secret_key=self.secret_key)
        parsed = BinaryFrame.deserialize(data, secret_key=self.secret_key)
        self.assertEqual(parsed.packet_type, PacketType.ALERT)
        self.assertTrue(parsed.flags & FLAG_PRIORITY_ALERT)

    def test_invalid_magic_rejection(self):
        frame = BinaryFrame(PacketType.HELLO, 1, 1, 1, payload=b"Hi")
        data = frame.serialize()
        corrupted = b"ZZ" + data[2:]
        with self.assertRaises(ValueError):
            BinaryFrame.deserialize(corrupted)

    def test_auth_tag_tampering(self):
        frame = BinaryFrame(PacketType.TEXT_FINAL, 1, 1, 1, payload=b"Secret")
        data = frame.serialize(secret_key=self.secret_key)
        tampered = data[:-2] + b"\xFF\xFF"
        with self.assertRaises(PermissionError):
            BinaryFrame.deserialize(tampered, secret_key=self.secret_key)

    def test_ten_languages_encoding(self):
        languages = [
            (LanguageCode.HINDI, "नमस्ते"),
            (LanguageCode.GUJARATI, "નમસ્તે"),
            (LanguageCode.MARATHI, "नमस्कार"),
            (LanguageCode.KANNADA, "ನಮಸ್ಕಾರ"),
            (LanguageCode.MALAYALAM, "നമസ്കാരം"),
            (LanguageCode.TAMIL, "வணக்கம்"),
            (LanguageCode.TELUGU, "నమస్కారం"),
            (LanguageCode.ODIA, "ନମସ୍କାର"),
            (LanguageCode.BENGALI, "নমস্কার"),
            (LanguageCode.ENGLISH, "Hello World"),
        ]
        for lang, text in languages:
            frame = BinaryFrame(PacketType.TEXT_FINAL, 1, 1, 1, language=lang, payload=text.encode("utf-8"))
            data = frame.serialize(self.secret_key)
            parsed = BinaryFrame.deserialize(data, self.secret_key)
            self.assertEqual(parsed.language, lang)
            self.assertEqual(parsed.text, text)


if __name__ == "__main__":
    unittest.main()
