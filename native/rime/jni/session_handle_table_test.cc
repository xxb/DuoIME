// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
//
// Standalone C++17 host unit tests for duoime::rime::SessionHandleTable.

#include "session_handle_table.h"

#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <limits>

namespace {

int g_failures = 0;

void expectTrue(bool cond, const char* expr, int line) {
  if (!cond) {
    std::cerr << "FAIL (line " << line << "): " << expr << '\n';
    ++g_failures;
  }
}

#define EXPECT_TRUE(expr) expectTrue((expr), #expr, __LINE__)
#define EXPECT_EQ(actual, expected) \
  expectTrue(((actual) == (expected)), #actual " == " #expected, __LINE__)

void testArm64TaggedPointerAndInvalidLookups() {
  duoime::rime::SessionHandleTable table;
  constexpr uint64_t kArm64TaggedPtr = 0xb4000072a1c34000ULL;

  // Reject zero native IDs.
  EXPECT_EQ(table.allocate(0), 0);
  EXPECT_EQ(table.size(), static_cast<size_t>(0));

  // Map ARM64 Scudo tagged pointer (top byte 0xb4, negative as signed int64_t)
  // to an opaque positive handle without exposing raw pointer bits.
  const int64_t handle = table.allocate(kArm64TaggedPtr);
  EXPECT_EQ(handle, 1);
  EXPECT_TRUE(handle > 0);
  EXPECT_TRUE(static_cast<uint64_t>(handle) != kArm64TaggedPtr);
  EXPECT_EQ(table.resolve(handle), kArm64TaggedPtr);

  // Raw tagged pointer, zero, negative, and unknown handles must not resolve.
  EXPECT_EQ(table.resolve(static_cast<int64_t>(kArm64TaggedPtr)), 0ULL);
  EXPECT_EQ(table.resolve(0), 0ULL);
  EXPECT_EQ(table.resolve(-1), 0ULL);
  EXPECT_EQ(table.resolve(999), 0ULL);
}

void testMonotonicAllocationAndEraseOnce() {
  duoime::rime::SessionHandleTable table;
  constexpr uint64_t kPtr1 = 0xb400007000001000ULL;
  constexpr uint64_t kPtr2 = 0xb400007000002000ULL;

  const int64_t h1 = table.allocate(kPtr1);
  const int64_t h2 = table.allocate(kPtr2);
  EXPECT_EQ(h1, 1);
  EXPECT_EQ(h2, 2);
  EXPECT_EQ(table.size(), static_cast<size_t>(2));

  // Erase returns exact native ID once, then refuses stale/double erase.
  EXPECT_EQ(table.erase(h1), kPtr1);
  EXPECT_EQ(table.resolve(h1), 0ULL);
  EXPECT_EQ(table.erase(h1), 0ULL);
  EXPECT_EQ(table.resolve(h2), kPtr2);
  EXPECT_EQ(table.size(), static_cast<size_t>(1));

  // Invalid handles cannot erase anything.
  EXPECT_EQ(table.erase(0), 0ULL);
  EXPECT_EQ(table.erase(-42), 0ULL);
  EXPECT_EQ(table.size(), static_cast<size_t>(1));
}

void testWrapAroundSkipsLiveHandles() {
  duoime::rime::SessionHandleTable table;
  constexpr int64_t kMax = std::numeric_limits<int64_t>::max();
  constexpr uint64_t kPtr1 = 0xb400007100000001ULL;
  constexpr uint64_t kPtrMax = 0xb400007100000002ULL;
  constexpr uint64_t kPtrWrapped = 0xb400007100000003ULL;

  const int64_t h1 = table.allocate(kPtr1);
  EXPECT_EQ(h1, 1);

  table.setNextHandleForTesting(kMax);
  const int64_t hMax = table.allocate(kPtrMax);
  EXPECT_EQ(hMax, kMax);

  // Next allocation wraps INT64_MAX -> 1, skips live handle 1, lands on 2.
  const int64_t hWrapped = table.allocate(kPtrWrapped);
  EXPECT_EQ(hWrapped, 2);
  EXPECT_EQ(table.resolve(h1), kPtr1);
  EXPECT_EQ(table.resolve(hMax), kPtrMax);
  EXPECT_EQ(table.resolve(hWrapped), kPtrWrapped);
}

void testClearInvalidatesAllAndContinuesMonotonically() {
  duoime::rime::SessionHandleTable table;
  constexpr uint64_t kPtr1 = 0xb400007200000001ULL;
  constexpr uint64_t kPtr2 = 0xb400007200000002ULL;
  constexpr uint64_t kPtr3 = 0xb400007200000003ULL;

  const int64_t h1 = table.allocate(kPtr1);
  const int64_t h2 = table.allocate(kPtr2);
  table.clear();

  EXPECT_EQ(table.size(), static_cast<size_t>(0));
  EXPECT_EQ(table.resolve(h1), 0ULL);
  EXPECT_EQ(table.resolve(h2), 0ULL);

  // New allocations after clear() do not immediately alias old handles.
  const int64_t h3 = table.allocate(kPtr3);
  EXPECT_EQ(h3, 3);
  EXPECT_EQ(table.resolve(h3), kPtr3);
}

}  // namespace

int main() {
  testArm64TaggedPointerAndInvalidLookups();
  testMonotonicAllocationAndEraseOnce();
  testWrapAroundSkipsLiveHandles();
  testClearInvalidatesAllAndContinuesMonotonically();
  if (g_failures != 0) {
    std::cerr << g_failures << " SessionHandleTable test assertion(s) failed.\n";
    return EXIT_FAILURE;
  }
  std::cout << "SessionHandleTable C++17 unit tests passed (4 test cases).\n";
  return EXIT_SUCCESS;
}
