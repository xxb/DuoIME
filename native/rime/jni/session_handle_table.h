// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
//
// Opaque monotonic positive int64 handle table for mapping JNI session handles
// to native RimeSessionId values without exposing raw (potentially ARM64-tagged)
// pointers across the JNI boundary.
//
// Callers must hold external synchronization (e.g., g_rime_mutex) when invoking
// any method on a shared SessionHandleTable instance.

#ifndef DUOIME_RIME_JNI_SESSION_HANDLE_TABLE_H_
#define DUOIME_RIME_JNI_SESSION_HANDLE_TABLE_H_

#include <cstddef>
#include <cstdint>
#include <limits>
#include <unordered_map>

namespace duoime::rime {

class SessionHandleTable {
 public:
  int64_t allocate(uint64_t native_id) {
    if (native_id == 0) {
      return 0;
    }
    constexpr int64_t kMaxHandle = std::numeric_limits<int64_t>::max();
    while (true) {
      const int64_t candidate = next_handle_;
      next_handle_ = (next_handle_ == kMaxHandle) ? 1 : (next_handle_ + 1);
      if (candidate > 0 && handles_.find(candidate) == handles_.end()) {
        handles_.emplace(candidate, native_id);
        return candidate;
      }
    }
  }

  uint64_t resolve(int64_t handle) const {
    if (handle <= 0) {
      return 0;
    }
    const auto it = handles_.find(handle);
    return it != handles_.end() ? it->second : 0;
  }

  uint64_t erase(int64_t handle) {
    if (handle <= 0) {
      return 0;
    }
    const auto it = handles_.find(handle);
    if (it == handles_.end()) {
      return 0;
    }
    const uint64_t native_id = it->second;
    handles_.erase(it);
    return native_id;
  }

  void clear() noexcept { handles_.clear(); }

  bool empty() const noexcept { return handles_.empty(); }

  size_t size() const noexcept { return handles_.size(); }

  void setNextHandleForTesting(int64_t next_handle) noexcept {
    next_handle_ = next_handle > 0 ? next_handle : 1;
  }

 private:
  std::unordered_map<int64_t, uint64_t> handles_;
  int64_t next_handle_ = 1;
};

}  // namespace duoime::rime

#endif  // DUOIME_RIME_JNI_SESSION_HANDLE_TABLE_H_
