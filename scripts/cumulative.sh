#!/bin/bash
# Definitive cumulative: 51f3a6d (orig) vs HEAD(current jar), all real corpora.
set -e
cd /Users/i560383_1/code/experiments/femtolz4
A=file:///Users/i560383_1/code/experiments/femtolz4/build/femtolz4-51f3a6d.jar
B=file:///Users/i560383_1/code/experiments/femtolz4/build/femtolz4-current.jar
for f in bench-data/corpora-real/jfr-all_gc_SerialGC.bin bench-data/corpora-real/jfr-all_gc_G1.bin bench-data/corpora-real/jfr-fj-kmeans_default_G1.bin bench-data/corpora-real/jfr-movie-lens_default_G1.bin bench-data/corpora-real/jfr-all_profile_G1.bin; do
  for lvl in 1 8; do
    for mode in c d; do
      echo "=== $(basename $f) level=$lvl mode=$mode"
      java -Xmx4g --enable-native-access=ALL-UNNAMED -cp target/test-classes \
        me.bechberger.femtolz4.DualBench $f $lvl 1.2 4 "$A" "$B" $mode 2>&1 | grep -E 'compLen|median|^[0-9]'
    done
  done
done
echo CUMUL_DONE
