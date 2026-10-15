#!/usr/bin/env bash
set -euo pipefail
if [ "$#" -ne 1 ]; then echo 'Usage: bash build-cabac-subtype-reference.sh /absolute/build-directory' >&2; exit 2; fi
work="$1"
mkdir -p "$work"
curl -fsSL https://ffmpeg.org/releases/ffmpeg-6.1.1.tar.xz -o "$work/ffmpeg-6.1.1.tar.xz"
python3 - "$work/ffmpeg-6.1.1.tar.xz" <<'PYHASH'
import sys,hashlib
from pathlib import Path
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == "8684f4b00f94b85461884c3719382f1261f0d9eb3d59640a1f4ac0873616f968", 'Reference source hash differs'
PYHASH
tar --no-same-owner -xf "$work/ffmpeg-6.1.1.tar.xz" -C "$work"
python3 - "$work/ffmpeg-6.1.1/libavcodec/h264_cabac.c" <<'PYPATCH'
import sys
from pathlib import Path
p=Path(sys.argv[1]);s=p.read_text()
needle='int ff_h264_decode_mb_cabac(const H264Context *h, H264SliceContext *sl)\n{'
assert s.count(needle)==1
s=s.replace(needle,needle+'\n    if (sl->mb_x == 0 && sl->mb_y == 0) av_log(h->avctx, AV_LOG_DEBUG, "IVO_FRAME\\n");',1)
needle='sl->sub_mb_type[i] = decode_cabac_p_mb_sub_type(sl);'
assert s.count(needle)==1
s=s.replace(needle,needle+'\n                av_log(h->avctx, AV_LOG_DEBUG, "IVO_SUB %d %d %d %d\\n", sl->mb_x, sl->mb_y, i, sl->sub_mb_type[i]);',1)
needle='    mx += mxd;\\\n'
assert s.count(needle)==1
s=s.replace(needle,'    av_log(sl->h264->avctx, AV_LOG_DEBUG, "IVO_MOTION %d %d %d %d %d %d %d %d %d\\n", sl->mb_x, sl->mb_y, list, n, sl->ref_cache[list][scan8[n]], mxd, myd, amvd0, amvd1);\\\n'+needle)
p.write_text(s)
PYPATCH
cd "$work/ffmpeg-6.1.1"
./configure --disable-everything --disable-doc --disable-x86asm --disable-network --disable-ffplay --disable-ffprobe --enable-ffmpeg --enable-decoder=h264 --enable-parser=h264 --enable-demuxer=h264 --enable-protocol=file --enable-muxer=null --enable-encoder=wrapped_avframe --enable-filter=null --enable-filter=buffer --enable-filter=buffersink > "$work/configure.log" 2>&1
make -j4 ffmpeg > "$work/build.log" 2>&1
printf '%s\n' "$work/ffmpeg-6.1.1/ffmpeg"
