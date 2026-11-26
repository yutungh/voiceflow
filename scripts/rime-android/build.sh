#!/usr/bin/env bash
# Builds Rime for the Android keyboard (0.9.0): libvfrime.so for arm64-v8a
# (librime + octagram + predict and their dependencies, statically linked,
# with VoiceFlow's own JNI bridge) and the compiled Rime data the keyboard
# ships. Everything is built from source at pinned commits; nothing built is
# committed. MIT, written for VoiceFlow.
#
#   scripts/rime-android/build.sh [work dir, default $HOME/vfrime-android] [dist dir, default <repo>/.rime-dist]
#
# Linux or WSL (Ubuntu 24.04 tested), with cmake, ninja, git, curl, python3
# and node. The PC side (rime_deployer, which compiles the data, and the
# replay tool) comes from bench/pinyin/rime/build.sh, run here when missing
# ($VFRIME, default $HOME/vfrime).
#
# Output (<dist>, which app/build.gradle picks up):
#   jniLibs/arm64-v8a/libvfrime.so
#   assets/rime/   schemas, compiled dictionary (build/*.bin), replay fixture, manifest
#   rime-build.txt every pin and hash, for the release notes
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
W="${1:-$HOME/vfrime-android}"
DIST="${2:-$ROOT/.rime-dist}"
VFRIME="${VFRIME:-$HOME/vfrime}"
JOBS="$(nproc)"

# ---------------------------------------------------------------- pins
NDK_URL=https://dl.google.com/android/repository/android-ndk-r28c-linux.zip
NDK_SHA256=dfb20d396df28ca02a8c708314b814a4d961dc9074f9a161932746f815aa552f   # r28c, 28.2.13676358
BOOST_URL=https://archives.boost.io/release/1.84.0/source/boost_1_84_0.tar.gz
BOOST_SHA256=a5800f405508f5df8114558ca9855d2640a2de8f0445f051fa1c7c3383045724 # headers only are used
YAMLCPP_COMMIT=f7320141120f720aecc4c32be25586e7da9eb978    # jbeder/yaml-cpp 0.8.0 (MIT)
LEVELDB_COMMIT=99b3c03b3284f5886f9ef9a4ef703d57373e61be    # google/leveldb 1.23 (BSD-3)
OPENCC_COMMIT=556ed22496d650bd0b13b6c163be9814637970ae     # BYVoid/OpenCC ver.1.1.9 (Apache-2.0), with its bundled marisa 0.2.6 (BSD-2 option)
# The same three as the PC build (bench/pinyin/rime/build.sh):
LIBRIME_COMMIT=33e78140250125871856cdc5b42ddc6a5fcd3cd4    # rime/librime 1.17.0 (BSD-3)
OCTAGRAM_COMMIT=57d18b9f58e5284bd891d559f6bdd16cf60341e9   # lotem/librime-octagram (BSD-3)
PREDICT_COMMIT=920bd41ebf6f9bf6855d14fbe80212e54e749791    # rime/librime-predict (BSD-3)
API=26   # minSdk

checkout() {  # <url> <dir> <commit>
  [ -d "$2/.git" ] || git clone -q "$1" "$2"
  git -C "$2" fetch -q origin "$3" 2>/dev/null || true
  git -C "$2" checkout -q "$3"
  test "$(git -C "$2" rev-parse HEAD)" = "$3" || { echo "wrong commit in $2" >&2; exit 1; }
}
verify() {  # <file> <sha256>
  echo "$2  $1" | sha256sum -c --quiet - || { echo "hash mismatch: $1" >&2; exit 1; }
}

mkdir -p "$W/downloads" "$W/src" "$W/build"

# ---------------------------------------------------------------- NDK, boost
if [ ! -d "$W/ndk/android-ndk-r28c" ]; then
  [ -f "$W/downloads/ndk.zip" ] || curl -fsSL -o "$W/downloads/ndk.zip" "$NDK_URL"
  verify "$W/downloads/ndk.zip" "$NDK_SHA256"
  # Python keeps the executable bits (and needs no unzip package).
  python3 - "$W/downloads/ndk.zip" "$W/ndk" <<'PY'
import os, stat, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    for info in z.infolist():
        out = z.extract(info, sys.argv[2])
        mode = info.external_attr >> 16
        if stat.S_ISLNK(mode):
            target = open(out).read(); os.remove(out); os.symlink(target, out)
        elif mode & 0o777:
            os.chmod(out, mode & 0o777)
