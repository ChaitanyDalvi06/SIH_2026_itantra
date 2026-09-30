"""
iTantra Simulated Transceiver Node
Full-stack transceiver implementation used both for testing and driving the interactive dual-phone simulator.
Encapsulates: Mic -> VAD -> STT -> Framing -> Crypto -> Network IO -> ReplayGuard -> Reorder -> Text Normalizer -> TTS -> Speaker.
"""

import time
import random
from typing import Optional, Callable, Dict, Any, List
from core_engine.protocol import (
    BinaryFrame, PacketType, LanguageCode, LANGUAGE_MAP, REVERSE_LANG_MAP,
    FLAG_PRIORITY_ALERT, FLAG_ACK_REQUESTED
)
from core_engine.replay_guard import ReplayGuard
from core_engine.reorder_buffer import ReorderBuffer
from core_engine.adaptive_endpoint import AdaptiveEndpointDetector, EndpointState
from core_engine.offline_queue import OfflineMessageQueue
from core_engine.session_controller import SessionController, ConnectionState, TransportTier


class SimulatedTransceiverNode:
    def __init__(self, node_id: str, secret_key: bytes = b"iTantra_Secured_Key_2026_Prod01!"):
        self.node_id = node_id
        self.secret_key = secret_key
        
        # Core engines
        self.replay_guard = ReplayGuard()
        self.reorder_buffer = ReorderBuffer(timeout_ms=450)
        self.endpoint_detector = AdaptiveEndpointDetector()
        self.offline_queue = OfflineMessageQueue(db_path=f":memory:")
        self.session_controller = SessionController(node_id=node_id)
        
        # State
        self.current_language = LanguageCode.HINDI
        self.tx_sequence = 1
        self.session_id = 0x8899AABBCCDDEEFF
        self.is_ptt_pressed = False
        self.is_continuous_mode = False
        self.current_partial_text = ""
        
        # Queues & Audio simulation
        self.normal_tts_queue: List[Dict[str, Any]] = []
        self.alert_tts_queue: List[Dict[str, Any]] = []
        
        # Callback to network simulator to send out bytes
        self.network_send_hook: Optional[Callable[[bytes, str], None]] = None
        # Telemetry / Event emitter for UI
        self.event_listener: Optional[Callable[[Dict[str, Any]], None]] = None

    def emit_event(self, event_type: str, data: Dict[str, Any]):
        if self.event_listener:
            event = {
                "node_id": self.node_id,
                "event": event_type,
                "timestamp": time.time(),
                "data": data,
            }
            self.event_listener(event)

    def set_language(self, lang_str: str):
        if lang_str in LANGUAGE_MAP:
            self.current_language = LANGUAGE_MAP[lang_str]
            self.emit_event("LANGUAGE_CHANGED", {"language": lang_str})

    def start_ptt(self):
        """User presses Push-To-Talk button."""
        self.is_ptt_pressed = True
        self.endpoint_detector.reset()
        self.current_partial_text = ""
        self.emit_event("PTT_PRESSED", {"state": "TRANSMITTING"})

    def update_streaming_stt(self, partial_text: str, is_speech: bool = True):
        """Simulates incoming 20ms audio frame and streaming STT hypothesis."""
        self.current_partial_text = partial_text
        self.emit_event("STT_PARTIAL", {"partial_text": partial_text, "is_speech": is_speech})

        if self.is_continuous_mode:
            state, should_finalize = self.endpoint_detector.on_frame(is_speech, partial_text)
            if should_finalize and partial_text.strip():
                self.finalize_and_transmit(partial_text.strip())

    def release_ptt(self, final_text: Optional[str] = None):
        """User releases Push-To-Talk button."""
        self.is_ptt_pressed = False
        text_to_send = (final_text or self.current_partial_text).strip()
        if text_to_send:
            self.finalize_and_transmit(text_to_send)
        self.current_partial_text = ""
        self.emit_event("PTT_RELEASED", {"state": "IDLE"})

    def broadcast_alert(self, alert_text: str):
        """Emergency Alert broadcast - high priority preemption."""
        self.finalize_and_transmit(alert_text, is_alert=True)

    def finalize_and_transmit(self, text: str, is_alert: bool = False):
        """Builds encrypted binary frame, enqueues to offline queue, and transmits."""
        t0 = time.time() * 1000.0
        msg_id = random.getrandbits(63)
        seq = self.tx_sequence
        self.tx_sequence += 1

        flags = FLAG_ACK_REQUESTED
        if is_alert:
            flags |= FLAG_PRIORITY_ALERT

        packet_type = PacketType.ALERT if is_alert else PacketType.TEXT_FINAL

        frame = BinaryFrame(
            packet_type=packet_type,
            session_id=self.session_id,
            message_id=msg_id,
            sequence=seq,
            language=self.current_language,
            payload=text.encode("utf-8"),
            flags=flags,
        )

        serialized_bytes = frame.serialize(secret_key=self.secret_key)
        
        # Enqueue in offline store-and-forward queue
        self.offline_queue.enqueue_message(
            message_id=msg_id,
            session_id=self.session_id,
            sequence=seq,
            language=int(self.current_language),
            text=text,
            raw_bytes=serialized_bytes,
            is_alert=is_alert,
        )

        t3 = time.time() * 1000.0

        telemetry = {
            "message_id": f"0x{msg_id:016X}",
            "sequence": seq,
            "text": text,
            "is_alert": is_alert,
            "language": REVERSE_LANG_MAP.get(self.current_language, "hi"),
            "bytes_length": len(serialized_bytes),
            "payload_len": frame.payload_len,
            "stt_final_ms": round(t3 - t0, 2),
            "serialized_hex": serialized_bytes.hex().upper()[:64] + "...",
        }
        self.emit_event("PACKET_OUTBOUND", telemetry)

        # Transmit via network hook if online
        if self.session_controller.state != ConnectionState.DISCONNECTED and self.network_send_hook:
            self.session_controller.total_packets_sent += 1
            self.offline_queue.mark_sending(msg_id)
            self.network_send_hook(serialized_bytes, self.node_id)
        else:
            self.emit_event("MESSAGE_QUEUED_OFFLINE", telemetry)

    def receive_bytes(self, data: bytes, ingress_timestamp: float) -> Optional[Dict[str, Any]]:
        """
        Ingests incoming bytes from network layer:
        Validates frame -> Decrypts & verifies AuthTag -> Replay Guard -> Reorder Buffer -> TTS Queue.
        """
        t5 = ingress_timestamp * 1000.0
        try:
            frame = BinaryFrame.deserialize(data, secret_key=self.secret_key)
        except Exception as e:
            self.emit_event("SECURITY_ALERT_DROPPED", {"error": str(e), "raw_bytes_len": len(data)})
            return None

        # Replay Guard Check
        is_seq_valid, reason = self.replay_guard.check_sequence(frame.sequence)
        if not is_seq_valid:
            self.emit_event("PACKET_REPLAY_REJECTED", {
                "sequence": frame.sequence,
                "reason": reason,
                "message_id": f"0x{frame.message_id:016X}",
            })
            return None

        # Check deduplication on message ID
        is_new_message = self.replay_guard.check_and_record_message(frame.message_id)
        if not is_new_message:
            # Duplicate message received (e.g. lost ACK retransmission). Send ACK again, but skip TTS
            self.emit_event("DUPLICATE_SUPPRESSED", {"sequence": frame.sequence, "message_id": f"0x{frame.message_id:016X}"})
            self._send_ack(frame)
            return None

        # Send ACK back immediately if requested
        if frame.flags & FLAG_ACK_REQUESTED:
            self._send_ack(frame)

        # Handle Heartbeats & ACKs
        if frame.packet_type in (PacketType.TEXT_ACK, PacketType.ALERT_ACK):
            self.offline_queue.mark_acknowledged(frame.message_id)
            self.emit_event("ACK_RECEIVED", {"message_id": f"0x{frame.message_id:016X}", "sequence": frame.sequence})
            return None

        if frame.packet_type in (PacketType.HEARTBEAT, PacketType.HEARTBEAT_ACK):
            rtt = t5 - frame.timestamp
            self.session_controller.record_heartbeat_received(max(rtt, 5.0))
            return None

        # Pass through Reorder Buffer
        deliverable_frames = self.reorder_buffer.push(frame)
        for d_frame in deliverable_frames:
            self._process_delivered_text_frame(d_frame, t5)

        return frame.to_dict()

    def _send_ack(self, original_frame: BinaryFrame):
        ack_type = PacketType.ALERT_ACK if original_frame.packet_type == PacketType.ALERT else PacketType.TEXT_ACK
        ack_frame = BinaryFrame(
            packet_type=ack_type,
            session_id=original_frame.session_id,
            message_id=original_frame.message_id,
            sequence=self.tx_sequence,
            language=original_frame.language,
            payload=b"ACK",
        )
        self.tx_sequence += 1
        ack_bytes = ack_frame.serialize(secret_key=self.secret_key)
        if self.network_send_hook:
            self.network_send_hook(ack_bytes, self.node_id)

    def _process_delivered_text_frame(self, frame: BinaryFrame, ingress_time_ms: float):
        """Processes finalized text frame into the appropriate TTS queue (priority vs normal)."""
        is_alert = (frame.packet_type == PacketType.ALERT) or bool(frame.flags & FLAG_PRIORITY_ALERT)
        text = frame.text
        lang = REVERSE_LANG_MAP.get(frame.language, "hi")

        tts_job = {
            "message_id": f"0x{frame.message_id:016X}",
            "sequence": frame.sequence,
            "text": text,
            "language": lang,
            "is_alert": is_alert,
            "created_at_ms": frame.timestamp,
            "ingress_time_ms": ingress_time_ms,
        }

        if is_alert:
            # Preempt normal queue
            self.alert_tts_queue.insert(0, tts_job)
            self.emit_event("ALERT_PREEMPTION_TRIGGERED", tts_job)
        else:
            self.normal_tts_queue.append(tts_job)
            self.emit_event("TTS_ENQUEUED", tts_job)

    def poll_next_tts_job(self) -> Optional[Dict[str, Any]]:
        """Audio Engine polls this to play speech through AudioTrack."""
        if self.alert_tts_queue:
            return self.alert_tts_queue.pop(0)
        if self.normal_tts_queue:
            return self.normal_tts_queue.pop(0)
        return None
