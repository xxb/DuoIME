#!/usr/bin/env bash
# SPDX-License-Identifier: BSD-3-Clause
# Copyright (c) 2026 DuoIME Contributors
#
# Reproducible Android NDK build script for DuoIME's 100%-permissive native Rime
# library (libduoime_rime.so = librime + librime-lua + Lua 5.4 + OpenCC +
# yaml-cpp + leveldb + marisa-trie [BSD-2-Clause] + Boost header-only + JNI).
#
# Usage:
#   ANDROID_NDK_HOME=/path/to/ndk bash native/rime/build_android.sh \
#     [--abi arm64-v8a] [--api 26] [--work-dir /tmp/work] \
#     [--cache-dir /tmp/cache] [--output-dir /tmp/out]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=versions.env disable=SC1091
source "${SCRIPT_DIR}/versions.env"

ABI="${ANDROID_ABI_DEFAULT}"
API_LEVEL="${ANDROID_API_LEVEL}"
WORK_DIR=""
CACHE_DIR=""
OUTPUT_DIR=""
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

usage() {
    cat <<EOF >&2
Usage: $0 [--abi <abi>] [--api <level>] [--work-dir <dir>] [--cache-dir <dir>] [--output-dir <dir>]
Requires ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) pointing to Android NDK r27+.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --abi)
            ABI="${2:?missing value for --abi}"
            shift 2
            ;;
        --api)
            API_LEVEL="${2:?missing value for --api}"
            shift 2
            ;;
        --work-dir)
            WORK_DIR="${2:?missing value for --work-dir}"
            shift 2
            ;;
        --cache-dir)
            CACHE_DIR="${2:?missing value for --cache-dir}"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="${2:?missing value for --output-dir}"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $1" >&2
            usage
            exit 2
            ;;
    esac
done

case "$ABI" in
    arm64-v8a|x86_64|armeabi-v7a) ;;
    *)
        echo "ERROR: unsupported Android ABI: $ABI" >&2
        exit 2
        ;;
esac

NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
    echo "ERROR: ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) must point to an installed Android NDK r27+." >&2
    exit 1
fi

TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake"
if [[ ! -f "$TOOLCHAIN_FILE" ]]; then
    echo "ERROR: NDK CMake toolchain file missing: $TOOLCHAIN_FILE" >&2
    exit 1
fi

REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TEMP_WORK_CREATED=0
if [[ -z "$WORK_DIR" ]]; then
    WORK_DIR="$(mktemp -d)"
    TEMP_WORK_CREATED=1
fi
WORK_DIR="$(mkdir -p "$WORK_DIR" && cd "$WORK_DIR" && pwd)"

if [[ -z "$CACHE_DIR" ]]; then
    CACHE_DIR="${WORK_DIR}/downloads"
fi
CACHE_DIR="$(mkdir -p "$CACHE_DIR" && cd "$CACHE_DIR" && pwd)"

if [[ -z "$OUTPUT_DIR" ]]; then
    OUTPUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/duoime-rime-dist.XXXXXX")"
fi
OUTPUT_DIR="$(mkdir -p "$OUTPUT_DIR" && cd "$OUTPUT_DIR" && pwd)"

