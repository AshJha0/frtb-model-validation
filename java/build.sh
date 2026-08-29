#!/usr/bin/env bash
# Build the frtb Java library and tests into out/.
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir -p out/main out/test
javac -Xlint:all -Werror -d out/main $(find src/main/java -name '*.java' | sort)
javac -Xlint:all -Werror \
    -cp out/main:/usr/share/java/junit4.jar:/usr/share/java/hamcrest.jar \
    -d out/test $(find src/test/java -name '*.java' | sort)
echo "build OK"
