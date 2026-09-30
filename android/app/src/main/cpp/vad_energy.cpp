#include "vad_energy.h"
#include <cmath>
#include <algorithm>

namespace itantra {

EnergyVad::EnergyVad(float min_energy_threshold, float zcr_min, float zcr_max, float snr_ratio)
    : min_energy_threshold_(min_energy_threshold),
      zcr_min_(zcr_min),
      zcr_max_(zcr_max),
      snr_ratio_(snr_ratio),
      noise_floor_(200.0f) {}

float EnergyVad::compute_rms_energy(const int16_t* frame_pcm, size_t sample_count) {
    if (sample_count == 0) return 0.0f;
    double sum_squares = 0.0;
    for (size_t i = 0; i < sample_count; ++i) {
        double val = static_cast<double>(frame_pcm[i]);
        sum_squares += val * val;
    }
    return static_cast<float>(std::sqrt(sum_squares / sample_count));
}

bool EnergyVad::is_speech(const int16_t* frame_pcm, size_t sample_count) {
    if (sample_count < 2) return false;

    float rms = compute_rms_energy(frame_pcm, sample_count);

    // Dynamic SNR threshold: requires speech to stand out above ambient background
    float dynamic_threshold = std::max(min_energy_threshold_, noise_floor_ * snr_ratio_);

    // Zero Crossing Rate (ZCR)
    size_t zero_crossings = 0;
    for (size_t i = 1; i < sample_count; ++i) {
        if ((frame_pcm[i] >= 0 && frame_pcm[i - 1] < 0) ||
            (frame_pcm[i] < 0 && frame_pcm[i - 1] >= 0)) {
            zero_crossings++;
        }
    }
    float zcr = static_cast<float>(zero_crossings) / static_cast<float>(sample_count);

    // Speech check: must exceed dynamic SNR threshold and lie within human speech ZCR envelope
    bool is_speech_candidate = (rms >= dynamic_threshold) && (zcr >= zcr_min_ && zcr <= zcr_max_);

    if (!is_speech_candidate) {
        // Adaptively update noise floor during quiet periods using Exponential Moving Average (EMA)
        // Constrain noise floor between 80.0 and 1500.0 to prevent runaway
        float updated = 0.95f * noise_floor_ + 0.05f * rms;
        noise_floor_ = std::max(80.0f, std::min(1500.0f, updated));
        return false;
    }

    return true;
}

} // namespace itantra
