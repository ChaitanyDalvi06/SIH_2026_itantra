#ifndef VAD_ENERGY_H
#define VAD_ENERGY_H

#include <cstdint>
#include <cstddef>

namespace itantra {

class EnergyVad {
public:
    EnergyVad(float min_energy_threshold = 320.0f,
              float zcr_min = 0.06f,
              float zcr_max = 0.45f,
              float snr_ratio = 2.2f);
    ~EnergyVad() = default;

    bool is_speech(const int16_t* frame_pcm, size_t sample_count);
    float compute_rms_energy(const int16_t* frame_pcm, size_t sample_count);
    float get_noise_floor() const { return noise_floor_; }
    void reset_noise_floor(float initial_floor = 200.0f) { noise_floor_ = initial_floor; }

private:
    float min_energy_threshold_;
    float zcr_min_;
    float zcr_max_;
    float snr_ratio_;
    float noise_floor_;
};

} // namespace itantra

#endif // VAD_ENERGY_H
