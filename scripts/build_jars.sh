#!/bin/bash
# Build pristine baseline (git HEAD) and current (worktree src) class dirs + jars
# without Maven so the IDE's incremental builds (target/) can't interfere.
# Outputs:
#   build/base-classes,  build/femtolz4-baseline.jar
#   build/ab-current/,   build/femtolz4-current.jar
set -e
cd "$(dirname "$0")/.."

rm -rf build/base-classes
mkdir -p build/base-classes

echo "-- extracting baseline sources from git HEAD"
git archive HEAD src/main | tar -x -C build/
BASE_MAIN=build/src/main

echo "-- javac baseline"
javac --release 17 -d build/base-classes $(find $BASE_MAIN/java -name '*.java')
# native resources are build artifacts not tracked by git; the worktree's copies
# match HEAD because the C sources are unchanged.
rsync -a src/main/resources/ build/base-classes/
jar cf build/femtolz4-baseline.jar -C build/base-classes .
rm -rf build/src

echo "-- javac current"
rm -rf build/ab-current
javac --release 17 -d build/ab-current $(find src/main/java -name '*.java')
rsync -a src/main/resources/ build/ab-current/
jar cf build/femtolz4-current.jar -C build/ab-current .
echo DONE
