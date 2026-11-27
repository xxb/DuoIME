// SPDX-License-Identifier: BSD-3-Clause
// Copyright (c) 2026 DuoIME Contributors
//
// Clean-room JNI bridge connecting dev.duoime.engine.rime.JniRimeNativeBridge
// to statically linked librime (+ merged librime-lua + OpenCC).
//
// String safety: ALL jstring <-> UTF-8 conversions go through UTF-16
// (GetStringChars / NewString) so supplementary Unicode characters (CJK Ext-B+,
// emoji) never encounter JNI Modified-UTF-8 corruption.

#include <jni.h>
#include <rime_api.h>

#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#define DUOIME_JNI_EXPORT extern "C" __attribute__((visibility("default")))

namespace {

constexpr int kSnapshotFieldCount = 14;
constexpr size_t kSchemaIdBufSize = 256;

std::mutex g_rime_mutex;
bool g_setup_done = false;
bool g_initialized = false;

// Owned copies of trait strings kept alive across librime initialization.
std::string g_shared_data_dir;
std::string g_user_data_dir;
std::string g_prebuilt_data_dir;
std::string g_staging_dir;
std::string g_distribution_version;

void appendUtf8CodePoint(std::string& out, uint32_t cp) {
  if (cp <= 0x7Fu) {
    out.push_back(static_cast<char>(cp));
  } else if (cp <= 0x7FFu) {
    out.push_back(static_cast<char>(0xC0u | (cp >> 6)));
    out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
  } else if (cp <= 0xFFFFu) {
    out.push_back(static_cast<char>(0xE0u | (cp >> 12)));
    out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
    out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
  } else if (cp <= 0x10FFFFu) {
    out.push_back(static_cast<char>(0xF0u | (cp >> 18)));
    out.push_back(static_cast<char>(0x80u | ((cp >> 12) & 0x3Fu)));
    out.push_back(static_cast<char>(0x80u | ((cp >> 6) & 0x3Fu)));
    out.push_back(static_cast<char>(0x80u | (cp & 0x3Fu)));
  } else {
    appendUtf8CodePoint(out, 0xFFFDu);
  }
}

std::string jstringToUtf8(JNIEnv* env, jstring input) {
  if (env == nullptr || input == nullptr) {
    return {};
  }
  const jsize len = env->GetStringLength(input);
  if (len <= 0) {
    return {};
  }
  const jchar* chars = env->GetStringChars(input, nullptr);
  if (chars == nullptr) {
    return {};
  }
  std::string out;
  out.reserve(static_cast<size_t>(len) * 3u);
  for (jsize i = 0; i < len; ++i) {
    const uint32_t c1 = static_cast<uint32_t>(chars[i]);
    if (c1 >= 0xD800u && c1 <= 0xDBFFu) {
      if (i + 1 < len) {
        const uint32_t c2 = static_cast<uint32_t>(chars[i + 1]);
        if (c2 >= 0xDC00u && c2 <= 0xDFFFu) {
          const uint32_t cp =
              0x10000u + (((c1 - 0xD800u) << 10) | (c2 - 0xDC00u));
          appendUtf8CodePoint(out, cp);
          ++i;
          continue;
        }
      }
      appendUtf8CodePoint(out, 0xFFFDu);
    } else if (c1 >= 0xDC00u && c1 <= 0xDFFFu) {
      appendUtf8CodePoint(out, 0xFFFDu);
    } else {
      appendUtf8CodePoint(out, c1);
    }
  }
  env->ReleaseStringChars(input, chars);
  return out;
}

jstring utf8ToJstring(JNIEnv* env, const char* data, size_t len) {
  if (env == nullptr) {
    return nullptr;
  }
  if (data == nullptr || len == 0) {
    return env->NewString(nullptr, 0);
  }
  std::vector<jchar> u16;
  u16.reserve(len);
  const auto* bytes = reinterpret_cast<const uint8_t*>(data);
  size_t i = 0;
  while (i < len) {
    uint32_t cp = 0xFFFDu;
    const uint8_t b0 = bytes[i];
    if (b0 < 0x80u) {
      cp = b0;
      i += 1;
    } else if ((b0 & 0xE0u) == 0xC0u && i + 1 < len) {
      const uint8_t b1 = bytes[i + 1];
      if ((b1 & 0xC0u) == 0x80u) {
        const uint32_t val =
            ((static_cast<uint32_t>(b0 & 0x1Fu)) << 6) | (b1 & 0x3Fu);
        if (val >= 0x80u) {
          cp = val;
        }
        i += 2;
      } else {
        i += 1;
      }
    } else if ((b0 & 0xF0u) == 0xE0u && i + 2 < len) {
      const uint8_t b1 = bytes[i + 1];
      const uint8_t b2 = bytes[i + 2];
      if ((b1 & 0xC0u) == 0x80u && (b2 & 0xC0u) == 0x80u) {
        const uint32_t val =
            ((static_cast<uint32_t>(b0 & 0x0Fu)) << 12) |
            ((static_cast<uint32_t>(b1 & 0x3Fu)) << 6) | (b2 & 0x3Fu);
        if (val >= 0x800u && !(val >= 0xD800u && val <= 0xDFFFu)) {
          cp = val;
        }
        i += 3;
      } else {
        i += 1;
      }
    } else if ((b0 & 0xF8u) == 0xF0u && i + 3 < len) {
      const uint8_t b1 = bytes[i + 1];
      const uint8_t b2 = bytes[i + 2];
      const uint8_t b3 = bytes[i + 3];
      if ((b1 & 0xC0u) == 0x80u && (b2 & 0xC0u) == 0x80u &&
          (b3 & 0xC0u) == 0x80u) {
        const uint32_t val =
            ((static_cast<uint32_t>(b0 & 0x07u)) << 18) |
            ((static_cast<uint32_t>(b1 & 0x3Fu)) << 12) |
            ((static_cast<uint32_t>(b2 & 0x3Fu)) << 6) | (b3 & 0x3Fu);
        if (val >= 0x10000u && val <= 0x10FFFFu) {
          cp = val;
        }
        i += 4;
      } else {
        i += 1;
      }
    } else {
      i += 1;
    }

    if (cp <= 0xFFFFu) {
      u16.push_back(static_cast<jchar>(cp));
    } else {
      const uint32_t adjusted = cp - 0x10000u;
      u16.push_back(static_cast<jchar>(0xD800u + (adjusted >> 10)));
      u16.push_back(static_cast<jchar>(0xDC00u + (adjusted & 0x3FFu)));
    }
  }
  return env->NewString(u16.data(), static_cast<jsize>(u16.size()));
}

jstring utf8ToJstring(JNIEnv* env, const char* cstr) {
  if (cstr == nullptr) {
    return utf8ToJstring(env, "", 0);
  }
  return utf8ToJstring(env, cstr, std::strlen(cstr));
}

jobject boxBoolean(JNIEnv* env, jclass boolClass, jmethodID valueOf, bool v) {
  return env->CallStaticObjectMethod(boolClass, valueOf,
                                     v ? JNI_TRUE : JNI_FALSE);
}

jobject boxInt(JNIEnv* env, jclass intClass, jmethodID valueOf, jint v) {
  return env->CallStaticObjectMethod(intClass, valueOf, v);
}

bool setSlotAndRelease(JNIEnv* env, jobjectArray arr, jsize idx, jobject obj) {
  if (obj == nullptr || env->ExceptionCheck()) {
    if (obj != nullptr) {
      env->DeleteLocalRef(obj);
    }
    return false;
  }
  env->SetObjectArrayElement(arr, idx, obj);
  env->DeleteLocalRef(obj);
  return !env->ExceptionCheck();
}

RimeSessionId toSessionId(jlong raw) {
  return raw > 0 ? static_cast<RimeSessionId>(raw) : 0;
}

struct CommitGuard {
  RimeApi* api;
  RimeCommit commit;
  bool active = false;
  explicit CommitGuard(RimeApi* a) : api(a) {
    RIME_STRUCT_INIT(RimeCommit, commit);
  }
  ~CommitGuard() {
    if (active && api != nullptr && api->free_commit != nullptr) {
      api->free_commit(&commit);
    }
  }
};

struct StatusGuard {
  RimeApi* api;
  RimeStatus status;
  bool active = false;
  explicit StatusGuard(RimeApi* a) : api(a) {
    RIME_STRUCT_INIT(RimeStatus, status);
  }
  ~StatusGuard() {
    if (active && api != nullptr && api->free_status != nullptr) {
      api->free_status(&status);
    }
  }
};

struct ContextGuard {
  RimeApi* api;
  RimeContext context;
  bool active = false;
  explicit ContextGuard(RimeApi* a) : api(a) {
    RIME_STRUCT_INIT(RimeContext, context);
  }
  ~ContextGuard() {
    if (active && api != nullptr && api->free_context != nullptr) {
      api->free_context(&context);
    }
  }
};

struct SchemaListGuard {
  RimeApi* api;
  RimeSchemaList list{};
  bool active = false;
  explicit SchemaListGuard(RimeApi* a) : api(a) {}
  ~SchemaListGuard() {
    if (active && api != nullptr && api->free_schema_list != nullptr) {
      api->free_schema_list(&list);
    }
  }
};

}  // namespace

