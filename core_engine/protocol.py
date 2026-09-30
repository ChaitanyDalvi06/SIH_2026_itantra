"""
iTantra EdgeSpeech Protocol - Binary Frame Specification & Parser
Low-latency, secure, offline speech packet framing.
"""

import struct
import time
import hmac
import hashlib
from enum import IntEnum
from typing import Optional, Tuple

MAGIC_BYTES = b"IT"  # 0x49 0x54
PROTOCOL_VERSION = 0x01
MAX_PAYLOAD_SIZE = 4096
HEADER_SIZE = 37
AUTH_TAG_SIZE = 16
MIN_FRAME_SIZE = HEADER_SIZE + AUTH_TAG_SIZE

# Packet Types
class PacketType(IntEnum):
    HELLO = 0x01
    HELLO_ACK = 0x02
    CAPABILITIES = 0x03
    CAPABILITIES_ACK = 0x04
    SESSION_START = 0x10
    SESSION_END = 0x11
    TEXT_PARTIAL = 0x1F
    TEXT_FINAL = 0x20
    TEXT_ACK = 0x21
    ALERT = 0x30
    ALERT_ACK = 0x31
    HEARTBEAT = 0x40
    HEARTBEAT_ACK = 0x41
    ERROR = 0x50
    CLOSE = 0x51

# Language Mapping (10 Indian Languages)
class LanguageCode(IntEnum):
    HINDI = 0       # hi
    GUJARATI = 1    # gu
    MARATHI = 2     # mr
    KANNADA = 3     # kn
    MALAYALAM = 4   # ml
    TAMIL = 5       # ta
    TELUGU = 6      # te
    ODIA = 7        # or
    BENGALI = 8     # bn
    ENGLISH = 9     # en

LANGUAGE_MAP = {
    "hi": LanguageCode.HINDI,
    "gu": LanguageCode.GUJARATI,
    "mr": LanguageCode.MARATHI,
    "kn": LanguageCode.KANNADA,
    "ml": LanguageCode.MALAYALAM,
    "ta": LanguageCode.TAMIL,
    "te": LanguageCode.TELUGU,
    "or": LanguageCode.ODIA,
    "bn": LanguageCode.BENGALI,
    "en": LanguageCode.ENGLISH,
}

REVERSE_LANG_MAP = {v: k for k, v in LANGUAGE_MAP.items()}

# Flags
FLAG_COMPRESSED = 0x01
FLAG_ENCRYPTED  = 0x02
FLAG_PRIORITY_ALERT = 0x04
FLAG_ACK_REQUESTED = 0x08


