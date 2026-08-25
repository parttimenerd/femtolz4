#!/bin/bash
set -e
cd /Users/i560383_1/code/experiments/femtolz4
./scripts/ab_bench.sh baseline-java java 0,1,8,256
./scripts/ab_bench.sh baseline-native native 1,8,256
echo BASELINE_SWEEP_DONE
