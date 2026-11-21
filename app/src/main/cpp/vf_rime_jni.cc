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

// Holds a Java string's UTF-16 characters and releases them on every path.
class JavaChars {
 public:
  JavaChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
    if (value_) {
      length_ = env_->GetStringLength(value_);
      chars_ = env_->GetStringChars(value_, nullptr);
    }
  }
  ~JavaChars() {
    if (chars_) env_->ReleaseStringChars(value_, chars_);
  }
  JavaChars(const JavaChars&) = delete;
  JavaChars& operator=(const JavaChars&) = delete;
  bool ok() const { return !value_ || chars_; }
  jsize length() const { return chars_ ? length_ : 0; }
  const jchar* data() const { return chars_; }

 private:
  JNIEnv* env_;
  jstring value_;
  jsize length_ = 0;
  const jchar* chars_ = nullptr;
};

// UTF-16 from Java to UTF-8. Unpaired surrogates become U+FFFD. False when the
// characters could not be read (an exception is then pending in Java).
bool ToUtf8(JNIEnv* env, jstring value, std::string* out) {
  out->clear();
  JavaChars chars(env, value);
  if (!chars.ok()) return false;
  const jsize length = chars.length();
  const jchar* c = chars.data();
  out->reserve(static_cast<size_t>(length) * 3);
  for (jsize i = 0; i < length; ++i) {
    uint32_t cp = c[i];
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < length && c[i + 1] >= 0xDC00 && c[i + 1] <= 0xDFFF) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (c[i + 1] - 0xDC00);
      ++i;
    } else if (cp >= 0xD800 && cp <= 0xDFFF) {
      cp = 0xFFFD;
    }
    if (cp < 0x80) {
      *out += static_cast<char>(cp);
    } else if (cp < 0x800) {
      *out += static_cast<char>(0xC0 | (cp >> 6));
      *out += static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      *out += static_cast<char>(0xE0 | (cp >> 12));
      *out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      *out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
      *out += static_cast<char>(0xF0 | (cp >> 18));
      *out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
      *out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
      *out += static_cast<char>(0x80 | (cp & 0x3F));
    }
  }
  return true;
}

// UTF-8 from Rime to a Java string. Malformed, overlong or surrogate
// sequences become U+FFFD. Null when the JVM could not allocate it (an
// OutOfMemoryError is then pending).
jstring ToJava(JNIEnv* env, const std::string& text) {
  static const uint32_t kMinimum[4] = {0, 0x80, 0x800, 0x10000};
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
    } else if (cp < kMinimum[extra] || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
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

// Every entry point catches everything: no C++ exception may unwind into the
// JVM. A failure returns null (or false), which Java treats as "Rime cannot
// answer" and falls back to Classic.

jstring Start(JNIEnv* env, jclass, jstring shared, jstring prebuilt, jstring user) {
  try {
    std::string a, b, c;
    if (!ToUtf8(env, shared, &a) || !ToUtf8(env, prebuilt, &b) || !ToUtf8(env, user, &c)) return nullptr;
    return ToJava(env, vf::RimeCore::Start(a, b, c));
  } catch (...) {
    return nullptr;
  }
}

void Stop(JNIEnv*, jclass) {
  try {
    vf::RimeCore::Stop();
  } catch (...) {
  }
}

// Flat array, four strings per candidate: text, consumed, spelling, flags
// ("c" for a completion, else ""). Null on an error.
jobjectArray Query(JNIEnv* env, jclass, jint mode, jstring input, jint limit) {
  try {
    std::string buffer;
    if (!ToUtf8(env, input, &buffer)) return nullptr;
    std::vector<vf::Candidate> found;
    if (!vf::RimeCore::Query(mode, buffer, limit, &found)) return nullptr;
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
        if (!value) {
          env->DeleteLocalRef(out);
          return nullptr;  // OutOfMemoryError is pending; Java sees it.
        }
        env->SetObjectArrayElement(out, at++, value);
        env->DeleteLocalRef(value);
      }
    }
    return out;
  } catch (...) {
    return nullptr;
  }
}

jboolean Learn(JNIEnv* env, jclass, jint mode, jstring input, jstring text) {
  try {
    std::string buffer, word;
    if (!ToUtf8(env, input, &buffer) || !ToUtf8(env, text, &word)) return JNI_FALSE;
    return vf::RimeCore::Learn(mode, buffer, word) ? JNI_TRUE : JNI_FALSE;
  } catch (...) {
    return JNI_FALSE;
  }
}

jstring Version(JNIEnv* env, jclass) {
  try {
    return ToJava(env, vf::RimeCore::Version());
  } catch (...) {
    return nullptr;
  }
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
