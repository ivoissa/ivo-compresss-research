#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [ "$#" -ne 3 ]; then
  echo 'Usage: bash benchmark-compact-mixer.sh archive.ivo original result-directory' >&2
  exit 2
fi
archive_path="$1"
original_path="$2"
result_directory="$3"
mkdir -p target/classes target/cm32-source
find src/main/java -name '*.java' | sort > target/main-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -d target/classes @target/main-sources.txt
rm -rf target/cm32-classes
cp -a target/classes target/cm32-classes
cp research-experiments/cm16/reference/Ivo-v28.java.txt target/cm32-source/Ivo.java
# All other classes are shared; only the exact former predictor is restored.
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -cp target/cm32-classes -d target/cm32-classes target/cm32-source/Ivo.java
java -Xms64m -Xmx2g -XX:+UseG1GC -cp target/classes io.ivo.bench.DecoderCompare \
  target/cm32-classes target/classes "$archive_path" "$original_path" "$result_directory" compact