PY
fi
NDK="$W/ndk/android-ndk-r28c"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$W/boost_1_84_0/boost" ]; then
  [ -f "$W/downloads/boost.tar.gz" ] || curl -fsSL -o "$W/downloads/boost.tar.gz" "$BOOST_URL"
  verify "$W/downloads/boost.tar.gz" "$BOOST_SHA256"
  tar -xzf "$W/downloads/boost.tar.gz" -C "$W"
fi

# ---------------------------------------------------------------- sources
checkout https://github.com/jbeder/yaml-cpp.git "$W/src/yaml-cpp" "$YAMLCPP_COMMIT"
checkout https://github.com/google/leveldb.git "$W/src/leveldb" "$LEVELDB_COMMIT"
checkout https://github.com/BYVoid/OpenCC.git "$W/src/OpenCC" "$OPENCC_COMMIT"
checkout https://github.com/rime/librime.git "$W/src/librime" "$LIBRIME_COMMIT"
mkdir -p "$W/src/librime/plugins"
checkout https://github.com/lotem/librime-octagram.git "$W/src/librime/plugins/octagram" "$OCTAGRAM_COMMIT"
checkout https://github.com/rime/librime-predict.git "$W/src/librime/plugins/predict" "$PREDICT_COMMIT"

# ---------------------------------------------------------------- dependencies (static, arm64)
P="$W/prefix"
mkdir -p "$P/include" "$P/lib"
# No path of the build machine (a home folder, a user name) goes into the library: source
# paths are mapped to neutral ones (__FILE__ in asserts and exceptions), and OpenCC's
# built-in data folder is a neutral one too (the keyboard never uses it). Libraries built
# with other flags are set aside and rebuilt.
MAP="-ffile-prefix-map=$W=/vfrime -ffile-prefix-map=$ROOT=/voiceflow"
FLAGS="-fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections $MAP"
if [ -d "$P/lib" ] && [ "$(cat "$P/.flags" 2>/dev/null)" != "$FLAGS" ]; then
  mv "$P" "$P.old.$(date +%s%N)"
  mkdir -p "$P/include" "$P/lib"
fi
printf '%s' "$FLAGS" > "$P/.flags"
android_cmake() {  # <source> <build> [cmake args...]
  local src="$1" bld="$2"; shift 2
  cmake -S "$src" -B "$bld" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_C_FLAGS="$FLAGS" -DCMAKE_CXX_FLAGS="$FLAGS" \
    -DCMAKE_INSTALL_PREFIX="$P" -DCMAKE_FIND_ROOT_PATH="$P" -DCMAKE_PREFIX_PATH="$P" "$@" > "$bld.configure.log"
}

if [ ! -f "$P/lib/libyaml-cpp.a" ]; then
  android_cmake "$W/src/yaml-cpp" "$W/build/yaml-cpp" -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF \
    -DYAML_CPP_BUILD_CONTRIB=OFF -DYAML_CPP_FORMAT_SOURCE=OFF -DYAML_CPP_INSTALL=ON
  cmake --build "$W/build/yaml-cpp" -j "$JOBS" > "$W/build/yaml-cpp.log"
  cmake --install "$W/build/yaml-cpp" > /dev/null
fi
if [ ! -f "$P/lib/libleveldb.a" ]; then
  android_cmake "$W/src/leveldb" "$W/build/leveldb" -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF \
    -DHAVE_SNAPPY=OFF -DHAVE_CRC32C=OFF -DHAVE_TCMALLOC=OFF
  cmake --build "$W/build/leveldb" -j "$JOBS" > "$W/build/leveldb.log"
  cmake --install "$W/build/leveldb" > /dev/null
fi
if [ ! -f "$P/lib/libopencc.a" ]; then
  # Only the library and its bundled marisa: OpenCC's data step runs a host
  # tool, which a cross build cannot.
  android_cmake "$W/src/OpenCC" "$W/build/OpenCC" -DENABLE_GTEST=OFF -DENABLE_BENCHMARK=OFF \
    -DBUILD_DOCUMENTATION=OFF -DBUILD_PYTHON=OFF -DUSE_SYSTEM_MARISA=OFF -DCMAKE_INSTALL_PREFIX=/vfrime/prefix
  cmake --build "$W/build/OpenCC" -j "$JOBS" --target libopencc marisa > "$W/build/OpenCC.log"
  cp "$(find "$W/build/OpenCC" -name 'libopencc.a' | head -1)" "$P/lib/libopencc.a"
  cp "$(find "$W/build/OpenCC" -name 'libmarisa.a' | head -1)" "$P/lib/libmarisa.a"
  mkdir -p "$P/include/opencc"
  cp "$W/src/OpenCC/src/"*.hpp "$W/src/OpenCC/src/"*.h "$P/include/opencc/" 2>/dev/null || true
  find "$W/build/OpenCC/src" -maxdepth 1 -name '*.h' -exec cp {} "$P/include/opencc/" \;
  cp -r "$W/src/OpenCC/deps/marisa-0.2.6/include/"* "$P/include/"
