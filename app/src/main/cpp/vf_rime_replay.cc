// vf_rime_replay: the host side of the Android replay check. MIT, written
// for VoiceFlow.
//
// Runs the same Rime core the phone runs (vf_rime_core.cc), built for the PC
// against the same librime commit and the same compiled data, over a replay
// fixture: one buffer per line, the expected first candidates after it.
//
//   vf_rime_replay <shared_dir> <prebuilt_dir> <user_dir> record <buffers.tsv>  > replay.tsv
//   vf_rime_replay <shared_dir> <prebuilt_dir> <user_dir> check  <replay.tsv>
//   vf_rime_replay <shared_dir> <prebuilt_dir> <user_dir> learn
//
// buffers.tsv: <mode>\t<buffer> per line (mode t9 or qwerty, # comments).
// replay.tsv:  <mode>\t<buffer>\t<text>\t<consumed>\t<spelling>... (the first
//              five candidates). The phone checks the same file
//              (RimeGateActivity); check exits 1 on any difference.
// learn: commits one choice and checks it moves up (user dictionary works).

#include <cstdio>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "vf_rime_core.h"

namespace {

constexpr int kTop = 5;

int ModeOf(const std::string& name) {
  return name == "qwerty" ? vf::kQwerty : vf::kT9;
}

std::vector<std::string> Split(const std::string& line) {
  std::vector<std::string> out;
  std::string field;
  std::istringstream in(line);
  while (std::getline(in, field, '\t')) out.push_back(field);
  if (!line.empty() && line.back() == '\t') out.push_back("");
  return out;
}

std::string Row(const std::string& mode, const std::string& buffer) {
  std::vector<vf::Candidate> found;
  if (!vf::RimeCore::Query(ModeOf(mode), buffer, 30, &found)) return "";
  std::string row = mode + "\t" + buffer;
  for (size_t i = 0; i < found.size() && i < static_cast<size_t>(kTop); ++i) {
    row += "\t" + found[i].text + "\t" + std::to_string(found[i].consumed) + "\t" + found[i].spelling;
  }
  return row;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 5) {
    std::fprintf(stderr, "usage: %s <shared> <prebuilt> <user> record|check <file> | learn\n", argv[0]);
    return 2;
  }
  std::string error = vf::RimeCore::Start(argv[1], argv[2], argv[3]);
  if (!error.empty()) {
    std::fprintf(stderr, "start failed: %s\n", error.c_str());
    return 2;
  }
  std::string command = argv[4];
  int status = 0;
  if (command == "learn") {
    // 9-key 943943 shows 这些 before 谢谢 on a fresh dictionary; after she
    // picks 谢谢 twice it must come first.
    std::vector<vf::Candidate> before, after;
    vf::RimeCore::Query(vf::kT9, "943943", 30, &before);
    bool learned = vf::RimeCore::Learn(vf::kT9, "943943", "谢谢");
    learned = vf::RimeCore::Learn(vf::kT9, "943943", "谢谢") && learned;
    vf::RimeCore::Query(vf::kT9, "943943", 30, &after);
    std::printf("before\t%s\nlearned\t%d\nafter\t%s\n", before.empty() ? "" : before[0].text.c_str(),
                learned ? 1 : 0, after.empty() ? "" : after[0].text.c_str());
    status = learned && !after.empty() && after[0].text == "谢谢" ? 0 : 1;
  } else if (argc >= 6) {
    std::ifstream in(argv[5]);
    std::string line;
    int rows = 0, differ = 0;
    while (std::getline(in, line)) {
      if (!line.empty() && line.back() == '\r') line.pop_back();
      if (line.empty() || line[0] == '#') continue;
      std::vector<std::string> f = Split(line);
      if (f.size() < 2) continue;
      std::string actual = Row(f[0], f[1]);
      if (command == "record") {
        std::printf("%s\n", actual.c_str());
      } else {
        ++rows;
        if (actual != line) {
          ++differ;
          std::printf("DIFF\n  want %s\n  got  %s\n", line.c_str(), actual.c_str());
        }
      }
    }
    if (command == "check") {
      std::printf("replay: %d rows, %d differ\n", rows, differ);
      status = differ == 0 && rows > 0 ? 0 : 1;
    }
  } else {
    status = 2;
  }
  vf::RimeCore::Stop();
  return status;
}
