#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash build.sh
mkdir -p target/reservoir-classes
find research-experiments/mp3-reservoir/src -name '*.java' | sort > target/reservoir-sources.txt
if command -v javac >/dev/null 2>&1; then
  javac --release 17 -cp target/classes -d target/reservoir-classes @target/reservoir-sources.txt
else
  java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -cp target/classes -d target/reservoir-classes @target/reservoir-sources.txt
fi
java -cp target/reservoir-classes:target/classes io.ivo.mp3.Layer3SideInfoTest
java -Xmx1024m -cp target/reservoir-classes:target/classes io.ivo.mp3.SemanticSideTest
java -Xmx1024m -cp target/reservoir-classes:target/classes io.ivo.mp3.BlockSideTest
java -Xmx1024m -cp target/reservoir-classes:target/classes io.ivo.mp3.GranuleMainTest

java -cp target/reservoir-classes:target/classes io.ivo.mp3.HuffmanPairTest
java -cp target/reservoir-classes:target/classes io.ivo.mp3.SpectralSyntaxTest
java -Xmx1024m -cp target/reservoir-classes:target/classes io.ivo.mp3.SpectralCordsTest
java -cp target/reservoir-classes:target/classes io.ivo.mp3.ContextSchemeTest

java -cp target/reservoir-classes:target/classes io.ivo.mp3.TemporalScaleTest