DUOIME_JNI_EXPORT jint JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
  return JNI_VERSION_1_6;
}

DUOIME_JNI_EXPORT void JNI_OnUnload(JavaVM* /*vm*/, void* /*reserved*/) {
  try {
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (g_initialized) {
      RimeApi* api = rime_get_api();
      if (api != nullptr && api->finalize != nullptr) {
        api->finalize();
      }
      g_initialized = false;
    }
  } catch (...) {
    g_initialized = false;
  }
}

DUOIME_JNI_EXPORT jboolean JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeInitialize(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring sharedDataDir,
    jstring userDataDir,
    jstring prebuiltDataDir,
    jstring stagingDir,
    jstring distributionVersion) {
  try {
    const std::string shared = jstringToUtf8(env, sharedDataDir);
    const std::string user = jstringToUtf8(env, userDataDir);
    const std::string prebuilt = jstringToUtf8(env, prebuiltDataDir);
    const std::string staging = jstringToUtf8(env, stagingDir);
    const std::string distVer = jstringToUtf8(env, distributionVersion);
    if (shared.empty() || user.empty()) {
      return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_rime_mutex);
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->setup == nullptr || api->initialize == nullptr) {
      return JNI_FALSE;
    }

    if (g_initialized && api->finalize != nullptr) {
      api->finalize();
      g_initialized = false;
    }

    g_shared_data_dir = shared;
    g_user_data_dir = user;
    g_prebuilt_data_dir = prebuilt;
    g_staging_dir = staging;
    g_distribution_version = distVer.empty() ? "0.1.0" : distVer;

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = g_shared_data_dir.c_str();
    traits.user_data_dir = g_user_data_dir.c_str();
    traits.distribution_name = "DuoIME";
    traits.distribution_code_name = "DuoIME";
    traits.distribution_version = g_distribution_version.c_str();
    traits.app_name = "rime.duoime";
    traits.min_log_level = 3;
    traits.log_dir = "";
    if (!g_prebuilt_data_dir.empty()) {
      traits.prebuilt_data_dir = g_prebuilt_data_dir.c_str();
    }
    if (!g_staging_dir.empty()) {
      traits.staging_dir = g_staging_dir.c_str();
    }

    if (!g_setup_done) {
      api->setup(&traits);
      g_setup_done = true;
    }
    api->initialize(&traits);
    if (api->start_maintenance != nullptr &&
        api->join_maintenance_thread != nullptr) {
      if (api->start_maintenance(False)) {
        api->join_maintenance_thread();
      }
    }
    g_initialized = true;
    return JNI_TRUE;
  } catch (...) {
    return JNI_FALSE;
  }
}

