// VoiceFlow's Rime core (see vf_rime_core.h). MIT, written for VoiceFlow.
//
// librime is started through its C API and read through its C++ classes:
// the C API has no candidate spans, and VoiceFlow's candidate bar needs to
// know how much of the buffer each candidate reads (the rest stays
// composing). The benchmark driver (bench/pinyin/rime/vf_rime_driver.cc)
// reads candidates the same way.

#include "vf_rime_core.h"

#include <rime_api.h>

#include <rime/candidate.h>
#include <rime/composition.h>
#include <rime/context.h>
#include <rime/menu.h>
#include <rime/segmentation.h>
#include <rime/service.h>

#include <algorithm>
#include <exception>
#include <mutex>

namespace vf {
namespace {

std::mutex lock;
RimeApi* api = nullptr;
bool set_up = false;
bool running = false;
RimeSessionId sessions[2] = {0, 0};
const char* const kSchemas[2] = {"vf_t9", "vf_qwerty"};

// Strings handed to librime's traits must outlive initialize().
std::string shared_dir, prebuilt_dir, user_dir;

RimeSessionId SessionFor(int mode) {
  if (mode != kT9 && mode != kQwerty) return 0;
  RimeSessionId& id = sessions[mode];
  if (id && api->find_session(id)) return id;
  id = api->create_session();
  if (!id) return 0;
  if (!api->select_schema(id, kSchemas[mode])) {
    api->destroy_session(id);
    id = 0;
  }
  return id;
}

rime::Context* ContextFor(int mode) {
  RimeSessionId id = SessionFor(mode);
  if (!id) return nullptr;
  rime::an<rime::Session> session = rime::Service::instance().GetSession(id);
  return session ? session->context() : nullptr;
}

// The candidate's spelling as the schema's comment_format left it: toneless
// syllables split by the schema's delimiter. Kept to a-z and apostrophes.
std::string SpellingOf(const rime::an<rime::Candidate>& c) {
  std::string comment = c->comment();
  std::string out;
  out.reserve(comment.size());
  for (char ch : comment) {
    if (ch >= 'a' && ch <= 'z') {
      out += ch;
    } else if ((ch == ' ' || ch == '\'') && !out.empty() && out.back() != '\'') {
      out += '\'';
    }
  }
  while (!out.empty() && out.back() == '\'') out.pop_back();
  return out;
}

rime::Segment* FirstSegment(rime::Context* ctx) {
  rime::Composition& composition = ctx->composition();
  for (rime::Segment& segment : composition) {
    if (segment.start == 0) return segment.menu ? &segment : nullptr;
  }
  return nullptr;
}

}  // namespace

std::string RimeCore::Start(const std::string& shared, const std::string& prebuilt,
                            const std::string& user) {
  std::lock_guard<std::mutex> guard(lock);
  try {
    if (running) return "";
    api = rime_get_api();
    if (!api) return "no-api";
    shared_dir = shared;
    prebuilt_dir = prebuilt;
    user_dir = user;
    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = shared_dir.c_str();
    traits.user_data_dir = user_dir.c_str();
    traits.prebuilt_data_dir = prebuilt_dir.c_str();
    traits.distribution_name = "VoiceFlow";
    traits.distribution_code_name = "voiceflow";
    traits.distribution_version = "1";
    traits.app_name = "rime.voiceflow";
    traits.min_log_level = 3;
    if (!set_up) {
      api->setup(&traits);
      set_up = true;
    }
    // No maintenance and no deployment: the data was compiled at build time,
    // and compiling it on a phone takes tens of seconds.
    api->initialize(&traits);
    running = true;
    sessions[0] = sessions[1] = 0;
    for (int mode : {kT9, kQwerty}) {
      if (!SessionFor(mode)) {
        api->finalize();
        running = false;
        return mode == kT9 ? "no-t9-schema" : "no-qwerty-schema";
      }
    }
    return "";
  } catch (const std::exception&) {
    running = false;
    return "exception";
  } catch (...) {
    running = false;
    return "exception";
  }
}

void RimeCore::Stop() {
  std::lock_guard<std::mutex> guard(lock);
  try {
    if (!running) return;
    for (RimeSessionId& id : sessions) {
      if (id) api->destroy_session(id);
      id = 0;
    }
    api->finalize();
  } catch (...) {
  }
  running = false;
}

bool RimeCore::started() {
  std::lock_guard<std::mutex> guard(lock);
  return running;
}

bool RimeCore::Query(int mode, const std::string& input, int limit,
                     std::vector<Candidate>* out) {
  std::lock_guard<std::mutex> guard(lock);
  out->clear();
  if (!running) return false;
  try {
    rime::Context* ctx = ContextFor(mode);
    if (!ctx) return false;
    ctx->Clear();
    if (input.empty() || limit <= 0) return true;
    ctx->set_input(input);
    rime::Segment* segment = FirstSegment(ctx);
    if (!segment) {
      ctx->Clear();
      return true;
    }
    segment->menu->Prepare(limit);
    int n = std::min(limit, static_cast<int>(segment->menu->candidate_count()));
    for (int i = 0; i < n; ++i) {
      rime::an<rime::Candidate> c = segment->menu->GetCandidateAt(i);
      if (!c) break;
      Candidate one;
      one.text = c->text();
      one.consumed = static_cast<int>(std::min(c->end(), input.size()));
      one.spelling = SpellingOf(c);
      one.completion = c->type().find("completion") != std::string::npos;
      if (one.text.empty() || one.consumed <= 0) continue;
      out->push_back(std::move(one));
    }
    ctx->Clear();
    return true;
  } catch (...) {
    out->clear();
    return false;
  }
}

bool RimeCore::Learn(int mode, const std::string& input, const std::string& text) {
  std::lock_guard<std::mutex> guard(lock);
  if (!running || input.empty() || text.empty()) return false;
  try {
    rime::Context* ctx = ContextFor(mode);
    if (!ctx) return false;
    ctx->Clear();
    ctx->set_input(input);
    rime::Segment* segment = FirstSegment(ctx);
    // Context::Select acts on the last segment, so the choice must be in it.
    if (!segment || segment != &ctx->composition().back()) {
      ctx->Clear();
      return false;
    }
    // Find the same choice over exactly this input, then select and commit
    // it: the commit is what Rime's memory turns into user-dictionary updates.
    const int kSearch = 100;
    segment->menu->Prepare(kSearch);
    int n = std::min(kSearch, static_cast<int>(segment->menu->candidate_count()));
    int found = -1;
    for (int i = 0; i < n; ++i) {
      rime::an<rime::Candidate> c = segment->menu->GetCandidateAt(i);
      if (!c) break;
      if (c->text() == text && c->end() == input.size()) {
        found = i;
        break;
      }
    }
    bool learned = false;
    if (found >= 0 && ctx->Select(static_cast<size_t>(found))) {
      // The express editor turns on _auto_commit, so selecting a candidate
      // that reads the whole input has usually committed it already.
      learned = ctx->IsComposing() ? ctx->Commit() : true;
    }
    ctx->Clear();
    // The keyboard commits the text itself; drop the copy Rime kept for a
    // front end, so it does not grow with every choice.
    rime::an<rime::Session> session = rime::Service::instance().GetSession(sessions[mode]);
    if (session) session->ResetCommitText();
    return learned;
  } catch (...) {
    return false;
  }
}

const char* RimeCore::Version() {
  return "vfrime-1 librime-1.17.0";
}

}  // namespace vf
