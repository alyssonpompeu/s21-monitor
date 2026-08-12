cmake_minimum_required(VERSION 3.24)
project(IAOfflineWindows LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# CMake/MSVC already emits an embedded Windows manifest for WIN32 executables.
# Do not also inject app.rc here, otherwise newer MSVC linkers report duplicate MANIFEST resources.
add_executable(IA-Offline-Core-x64 WIN32 src/main.cpp)
add_executable(IA-Offline-Windows10-Ryzen3500X-RTX2070S-x64 WIN32 src/launcher.cpp)

foreach(target IA-Offline-Core-x64 IA-Offline-Windows10-Ryzen3500X-RTX2070S-x64)
  target_compile_definitions(${target} PRIVATE
    UNICODE
    _UNICODE
    NOMINMAX
    WIN32_LEAN_AND_MEAN
    WINVER=0x0A00
    _WIN32_WINNT=0x0A00
  )
  target_link_libraries(${target} PRIVATE shell32 psapi)

  if(MSVC)
    set_property(TARGET ${target} PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    target_compile_options(${target} PRIVATE
      /W4 /permissive- /EHsc /utf-8
      $<$<CONFIG:Release>:/O2>
      $<$<CONFIG:Release>:/Ob3>
      $<$<CONFIG:Release>:/GL>
      $<$<CONFIG:Release>:/arch:AVX2>
    )
    target_link_options(${target} PRIVATE $<$<CONFIG:Release>:/LTCG>)
  else()
    target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic -O3 -mavx2 -mfma)
  endif()
endforeach()

target_link_libraries(IA-Offline-Core-x64 PRIVATE comctl32)