DUOIME_JNI_EXPORT void JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeFinalize(JNIEnv* /*env*/,
                                                               jclass /*clazz*/) {
  try {
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return;
    }
    RimeApi* api = rime_get_api();
    if (api != nullptr && api->finalize != nullptr) {
      api->finalize();
    }
    g_initialized = false;
  } catch (...) {
    g_initialized = false;
  }
}

DUOIME_JNI_EXPORT jboolean JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeDeployWorkspace(
    JNIEnv* /*env*/,
    jclass /*clazz*/) {
  try {
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return JNI_FALSE;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->start_maintenance == nullptr ||
        api->join_maintenance_thread == nullptr) {
      return JNI_FALSE;
    }
    const Bool started = api->start_maintenance(True);
    api->join_maintenance_thread();
    return started ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

DUOIME_JNI_EXPORT jlong JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeCreateSession(
    JNIEnv* /*env*/,
    jclass /*clazz*/) {
  try {
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return 0;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->create_session == nullptr) {
      return 0;
    }
    return static_cast<jlong>(api->create_session());
  } catch (...) {
    return 0;
  }
}

DUOIME_JNI_EXPORT jboolean JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeDestroySession(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong sessionId) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    if (sid == 0) {
      return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return JNI_FALSE;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->destroy_session == nullptr) {
      return JNI_FALSE;
    }
    return api->destroy_session(sid) ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

