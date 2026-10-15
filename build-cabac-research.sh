#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash build-mp4-nal.sh
mkdir -p target/cabac-classes
java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -cp target/classes:target/nal-classes -d target/cabac-classes research-experiments/mp4-cabac/src/io/ivo/mp4/*.java
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacArithmeticTest
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacRegularTest
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacIntervalTest
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacMbTypeTest
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacSubTypeTest

java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacMotionTest

java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacResidualTest
java -cp target/classes:target/nal-classes:target/cabac-classes io.ivo.mp4.CabacPrefixReplayTest
