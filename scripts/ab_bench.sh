#!/usr/bin/env bash
# A/B corpus benchmark: runs CorpusBench with fixed flags and appends CSV to results/.
# Usage: scripts/ab_bench.sh <label> [impl] [levels] [corpora...]
#   impl:   java | native | dispatch   (default: java)
#   levels: comma list of maxChain    (default: 0,1,8,256)
set -euo pipefail
cd "$(dirname "$0")/.."

LABEL="${1:?label required}"
IMPL="${2:-java}"
LEVELS="${3:-0,1,8,256}"
shift 3 || true
CORPORA=("$@")
if [[ ${#CORPORA[@]} -eq 0 ]]; then
    CORPORA=(bench-data/corpora/json-10m.bin bench-data/corpora/text-20m.bin \
             bench-data/corpora/mixed-20m.bin bench-data/corpora/rle-20m.bin \
             bench-data/corpora/random-20m.bin)
fi

# Point MAIN at a jar/classes dir to select the implementation under test.
MAIN="${MAIN:-build/femtolz4-baseline.jar}"
YAWKAT_JAR=$(ls ~/.m2/repository/at/yawk/lz4/lz4-java/1.11.0/*.jar 2>/dev/null | grep -v sources | grep -v javadoc | head -1)
# build/test-classes holds javac-built bench drivers (target/ is clobbered by IDE builds).
CP="build/test-classes:$MAIN"
[[ -n "$YAWKAT_JAR" ]] && CP="$CP:$YAWKAT_JAR"

mkdir -p results
OUT="results/corpus-${LABEL}.csv"
java --enable-native-access=ALL-UNNAMED -Xmx4g \
     -Dbench.warmMs=1200 -Dbench.measureMs=1200 -Dbench.trials=9 \
     -Dbench.impl="$IMPL" -Dbench.levels="$LEVELS" -Dbench.extras="${EXTRAS:-true}" \
     -cp "$CP" me.bechberger.femtolz4.CorpusBench "${CORPORA[@]}" 2>/dev/null > "$OUT"
echo "wrote $OUT  ($(grep -c '^CSV' "$OUT") rows)"
