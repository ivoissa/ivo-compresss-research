#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
  echo 'Usage: bash test-cabac-first-skip.sh /absolute/input.mp4 /absolute/results [--prefix|--mb-types|--sub-types|--motion|--residuals]' >&2
  exit 2
fi
prefix_args=()
if [ "$#" -eq 3 ]; then
  if [ "$3" != --prefix ] && [ "$3" != --mb-types ] && [ "$3" != --sub-types ] && [ "$3" != --motion ] && [ "$3" != --residuals ]; then echo "Unknown mode" >&2; exit 2; fi
  prefix_args=("$3")
fi
input="$1"
results="$2"
mkdir -p "$results"
bash build-cabac-research.sh > "$results/build-validation.log" 2>&1
ffmpeg -v error -y -i "$input" -map 0:v:0 -c copy -bsf:v h264_mp4toannexb -f h264 "$results/source.h264"
ffmpeg -v trace -i "$input" -map 0:v:0 -c copy -bsf:v trace_headers -f null - > "$results/trace-headers.log" 2>&1
ffmpeg -v debug -threads 1 -debug mb_type -probesize 32 -analyzeduration 0 -f h264 -i "$results/source.h264" -map 0:v:0 -an -f null - > "$results/ffmpeg-mb-type.log" 2>&1
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacFirstSkip "$results/source.h264" "$results/trace-headers.log" "$results/ffmpeg-mb-type.log" "$results/first-skip.csv" "${prefix_args[@]}" > "$results/validation.log"
cat "$results/validation.log"
