# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors

cmake_minimum_required(VERSION 3.25)
project(duoime_rime LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(DUOIME_RIME_STAGE_DIR "" CACHE PATH "Prefix containing staged static librime and dependency libs/headers")
set(DUOIME_BOOST_INCLUDE_DIR "" CACHE PATH "Path to Boost header-only include root")

if(NOT DUOIME_RIME_STAGE_DIR OR NOT EXISTS "${DUOIME_RIME_STAGE_DIR}/include/rime_api.h")
  message(FATAL_ERROR "DUOIME_RIME_STAGE_DIR must point to staged librime prefix containing include/rime_api.h")
endif()

if(NOT DUOIME_BOOST_INCLUDE_DIR OR NOT EXISTS "${DUOIME_BOOST_INCLUDE_DIR}/boost/version.hpp")
  message(FATAL_ERROR "DUOIME_BOOST_INCLUDE_DIR must point to Boost include root containing boost/version.hpp")
endif()

function(duoime_require_static_lib out_var)
  set(found "")
  foreach(name IN LISTS ARGN)
    foreach(subdir lib lib64)
      set(candidate "${DUOIME_RIME_STAGE_DIR}/${subdir}/lib${name}.a")
      if(EXISTS "${candidate}")
        set(found "${candidate}")
        break()
      endif()
    endforeach()
    if(found)
      break()
    endif()
  endforeach()
  if(NOT found)
    message(FATAL_ERROR "Missing required static library (tried: ${ARGN}) under ${DUOIME_RIME_STAGE_DIR}")
  endif()
  set(${out_var} "${found}" PARENT_SCOPE)
endfunction()

duoime_require_static_lib(RIME_STATIC_LIB rime)
duoime_require_static_lib(OPENCC_STATIC_LIB opencc)
duoime_require_static_lib(YAML_CPP_STATIC_LIB yaml-cpp)
duoime_require_static_lib(LEVELDB_STATIC_LIB leveldb)
duoime_require_static_lib(MARISA_STATIC_LIB marisa)

add_library(duoime_rime SHARED
  jni/duoime_rime_jni.cc
)

target_include_directories(duoime_rime PRIVATE
  "${DUOIME_RIME_STAGE_DIR}/include"
  "${DUOIME_BOOST_INCLUDE_DIR}"
)

target_compile_definitions(duoime_rime PRIVATE
  BOOST_REGEX_STANDALONE
  YAML_CPP_STATIC_DEFINE
  Opencc_BUILT_AS_STATIC
)

target_compile_options(duoime_rime PRIVATE
  -O2
  -fvisibility=hidden
  -fvisibility-inlines-hidden
  -ffunction-sections
  -fdata-sections
  -Wall
  -Wextra
  -Wno-unused-parameter
)

set(DUOIME_EXPORTS_MAP "${CMAKE_CURRENT_SOURCE_DIR}/jni/exports.map")

target_link_options(duoime_rime PRIVATE
  -Wl,-z,max-page-size=16384
  -Wl,-z,common-page-size=16384
  -Wl,--gc-sections
  -Wl,--exclude-libs,ALL
  "-Wl,--version-script=${DUOIME_EXPORTS_MAP}"
)

set_target_properties(duoime_rime PROPERTIES
  LINK_DEPENDS "${DUOIME_EXPORTS_MAP}"
)

# Link librime with --whole-archive so all RIME_REGISTER_MODULE initializers
# (core, dict, gears, levers, and merged lua plugin) are preserved in .init_array.
target_link_libraries(duoime_rime PRIVATE
  -Wl,--whole-archive
  "${RIME_STATIC_LIB}"
  -Wl,--no-whole-archive
  "${OPENCC_STATIC_LIB}"
  "${YAML_CPP_STATIC_LIB}"
  "${LEVELDB_STATIC_LIB}"
  "${MARISA_STATIC_LIB}"
  log
  android
  dl
  m
)