DUOIME_JNI_EXPORT jboolean JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeProcessKey(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong sessionId,
    jint keysym,
    jint mask) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    if (sid == 0) {
      return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return JNI_FALSE;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->process_key == nullptr) {
      return JNI_FALSE;
    }
    return api->process_key(sid, static_cast<int>(keysym),
                            static_cast<int>(mask))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

DUOIME_JNI_EXPORT jstring JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetCommitText(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong sessionId) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    if (sid == 0) {
      return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return nullptr;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->get_commit == nullptr) {
      return nullptr;
    }
    CommitGuard guard(api);
    if (!api->get_commit(sid, &guard.commit)) {
      return nullptr;
    }
    guard.active = true;
    if (guard.commit.text == nullptr) {
      return nullptr;
    }
    return utf8ToJstring(env, guard.commit.text);
  } catch (...) {
    return nullptr;
  }
}

// Structured snapshot Object[14] (zero delimiter ambiguity):
//  [0]  String   schemaId
//  [1]  String   schemaName
//  [2]  Boolean  isComposing
//  [3]  Boolean  isAsciiMode
//  [4]  Boolean  isTraditional
//  [5]  String   preedit
//  [6]  String   commitTextPreview
//  [7]  Integer  pageIndex
//  [8]  Boolean  isLastPage
//  [9]  Integer  highlightedIndex
//  [10] String[] candidateTexts
//  [11] String[] candidateComments (elements may be null)
//  [12] String   rawInput
//  [13] Integer  caretPos
DUOIME_JNI_EXPORT jobjectArray JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetSnapshot(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong sessionId) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    if (sid == 0) {
      return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return nullptr;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->get_status == nullptr ||
        api->get_context == nullptr) {
      return nullptr;
    }

    StatusGuard statusGuard(api);
    statusGuard.active = api->get_status(sid, &statusGuard.status) != False;

    ContextGuard contextGuard(api);
    contextGuard.active = api->get_context(sid, &contextGuard.context) != False;

    if (!statusGuard.active && !contextGuard.active) {
      return nullptr;
    }

    jclass objClass = env->FindClass("java/lang/Object");
    jclass strClass = env->FindClass("java/lang/String");
    jclass boolClass = env->FindClass("java/lang/Boolean");
    jclass intClass = env->FindClass("java/lang/Integer");
    if (objClass == nullptr || strClass == nullptr || boolClass == nullptr ||
        intClass == nullptr) {
      return nullptr;
    }
    jmethodID boolValueOf =
        env->GetStaticMethodID(boolClass, "valueOf", "(Z)Ljava/lang/Boolean;");
    jmethodID intValueOf =
        env->GetStaticMethodID(intClass, "valueOf", "(I)Ljava/lang/Integer;");
    if (boolValueOf == nullptr || intValueOf == nullptr) {
      return nullptr;
    }

    jobjectArray result =
        env->NewObjectArray(kSnapshotFieldCount, objClass, nullptr);
    if (result == nullptr) {
      return nullptr;
    }

    const char* schemaId =
        (statusGuard.active && statusGuard.status.schema_id != nullptr)
            ? statusGuard.status.schema_id
            : "";
    const char* schemaName =
        (statusGuard.active && statusGuard.status.schema_name != nullptr)
            ? statusGuard.status.schema_name
            : "";
    const bool isComposing =
        statusGuard.active && statusGuard.status.is_composing != False;
    const bool isAsciiMode =
        statusGuard.active && statusGuard.status.is_ascii_mode != False;
    const bool isTraditional =
        statusGuard.active && statusGuard.status.is_traditional != False;

    const char* preedit =
        (contextGuard.active &&
         contextGuard.context.composition.preedit != nullptr)
            ? contextGuard.context.composition.preedit
            : "";
    const char* preview =
        (contextGuard.active &&
         contextGuard.context.commit_text_preview != nullptr)
            ? contextGuard.context.commit_text_preview
            : "";
    const int pageIndex =
        contextGuard.active ? contextGuard.context.menu.page_no : 0;
    const bool isLastPage =
        !contextGuard.active || contextGuard.context.menu.is_last_page != False;
    const int highlighted =
        contextGuard.active
            ? contextGuard.context.menu.highlighted_candidate_index
            : 0;
    const int caretPos =
        contextGuard.active ? contextGuard.context.composition.cursor_pos : 0;

    const int numCandidates =
        (contextGuard.active && contextGuard.context.menu.num_candidates > 0 &&
         contextGuard.context.menu.candidates != nullptr)
            ? contextGuard.context.menu.num_candidates
            : 0;

    jobjectArray candTexts = env->NewObjectArray(
        static_cast<jsize>(numCandidates), strClass, nullptr);
    jobjectArray candComments = env->NewObjectArray(
        static_cast<jsize>(numCandidates), strClass, nullptr);
    if (candTexts == nullptr || candComments == nullptr) {
      return nullptr;
    }

    for (int i = 0; i < numCandidates; ++i) {
      const RimeCandidate& cand = contextGuard.context.menu.candidates[i];
      jstring textObj = utf8ToJstring(env, cand.text);
      if (textObj == nullptr || env->ExceptionCheck()) {
        return nullptr;
      }
      env->SetObjectArrayElement(candTexts, static_cast<jsize>(i), textObj);
      env->DeleteLocalRef(textObj);

      if (cand.comment != nullptr && cand.comment[0] != '\0') {
        jstring commentObj = utf8ToJstring(env, cand.comment);
        if (commentObj == nullptr || env->ExceptionCheck()) {
          return nullptr;
        }
        env->SetObjectArrayElement(candComments, static_cast<jsize>(i),
                                   commentObj);
        env->DeleteLocalRef(commentObj);
      }
    }

    const char* rawInput = "";
    if (RIME_API_AVAILABLE(api, get_input)) {
      const char* raw = api->get_input(sid);
      if (raw != nullptr) {
        rawInput = raw;
      }
    }

    const bool ok =
        setSlotAndRelease(env, result, 0, utf8ToJstring(env, schemaId)) &&
        setSlotAndRelease(env, result, 1, utf8ToJstring(env, schemaName)) &&
        setSlotAndRelease(
            env, result, 2,
            boxBoolean(env, boolClass, boolValueOf, isComposing)) &&
        setSlotAndRelease(
            env, result, 3,
            boxBoolean(env, boolClass, boolValueOf, isAsciiMode)) &&
        setSlotAndRelease(
            env, result, 4,
            boxBoolean(env, boolClass, boolValueOf, isTraditional)) &&
        setSlotAndRelease(env, result, 5, utf8ToJstring(env, preedit)) &&
        setSlotAndRelease(env, result, 6, utf8ToJstring(env, preview)) &&
        setSlotAndRelease(env, result, 7,
                          boxInt(env, intClass, intValueOf, pageIndex)) &&
        setSlotAndRelease(
            env, result, 8,
            boxBoolean(env, boolClass, boolValueOf, isLastPage)) &&
        setSlotAndRelease(env, result, 9,
                          boxInt(env, intClass, intValueOf, highlighted)) &&
        setSlotAndRelease(env, result, 10, candTexts) &&
        setSlotAndRelease(env, result, 11, candComments) &&
        setSlotAndRelease(env, result, 12, utf8ToJstring(env, rawInput)) &&
        setSlotAndRelease(env, result, 13,
                          boxInt(env, intClass, intValueOf, caretPos));

    return ok ? result : nullptr;
  } catch (...) {
    return nullptr;
  }
}

