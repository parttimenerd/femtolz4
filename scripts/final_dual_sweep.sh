#!/bin/bash
# Cumulative DualBench: original (51f3a6d) vs current, on all main corpora.
cd "$(dirname "$0")/.."
A=file:///Users/i560383_1/code/experiments/femtolz4/build/femtolz4-51f3a6d.jar
B=file:///Users/i560383_1/code/experiments/femtolz4/build/femtolz4-current.jar
CP=target/test-classes
JB="--enable-native-access=ALL-UNNAMED -Xmx4g"
run() { # corpus level mode tag
  echo "=== $1 level=$2 mode=$3"
  java $JB -cp $CP me.bechberger.femtolz4.DualBench "$1" "$2" 1.2 4 "$A" "$B" "$3" 2>&1 | grep -E 'compLen|median'
}
for f in bench-data/corpora-real/jfr-all_gc_SerialGC.bin bench-data/corpora-big/wat-jfrtofp-160m.bin bench-data/corpora/json-10m.bin bench-data/corpora/mixed-20m.bin bench-data/corpora/rle-20m.bin; do
  run "$f" 1 c
  run "$f" 8 c
done
for f in bench-data/corpora-real/jfr-all_gc_SerialGC.bin bench-data/corpora/json-10m.bin bench-data/corpora/mixed-20m.bin bench-data/corpora/rle-20m.bin; do
  run "$f" 1 d
  run "$f" 8 d
done
echo FINAL_SWEEP_DONE
