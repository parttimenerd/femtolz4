#!/bin/bash
cd "$(dirname "$0")/.."
A=file:///Users/i560383_1/code/experiments/femtolz4/build/femtolz4-baseline.jar
# rebuild proper pre-E50 base = e87375f content = current tree before sed
B=file:///Users/i560383_1/code/experiments/femtolz4/build/femtolz4-current.jar
for f in bench-data/corpora-real/jfr-all_gc_SerialGC.bin bench-data/corpora/json-10m.bin bench-data/corpora/mixed-20m.bin bench-data/corpora/rle-20m.bin; do
  echo "=== $f c@1 E50(13bit)/E42(12bit)"
  java --enable-native-access=ALL-UNNAMED -Xmx4g -cp target/test-classes me.bechberger.femtolz4.DualBench "$f" 1 1.2 4 "$A" "$B" c 2>&1 | grep -E 'compLen|median'
done
echo E50_DONE
