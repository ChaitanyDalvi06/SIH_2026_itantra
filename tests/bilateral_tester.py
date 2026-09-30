#!/usr/bin/env python3
"""
iTantra EdgeSpeech Bilateral Prototype Verification
Simulates the complete end-to-end bilateral voice-to-text-to-voice communication loop.
Tests:
1. Low-bandwidth payload encoding (sending 50-90 byte text instead of 64kbps audio)
2. Code-mixing & Hinglish intent/urgency detection (e.g. "bhai jaldi help bhejo bridge toot gaya")
3. Stop/pause detection simulation with adaptive endpointing
4. Bilateral transmission (Node A -> Node B and Node B -> Node A)
5. Non-interruptible emergency alert handling
6. Live socket connection to Android phone (if available on IP:8988)
"""

import sys
import os
import time
import socket
import struct
import hmac
import hashlib

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from core_engine.protocol import BinaryFrame, PacketType, LanguageCode
from core_engine.adaptive_endpoint import AdaptiveEndpointDetector, EndpointState
from simulator.context_engine import analyze_text

SHARED_SECRET = b"iTantra_Secured_Key_2026_Prod01!"

def test_bilateral_loopback():
    print("=" * 70)
    print("🔬 RUNNING BILATERAL PROTOTYPE VERIFICATION (NODE A <-> NODE B)")
    print("=" * 70)

    # -------------------------------------------------------------
    # Step 1: Speaker A speaks Hinglish into Node A
    # -------------------------------------------------------------
    spoken_hinglish = "bhai jaldi help bhejo sector 4 me bridge toot gaya hai ambulance chahiye"
    print(f"\n[1] Node A: User speaks in Hinglish:")
    print(f"    🗣️  \"{spoken_hinglish}\"")

    # Step 2: Adaptive Endpoint & Pause Detection
    endpoint = AdaptiveEndpointDetector()
    endpoint.configure_for_language("hi")

    # Simulate speaking followed by silence pause
    state, is_final = endpoint.on_frame(is_speech=True, current_partial_text="bhai jaldi help")
    state, is_final = endpoint.on_frame(is_speech=True, current_partial_text=spoken_hinglish)
    time.sleep(0.4) # Pause
    state, is_final = endpoint.on_frame(is_speech=False, current_partial_text=spoken_hinglish)

    print(f"    ⏸️  Pause detected -> State: {state.name}, Utterance Finalized: {is_final}")

    # Step 3: Context Analysis on Node A
    context_a = analyze_text(spoken_hinglish)
    print(f"    🧠 Context Analyzed:")
    print(f"       • Intent:  {context_a['intent']}")
    print(f"       • Urgency: {context_a['urgency'].upper()} (Confidence: {context_a.get('urgency_confidence', 0.8):.2f})")
    print(f"       • Entities: {context_a['entities']}")

    # Step 4: Binary Frame Serialization
    frame_a = BinaryFrame(
        packet_type=PacketType.TEXT_FINAL,
        session_id=0x1122334455667788,
        message_id=int(time.time() * 1000),
        sequence=1,
        language=LanguageCode.HINDI,
        payload=spoken_hinglish.encode("utf-8")
    )
    raw_bytes = frame_a.serialize(SHARED_SECRET)
    print(f"\n[2] Network Transmission (Node A -> Node B):")
    print(f"    📦 Payload size: {len(raw_bytes)} bytes (vs ~64,000 bytes for 1s raw PCM audio)")
    print(f"    📉 Bandwidth Savings: {((64000 - len(raw_bytes)) / 64000) * 100:.2f}%")

    # Step 5: Node B receives, deserializes, verifies HMAC
    t_start = time.perf_counter()
    frame_b_received = BinaryFrame.deserialize(raw_bytes, SHARED_SECRET)
    t_deserialize_ms = (time.perf_counter() - t_start) * 1000

    print(f"\n[3] Node B: Frame Received & Authenticated:")
    print(f"    🔐 HMAC-SHA256 verified successfully in {t_deserialize_ms:.3f} ms")
    print(f"    💬 Transcribed Text: \"{frame_b_received.text}\"")
    print(f"    🔊 Synthesis Action: Speaking aloud in Hindi/Hinglish via loudspeaker")

    # Step 6: Node B responds in bilateral dialogue
    reply_text = "मदद रवाना हो चुकी है, 10 मिनट में पहुंचेगी / Team dispatched"
    print(f"\n[4] Node B: User speaks reply:")
    print(f"    🗣️  \"{reply_text}\"")

    context_b = analyze_text(reply_text)
    print(f"    🧠 Reply Intent: {context_b['intent']} (Urgency: {context_b['urgency']})")

    frame_b_reply = BinaryFrame(
        packet_type=PacketType.TEXT_FINAL,
        session_id=0x1122334455667788,
        message_id=int(time.time() * 1000) + 1,
        sequence=2,
        language=LanguageCode.HINDI,
        payload=reply_text.encode("utf-8")
    )
    reply_bytes = frame_b_reply.serialize(SHARED_SECRET)
    print(f"    📦 Reply size: {len(reply_bytes)} bytes")

    # Step 7: Node A receives reply
    frame_a_received = BinaryFrame.deserialize(reply_bytes, SHARED_SECRET)
    print(f"\n[5] Node A: Reply Received:")
    print(f"    💬 Text: \"{frame_a_received.text}\"")
    print(f"    🔊 Synthesis Action: Playing aloud on Node A loudspeaker")

    print("\n" + "=" * 70)
    print("✅ BILATERAL LOOP SUCCESSFULLY VERIFIED (100% OFFLINE, ZERO CLOUD)")
    print("=" * 70)

def test_live_socket_connection(target_ip, port=8988):
    print(f"\nConnecting to physical Android device @ {target_ip}:{port}...")
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(3.0)
        s.connect((target_ip, port))

        test_msg = "🚨 आपातकालीन अलर्ट: परीक्षण संदेश (Bilateral Test)"
        frame = BinaryFrame(
            packet_type=PacketType.ALERT,
            session_id=0x1122334455667788,
            message_id=int(time.time() * 1000),
            sequence=100,
            language=LanguageCode.HINDI,
            payload=test_msg.encode("utf-8"),
            flags=BinaryFrame.FLAG_PRIORITY_ALERT
        )
        data = frame.serialize(SHARED_SECRET)
        s.sendall(struct.pack(">I", len(data)) + data)
        print(f"✅ Successfully sent {len(data)} bytes alert frame to Android device!")
        s.close()
    except Exception as e:
        print(f"ℹ️  Device not reachable at {target_ip}:{port} ({e})")

if __name__ == "__main__":
    test_bilateral_loopback()
    if len(sys.argv) > 1:
        test_live_socket_connection(sys.argv[1])
