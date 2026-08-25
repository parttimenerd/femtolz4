#!/bin/bash
set -e
cd /Users/i560383_1/code/experiments/femtolz4
CP="target/test-classes:target/femtolz4-0.2.2.jar"
for spec in 'compress-java 1 flight cjava1' 'decompress-java 1 flight djava1' 'compress-java 8 flight cjava8' 'compress-java 256 flight cjava256'; do
  set -- $spec
  java --enable-native-access=ALL-UNNAMED \
       -XX:StartFlightRecording=filename=results/prof-$4.jfr,settings=scripts/prof.jfc \
       -cp $CP me.bechberger.femtolz4.ProfileDriver $1 $2 bench-data/$3.jfr > results/prof-$4.log 2>&1
  echo done $4
done
echo ALL_PROFILES_DONE
