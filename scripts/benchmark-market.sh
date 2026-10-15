#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 ]]; then echo "Usage: $0 <file> [ivo.jar]" >&2; exit 2; fi
file=$1
jar=${2:-target/ivo-0.2.0-SNAPSHOT.jar}
if [[ ! -f "$file" ]]; then echo "Missing file: $file" >&2; exit 2; fi
if [[ ! -f "$jar" ]]; then echo "Missing IVO jar: $jar" >&2; exit 2; fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
size(){ wc -c < "$1" | tr -d ' '; }
printf '%-14s %14s\n' method bytes
printf '%-14s %14d\n' original "$(size "$file")"
java -jar "$jar" compress "$file" "$tmp/a.ivo" >/dev/null
printf '%-14s %14d\n' IVO "$(size "$tmp/a.ivo")"
if command -v gzip >/dev/null; then gzip -9 -c "$file" > "$tmp/a.gz"; printf '%-14s %14d\n' gzip-9 "$(size "$tmp/a.gz")"; fi
if command -v bzip2 >/dev/null; then bzip2 -9 -c "$file" > "$tmp/a.bz2"; printf '%-14s %14d\n' bzip2-9 "$(size "$tmp/a.bz2")"; fi
if command -v xz >/dev/null; then xz -9e -c "$file" > "$tmp/a.xz"; printf '%-14s %14d\n' xz-9e "$(size "$tmp/a.xz")"; fi
if command -v zstd >/dev/null; then zstd -q -19 -c "$file" > "$tmp/a.zst"; printf '%-14s %14d\n' zstd-19 "$(size "$tmp/a.zst")"; fi
if command -v brotli >/dev/null; then
  brotli -q 11 -c "$file" > "$tmp/a.br"; printf '%-14s %14d\n' brotli-11 "$(size "$tmp/a.br")"
elif python3 -c 'import brotli' >/dev/null 2>&1; then
  python3 - "$file" "$tmp/a.br" <<'PY'
import brotli,sys
src,dst=sys.argv[1:3]
open(dst,'wb').write(brotli.compress(open(src,'rb').read(), quality=11))
PY
  printf '%-14s %14d\n' brotli-11 "$(size "$tmp/a.br")"
fi
