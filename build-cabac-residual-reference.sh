#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$#" -ne 1 ]; then echo 'Usage: bash build-cabac-residual-reference.sh /absolute/build-directory' >&2; exit 2; fi
work="$1"
mkdir -p "$work"
curl -fsSL https://ffmpeg.org/releases/ffmpeg-6.1.1.tar.xz -o "$work/ffmpeg-6.1.1.tar.xz"
python3 - "$work/ffmpeg-6.1.1.tar.xz" <<'PYHASH'
import sys,hashlib
from pathlib import Path
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == "8684f4b00f94b85461884c3719382f1261f0d9eb3d59640a1f4ac0873616f968", 'Reference source hash differs'
PYHASH
tar --no-same-owner -xf "$work/ffmpeg-6.1.1.tar.xz" -C "$work"
patch -d "$work/ffmpeg-6.1.1" -p1 < research-experiments/mp4-cabac/residual-reference.patch
cd "$work/ffmpeg-6.1.1"
./configure --disable-everything --disable-doc --disable-x86asm --disable-network --disable-ffplay --disable-ffprobe --enable-ffmpeg --enable-decoder=h264 --enable-parser=h264 --enable-demuxer=h264 --enable-protocol=file --enable-muxer=null --enable-encoder=wrapped_avframe --enable-filter=null --enable-filter=buffer --enable-filter=buffersink > "$work/configure.log" 2>&1
make -j4 ffmpeg > "$work/build.log" 2>&1
printf '%s\n' "$work/ffmpeg-6.1.1/ffmpeg"
# Diagnostic globals require -threads 1; do not use this binary as a production decoder.
