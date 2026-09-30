"""
iTantra End-to-End Latency Waterfall & Bandwidth Efficiency Model
Measures and simulates T0 through T8:
T0 = Speech start
T1 = Speech end (VAD detected)
T2 = STT finalization
T3 = Packet serialization & crypto tag
T4 = Network transmission
T5 = Network receipt & decryption
T6 = TTS ingest
T7 = First audio chunk generated
T8 = Speaker playback starts
"""

import time
import json
from typing import Dict, Any


def calculate_pipeline_latency(
    speech_duration_sec: float = 2.0,
    network_latency_ms: float = 25.0,
    stt_rtf: float = 0.28,
    tts_rtf: float = 0.24,
    device_profile: str = "low_end",
) -> Dict[str, Any]:
    # Profile CPU scaling factors
    profile_factors = {
        "low_end": 1.35,
        "mid_range": 1.0,
        "high_end": 0.75,
    }
    factor = profile_factors.get(device_profile, 1.0)

    t0_ms = 0.0
    t1_ms = speech_duration_sec * 1000.0
    
    # Streaming STT latency (chunk processing overlap leaves ~180-280ms lag after speech end)
    stt_processing_ms = 220.0 * factor
    t2_ms = t1_ms + stt_processing_ms

    # Packet serialization + AES-GCM tag
    crypto_ms = 3.5 * factor
    t3_ms = t2_ms + crypto_ms

    # Network flight time (Wi-Fi Direct / LAN)
    t4_ms = t3_ms
    t5_ms = t4_ms + network_latency_ms

    # Ingest, replay guard, authentication check
    ingest_ms = 2.5 * factor
    t6_ms = t5_ms + ingest_ms

    # TTS first audio chunk synthesis
    tts_startup_ms = 260.0 * factor
    t7_ms = t6_ms + tts_startup_ms

    # AudioTrack buffer fill & DAC delay
    dac_buffer_ms = 40.0
    t8_ms = t7_ms + dac_buffer_ms

    # Latencies
    stt_latency_ms = t2_ms - t1_ms
    net_latency_ms = t5_ms - t4_ms
    tts_latency_ms = t7_ms - t6_ms
    e2e_latency_ms = t8_ms - t1_ms  # Measured from speech finish to audio heard
    total_pipeline_ms = t8_ms - t0_ms

    # Bandwidth comparison: Raw Audio vs iTantra Encrypted Frame
    raw_audio_bytes = int(16000 * 2 * speech_duration_sec)  # 16kHz 16-bit mono = 32,000 bytes/sec
    it_payload_bytes = 38  # Example Hindi UTF-8 string: "मुझे मदद चाहिए"
    it_total_frame_bytes = 37 + it_payload_bytes + 16  # 91 bytes total!
    compression_ratio = raw_audio_bytes / it_total_frame_bytes
    bandwidth_saved_pct = ((raw_audio_bytes - it_total_frame_bytes) / raw_audio_bytes) * 100.0

    return {
        "device_profile": device_profile,
        "speech_duration_sec": speech_duration_sec,
        "timestamps_ms": {
            "T0_speech_start": round(t0_ms, 1),
            "T1_speech_end": round(t1_ms, 1),
            "T2_stt_final": round(t2_ms, 1),
            "T3_packet_serialized": round(t3_ms, 1),
            "T4_packet_sent": round(t4_ms, 1),
            "T5_packet_received": round(t5_ms, 1),
            "T6_tts_ingest": round(t6_ms, 1),
            "T7_tts_first_chunk": round(t7_ms, 1),
            "T8_speaker_starts": round(t8_ms, 1),
        },
        "stage_durations_ms": {
            "stt_finalization": round(stt_latency_ms, 1),
            "packet_crypto": round(crypto_ms, 1),
            "network_transit": round(net_latency_ms, 1),
            "packet_validation": round(ingest_ms, 1),
            "tts_first_audio": round(tts_latency_ms, 1),
            "audiotrack_dac": round(dac_buffer_ms, 1),
        },
        "summary": {
            "speech_to_heard_latency_s": round(e2e_latency_ms / 1000.0, 2),
            "total_turnaround_s": round(total_pipeline_ms / 1000.0, 2),
        },
        "bandwidth_efficiency": {
            "raw_pcm_bytes": raw_audio_bytes,
            "raw_pcm_bitrate_kbps": 256.0,
            "it_frame_bytes": it_total_frame_bytes,
            "compression_ratio": f"{round(compression_ratio, 1)}:1",
            "bandwidth_reduction_pct": round(bandwidth_saved_pct, 2),
        },
    }
