#!/usr/bin/env bash
# bench_thinkstation.sh — deploy femtolz4 to ThinkStation, run benchmark + Valgrind harness.
# Usage: ./bench_thinkstation.sh [benchmark-file ...]
# Set THINKSTATION env var to override the SSH alias (default: thinkstation).
set -euo pipefail

THINKSTATION=${THINKSTATION:-thinkstation}
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REMOTE_HOME="$(ssh "$THINKSTATION" 'echo $HOME')"
REMOTE_DIR="${REMOTE_HOME}/femtolz4"

# ── Local prereqs ─────────────────────────────────────────────────────────────
if ! command -v mvn &>/dev/null; then echo "ERROR: mvn not found locally"; exit 1; fi
if ! ssh -o BatchMode=yes -o ConnectTimeout=5 "$THINKSTATION" true 2>/dev/null; then
    echo "ERROR: cannot SSH to $THINKSTATION"
    echo "  Set THINKSTATION env var if your SSH alias differs."
    exit 1
fi

# ── Build JAR locally ─────────────────────────────────────────────────────────
echo "=== Building JAR locally ==="
(cd "$SCRIPT_DIR" && mvn package -q -DskipTests)
JAR="$SCRIPT_DIR/target/femtolz4-0.1.0.jar"
TEST_CLASSES="$SCRIPT_DIR/target/test-classes"

YAWKAT_JAR="$HOME/.m2/repository/at/yawk/lz4/lz4-java/1.11.0/lz4-java-1.11.0.jar"
if [[ ! -f "$YAWKAT_JAR" ]]; then
    (cd "$SCRIPT_DIR" && mvn dependency:resolve -q)
fi
if [[ ! -f "$YAWKAT_JAR" ]]; then
    echo "ERROR: lz4-java jar not found at $YAWKAT_JAR"; exit 1
fi

# ── Find JNI headers ─────────────────────────────────────────────────────────
JNI_CACHE="$(find /var/folders /tmp -name 'femtolz4-jni-linux-amd64' -maxdepth 7 2>/dev/null | head -1 || true)"
if [[ -z "$JNI_CACHE" ]]; then
    echo "JNI header cache not found — running mvn generate-resources to populate it..."
    (cd "$SCRIPT_DIR" && mvn generate-resources -q)
    JNI_CACHE="$(find /var/folders /tmp -name 'femtolz4-jni-linux-amd64' -maxdepth 7 2>/dev/null | head -1 || true)"
fi
if [[ -z "$JNI_CACHE" ]]; then
    echo "ERROR: JNI headers not found after generate-resources. Cannot continue."
    exit 1
fi

