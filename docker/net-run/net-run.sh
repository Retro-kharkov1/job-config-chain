#!/usr/bin/env bash
# UI-38 + UI-39: one baseline run (unchanged tree reproduced) and one after run of the full suite.
# Usage: net-run.sh [orig-dir-with-PageHelp.java-and-csp-block.txt]
# Exit code is non-zero if either run fails (or the container could not start).
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
export MSYS_NO_PATHCONV=1
DEFAULT_ORIG="C:/Users/retro/AppData/Local/Temp/claude/C--Repos/6d83099c-b33c-4705-9146-ba1bd065b8af/scratchpad/orig"
ORIG_DIR="$(cd "${1:-$DEFAULT_ORIG}" && { pwd -W 2>/dev/null || pwd; })" || { echo "orig dir not found" >&2; exit 2; }
export ORIG_DIR
overall=0
for mode in baseline after; do
  echo "######## RUN $mode ########"
  docker compose -p repos-config-template-sync -f docker-compose.net.yml run --rm net "$mode" 2>&1 | tee "net-run-$mode.log"
  rc=${PIPESTATUS[0]}
  echo "######## $mode exit $rc ########"
  if [ "$rc" -ne 0 ]; then overall=1; fi
done
exit $overall
