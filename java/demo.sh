#!/usr/bin/env bash
# Run the end-to-end demo (writes validation_report.md into java/).
set -euo pipefail
cd "$(dirname "$0")"
[ -d out/main ] || ./build.sh
java -cp out/main com.quant.frtb.Demo "$@"
