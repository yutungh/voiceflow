// vf_rime_driver: drives librime over VoiceFlow's pinyin benchmark line
// protocol (bench/pinyin/README.md). MIT, written for VoiceFlow.
//
//   vf_rime_driver <shared_dir> <user_dir> [--log <dir>]
//
// stdin, one request per line, one reply line each:
//   t9<TAB><buffer>       -> <text><TAB><consumed><TAB>...  (best first, up to 30)
//   qwerty<TAB><buffer>   -> same, full-keyboard schema
//   time<TAB><mode><TAB><keys>
//                         -> ms per key, tab separated: each key pushed onto
//                            the input as a keyboard would, then the first 30
//                            candidates read
//   mem                   -> VmRSS, VmHWM, RssAnon, RssFile in kB (Linux)
//
//   vf_rime_driver <shared_dir> <user_dir> --cold <mode> <buffer>
//     prints, on stdout, the ms to initialize, to load the schema and to
//     answer the first request, then exits.
//
// "consumed" is the candidate's end in the input (Rime's own span), so
// apostrophes are counted, as the protocol asks. The schemas are vf_t9 and
// vf_qwerty; their compiled data must already be in <shared_dir>/build
// (rime_deployer --build). Uses librime's C API to start and librime's C++
// classes to read each candidate's span, which the C API does not expose.

#include <rime_api.h>

#include <rime/candidate.h>
#include <rime/composition.h>
#include <rime/context.h>
#include <rime/menu.h>
#include <rime/segmentation.h>
#include <rime/service.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

constexpr int kCandidates = 30;

using Clock = std::chrono::steady_clock;

double MsSince(Clock::time_point start) {
  return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}

RimeApi* api = nullptr;
RimeSessionId t9_session = 0;
RimeSessionId qwerty_session = 0;

RimeSessionId Open(const char* schema) {
  RimeSessionId id = api->create_session();
  if (!id || !api->select_schema(id, schema)) {
    std::fprintf(stderr, "cannot open schema %s\n", schema);
    std::exit(2);
  }
  return id;
}

rime::Context* ContextOf(RimeSessionId id) {
  rime::an<rime::Session> session = rime::Service::instance().GetSession(id);
  return session ? session->context() : nullptr;
}

RimeSessionId SessionFor(const std::string& mode) {
  if (mode == "t9") {
    if (!t9_session) t9_session = Open("vf_t9");
    return t9_session;
  }
  if (mode == "qwerty") {
    if (!qwerty_session) qwerty_session = Open("vf_qwerty");
    return qwerty_session;
  }
  std::fprintf(stderr, "unknown mode %s\n", mode.c_str());
  std::exit(2);
}

// The candidates of the segment that starts the input, best first.
std::string Candidates(rime::Context* ctx) {
  std::string out;
  rime::Composition& composition = ctx->composition();
  for (rime::Segment& segment : composition) {
    if (segment.start != 0) continue;
    if (!segment.menu) break;
    segment.menu->Prepare(kCandidates);
    int n = std::min(kCandidates, static_cast<int>(segment.menu->candidate_count()));
    for (int i = 0; i < n; ++i) {
      rime::an<rime::Candidate> c = segment.menu->GetCandidateAt(i);
      if (!c) break;
      if (!out.empty()) out += '\t';
      out += c->text();
      out += '\t';
      out += std::to_string(c->end());
    }
    break;
  }
  return out;
}

std::string Query(const std::string& mode, const std::string& buffer) {
  rime::Context* ctx = ContextOf(SessionFor(mode));
  ctx->Clear();
  if (buffer.empty()) return "";
  ctx->set_input(buffer);
  return Candidates(ctx);
}

std::string Time(const std::string& mode, const std::string& keys) {
  rime::Context* ctx = ContextOf(SessionFor(mode));
  ctx->Clear();
  std::ostringstream out;
  out.precision(4);
  for (size_t i = 0; i < keys.size(); ++i) {
    Clock::time_point start = Clock::now();
    ctx->PushInput(keys[i]);
    std::string shown = Candidates(ctx);
    double ms = MsSince(start);
    if (i) out << '\t';
    out << std::fixed << ms;
  }
  ctx->Clear();
  return out.str();
}

std::string Memory() {
  std::ifstream status("/proc/self/status");
  std::string line;
  std::string out;
  while (std::getline(status, line)) {
    for (const char* key : {"VmRSS:", "VmHWM:", "RssAnon:", "RssFile:"}) {
      if (line.rfind(key, 0) == 0) {
        std::istringstream fields(line.substr(std::strlen(key)));
        long kb = 0;
        fields >> kb;
        if (!out.empty()) out += '\t';
        out += std::string(key, std::strlen(key) - 1) + "\t" + std::to_string(kb);
      }
    }
  }
  return out;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 3) {
    std::fprintf(stderr, "usage: %s <shared_dir> <user_dir> [--log <dir>] [--cold <mode> <buffer>]\n", argv[0]);
    return 2;
  }
  std::string shared = argv[1];
  std::string user = argv[2];
  std::string log_dir;
  std::string cold_mode;
  std::string cold_buffer;
  for (int i = 3; i < argc; ++i) {
    if (!std::strcmp(argv[i], "--log") && i + 1 < argc) {
      log_dir = argv[++i];
    } else if (!std::strcmp(argv[i], "--cold") && i + 2 < argc) {
      cold_mode = argv[++i];
      cold_buffer = argv[++i];
    }
  }
  std::string prebuilt = shared + "/build";

  Clock::time_point start = Clock::now();
  api = rime_get_api();
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared.c_str();
  traits.user_data_dir = user.c_str();
  traits.prebuilt_data_dir = prebuilt.c_str();
  traits.distribution_name = "VoiceFlow benchmark";
  traits.distribution_code_name = "vfbench";
  traits.distribution_version = "0.1";
  traits.app_name = "rime.vfbench";
  traits.min_log_level = log_dir.empty() ? 3 : 0;
  traits.log_dir = log_dir.empty() ? "" : log_dir.c_str();
  api->setup(&traits);
  api->initialize(&traits);
  double init_ms = MsSince(start);

  if (!cold_mode.empty()) {
    Clock::time_point schema_start = Clock::now();
    SessionFor(cold_mode);
    double schema_ms = MsSince(schema_start);
    Clock::time_point query_start = Clock::now();
    std::string first = Query(cold_mode, cold_buffer);
    double query_ms = MsSince(query_start);
    std::string top = first.substr(0, first.find('\t'));
    std::printf("init_ms\t%.1f\tschema_ms\t%.1f\tfirst_query_ms\t%.1f\ttotal_ms\t%.1f\ttop\t%s\n",
                init_ms, schema_ms, query_ms, MsSince(start), top.c_str());
    std::printf("%s\n", Memory().c_str());
    api->finalize();
    return 0;
  }

  std::ios::sync_with_stdio(false);
  std::string line;
  while (std::getline(std::cin, line)) {
    if (!line.empty() && line.back() == '\r') line.pop_back();
    std::string reply;
    if (line == "mem") {
      reply = Memory();
    } else {
      size_t tab = line.find('\t');
      std::string head = line.substr(0, tab);
      std::string rest = tab == std::string::npos ? "" : line.substr(tab + 1);
      if (head == "time") {
        size_t tab2 = rest.find('\t');
        reply = Time(rest.substr(0, tab2), tab2 == std::string::npos ? "" : rest.substr(tab2 + 1));
      } else {
        reply = Query(head, rest);
      }
    }
    std::cout << reply << '\n' << std::flush;
  }
  api->finalize();
  return 0;
}
