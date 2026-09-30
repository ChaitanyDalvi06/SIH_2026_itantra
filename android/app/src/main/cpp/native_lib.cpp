#include <jni.h>
#include <string>
#include <memory>
#include "audio_ring_buffer.h"
#include "vad_energy.h"

static std::unique_ptr<itantra::AudioRingBuffer> g_ring_buffer;
static std::unique_ptr<itantra::EnergyVad> g_vad;

extern "C" {

JNIEXPORT void JNICALL
Java_com_itantra_edgespeech_audio_RingBufferJni_nativeInitRingBuffer(
    JNIEnv* env, jobject /* this */, jint capacity_samples) {
    g_ring_buffer = std::make_unique<itantra::AudioRingBuffer>(static_cast<size_t>(capacity_samples));
    g_vad = std::make_unique<itantra::EnergyVad>();
}

JNIEXPORT jint JNICALL
Java_com_itantra_edgespeech_audio_RingBufferJni_nativeWritePcm(
    JNIEnv* env, jobject /* this */, jshortArray pcm_data, jint count) {
    if (!g_ring_buffer) return 0;

    jshort* body = env->GetShortArrayElements(pcm_data, nullptr);
    size_t written = g_ring_buffer->write(reinterpret_cast<const int16_t*>(body), static_cast<size_t>(count));
    env->ReleaseShortArrayElements(pcm_data, body, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT jint JNICALL
Java_com_itantra_edgespeech_audio_RingBufferJni_nativeReadPcm(
    JNIEnv* env, jobject /* this */, jshortArray dest, jint count) {
    if (!g_ring_buffer) return 0;

    jshort* body = env->GetShortArrayElements(dest, nullptr);
    size_t read_bytes = g_ring_buffer->read(reinterpret_cast<int16_t*>(body), static_cast<size_t>(count));
    env->ReleaseShortArrayElements(dest, body, 0);
    return static_cast<jint>(read_bytes);
}

JNIEXPORT jboolean JNICALL
Java_com_itantra_edgespeech_audio_RingBufferJni_nativeIsSpeech(
    JNIEnv* env, jobject /* this */, jshortArray frame_pcm, jint count) {
    if (!g_vad) return JNI_FALSE;

    jshort* body = env->GetShortArrayElements(frame_pcm, nullptr);
    bool speech = g_vad->is_speech(reinterpret_cast<const int16_t*>(body), static_cast<size_t>(count));
    env->ReleaseShortArrayElements(frame_pcm, body, JNI_ABORT);
    return speech ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloat JNICALL
Java_com_itantra_edgespeech_audio_RingBufferJni_nativeComputeEnergy(
    JNIEnv* env, jobject /* this */, jshortArray frame_pcm, jint count) {
    if (!g_vad) return 0.0f;

    jshort* body = env->GetShortArrayElements(frame_pcm, nullptr);
    float energy = g_vad->compute_rms_energy(reinterpret_cast<const int16_t*>(body), static_cast<size_t>(count));
    env->ReleaseShortArrayElements(frame_pcm, body, JNI_ABORT);
    return energy;
}

} // extern "C"