case "$WORK_DIR" in
    "${REPO_ROOT}"|"${REPO_ROOT}"/*)
        echo "ERROR: --work-dir must be outside the git repository (${REPO_ROOT})." >&2
        exit 1
        ;;
esac
case "$OUTPUT_DIR" in
    "${REPO_ROOT}"|"${REPO_ROOT}"/*)
        echo "ERROR: --output-dir must be outside the git repository (${REPO_ROOT})." >&2
        exit 1
        ;;
esac
if (( TEMP_WORK_CREATED == 1 )); then
    case "$OUTPUT_DIR" in
        "${WORK_DIR}"|"${WORK_DIR}"/*)
            echo "ERROR: --output-dir must not live inside the temporary work directory (${WORK_DIR})." >&2
            exit 1
            ;;
    esac
fi

cleanup() {
    if (( TEMP_WORK_CREATED == 1 )) && [[ -d "$WORK_DIR" ]]; then
        rm -rf "$WORK_DIR"
    fi
}
trap cleanup EXIT

CMAKE_GENERATOR_ARGS=()
if command -v ninja >/dev/null 2>&1; then
    CMAKE_GENERATOR_ARGS=(-G Ninja)
fi

find_ndk_executable() {
    local tool_name="$1"
    local candidate=""
    while IFS= read -r candidate; do
        if [[ -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done < <(find "${NDK_DIR}/toolchains/llvm/prebuilt" -maxdepth 3 \( -type f -o -type l \) -name "$tool_name" 2>/dev/null || true)
    return 1
}

LLVM_STRIP="$(find_ndk_executable llvm-strip || true)"
LLVM_READELF="$(find_ndk_executable llvm-readelf || true)"
if [[ -z "$LLVM_STRIP" ]]; then
    echo "ERROR: executable llvm-strip not found under NDK prebuilt toolchain: ${NDK_DIR}" >&2
    exit 1
fi
if [[ -z "$LLVM_READELF" ]]; then
    echo "ERROR: executable llvm-readelf not found under NDK prebuilt toolchain: ${NDK_DIR}" >&2
    exit 1
fi
if ! command -v patch >/dev/null 2>&1; then
    echo "ERROR: required host tool 'patch' not found in PATH." >&2
    exit 1
fi

SRC_DIR="${WORK_DIR}/src"
BUILD_DIR="${WORK_DIR}/build-${ABI}"
STAGE_DIR="${WORK_DIR}/stage-${ABI}"
rm -rf "$SRC_DIR" "$BUILD_DIR" "$STAGE_DIR"
mkdir -p "$SRC_DIR" "$BUILD_DIR" "$STAGE_DIR/lib" "$STAGE_DIR/include"

fetch_verified() {
    local filename="$1"
    local url="$2"
    local expected_sha="$3"
    local dest="${CACHE_DIR}/${filename}"

    if [[ -f "$dest" ]]; then
        local actual_sha
        actual_sha="$(sha256sum "$dest" | awk '{print $1}')"
        if [[ "$actual_sha" != "$expected_sha" ]]; then
            echo "==> Cached archive ${filename} checksum mismatch; re-downloading..."
            rm -f "$dest"
        fi
    fi

    if [[ ! -f "$dest" ]]; then
        echo "==> Downloading ${filename}..."
        curl -fsSL --retry 3 --retry-delay 2 -o "${dest}.part" "$url"
        mv "${dest}.part" "$dest"
    fi

    echo "${expected_sha}  ${dest}" | sha256sum -c - >/dev/null
    echo "==> Verified ${filename} (${expected_sha:0:12}...)"
}

extract_to_dir() {
    local archive="$1"
    local target_dir="$2"
    mkdir -p "$target_dir"
    case "$archive" in
        *.tar.gz|*.tgz)
            tar -xzf "$archive" -C "$target_dir" --strip-components=1
            ;;
        *.tar.xz)
            tar -xJf "$archive" -C "$target_dir" --strip-components=1
            ;;
        *)
            echo "ERROR: unsupported archive format: $archive" >&2
            exit 1
            ;;
    esac
}

echo "==> Fetching & verifying pinned Option A native sources..."
fetch_verified "librime-${LIBRIME_VERSION}.tar.gz" "$LIBRIME_URL" "$LIBRIME_SHA256"
fetch_verified "librime-lua-${LIBRIME_LUA_VERSION}.tar.gz" "$LIBRIME_LUA_URL" "$LIBRIME_LUA_SHA256"
fetch_verified "lua-${LUA_VERSION}.tar.gz" "$LUA_URL" "$LUA_SHA256"
fetch_verified "boost-${BOOST_VERSION}.tar.xz" "$BOOST_URL" "$BOOST_SHA256"
fetch_verified "yaml-cpp-${YAML_CPP_VERSION}.tar.gz" "$YAML_CPP_URL" "$YAML_CPP_SHA256"
fetch_verified "leveldb-${LEVELDB_VERSION}.tar.gz" "$LEVELDB_URL" "$LEVELDB_SHA256"
fetch_verified "marisa-trie-${MARISA_VERSION}.tar.gz" "$MARISA_URL" "$MARISA_SHA256"
fetch_verified "opencc-${OPENCC_VERSION}.tar.xz" "$OPENCC_URL" "$OPENCC_SHA256"

echo "==> Extracting sources into ${SRC_DIR}..."
extract_to_dir "${CACHE_DIR}/librime-${LIBRIME_VERSION}.tar.gz" "${SRC_DIR}/librime"

if [[ -d "${SCRIPT_DIR}/patches" ]]; then
    shopt -s nullglob
    PATCH_FILES=("${SCRIPT_DIR}/patches/"*.patch)
    shopt -u nullglob
    if (( ${#PATCH_FILES[@]} > 0 )); then
        mapfile -t PATCH_FILES < <(printf '%s\n' "${PATCH_FILES[@]}" | LC_ALL=C sort)
        for patch_file in "${PATCH_FILES[@]}"; do
            echo "==> Applying librime patch: $(basename "$patch_file")..."
            patch -d "${SRC_DIR}/librime" -p1 --batch --forward -E --fuzz=0 < "$patch_file"
        done
    fi
fi

extract_to_dir "${CACHE_DIR}/librime-lua-${LIBRIME_LUA_VERSION}.tar.gz" "${SRC_DIR}/librime/plugins/lua"
extract_to_dir "${CACHE_DIR}/lua-${LUA_VERSION}.tar.gz" "${SRC_DIR}/lua"
extract_to_dir "${CACHE_DIR}/boost-${BOOST_VERSION}.tar.xz" "${SRC_DIR}/boost"
extract_to_dir "${CACHE_DIR}/yaml-cpp-${YAML_CPP_VERSION}.tar.gz" "${SRC_DIR}/yaml-cpp"
extract_to_dir "${CACHE_DIR}/leveldb-${LEVELDB_VERSION}.tar.gz" "${SRC_DIR}/leveldb"
extract_to_dir "${CACHE_DIR}/marisa-trie-${MARISA_VERSION}.tar.gz" "${SRC_DIR}/marisa-trie"
extract_to_dir "${CACHE_DIR}/opencc-${OPENCC_VERSION}.tar.xz" "${SRC_DIR}/opencc"

# Wire in-tree Lua 5.4 sources for librime-lua (excluding CLI entrypoints lua.c / luac.c).
mkdir -p "${SRC_DIR}/librime/plugins/lua/thirdparty/lua5.4"
cp "${SRC_DIR}/lua/src/"*.c "${SRC_DIR}/lua/src/"*.h "${SRC_DIR}/lua/src/"*.hpp \
    "${SRC_DIR}/librime/plugins/lua/thirdparty/lua5.4/"
rm -f \
    "${SRC_DIR}/librime/plugins/lua/thirdparty/lua5.4/lua.c" \
    "${SRC_DIR}/librime/plugins/lua/thirdparty/lua5.4/luac.c" \
    "${SRC_DIR}/librime/plugins/lua/thirdparty/lua5.4/onelua.c"

COMMON_C_FLAGS="-O2 -ffunction-sections -fdata-sections -fvisibility=hidden"
COMMON_CXX_FLAGS="${COMMON_C_FLAGS} -fvisibility-inlines-hidden"
COMMON_CMAKE_ARGS=(
    "${CMAKE_GENERATOR_ARGS[@]}"
    "-DCMAKE_TOOLCHAIN_FILE=${TOOLCHAIN_FILE}"
    "-DANDROID_ABI=${ABI}"
    "-DANDROID_PLATFORM=android-${API_LEVEL}"
    "-DANDROID_STL=c++_static"
    "-DCMAKE_BUILD_TYPE=Release"
    "-DCMAKE_INSTALL_PREFIX=${STAGE_DIR}"
    "-DCMAKE_INSTALL_LIBDIR=lib"
    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
    "-DCMAKE_PREFIX_PATH=${STAGE_DIR}"
    "-DCMAKE_FIND_ROOT_PATH=${STAGE_DIR}"
)

echo "==> [1/6] Building marisa-trie ${MARISA_VERSION} (BSD-2-Clause elected)..."
cmake -S "${SRC_DIR}/marisa-trie" -B "${BUILD_DIR}/marisa-trie" \
    "${COMMON_CMAKE_ARGS[@]}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_TESTING=OFF \
    -DENABLE_NATIVE_CODE=OFF \
    -DENABLE_TOOLS=OFF \
    "-DCMAKE_C_FLAGS=${COMMON_C_FLAGS}" \
    "-DCMAKE_CXX_FLAGS=${COMMON_CXX_FLAGS}"
cmake --build "${BUILD_DIR}/marisa-trie" --parallel "$JOBS"
cmake --install "${BUILD_DIR}/marisa-trie"

echo "==> [2/6] Building yaml-cpp ${YAML_CPP_VERSION} (MIT)..."
cmake -S "${SRC_DIR}/yaml-cpp" -B "${BUILD_DIR}/yaml-cpp" \
    "${COMMON_CMAKE_ARGS[@]}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DYAML_BUILD_SHARED_LIBS=OFF \
    -DYAML_CPP_BUILD_TESTS=OFF \
    -DYAML_CPP_BUILD_TOOLS=OFF \
    -DYAML_CPP_BUILD_CONTRIB=OFF \
    -DYAML_CPP_INSTALL=ON \
    -DYAML_ENABLE_PIC=ON \
    "-DCMAKE_C_FLAGS=${COMMON_C_FLAGS}" \
    "-DCMAKE_CXX_FLAGS=${COMMON_CXX_FLAGS}"
cmake --build "${BUILD_DIR}/yaml-cpp" --parallel "$JOBS"
cmake --install "${BUILD_DIR}/yaml-cpp"

echo "==> [3/6] Building leveldb ${LEVELDB_VERSION:0:12} (BSD-3-Clause)..."
cmake -S "${SRC_DIR}/leveldb" -B "${BUILD_DIR}/leveldb" \
    "${COMMON_CMAKE_ARGS[@]}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DLEVELDB_BUILD_TESTS=OFF \
    -DLEVELDB_BUILD_BENCHMARKS=OFF \
    -DLEVELDB_INSTALL=ON \
    -DHAVE_CRC32C=0 \
    -DHAVE_SNAPPY=0 \
    -DHAVE_ZSTD=0 \
    -DHAVE_TCMALLOC=0 \
    "-DCMAKE_C_FLAGS=${COMMON_C_FLAGS}" \
    "-DCMAKE_CXX_FLAGS=${COMMON_CXX_FLAGS}"
cmake --build "${BUILD_DIR}/leveldb" --parallel "$JOBS"
cmake --install "${BUILD_DIR}/leveldb"

echo "==> [4/6] Building OpenCC ${OPENCC_VERSION} (Apache-2.0, static libopencc)..."
cmake -S "${SRC_DIR}/opencc" -B "${BUILD_DIR}/opencc" \
    "${COMMON_CMAKE_ARGS[@]}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DENABLE_GTEST=OFF \
    -DENABLE_BENCHMARK=OFF \
    -DBUILD_DOCUMENTATION=OFF \
    -DBUILD_PYTHON=OFF \
    -DBUILD_OPENCC_JIEBA_PLUGIN=OFF \
    -DUSE_SYSTEM_MARISA=ON \
    -DOPENCC_DICT_FORMAT=text \
    "-DCMAKE_C_FLAGS=${COMMON_C_FLAGS} -I${STAGE_DIR}/include" \
    "-DCMAKE_CXX_FLAGS=${COMMON_CXX_FLAGS} -I${STAGE_DIR}/include"
cmake --build "${BUILD_DIR}/opencc" --target libopencc --parallel "$JOBS"
cp "${BUILD_DIR}/opencc/src/libopencc.a" "${STAGE_DIR}/lib/libopencc.a"
mkdir -p "${STAGE_DIR}/include/opencc/plugin"
cp "${SRC_DIR}/opencc/src/"*.h "${SRC_DIR}/opencc/src/"*.hpp "${STAGE_DIR}/include/opencc/"
cp "${BUILD_DIR}/opencc/src/Opencc_Export.h" "${STAGE_DIR}/include/opencc/"
cp "${SRC_DIR}/opencc/src/plugin/OpenCCPlugin.h" "${STAGE_DIR}/include/opencc/plugin/"

echo "==> [5/6] Building librime ${LIBRIME_VERSION} + merged librime-lua + Lua ${LUA_VERSION}..."
cmake -S "${SRC_DIR}/librime" -B "${BUILD_DIR}/librime" \
    "${CMAKE_GENERATOR_ARGS[@]}" \
    "-DCMAKE_TOOLCHAIN_FILE=${TOOLCHAIN_FILE}" \
    "-DANDROID_ABI=${ABI}" \
    "-DANDROID_PLATFORM=android-${API_LEVEL}" \
    "-DANDROID_STL=c++_static" \
    "-DCMAKE_BUILD_TYPE=Release" \
    "-DCMAKE_INSTALL_PREFIX=${STAGE_DIR}" \
    "-DCMAKE_INSTALL_LIBDIR=lib" \
    "-DCMAKE_POSITION_INDEPENDENT_CODE=ON" \
    "-DCMAKE_PREFIX_PATH=${STAGE_DIR}" \
    "-DCMAKE_FIND_ROOT_PATH=${STAGE_DIR};${SRC_DIR}/boost" \
    "-DBOOST_ROOT=${SRC_DIR}/boost" \
    "-DBoost_INCLUDE_DIR=${SRC_DIR}/boost" \
    -DBoost_NO_BOOST_CMAKE=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DBUILD_STATIC=ON \
    -DBUILD_MERGED_PLUGINS=ON \
    -DBUILD_TEST=OFF \
    -DBUILD_DATA=OFF \
    -DBUILD_SAMPLE=OFF \
    -DENABLE_LOGGING=OFF \
    -DENABLE_EXTERNAL_PLUGINS=OFF \
    -DENABLE_THREADING=ON \
    -DBUILD_WITH_ICU=OFF \
    "-DYamlCpp_INCLUDE_PATH=${STAGE_DIR}/include" \
    "-DYamlCpp_LIBRARY=${STAGE_DIR}/lib/libyaml-cpp.a" \
    "-DLevelDb_INCLUDE_PATH=${STAGE_DIR}/include" \
    "-DLevelDb_LIBRARY=${STAGE_DIR}/lib/libleveldb.a" \
    "-DMarisa_INCLUDE_PATH=${STAGE_DIR}/include" \
    "-DMarisa_LIBRARY=${STAGE_DIR}/lib/libmarisa.a" \
    "-DOpencc_INCLUDE_PATH=${STAGE_DIR}/include" \
    "-DOpencc_LIBRARY=${STAGE_DIR}/lib/libopencc.a" \
    "-DCMAKE_C_FLAGS=${COMMON_C_FLAGS}" \
    "-DCMAKE_CXX_FLAGS=${COMMON_CXX_FLAGS} -DRIME_DLL= -DBOOST_REGEX_STANDALONE -I${SRC_DIR}/boost -I${STAGE_DIR}/include"
cmake --build "${BUILD_DIR}/librime" --parallel "$JOBS"
cmake --install "${BUILD_DIR}/librime"

echo "==> [6/6] Building clean-room JNI libduoime_rime.so (16 KB page aligned)..."
cmake -S "${SCRIPT_DIR}" -B "${BUILD_DIR}/jni" \
    "${COMMON_CMAKE_ARGS[@]}" \
    "-DDUOIME_RIME_STAGE_DIR=${STAGE_DIR}" \
    "-DDUOIME_BOOST_INCLUDE_DIR=${SRC_DIR}/boost"
cmake --build "${BUILD_DIR}/jni" --parallel "$JOBS"

RAW_SO="${BUILD_DIR}/jni/libduoime_rime.so"
if [[ ! -f "$RAW_SO" ]]; then
    echo "ERROR: expected built library missing: $RAW_SO" >&2
    exit 1
fi

BUNDLE_ROOT="${WORK_DIR}/bundle-${ABI}"
rm -rf "$BUNDLE_ROOT"
mkdir -p "${BUNDLE_ROOT}/jniLibs/${ABI}"
FINAL_SO="${BUNDLE_ROOT}/jniLibs/${ABI}/libduoime_rime.so"
cp "$RAW_SO" "$FINAL_SO"
"$LLVM_STRIP" --strip-unneeded "$FINAL_SO"

echo "==> Verifying ELF 16 KB PT_LOAD alignment, DT_NEEDED, and exported JNI symbols..."
python3 - "$FINAL_SO" "$LLVM_READELF" <<'PY'
import struct
import subprocess
import sys

so_path = sys.argv[1]
llvm_readelf = sys.argv[2]

with open(so_path, "rb") as f:
    data = f.read()

if data[:4] != b"\x7fELF":
    sys.exit("ERROR: output is not a valid ELF binary")

ei_class = data[4]
ei_data = data[5]
endian = "<" if ei_data == 1 else ">"
if ei_class == 2:
    e_phoff, = struct.unpack_from(endian + "Q", data, 32)
    e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 54)
    load_segments = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from(endian + "I", data, off)
        if p_type == 1:  # PT_LOAD
            p_offset, p_vaddr = struct.unpack_from(endian + "QQ", data, off + 8)
            p_align, = struct.unpack_from(endian + "Q", data, off + 48)
            load_segments.append((p_offset, p_vaddr, p_align))
elif ei_class == 1:
    e_phoff, = struct.unpack_from(endian + "I", data, 28)
    e_phentsize, e_phnum = struct.unpack_from(endian + "HH", data, 42)
    load_segments = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, = struct.unpack_from(endian + "I", data, off)
        if p_type == 1:  # PT_LOAD
            p_offset, p_vaddr = struct.unpack_from(endian + "II", data, off + 4)
            p_align, = struct.unpack_from(endian + "I", data, off + 28)
            load_segments.append((p_offset, p_vaddr, p_align))
else:
    sys.exit(f"ERROR: unexpected ELF class {ei_class}")

if not load_segments:
    sys.exit("ERROR: no PT_LOAD segments found")
for idx, (p_offset, p_vaddr, p_align) in enumerate(load_segments):
    if p_align < 16384 or (p_align % 16384) != 0:
        sys.exit(f"ERROR: PT_LOAD[{idx}] p_align={hex(p_align)} < 0x4000 (16 KB)")
    if (p_offset % 16384) != (p_vaddr % 16384) or (p_offset % p_align) != (p_vaddr % p_align):
        sys.exit(
            f"ERROR: PT_LOAD[{idx}] incongruent offset/vaddr "
            f"(p_offset={hex(p_offset)}, p_vaddr={hex(p_vaddr)}, p_align={hex(p_align)})"
        )
print(
    "ELF PT_LOAD 16 KB alignment & congruence verified: "
    f"{[hex(seg[2]) for seg in load_segments]}"
)

dyn_out = subprocess.check_output([llvm_readelf, "-d", so_path], text=True)
allowed_needed = {"liblog.so", "libandroid.so", "libc.so", "libm.so", "libdl.so"}
needed = []
for line in dyn_out.splitlines():
    if "(NEEDED)" in line and "[" in line and "]" in line:
        lib = line.split("[", 1)[1].split("]", 1)[0]
        needed.append(lib)
        if lib not in allowed_needed:
            sys.exit(f"ERROR: forbidden DT_NEEDED library: {lib} (allowed: {sorted(allowed_needed)})")
print(f"DT_NEEDED verified (system-only): {needed}")

syms_out = subprocess.check_output([llvm_readelf, "--dyn-syms", "-W", so_path], text=True)
exported = []
for line in syms_out.splitlines():
    parts = line.split()
    if len(parts) < 8 or not parts[0].endswith(":"):
        continue
    sym_type, bind, ndx, name = parts[3], parts[4], parts[6], parts[7]
    if ndx == "UND" or bind not in ("GLOBAL", "WEAK"):
        continue
    if sym_type != "FUNC":
        continue
    base_name = name.split("@", 1)[0]
    exported.append(base_name)

expected_jni = {
    "JNI_OnLoad",
    "JNI_OnUnload",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeInitialize",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeFinalize",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeDeployWorkspace",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeCreateSession",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeDestroySession",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeProcessKey",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetCommitText",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetSnapshot",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeSelectCandidateOnCurrentPage",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeChangePage",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeSelectSchema",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetCurrentSchema",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetOption",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeSetOption",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeClearComposition",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeCommitComposition",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetSchemaList",
    "Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetVersion",
}
exported_set = set(exported)
missing = sorted(expected_jni - exported_set)
unexpected = sorted(exported_set - expected_jni)
if missing:
    sys.exit(f"ERROR: missing expected exported JNI symbols: {missing}")
if unexpected:
    sys.exit(f"ERROR: unexpected leaked exported symbols: {unexpected}")
print(f"Exported JNI symbols verified ({len(exported_set)} exact symbols, 0 leaks).")
PY

SO_BYTES="$(wc -c < "$FINAL_SO" | tr -d '[:space:]')"
SO_SHA256="$(sha256sum "$FINAL_SO" | awk '{print $1}')"
cat > "${BUNDLE_ROOT}/manifest.json" <<EOF
{
  "abi": "${ABI}",
  "api_level": ${API_LEVEL},
  "stl": "c++_static",
  "library": "jniLibs/${ABI}/libduoime_rime.so",
  "size_bytes": ${SO_BYTES},
  "sha256": "${SO_SHA256}",
  "versions": {
    "librime": "${LIBRIME_VERSION}",
    "librime_lua": "${LIBRIME_LUA_VERSION}",
    "lua": "${LUA_VERSION}",
    "boost": "${BOOST_VERSION}",
    "yaml_cpp": "${YAML_CPP_VERSION}",
    "leveldb": "${LEVELDB_VERSION}",
    "marisa_trie": "${MARISA_VERSION}",
    "opencc": "${OPENCC_VERSION}"
  }
}
EOF

ARCHIVE_NAME="duoime-rime-native-${ABI}.tar.gz"
ARCHIVE_PATH="${OUTPUT_DIR}/${ARCHIVE_NAME}"
CHECKSUM_FILE="SHA256SUMS-${ABI}"
tar --sort=name --mtime='UTC 2026-01-01' --owner=0 --group=0 --numeric-owner \
    -cf - -C "$BUNDLE_ROOT" jniLibs manifest.json \
    | gzip -n -9 > "$ARCHIVE_PATH"

(
    cd "$OUTPUT_DIR"
    sha256sum "$ARCHIVE_NAME" > "$CHECKSUM_FILE"
)

echo "==> Built ${ARCHIVE_PATH} (libduoime_rime.so: ${SO_BYTES} bytes, sha256=${SO_SHA256})"
cat "${OUTPUT_DIR}/${CHECKSUM_FILE}"
