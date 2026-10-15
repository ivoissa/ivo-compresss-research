#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf target/classes target/test-classes
mkdir -p target/classes target/test-classes
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
if command -v jar >/dev/null 2>&1; then
  JAR=(jar)
else
  JAR=(java -m jdk.jartool/sun.tools.jar.Main)
fi
find src/main/java -name '*.java' | sort > target/main-sources.txt
"${JAVAC[@]}" --release 17 -d target/classes @target/main-sources.txt
find src/test/java -name '*.java' | sort > target/test-sources.txt
"${JAVAC[@]}" --release 17 -cp target/classes -d target/test-classes @target/test-sources.txt
java -cp target/classes:target/test-classes io.ivo.SelfTest
java -cp target/classes:target/test-classes io.ivo.usercode.CompactMixerTest
java -Xmx2g -cp target/classes:target/test-classes io.ivo.usercode.CompactStorageTest
java -cp target/classes:target/test-classes io.ivo.jpeg.SpatialAmplitudeTest
java -cp target/classes:target/test-classes io.ivo.jpeg.BoundarySignsTest
java -cp target/classes:target/test-classes io.ivo.jpeg.BoundaryValuesTest
java -cp target/classes:target/test-classes io.ivo.nc.BoundaryCategoryTest
java -cp target/classes:target/test-classes io.ivo.jpeg.JpegBoundaryEngineTest
java -Xmx1g -cp target/classes:target/test-classes io.ivo.jpeg.MixedJpegTest
java -Xmx1024m -cp target/classes:target/test-classes io.ivo.jpeg.ProgressiveScanCodecTest
java -cp target/classes:target/test-classes io.ivo.deflate.DeflateExactTest
java -Xmx1024m -cp target/classes:target/test-classes io.ivo.mp3.SpectralRuntimeTest
java -cp target/classes:target/test-classes io.ivo.mp4.NalLengthTest
java -cp target/classes:target/test-classes io.ivo.mp4.NalTypeTest
java -cp target/classes:target/test-classes io.ivo.mp4.SliceHeaderTest
java -cp target/classes:target/test-classes io.ivo.mp3.EnergyContextTest
java -Xmx1024m -cp target/classes:target/test-classes io.ivo.mp4.VideoBodyTest
java -Xmx2g -cp target/classes:target/test-classes io.ivo.usercode.UserCodecIntegrationTest
java -Xmx2g -cp target/classes:target/test-classes io.ivo.usercode.Mp4AudioIntegrationTest
java -Xmx2g -cp target/classes:target/test-classes io.ivo.FastRouterTest
java -Xmx512m -cp target/classes:target/test-classes io.ivo.jpeg.PreferredPairTest
java -Xmx1g -cp target/classes:target/test-classes io.ivo.AutoRouterTest
"${JAR[@]}" --create --file target/ivo-0.8.0-research.jar --main-class io.ivo.cli.IvoCli -C target/classes .
"${JAR[@]}" --create --file target/ivo-research-1.5.0.jar --main-class io.ivo.cli.ResearchCli \
  -C target/classes . -C target/test-classes .
echo "Built target/ivo-0.8.0-research.jar and target/ivo-research-1.5.0.jar"