fi

# ---------------------------------------------------------------- librime (static, plugins merged)
if [ ! -f "$P/lib/librime.a" ]; then
  android_cmake "$W/src/librime" "$W/build/librime" -DBUILD_TEST=OFF -DBUILD_MERGED_PLUGINS=ON \
    -DBUILD_STATIC=ON -DENABLE_LOGGING=OFF -DENABLE_EXTERNAL_PLUGINS=OFF -DBUILD_DATA=OFF \
    -DBoost_INCLUDE_DIR="$W/boost_1_84_0" -DBoost_NO_SYSTEM_PATHS=ON -DBoost_NO_BOOST_CMAKE=ON \
    -DCMAKE_POLICY_DEFAULT_CMP0074=NEW
  grep -q "octagram" "$W/build/librime.configure.log" && grep -q "predict" "$W/build/librime.configure.log" \
    || { echo "librime: plugins not found" >&2; exit 1; }
  cmake --build "$W/build/librime" -j "$JOBS" > "$W/build/librime.log"
  cp "$(find "$W/build/librime" -name 'librime.a' | head -1)" "$P/lib/librime.a"
fi

# ---------------------------------------------------------------- libvfrime.so (our JNI bridge)
CPP="$ROOT/app/src/main/cpp"
OUT="$W/build/vfrime"
mkdir -p "$OUT"
"$TC/clang++" --target="aarch64-linux-android$API" -std=c++17 -O2 -fPIC -shared \
  -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections \
  -I"$W/src/librime/src" -I"$W/build/librime/src" -I"$W/src/librime/include" \
  -I"$W/boost_1_84_0" -I"$P/include" \
  $MAP "$CPP/vf_rime_jni.cc" "$CPP/vf_rime_core.cc" \
  -Wl,--whole-archive "$P/lib/librime.a" -Wl,--no-whole-archive \
  "$P/lib/libopencc.a" "$P/lib/libmarisa.a" "$P/lib/libleveldb.a" "$P/lib/libyaml-cpp.a" \
  -static-libstdc++ -llog \
  -Wl,--version-script="$CPP/vfrime.map" -Wl,--exclude-libs,ALL -Wl,--gc-sections \
  -Wl,-z,max-page-size=16384 -Wl,-z,separate-loadable-segments -Wl,--build-id=sha1 \
  -o "$OUT/libvfrime.unstripped.so"
"$TC/llvm-strip" --strip-unneeded -o "$OUT/libvfrime.so" "$OUT/libvfrime.unstripped.so"

# Checks: one exported symbol, no shared C++ runtime, 16 KB-aligned segments.
exported="$("$TC/llvm-readelf" --dyn-syms -W "$OUT/libvfrime.so" | awk '$5=="GLOBAL" && $7!="UND" {print $8}' | sort)"
[ "$exported" = "JNI_OnLoad" ] || { echo "unexpected exports: $exported" >&2; exit 1; }
if "$TC/llvm-readelf" -d "$OUT/libvfrime.so" | grep -q 'c++_shared'; then echo "links libc++_shared" >&2; exit 1; fi
"$TC/llvm-readelf" -l -W "$OUT/libvfrime.so" | awk '$1=="LOAD" {print $NF}' | while read -r align; do
  [ $((align)) -ge 16384 ] || { echo "LOAD segment aligned to $align, need 16 KB" >&2; exit 1; }
done
# No build-machine path in the library (a home folder or a user name would leak).
leaks="$(LC_ALL=C grep -a -o -E '/home/[^/]+/|/Users/[^/]+/|/mnt/[a-z]/|/root/' "$OUT/libvfrime.so" | sort -u || true)"
[ -z "$leaks" ] || { echo "build-machine paths in libvfrime.so: $(echo "$leaks" | wc -l) kinds" >&2; exit 1; }
needed="$("$TC/llvm-readelf" -d "$OUT/libvfrime.so" | grep NEEDED | sed 's/.*\[\(.*\)\]/\1/' | sort | tr '\n' ' ')"

