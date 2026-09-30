cmake_minimum_required(VERSION 3.22.1)

project("edgespeech")

add_library(
    edgespeech
    SHARED
    native_lib.cpp
    audio_ring_buffer.cpp
    vad_energy.cpp
)

find_library(
    log-lib
    log
)

target_include_directories(
    edgespeech
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(
    edgespeech
    ${log-lib}
)