# ── Copy sources to ThinkStation ─────────────────────────────────────────────
echo "=== Copying sources to ThinkStation ==="
ssh "$THINKSTATION" "mkdir -p ${REMOTE_DIR}/native ${REMOTE_DIR}/bench ${REMOTE_DIR}/jni-headers"
scp "$SCRIPT_DIR"/src/main/native/*.c \
    "$SCRIPT_DIR"/src/main/native/*.h \
    "$THINKSTATION":${REMOTE_DIR}/native/
# Copy fuzz harness if it exists
[[ -f "$SCRIPT_DIR/src/test/native/fuzz_harness.c" ]] && \
    scp "$SCRIPT_DIR/src/test/native/fuzz_harness.c" "$THINKSTATION":${REMOTE_DIR}/native/ || true
scp -r "$JNI_CACHE"/. "$THINKSTATION":${REMOTE_DIR}/jni-headers/

# ── Check ThinkStation prereqs ───────────────────────────────────────────────
echo "=== Checking ThinkStation prerequisites ==="
ssh "$THINKSTATION" bash <<'REMOTE_CHECK'
missing=""
command -v gcc &>/dev/null || missing="$missing gcc"
command -v zip &>/dev/null || missing="$missing zip"
if [[ -n "$missing" ]]; then
    echo "ERROR: missing required packages on ThinkStation:$missing"
    echo "  Ask your admin to install: sudo apt-get install$missing"
    exit 1
fi
echo "  gcc:  $(gcc --version | head -1)"
echo "  java: $(java -version 2>&1 | head -1)"
if command -v valgrind &>/dev/null; then
    echo "  valgrind: $(valgrind --version)"
else
    echo "  valgrind: NOT FOUND (Valgrind run will be skipped)"
    echo "  To install: sudo apt-get install valgrind"
fi
REMOTE_CHECK

# ── Build native lib on ThinkStation ─────────────────────────────────────────
echo "=== Building native lib on ThinkStation ==="
ssh "$THINKSTATION" bash <<REMOTE_BUILD
set -e
REMOTE_DIR="${REMOTE_DIR}"
gcc -O3 -march=native -shared -fPIC \
    -I\${REMOTE_DIR}/jni-headers \
    -I\${REMOTE_DIR}/jni-headers/linux \
    -I\${REMOTE_DIR}/native \
    \${REMOTE_DIR}/native/femtolz4_jni.c \
    -o \${REMOTE_DIR}/libfemtolz4.so
echo "  built: \$(ls -lh \${REMOTE_DIR}/libfemtolz4.so)"
REMOTE_BUILD

# ── Patch JAR ────────────────────────────────────────────────────────────────
echo "=== Patching JAR with ThinkStation native lib ==="
PATCHED_JAR="/tmp/femtolz4-thinkstation.jar"
cp "$JAR" "$PATCHED_JAR"
TMPDIR_SO="$(mktemp -d)"
scp "$THINKSTATION":${REMOTE_DIR}/libfemtolz4.so "$TMPDIR_SO/libfemtolz4.so"
(cd "$TMPDIR_SO" && mkdir -p native/linux-amd64 && \
 cp libfemtolz4.so native/linux-amd64/ && \
 zip -u "$PATCHED_JAR" native/linux-amd64/libfemtolz4.so)
rm -rf "$TMPDIR_SO"

# ── Copy benchmark files ─────────────────────────────────────────────────────
echo "=== Copying benchmark files to ThinkStation ==="
scp "$PATCHED_JAR"  "$THINKSTATION":${REMOTE_DIR}/bench/femtolz4.jar
scp "$YAWKAT_JAR"   "$THINKSTATION":${REMOTE_DIR}/bench/lz4-java.jar
rsync -a --quiet "$TEST_CLASSES"/ "$THINKSTATION":${REMOTE_DIR}/bench/test-classes/

# ── Run benchmark ────────────────────────────────────────────────────────────
echo ""
echo "=== Benchmark on ThinkStation ==="
BENCH_FILES="$*"
ssh "$THINKSTATION" bash <<REMOTE_BENCH
set -e
REMOTE_DIR="${REMOTE_DIR}"
CP="\${REMOTE_DIR}/bench/test-classes:\${REMOTE_DIR}/bench/femtolz4.jar:\${REMOTE_DIR}/bench/lz4-java.jar"
java --enable-native-access=ALL-UNNAMED -cp "\$CP" me.bechberger.femtolz4.Benchmark $BENCH_FILES
REMOTE_BENCH

# ── Valgrind + ASAN harness ──────────────────────────────────────────────────
echo ""
echo "=== Valgrind + ASAN harness on ThinkStation ==="
ssh "$THINKSTATION" bash <<REMOTE_FUZZ
set -e
REMOTE_DIR="${REMOTE_DIR}"

if [[ ! -f "\${REMOTE_DIR}/native/fuzz_harness.c" ]]; then
    echo "SKIP: fuzz_harness.c not found on ThinkStation"
    exit 0
fi

if ! command -v valgrind &>/dev/null; then
    echo "SKIP: valgrind not found. Install with: sudo apt-get install valgrind"
    exit 0
fi

echo "--- Building ASAN harness ---"
gcc -g -O1 -fsanitize=address,undefined \
    -I\${REMOTE_DIR}/native \
    -o \${REMOTE_DIR}/fuzz_asan \
    \${REMOTE_DIR}/native/fuzz_harness.c
echo "--- Running ASAN harness ---"
\${REMOTE_DIR}/fuzz_asan && echo "ASAN: PASS"

echo "--- Building Valgrind harness ---"
gcc -g -O1 \
    -I\${REMOTE_DIR}/native \
    -o \${REMOTE_DIR}/fuzz_vg \
    \${REMOTE_DIR}/native/fuzz_harness.c
echo "--- Running Valgrind memcheck ---"
valgrind --tool=memcheck --error-exitcode=1 --quiet \${REMOTE_DIR}/fuzz_vg && echo "Valgrind: PASS"
REMOTE_FUZZ

echo ""
echo "=== Done ==="
