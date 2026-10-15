#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash build.sh
mkdir -p target/nal-classes
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -cp target/classes -d target/nal-classes research-experiments/mp4-nal/src/io/ivo/mp4/*.java
printf '%s\n' 'NAL trial: java -Xmx2g -cp target/classes:target/nal-classes io.ivo.mp4.NalMeasure original.mp4 resultats'
