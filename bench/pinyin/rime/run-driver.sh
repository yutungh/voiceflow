#!/usr/bin/env bash
# Starts the driver on one variant (dict or gram) with a fresh, empty user
# directory, so every run is a first-day user with nothing learned.
#   run-driver.sh <variant> [driver options]   (work dir: $VFRIME, else $HOME/vfrime)
V="${VFRIME:-$HOME/vfrime}"
variant="$1"; shift
U="$V/run/$variant-$(date +%s%N)"
mkdir -p "$U"
export LD_LIBRARY_PATH="$V/install/lib"
exec "$V/vf_rime_driver" "$V/data/$variant/shared" "$U" "$@"