class BinaryFrame:
    def __init__(
        self,
        packet_type: PacketType,
        session_id: int,
        message_id: int,
        sequence: int,
        language: LanguageCode = LanguageCode.HINDI,
        payload: bytes = b"",
        timestamp: Optional[int] = None,
        flags: int = 0,
        auth_tag: bytes = b"\x00" * AUTH_TAG_SIZE,
    ):
        self.magic = MAGIC_BYTES
        self.version = PROTOCOL_VERSION
        self.packet_type = PacketType(packet_type)
        self.flags = flags
        self.header_len = HEADER_SIZE
        self.session_id = session_id
        self.message_id = message_id
        self.sequence = sequence
        self.timestamp = timestamp if timestamp is not None else int(time.time() * 1000)
        self.language = LanguageCode(language)
        self.payload = payload if isinstance(payload, bytes) else payload.encode("utf-8")
        self.payload_len = len(self.payload)
        self.auth_tag = auth_tag

    @property
    def text(self) -> str:
        """Helper to get string text from payload safely."""
        try:
            return self.payload.decode("utf-8", errors="replace")
        except Exception:
            return ""

    def serialize(self, secret_key: Optional[bytes] = None) -> bytes:
        """Serializes the frame into network bytes, computing HMAC-SHA256 auth tag if key is provided."""
        if self.payload_len > MAX_PAYLOAD_SIZE:
            raise ValueError(f"Payload size {self.payload_len} exceeds maximum {MAX_PAYLOAD_SIZE}")

        header_bytes = struct.pack(
            ">2sBBBBQQIQBH",
            self.magic,
            self.version,
            int(self.packet_type),
            self.flags,
            self.header_len,
            self.session_id,
            self.message_id,
            self.sequence,
            self.timestamp,
            int(self.language),
            self.payload_len,
        )

        frame_without_tag = header_bytes + self.payload

        if secret_key:
            tag = hmac.new(secret_key, frame_without_tag, hashlib.sha256).digest()[:AUTH_TAG_SIZE]
            self.auth_tag = tag
        else:
            tag = self.auth_tag if len(self.auth_tag) == AUTH_TAG_SIZE else (b"\x00" * AUTH_TAG_SIZE)

        return frame_without_tag + tag

    @classmethod
    def deserialize(cls, data: bytes, secret_key: Optional[bytes] = None) -> "BinaryFrame":
        """
        Parses raw bytes into a BinaryFrame.
        Performs rigorous validation to prevent crashes from malformed/adversarial frames.
        """
        if len(data) < MIN_FRAME_SIZE:
            raise ValueError(f"Frame too short: {len(data)} bytes (minimum {MIN_FRAME_SIZE})")

        magic, version, p_type, flags, header_len, session_id, message_id, sequence, timestamp, lang, payload_len = struct.unpack(
            ">2sBBBBQQIQBH", data[:HEADER_SIZE]
        )

        if magic != MAGIC_BYTES:
            raise ValueError(f"Invalid magic bytes: {magic!r}")

        if version != PROTOCOL_VERSION:
            raise ValueError(f"Unsupported protocol version: {version}")

        try:
            packet_type = PacketType(p_type)
        except ValueError:
            raise ValueError(f"Unknown packet type: 0x{p_type:02X}")

        if header_len != HEADER_SIZE:
            raise ValueError(f"Header length mismatch: expected {HEADER_SIZE}, got {header_len}")

        expected_total = HEADER_SIZE + payload_len + AUTH_TAG_SIZE
        if len(data) < expected_total:
            raise ValueError(f"Truncated frame: expected {expected_total} bytes, got {len(data)}")
        if len(data) > expected_total:
            raise ValueError(f"Extra trailing bytes: expected {expected_total} bytes, got {len(data)}")

        payload = data[HEADER_SIZE : HEADER_SIZE + payload_len]
        auth_tag = data[HEADER_SIZE + payload_len : expected_total]

        # Verify cryptographic authentication tag if key is provided
        if secret_key:
            expected_tag = hmac.new(secret_key, data[: HEADER_SIZE + payload_len], hashlib.sha256).digest()[:AUTH_TAG_SIZE]
            if not hmac.compare_digest(auth_tag, expected_tag):
                raise PermissionError("Cryptographic authentication tag mismatch (integrity check failed)")

        try:
            language = LanguageCode(lang)
        except ValueError:
            language = LanguageCode.HINDI

        frame = cls(
            packet_type=packet_type,
            session_id=session_id,
            message_id=message_id,
            sequence=sequence,
            language=language,
            payload=payload,
            timestamp=timestamp,
            flags=flags,
            auth_tag=auth_tag,
        )
        return frame

    def to_dict(self) -> dict:
        """Dictionary representation for logging, metrics, and JSON-RPC / UI telemetry."""
        return {
            "magic": self.magic.decode("latin1", errors="replace"),
            "version": self.version,
            "type": self.packet_type.name,
            "type_code": int(self.packet_type),
            "flags": self.flags,
            "header_len": self.header_len,
            "session_id": f"0x{self.session_id:016X}",
            "message_id": f"0x{self.message_id:016X}",
            "sequence": self.sequence,
            "timestamp": self.timestamp,
            "language": REVERSE_LANG_MAP.get(self.language, "unknown"),
            "payload_len": self.payload_len,
            "payload_text": self.text,
            "auth_tag": self.auth_tag.hex(),
            "total_frame_bytes": HEADER_SIZE + self.payload_len + AUTH_TAG_SIZE,
        }
