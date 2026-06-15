#!/usr/bin/env bash
#
# AsyncTNT integration matrix — boots a real Paper server per version with
# AsyncTNT + AsyncTNTTester installed, runs the in-server suite, and verifies
# the tester wrote PASS. Mirrors Mental's matrix-gate discipline: never trust a
# gradle "BUILD SUCCESSFUL" banner alone — verify each test-results.txt is FRESH
# and reads PASS.
#
# Usage:
#   scripts/integration-matrix.sh                  # every version in gradle.properties
#   scripts/integration-matrix.sh 1.17.1 26.1.2    # only the listed versions
#
# Notes:
#   - Runs sequentially (each gradle RunServer binds the same port). The Paper
#     paperclip jars are cached by run-paper under ~/.gradle/caches/run-task-jars,
#     so --offline boots without network once a version has been fetched once.
#   - Per-server console logs: core/build/integration-test-logs/<version>.log
#
set -uo pipefail
cd "$(dirname "$0")/.."

if [ "$#" -gt 0 ]; then
  VERSIONS=("$@")
else
  line=$(grep -E '^integrationTestVersions=' gradle.properties | cut -d= -f2)
  IFS=',' read -r -a VERSIONS <<< "$line"
fi

echo "AsyncTNT integration matrix: ${VERSIONS[*]}"
overall=0
declare -a SUMMARY=()

for v in "${VERSIONS[@]}"; do
  v="$(echo "$v" | tr -d '[:space:]')"
  [ -z "$v" ] && continue
  task="runIntegrationTest_${v//./_}"
  result="run/${v}/plugins/AsyncTNTTester/test-results.txt"
  rm -f "$result" "run/${v}/plugins/AsyncTNTTester/test-failures.txt"

  echo "── [$v] booting ($task) ──"
  ./gradlew "$task" --offline > "/tmp/asynctnt-matrix-${v}.log" 2>&1
  gradle_rc=$?

  if [ ! -f "$result" ]; then
    echo "[$v] NO RESULT — server crashed or hung (gradle rc=$gradle_rc). See core/build/integration-test-logs/${v}.log"
    SUMMARY+=("$v: NO-RESULT")
    overall=1
    continue
  fi
  verdict="$(tr -d '[:space:]' < "$result")"
  if [ "$verdict" = "PASS" ]; then
    echo "[$v] PASS"
    SUMMARY+=("$v: PASS")
  else
    echo "[$v] $verdict"
    [ -f "run/${v}/plugins/AsyncTNTTester/test-failures.txt" ] && \
      sed 's/^/    /' "run/${v}/plugins/AsyncTNTTester/test-failures.txt"
    SUMMARY+=("$v: $verdict")
    overall=1
  fi
done

echo ""
echo "════ matrix verdicts ════"
printf '  %s\n' "${SUMMARY[@]}"
[ "$overall" -eq 0 ] && echo "ALL PASS" || echo "MATRIX FAILED"
exit "$overall"
