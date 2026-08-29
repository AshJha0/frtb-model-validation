#!/usr/bin/env bash
# Run the JUnit 4 suite (golden values + unit tests).
set -euo pipefail
cd "$(dirname "$0")"
[ -d out/test ] || ./build.sh
classes=$(cd out/test && find . -name '*Test.class' ! -name '*\$*' \
    | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
java -cp out/main:out/test:/usr/share/java/junit4.jar:/usr/share/java/hamcrest.jar \
    org.junit.runner.JUnitCore $classes
