#!/bin/bash
cd "$(dirname "$0")/.."
A=file:///Users/i560383_1/code/experiments/femtolz4/build/femtolz4-51f3a6d.jar
B=file:///Users/i560383_1/code/experiments/femtolz4/build/femtolz4-838a57f.jar
for f in bench-data/corpora/mixed-20m.bin bench-data/corpora-real/jfr-all_gc_SerialGC.bin bench-data/corpora/json-10m.bin bench-data/corpora/rle-20m.bin bench-data/corpora-big/wat-jfrtofp-160m.bin; do
  echo "=== $f c@1 (E42-only/838a57f vs orig)"
  java --enable-native-access=ALL-UNNAMED -Xmx4g -cp target/test-classes me.bechberger.femtolz4.DualBench "$f" 1 1.2 4 "$A" "$B" c 2>&1 | grep -E 'median'
done
echo DECIDE_DONE
