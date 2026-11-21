// JNI bridge between com.voiceflowkeyboard.ime.RimeNative and the Rime core.
// MIT, written for VoiceFlow; no Trime or fcitx5-android code.
//
// Rules this file keeps:
//   - Nothing throws across the boundary: the core catches everything, and
//     this file only converts strings.
//   - Strings cross as UTF-16 (GetStringChars / NewString), never "modified
//     UTF-8": Wanxiang has characters outside the BMP, which NewStringUTF
//     would corrupt or abort on.
//   - Only JNI_OnLoad is exported (the link uses a version script); the
//     natives are registered here, so no Java_* symbols are needed.

#include <jni.h>

#include <string>
#include <vector>

#include "vf_rime_core.h"

namespace {

const char* const kClass = "com/voiceflowkeyboard/ime/RimeNative";

// UTF-16 from Java to UTF-8. Unpaired surrogates become U+FFFD.
std::string ToUtf8(JNIEnv* env, jstring value) {
  std::string out;
  if (!value) return out;
  const jsize length = env->GetStringLength(value);
  const jchar* chars = env->GetStringChars(value, nullptr);
  if (!chars) return out;
  out.reserve(static_cast<size_t>(length) * 3);
  for (jsize i = 0; i < length; ++i) {
    uint32_t cp = chars[i];
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
      ++i;
    } else if (cp >= 0xD800 && cp <= 0xDFFF) {
      cp = 0xFFFD;
    }
    if (cp < 0x80) {
      out += static_cast<char>(cp);
    } else if (cp < 0x800) {
      out += static_cast<char>(0xC0 | (cp >> 6));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      out += static_cast<char>(0xE0 | (cp >> 12));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
      out += static_cast<char>(0xF0 | (cp >> 18));
      out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
      out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      out += static_cast<char>(0x80 | (cp & 0x3F));
    }
  }
  env->ReleaseStringChars(value, chars);
  return out;
}

// UTF-8 from Rime to a Java string. Malformed bytes become U+FFFD.
jstring ToJava(JNIEnv* env, const std::string& text) {
  std::vector<jchar> out;
  out.reserve(text.size());
  const unsigned char* s = reinterpret_cast<const unsigned char*>(text.data());
  const size_t n = text.size();
  size_t i = 0;
  while (i < n) {
    uint32_t cp = 0xFFFD;
    size_t extra = 0;
    unsigned char b = s[i];
    if (b < 0x80) {
      cp = b;
    } else if ((b & 0xE0) == 0xC0) {
      cp = b & 0x1F;
      extra = 1;
    } else if ((b & 0xF0) == 0xE0) {
      cp = b & 0x0F;
      extra = 2;
    } else if ((b & 0xF8) == 0xF0) {
      cp = b & 0x07;
      extra = 3;
    }
    // A lead byte must start a sequence, and the whole sequence must fit.
    bool ok = (b < 0x80 || extra > 0) && i + extra < n;
    for (size_t k = 1; ok && k <= extra; ++k) {
      if ((s[i + k] & 0xC0) != 0x80) ok = false;
      else cp = (cp << 6) | (s[i + k] & 0x3F);
    }
    if (!ok) {
      cp = 0xFFFD;
      extra = 0;
    } else if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
      cp = 0xFFFD;
    }
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
      out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
    } else {
      out.push_back(static_cast<jchar>(cp));
    }
    i += 1 + extra;
  }
  return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

jstring Start(JNIEnv* env, jclass, jstring shared, jstring prebuilt, jstring user) {
  std::string error = vf::RimeCore::Start(ToUtf8(env, shared), ToUtf8(env, prebuilt), ToUtf8(env, user));
  return ToJava(env, error);
}

void Stop(JNIEnv*, jclass) {
  vf::RimeCore::Stop();
}

// Flat array, four strings per candidate: text, consumed, spelling, flags
// ("c" for a completion, else ""). Null on an error.
jobjectArray Query(JNIEnv* env, jclass, jint mode, jstring input, jint limit) {
  std::vector<vf::Candidate> found;
  if (!vf::RimeCore::Query(mode, ToUtf8(env, input), limit, &found)) return nullptr;
  jclass string_class = env->FindClass("java/lang/String");
  if (!string_class) return nullptr;
  jobjectArray out = env->NewObjectArray(static_cast<jsize>(found.size() * 4), string_class, nullptr);
  env->DeleteLocalRef(string_class);
  if (!out) return nullptr;
  jsize at = 0;
  for (const vf::Candidate& c : found) {
    const std::string fields[4] = {c.text, std::to_string(c.consumed), c.spelling, c.completion ? "c" : ""};
    for (const std::string& field : fields) {
      jstring value = ToJava(env, field);
      if (!value) return nullptr;  // OutOfMemoryError is pending; Java sees it.
      env->SetObjectArrayElement(out, at++, value);
      env->DeleteLocalRef(value);
    }
  }
  return out;
}

jboolean Learn(JNIEnv* env, jclass, jint mode, jstring input, jstring text) {
  return vf::RimeCore::Learn(mode, ToUtf8(env, input), ToUtf8(env, text)) ? JNI_TRUE : JNI_FALSE;
}

jstring Version(JNIEnv* env, jclass) {
  return ToJava(env, vf::RimeCore::Version());
}

const JNINativeMethod kMethods[] = {
    {const_cast<char*>("nativeStart"),
     const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
     reinterpret_cast<void*>(Start)},
    {const_cast<char*>("nativeStop"), const_cast<char*>("()V"), reinterpret_cast<void*>(Stop)},
    {const_cast<char*>("nativeQuery"), const_cast<char*>("(ILjava/lang/String;I)[Ljava/lang/String;"),
     reinterpret_cast<void*>(Query)},
    {const_cast<char*>("nativeLearn"), const_cast<char*>("(ILjava/lang/String;Ljava/lang/String;)Z"),
     reinterpret_cast<void*>(Learn)},
    {const_cast<char*>("nativeVersion"), const_cast<char*>("()Ljava/lang/String;"),
     reinterpret_cast<void*>(Version)},
};

}  // namespace

extern "C" __attribute__((visibility("default"))) jint JNI_OnLoad(JavaVM* vm, void*) {
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass cls = env->FindClass(kClass);
  if (!cls) return JNI_ERR;
  jint rc = env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0]));
  env->DeleteLocalRef(cls);
  return rc == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
