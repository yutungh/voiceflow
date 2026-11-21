#!/usr/bin/env bash
# Builds Rime (librime + octagram + predict, every commit pinned), the
# benchmark driver, and the two variants the phase 3 comparison scores:
#   dict  Wanxiang dictionary only
#   gram  the same plus Wanxiang's grammar model (wanxiang-lts-zh-hans.gram)
#
# Linux or WSL (Ubuntu 24.04 tested). Needs: build-essential cmake ninja-build
# git curl libboost-dev libboost-regex-dev libboost-locale-dev libleveldb-dev
# libmarisa-dev libopencc-dev libyaml-cpp-dev libgoogle-glog-dev.
#
#   bench/pinyin/rime/build.sh [work dir, default $HOME/vfrime] [--no-gram]
#
# Downloads, all hash-checked: librime and plugins (git), three Wanxiang
# dictionaries (CC BY 4.0), and, unless --no-gram, the 391 MB grammar model
# (CC BY 4.0). Nothing downloaded is committed. MIT, written for VoiceFlow.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
V="${1:-$HOME/vfrime}"
GRAM=1
[ "${2:-}" = "--no-gram" ] && GRAM=0

LIBRIME_COMMIT=33e78140250125871856cdc5b42ddc6a5fcd3cd4     # tag 1.17.0
OCTAGRAM_COMMIT=57d18b9f58e5284bd891d559f6bdd16cf60341e9    # lotem/librime-octagram
PREDICT_COMMIT=920bd41ebf6f9bf6855d14fbe80212e54e749791     # rime/librime-predict
WANXIANG_COMMIT=907edf67ce6d1f5c73b9f65ce9a2de55f8d7c82d    # same pin as scripts/wanxiang-source.mjs
GRAM_URL=https://github.com/amzxyz/rime-wanxiang-data/releases/download/LTS/wanxiang-lts-zh-hans.gram
GRAM_SHA256=e9ddd501b7d536bc5ea1346f8f900ae49a719a05be349e0a17cb27e717143ca5

declare -A DICT_SHA256=(
  [zi]=f321215a3aa7eef6317ec3b9e9725bf777480627488f5b6a42febaf7687e4924
  [jichu]=4a4598568f9f52cafb6ff9858adad4641a235165995c8d93c83aa67fe47343c7
  [duoyin]=85c2fe78121a05c50511e8defa473bd801c9697be79c4a548bb2f265c45f15f2
)

checkout() {  # <url> <dir> <commit>
  [ -d "$2/.git" ] || git clone -q "$1" "$2"
  git -C "$2" fetch -q origin "$3" 2>/dev/null || true
  git -C "$2" checkout -q "$3"
}

verify() {  # <file> <sha256>
  echo "$2  $1" | sha256sum -c --quiet - || { echo "hash mismatch: $1" >&2; exit 1; }
}

mkdir -p "$V/downloads"

# 1. librime with the octagram and predict plugins merged in.
checkout https://github.com/rime/librime.git "$V/librime" "$LIBRIME_COMMIT"
mkdir -p "$V/librime/plugins"
checkout https://github.com/lotem/librime-octagram.git "$V/librime/plugins/octagram" "$OCTAGRAM_COMMIT"
checkout https://github.com/rime/librime-predict.git "$V/librime/plugins/predict" "$PREDICT_COMMIT"
cmake -S "$V/librime" -B "$V/librime/build" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_TEST=OFF -DBUILD_MERGED_PLUGINS=ON -DENABLE_LOGGING=ON -DCMAKE_INSTALL_PREFIX="$V/install" > "$V/cmake.log"
grep -q "rime_plugins_modules: octagram;predict" "$V/cmake.log" || { echo "plugins not found" >&2; exit 1; }
cmake --build "$V/librime/build" > "$V/build.log"
cmake --install "$V/librime/build" > "$V/install.log"
export LD_LIBRARY_PATH="$V/install/lib"

# 2. The driver (needs librime's C++ headers for each candidate's span).
g++ -O2 -std=c++17 -o "$V/vf_rime_driver" "$HERE/vf_rime_driver.cc" \
  -I"$V/librime/src" -I"$V/librime/build/src" -I"$V/librime/include" \
  -L"$V/install/lib" -Wl,-rpath,"$V/install/lib" -lrime -lglog

# 3. Wanxiang dictionaries, unchanged, hash-checked.
for t in zi jichu duoyin; do
  f="$V/downloads/$t.dict.yaml"
  [ -f "$f" ] || curl -fsSL -o "$f" \
    "https://raw.githubusercontent.com/amzxyz/rime-wanxiang/$WANXIANG_COMMIT/dicts/$t.dict.yaml"
  verify "$f" "${DICT_SHA256[$t]}"
done
if [ "$GRAM" = 1 ]; then
  g="$V/downloads/wanxiang-lts-zh-hans.gram"
  [ -f "$g" ] || curl -fsSL -o "$g" "$GRAM_URL"
  verify "$g" "$GRAM_SHA256"
fi

# 4. Variants: schemas, dictionaries, then rime_deployer compiles them into
#    <variant>/shared/build, which the driver reads as prebuilt data.
variants="dict"
[ "$GRAM" = 1 ] && variants="dict gram"
for variant in $variants; do
  D="$V/data/$variant/shared"
  mkdir -p "$D"
  cp "$HERE"/schemas/default.yaml "$HERE"/schemas/vf_pinyin.dict.yaml \
     "$HERE"/schemas/vf_t9.schema.yaml "$HERE"/schemas/vf_qwerty.schema.yaml "$D/"
  for t in zi jichu duoyin; do cp "$V/downloads/$t.dict.yaml" "$D/"; done
  if [ "$variant" = gram ]; then
    cp "$HERE/schemas/gram.custom.yaml" "$D/vf_t9.custom.yaml"
    cp "$HERE/schemas/gram.custom.yaml" "$D/vf_qwerty.custom.yaml"
    ln -sf "$V/downloads/wanxiang-lts-zh-hans.gram" "$D/wanxiang-lts-zh-hans.gram"
  fi
  if [ -d "$D/build" ]; then mv "$D/build" "$D/build.old.$(date +%s%N)"; fi
  "$V/install/bin/rime_deployer" --build "$D" "$D" "$D/build" > "$V/deploy-$variant.log" 2>&1
  echo "$variant: $(du -cb "$D"/build/*.bin | tail -1 | cut -f1) bytes of compiled data"
done

cp "$HERE/run-driver.sh" "$V/run-driver.sh"
echo "done. Score with: java ... PinyinBenchmark external rime-dict bench/pinyin/phrases.tsv <out> bash $V/run-driver.sh dict"
