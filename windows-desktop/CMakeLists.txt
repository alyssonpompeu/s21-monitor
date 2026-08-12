cmake_minimum_required(VERSION 3.24)
project(IAOfflineWindows LANGUAGES CXX RC)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(IA-Offline-Windows-x64 WIN32
  src/main.cpp
  app.rc
)

target_compile_definitions(IA-Offline-Windows-x64 PRIVATE
  UNICODE
  _UNICODE
  NOMINMAX
  WIN32_LEAN_AND_MEAN
  WINVER=0x0A00
  _WIN32_WINNT=0x0A00
)

target_link_libraries(IA-Offline-Windows-x64 PRIVATE comctl32 shell32 psapi)

if(MSVC)
  set_property(TARGET IA-Offline-Windows-x64 PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
  target_compile_options(IA-Offline-Windows-x64 PRIVATE
    /W4 /permissive- /EHsc /utf-8
    $<$<CONFIG:Release>:/O2>
    $<$<CONFIG:Release>:/Ob3>
    $<$<CONFIG:Release>:/GL>
    $<$<CONFIG:Release>:/arch:AVX2>
  )
  target_link_options(IA-Offline-Windows-x64 PRIVATE $<$<CONFIG:Release>:/LTCG>)
else()
  target_compile_options(IA-Offline-Windows-x64 PRIVATE -Wall -Wextra -Wpedantic -O3 -mavx2 -mfma)
endif()
