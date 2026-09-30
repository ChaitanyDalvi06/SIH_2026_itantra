#include "audio_ring_buffer.h"
#include <algorithm>
#include <cstring>

namespace itantra {

AudioRingBuffer::AudioRingBuffer(size_t capacity_samples)
    : buffer_(capacity_samples + 1, 0), capacity_(capacity_samples + 1) {}

size_t AudioRingBuffer::available_read() const {
    size_t w = write_pos_.load(std::memory_order_acquire);
    size_t r = read_pos_.load(std::memory_order_relaxed);
    if (w >= r) {
        return w - r;
    }
    return capacity_ - (r - w);
}

size_t AudioRingBuffer::available_write() const {
    return (capacity_ - 1) - available_read();
}

size_t AudioRingBuffer::write(const int16_t* data, size_t count) {
    size_t writable = std::min(count, available_write());
    if (writable == 0) return 0;

    size_t w = write_pos_.load(std::memory_order_relaxed);
    size_t first_part = std::min(writable, capacity_ - w);
    size_t second_part = writable - first_part;

    std::memcpy(&buffer_[w], data, first_part * sizeof(int16_t));
    if (second_part > 0) {
        std::memcpy(&buffer_[0], data + first_part, second_part * sizeof(int16_t));
    }

    write_pos_.store((w + writable) % capacity_, std::memory_order_release);
    return writable;
}

size_t AudioRingBuffer::read(int16_t* destination, size_t count) {
    size_t readable = std::min(count, available_read());
    if (readable == 0) return 0;

    size_t r = read_pos_.load(std::memory_order_relaxed);
    size_t first_part = std::min(readable, capacity_ - r);
    size_t second_part = readable - first_part;

    std::memcpy(destination, &buffer_[r], first_part * sizeof(int16_t));
    if (second_part > 0) {
        std::memcpy(destination + first_part, &buffer_[0], second_part * sizeof(int16_t));
    }

    read_pos_.store((r + readable) % capacity_, std::memory_order_release);
    return readable;
}

void AudioRingBuffer::clear() {
    write_pos_.store(0, std::memory_order_relaxed);
    read_pos_.store(0, std::memory_order_relaxed);
}

} // namespace itantra
