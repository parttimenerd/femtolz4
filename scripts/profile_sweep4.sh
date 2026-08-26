#!/bin/bash
# Fresh JFR profile sweep against current HEAD (post E7/E8/E9 + optimal parser).
# 6 configs x ~35s each == ~3.5 min.
set -e
cd /Users/i560383_1/code/experiments/femtolz4
./scripts/build_jars.sh
CP="build/test-classes:build/femtolz4-current.jar"
mkdir -p results
if [ ! -d build/test-classes ]; then mvn -q test-compile; fi
for spec in 'compress-java 1 text-20m' 'compress-java 8 text-20m' 'compress-java 8 json-10m' 'compress-java 1 mixed-20m' 'decompress-java 1 text-20m' 'decompress-java 1 mixed-20m'; do
  set -- $spec
  echo "rec $1 $2 $3 ..."
  java --enable-native-access=ALL-UNNAMED \
       -XX:StartFlightRecording=filename=results/prof4-$1-$2-$3.jfr,settings=scripts/prof.jfc \
       -cp $CP me.bechberger.femtolz4.ProfileDriver $1 $2 bench-data/corpora/$3.bin > results/prof4-$1-$2-$3.log 2>&1
  echo done $1 $2 $3
done
echo SWEEP4_DONE