DUOIME_JNI_EXPORT jboolean JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeSelectCandidateOnCurrentPage(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong sessionId,
    jint indexOnPage) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    if (sid == 0 || indexOnPage < 0) {
      return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return JNI_FALSE;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr ||
        !RIME_API_AVAILABLE(api, select_candidate_on_current_page)) {
      return JNI_FALSE;
    }
    return api->select_candidate_on_current_page(
               sid, static_cast<size_t>(indexOnPage))
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

DUOIME_JNI_EXPORT jboolean JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeChangePage(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong sessionId,
    jboolean backward) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    if (sid == 0) {
      return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return JNI_FALSE;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || !RIME_API_AVAILABLE(api, change_page)) {
      return JNI_FALSE;
    }
    return api->change_page(sid, backward == JNI_TRUE ? True : False)
               ? JNI_TRUE
               : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

DUOIME_JNI_EXPORT jboolean JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeSelectSchema(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong sessionId,
    jstring schemaId) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    const std::string id = jstringToUtf8(env, schemaId);
    if (sid == 0 || id.empty()) {
      return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return JNI_FALSE;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->select_schema == nullptr) {
      return JNI_FALSE;
    }
    return api->select_schema(sid, id.c_str()) ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

DUOIME_JNI_EXPORT jstring JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetCurrentSchema(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong sessionId) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    if (sid == 0) {
      return nullptr;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return nullptr;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->get_current_schema == nullptr) {
      return nullptr;
    }
    char buf[kSchemaIdBufSize] = {0};
    if (!api->get_current_schema(sid, buf, sizeof(buf))) {
      return nullptr;
    }
    return utf8ToJstring(env, buf);
  } catch (...) {
    return nullptr;
  }
}

