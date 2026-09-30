"""
iTantra Master Benchmark Runner
Executes efficiency, accuracy, and latency evaluations across all 10 languages
and outputs a formatted engineering performance table.
"""

import json
from pathlib import Path
from benchmarks.latency_benchmarks import calculate_pipeline_latency


def main():
    root = Path(__file__).parent
    matrix_file = root / "model_matrix.json"
    with open(matrix_file, "r", encoding="utf-8") as f:
        data = json.load(f)

    print("=" * 85)
    print("       iTantra: EdgeSpeech Transceiver - Master Evaluation Matrix")
    print("=" * 85)
    print(f"Target Profiles: Low-End (2-4GB), Mid-Range (6-8GB), High-End (12GB+)")
    print(f"Protocol: Binary Encrypted Text (Magic: 0x4954, AuthTag: 16B)")
    print("-" * 85)
    header = f"{'Lang':<6} | {'STT Model':<20} | {'Size':<8} | {'RAM':<8} | {'WER(%)':<8} | {'RTF':<6} | {'E2E Lat'}"
    print(header)
    print("-" * 85)

    for lang in data["languages"]:
        size_str = f"{lang['stt_size_mb']}MB"
        ram_str = f"{lang['stt_ram_mb']}MB"
        wer_str = f"{lang['stt_wer_int8']}%"
        rtf_str = f"{lang['stt_rtf_int8']}"
        lat_str = f"{lang['e2e_median_latency_s']}s"
        row = f"{lang['code']:<6} | {lang['stt_model']:<20} | {size_str:<8} | {ram_str:<8} | {wer_str:<8} | {rtf_str:<6} | {lat_str}"
        print(row)

    print("=" * 85)

    # Calculate and display latency waterfall & bandwidth reduction for Hindi
    hindi_waterfall = calculate_pipeline_latency(speech_duration_sec=2.0, device_profile="low_end")
    print("\n--- End-to-End Latency Waterfall (Low-End Phone, 2.0s Hindi Utterance) ---")
    for stage, dur in hindi_waterfall["stage_durations_ms"].items():
        print(f"  • {stage:<22}: {dur:>6.1f} ms")

    bw = hindi_waterfall["bandwidth_efficiency"]
    print(f"\n--- Bandwidth & Compression Comparison ---")
    print(f"  • Raw Audio PCM (16kHz 16-bit mono) : {bw['raw_pcm_bytes']:,} bytes ({bw['raw_pcm_bitrate_kbps']} kbps)")
    print(f"  • iTantra Encrypted Frame            : {bw['it_frame_bytes']} bytes")
    print(f"  • Bandwidth Reduction                : {bw['bandwidth_reduction_pct']}% ({bw['compression_ratio']} compression)")
    print("=" * 85)


if __name__ == "__main__":
    main()
