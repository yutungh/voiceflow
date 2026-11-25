// VoiceFlow's Rime core: one librime instance, one session per layout, and
// the three things the keyboard asks of it (candidates for a buffer, learn a
// choice, start and stop). MIT, written for VoiceFlow; no Trime or
// fcitx5-android code.
//
// Shared by the Android JNI bridge (vf_rime_jni.cc) and the host replay tool
// (vf_rime_replay.cc), so the phone and the PC read candidates the same way.
// Not thread safe by design: the app calls it from one worker thread only; a
// mutex still guards every entry point in case that is ever broken.

#ifndef VF_RIME_CORE_H_
#define VF_RIME_CORE_H_

#include <string>
#include <vector>

namespace vf {

enum Mode { kT9 = 0, kQwerty = 1 };

struct Candidate {
  std::string text;      // UTF-8
  int consumed = 0;      // characters of the buffer it reads (Rime's end())
  std::string spelling;  // toneless syllables joined by ', or "" when unknown
  bool completion = false;
};

class RimeCore {
 public:
  // Starts librime over prebuilt data. Returns "" or a short error code.
  // shared: schemas; prebuilt: the compiled build/ folder; user: the user
  // dictionary and Rime's own bookkeeping.
  static std::string Start(const std::string& shared, const std::string& prebuilt,
                           const std::string& user);
  static void Stop();
  static bool started();

  // Candidates of the segment that starts the buffer, best first. False on
  // an error (the caller falls back); true with an empty list when nothing
  // reads the buffer.
  static bool Query(int mode, const std::string& input, int limit,
                    std::vector<Candidate>* out);

  // Records that `text` was chosen for `input` (the part of the buffer it
  // consumed), so Rime's user dictionary learns it. False when the choice
  // could not be found again (nothing is learned then).
  static bool Learn(int mode, const std::string& input, const std::string& text);

  static const char* Version();
};

}  // namespace vf

#endif  // VF_RIME_CORE_H_