DUOIME_JNI_EXPORT jboolean JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetOption(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong sessionId,
    jstring optionName) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    const std::string option = jstringToUtf8(env, optionName);
    if (sid == 0 || option.empty()) {
      return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return JNI_FALSE;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->get_option == nullptr) {
      return JNI_FALSE;
    }
    return api->get_option(sid, option.c_str()) ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

DUOIME_JNI_EXPORT void JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeSetOption(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong sessionId,
    jstring optionName,
    jboolean value) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    const std::string option = jstringToUtf8(env, optionName);
    if (sid == 0 || option.empty()) {
      return;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->set_option == nullptr) {
      return;
    }
    api->set_option(sid, option.c_str(), value == JNI_TRUE ? True : False);
  } catch (...) {
    // Swallow C++ exceptions across JNI boundary.
  }
}

DUOIME_JNI_EXPORT void JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeClearComposition(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong sessionId) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    if (sid == 0) {
      return;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->clear_composition == nullptr) {
      return;
    }
    api->clear_composition(sid);
  } catch (...) {
    // Swallow C++ exceptions across JNI boundary.
  }
}

DUOIME_JNI_EXPORT jboolean JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeCommitComposition(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong sessionId) {
  try {
    const RimeSessionId sid = toSessionId(sessionId);
    if (sid == 0) {
      return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return JNI_FALSE;
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->commit_composition == nullptr) {
      return JNI_FALSE;
    }
    return api->commit_composition(sid) ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

DUOIME_JNI_EXPORT jobjectArray JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetSchemaList(
    JNIEnv* env,
    jclass /*clazz*/) {
  try {
    jclass strClass = env->FindClass("java/lang/String");
    if (strClass == nullptr) {
      return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_rime_mutex);
    if (!g_initialized) {
      return env->NewObjectArray(0, strClass, nullptr);
    }
    RimeApi* api = rime_get_api();
    if (api == nullptr || api->get_schema_list == nullptr) {
      return env->NewObjectArray(0, strClass, nullptr);
    }

    SchemaListGuard guard(api);
    if (!api->get_schema_list(&guard.list) || guard.list.size == 0 ||
        guard.list.list == nullptr) {
      return env->NewObjectArray(0, strClass, nullptr);
    }
    guard.active = true;

    const jsize count = static_cast<jsize>(guard.list.size * 2);
    jobjectArray result = env->NewObjectArray(count, strClass, nullptr);
    if (result == nullptr) {
      return nullptr;
    }

    for (size_t i = 0; i < guard.list.size; ++i) {
      const RimeSchemaListItem& item = guard.list.list[i];
      if (!setSlotAndRelease(env, result, static_cast<jsize>(i * 2),
                             utf8ToJstring(env, item.schema_id)) ||
          !setSlotAndRelease(env, result, static_cast<jsize>(i * 2 + 1),
                             utf8ToJstring(env, item.name))) {
        return nullptr;
      }
    }
    return result;
  } catch (...) {
    return nullptr;
  }
}

DUOIME_JNI_EXPORT jstring JNICALL
Java_dev_duoime_engine_rime_JniRimeNativeBridge_nativeGetVersion(
    JNIEnv* env,
    jclass /*clazz*/) {
  try {
    RimeApi* api = rime_get_api();
    const char* version =
        (api != nullptr && RIME_API_AVAILABLE(api, get_version))
            ? api->get_version()
            : "";
    return utf8ToJstring(env, version);
  } catch (...) {
    return utf8ToJstring(env, "");
  }
}
