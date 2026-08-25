#!/bin/bash
# Interleaved A/B: A B A B over identical cells; writes corpus-<label>-{a,b}{1,2}.csv
set -e
cd /Users/i560383_1/code/experiments/femtolz4
LABEL=$1; IMPL=$2; LEVELS=$3; shift 3
for pass in 1 2; do
  MAIN=build/femtolz4-baseline.jar EXTRAS=false ./scripts/ab_bench.sh ${LABEL}-a${pass} $IMPL $LEVELS "$@" >/dev/null
  MAIN=build/femtolz4-current.jar  EXTRAS=false ./scripts/ab_bench.sh ${LABEL}-b${pass} $IMPL $LEVELS "$@" >/dev/null
done
echo AB_PAIR_DONE