# ---------------------------------------------------------------- data (compiled on the PC)
if [ ! -x "$VFRIME/install/bin/rime_deployer" ] || [ ! -f "$VFRIME/data/app/shared/build/vf_t9.prism.bin" ]; then
  bash "$ROOT/bench/pinyin/rime/build.sh" "$VFRIME" --no-gram
fi
DATA="$VFRIME/data/app/shared"

# Host replay tool: the same core over the same data on the PC.
g++ -O2 -std=c++17 -o "$VFRIME/vf_rime_replay" "$CPP/vf_rime_replay.cc" "$CPP/vf_rime_core.cc" \
  -I"$VFRIME/librime/src" -I"$VFRIME/librime/build/src" -I"$VFRIME/librime/include" \
  -L"$VFRIME/install/lib" -Wl,-rpath,"$VFRIME/install/lib" -lrime
REPLAY_USER="$(mktemp -d)"
"$VFRIME/vf_rime_replay" "$DATA" "$DATA/build" "$REPLAY_USER" record "$HERE/replay-buffers.tsv" > "$W/build/replay.tsv"
REPLAY_USER="$(mktemp -d)"
"$VFRIME/vf_rime_replay" "$DATA" "$DATA/build" "$REPLAY_USER" check "$W/build/replay.tsv"
REPLAY_USER="$(mktemp -d)"
"$VFRIME/vf_rime_replay" "$DATA" "$DATA/build" "$REPLAY_USER" learn
# The phone's core must read candidates exactly as the benchmark's driver does.
cut -f1,2 "$W/build/replay.tsv" | bash "$VFRIME/run-driver.sh" app > "$W/build/replay-driver.out"
python3 - "$W/build/replay.tsv" "$W/build/replay-driver.out" <<'PY'
import sys
rows = [l.rstrip("\n").split("\t") for l in open(sys.argv[1], encoding="utf-8") if l.strip()]
outs = [l.rstrip("\n").split("\t") for l in open(sys.argv[2], encoding="utf-8")]
differ = sum(1 for row, out in zip(rows, outs)
             if [(row[i], row[i + 1]) for i in range(2, len(row), 3)]
             != [(out[i], out[i + 1]) for i in range(0, min(len(out), 10), 2)])
print(f"core vs benchmark driver: {len(rows)} rows, {differ} differ")
sys.exit(0 if differ == 0 and len(rows) == len(outs) else 1)
PY

# ---------------------------------------------------------------- dist
A="$DIST/assets/rime"
rm_dir() { [ -d "$1" ] && mv "$1" "$1.old.$(date +%s%N)" || true; }
rm_dir "$DIST"
mkdir -p "$DIST/jniLibs/arm64-v8a" "$A/shared" "$A/build"
cp "$OUT/libvfrime.so" "$DIST/jniLibs/arm64-v8a/"
cp "$DATA/default.yaml" "$DATA/vf_t9.schema.yaml" "$DATA/vf_qwerty.schema.yaml" "$A/shared/"
cp "$DATA/build/"*.bin "$DATA/build/"*.yaml "$A/build/"
cp "$W/build/replay.tsv" "$A/replay.tsv"
# The manifest names every file with its hash; the app copies the data to its
# own storage once per data version and checks it against this list.
(
  cd "$A"
  data_version="$(cat shared/*.yaml build/* | sha256sum | cut -c1-16)"
  echo "version $data_version"
  find shared build -type f | sort | while read -r f; do
    echo "file $(stat -c %s "$f") $(sha256sum "$f" | cut -d' ' -f1) $f"
  done
) > "$A/manifest.txt"

so_sha="$(sha256sum "$DIST/jniLibs/arm64-v8a/libvfrime.so" | cut -d' ' -f1)"
{
  echo "libvfrime.so $(stat -c %s "$DIST/jniLibs/arm64-v8a/libvfrime.so") bytes sha256 $so_sha"
  echo "needed: $needed"
  echo "ndk r28c $NDK_SHA256"
  echo "boost 1.84.0 headers $BOOST_SHA256"
  echo "yaml-cpp $YAMLCPP_COMMIT"
  echo "leveldb $LEVELDB_COMMIT"
  echo "opencc $OPENCC_COMMIT (bundled marisa 0.2.6)"
  echo "librime $LIBRIME_COMMIT"
  echo "librime-octagram $OCTAGRAM_COMMIT"
  echo "librime-predict $PREDICT_COMMIT"
  echo "data $(head -1 "$A/manifest.txt")"
  echo "compiled data $(du -cb "$A/build/"*.bin | tail -1 | cut -f1) bytes"
} > "$DIST/rime-build.txt"
cat "$DIST/rime-build.txt"
echo "dist: $DIST"
