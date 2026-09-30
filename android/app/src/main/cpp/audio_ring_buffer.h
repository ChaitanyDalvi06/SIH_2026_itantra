#ifndef AUDIO_RING_BUFFER_H
#define AUDIO_RING_BUFFER_H

#include <vector>
#include <atomic>
#include <cstdint>
#include <cstddef>

namespace itantra {

class AudioRingBuffer {
public:
    explicit AudioRingBuffer(size_t capacity_samples);
    ~AudioRingBuffer() = default;

    size_t write(const int16_t* data, size_t count);
    size_t read(int16_t* destination, size_t count);
    size_t available_read() const;
    size_t available_write() const;
    void clear();

private:
    std::vector<int16_t> buffer_;
    size_t capacity_;
    std::atomic<size_t> write_pos_{0};
    std::atomic<size_t> read_pos_{0};
};

} // namespace itantra

#endif // AUDIO_RING_BUFFER_H
