#!/bin/bash
# Interleaved 2-pass A/B: corpus-<pre>-A1.csv, <pre>-B1.csv, <pre>-A2.csv, <pre>-B2.csv
set -e
cd /Users/i560383_1/code/experiments/femtolz4
PRE=$1; IMPL=$2; LEVELS=$3; shift 3
export EXTRAS=false
# Thermal/frequency burn-in: a discarded full pass so that A1..A2 all run at
# steady-state clocks (A1-vs-A2 sanity checks showed up to +20% compress drift
# from a cold start on Apple Silicon without this).
MAIN=build/femtolz4-current.jar ./scripts/ab_bench.sh ${PRE}-burnin $IMPL $LEVELS "$@" >/dev/null 2>&1 || true
rm -f results/corpus-${PRE}-burnin.csv
export MAIN=build/femtolz4-baseline.jar; ./scripts/ab_bench.sh ${PRE}-A1 $IMPL $LEVELS "$@"
export MAIN=build/femtolz4-current.jar;  ./scripts/ab_bench.sh ${PRE}-B1 $IMPL $LEVELS "$@"
export MAIN=build/femtolz4-current.jar;  ./scripts/ab_bench.sh ${PRE}-B2 $IMPL $LEVELS "$@"
export MAIN=build/femtolz4-baseline.jar; ./scripts/ab_bench.sh ${PRE}-A2 $IMPL $LEVELS "$@"
cat results/corpus-${PRE}-A1.csv results/corpus-${PRE}-A2.csv > results/corpus-${PRE}-A.csv
cat results/corpus-${PRE}-B1.csv results/corpus-${PRE}-B2.csv > results/corpus-${PRE}-B.csv
echo PAIR_DONE $PRE
