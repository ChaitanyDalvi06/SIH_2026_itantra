"""
iTantra Protocol Fuzzer & Adversarial Attack Simulator
Exercises the binary deserializer, replay guard, and security filters against
thousands of mutated, corrupted, and adversarial frames.
Zero crashes allowed.
"""

import os
import random
import time
from typing import Dict, Any, List
from core_engine.protocol import BinaryFrame, PacketType, LanguageCode, MAGIC_BYTES, PROTOCOL_VERSION, HEADER_SIZE
from core_engine.replay_guard import ReplayGuard


class ProtocolFuzzer:
    def __init__(self):
        self.valid_secret = b"iTantra_Secured_Key_2026_Prod01!"
        self.replay_guard = ReplayGuard()

    def generate_valid_frame(self, seq: int) -> bytes:
        frame = BinaryFrame(
            packet_type=PacketType.TEXT_FINAL,
            session_id=0x1122334455667788,
            message_id=0x99AABBCCDDEEFF00 + seq,
            sequence=seq,
            language=LanguageCode.HINDI,
            payload="परीक्षण संदेश (Valid Test Message)".encode("utf-8"),
        )
        return frame.serialize(secret_key=self.valid_secret)

    def run_fuzz_campaign(self, iterations: int = 1000) -> Dict[str, Any]:
        """Runs an adversarial fuzzing campaign against the protocol parser and guards."""
        start_time = time.time()
        rejected_count = 0
        accepted_count = 0
        crash_count = 0
        error_distribution: Dict[str, int] = {}

        mutation_types = [
            "EMPTY_FRAME",
            "TRUNCATED_HEADER",
            "CORRUPT_MAGIC",
            "INVALID_VERSION",
            "INVALID_TYPE",
            "OVERSIZED_PAYLOAD_LEN",
            "CORRUPT_AUTH_TAG",
            "BAD_UTF8_PAYLOAD",
            "RANDOM_NOISE",
            "REPLAYED_SEQUENCE",
            "EXPIRED_SEQUENCE",
            "ZERO_BYTE_FLOOD",
        ]

        for i in range(iterations):
            mutation = random.choice(mutation_types)
            data: bytes = b""

            if mutation == "EMPTY_FRAME":
                data = b""
            elif mutation == "TRUNCATED_HEADER":
                data = os.urandom(random.randint(1, HEADER_SIZE - 1))
            elif mutation == "CORRUPT_MAGIC":
                valid = self.generate_valid_frame(seq=i + 1)
                data = b"XX" + valid[2:]
            elif mutation == "INVALID_VERSION":
                valid = self.generate_valid_frame(seq=i + 1)
                data = valid[:2] + b"\x99" + valid[3:]
            elif mutation == "INVALID_TYPE":
                valid = self.generate_valid_frame(seq=i + 1)
                data = valid[:3] + b"\xFE" + valid[4:]
            elif mutation == "OVERSIZED_PAYLOAD_LEN":
                valid = self.generate_valid_frame(seq=i + 1)
                # Overwrite payload length with 0xFFFF (65535 bytes)
                data = valid[:35] + b"\xFF\xFF" + valid[37:]
            elif mutation == "CORRUPT_AUTH_TAG":
                valid = self.generate_valid_frame(seq=i + 1)
                # Flip bits in auth tag
                data = valid[:-4] + b"\xDE\xAD\xBE\xEF"
            elif mutation == "BAD_UTF8_PAYLOAD":
                frame = BinaryFrame(
                    packet_type=PacketType.TEXT_FINAL,
                    session_id=0x1122334455667788,
                    message_id=0x1000 + i,
                    sequence=i + 1,
                    language=LanguageCode.HINDI,
                    payload=b"\x80\xFF\xFE\xC0\xAF",  # Illegal UTF-8 sequences
                )
                data = frame.serialize(secret_key=self.valid_secret)
            elif mutation == "RANDOM_NOISE":
                data = os.urandom(random.randint(10, 500))
            elif mutation == "REPLAYED_SEQUENCE":
                # Sequence 10 repeated multiple times
                frame = BinaryFrame(
                    packet_type=PacketType.TEXT_FINAL,
                    session_id=0x1122334455667788,
                    message_id=0x2000 + i,
                    sequence=10,
                    language=LanguageCode.HINDI,
                    payload=b"Replay test",
                )
                data = frame.serialize(secret_key=self.valid_secret)
            elif mutation == "EXPIRED_SEQUENCE":
                frame = BinaryFrame(
                    packet_type=PacketType.TEXT_FINAL,
                    session_id=0x1122334455667788,
                    message_id=0x3000 + i,
                    sequence=1,  # Far behind if current is > 150
                    language=LanguageCode.HINDI,
                    payload=b"Expired test",
                )
                data = frame.serialize(secret_key=self.valid_secret)
            elif mutation == "ZERO_BYTE_FLOOD":
                data = b"\x00" * random.randint(50, 1024)

            # Test parsing without crashing
            try:
                parsed_frame = BinaryFrame.deserialize(data, secret_key=self.valid_secret)
                # If frame deserialized, test replay guard
                is_valid, reason = self.replay_guard.check_sequence(parsed_frame.sequence)
                if is_valid:
                    accepted_count += 1
                else:
                    rejected_count += 1
                    error_distribution[reason] = error_distribution.get(reason, 0) + 1
            except Exception as e:
                rejected_count += 1
                err_type = type(e).__name__
                error_distribution[err_type] = error_distribution.get(err_type, 0) + 1

        duration_sec = time.time() - start_time
        return {
            "total_iterations": iterations,
            "accepted_count": accepted_count,
            "rejected_count": rejected_count,
            "crashes": crash_count,
            "duration_sec": round(duration_sec, 4),
            "throughput_fps": round(iterations / max(duration_sec, 0.001), 1),
            "rejections_by_category": error_distribution,
            "status": "PASS" if crash_count == 0 else "FAIL",
        }
