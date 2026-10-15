#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$#" -lt 2 ]; then
  echo 'Usage: bash benchmark-spectral-cords.sh OUTDIR INPUT.mp3 [INPUT.mp3 ...]' >&2
  exit 2
fi
out="$1"
shift
mkdir -p "$out"
cp="target/classes:target/reservoir-classes"
for input in "$@"; do
  name="$(basename "$input")"
  java -Xmx2048m -cp "$cp" io.ivo.mp3.CordsMeasure \
    "$input" "$out/$name" 0,1,2,3 | tee "$out/$name.validation.txt"
done
