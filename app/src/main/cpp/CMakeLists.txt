cmake_minimum_required(VERSION 3.22)
project(oboegaki)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

find_library(LOG_LIB log)
include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR ${WHISPER_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

# One plain ARMv8 build. The Kompakt is a Helio A22 (MT6761): four Cortex-A53s with no
# half-precision arithmetic and no dot product, so anything built for armv8.2 would not run.
add_library(hearing SHARED ${WHISPER_DIR}/src/whisper.cpp ${CMAKE_SOURCE_DIR}/hearing.cpp)
target_compile_definitions(hearing PUBLIC GGML_USE_CPU)
target_compile_definitions(hearing PRIVATE WHISPER_VERSION="1.9.4")
target_compile_options(hearing PRIVATE -O3)
target_include_directories(hearing PRIVATE
    ${WHISPER_DIR} ${WHISPER_DIR}/src ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include ${WHISPER_DIR}/ggml/src ${WHISPER_DIR}/ggml/src/ggml-cpu)
target_link_libraries(hearing ${LOG_LIB} android ggml)
