#!/usr/bin/env bash
# Compare original synthetic exporter bytes on immutable historical readers.
set -euo pipefail
repo=$(git rev-parse --show-toplevel)
root="${RUNNER_TEMP:?}/tracker-restore-compat"
export TRACKER_MATRIX_DIR="$root/results"
mkdir -p "$TRACKER_MATRIX_DIR" "$root/sources"

run_case() {
  local revision=$1 template=$2 label=$3 fixed=$4
  local directory="$root/sources/$revision"
  if [ ! -d "$directory" ]; then
    mkdir -p "$directory"
    git -C "$repo" archive "$revision" | tar -x -C "$directory"
  fi
  cp "$repo/scripts/restore-compat/$template.kt.txt" "$directory/core/src/test/kotlin/dev/notai/tracker/LegacyMatrixTest.kt"
  local status=0
  (
    cd "$directory"
    export TRACKER_MATRIX_LABEL="$label" TRACKER_MATRIX_FIXED="$fixed"
    ./gradlew --no-daemon --console=plain -PtrackerUnsigned=true :core:test --tests 'dev.notai.tracker.LegacyMatrixTest'
  ) 2>&1 | tee "$TRACKER_MATRIX_DIR/$label.log" || status=$?
  local xml="$directory/core/build/test-results/test/TEST-dev.notai.tracker.LegacyMatrixTest.xml"
  if [ -f "$xml" ]; then cp "$xml" "$TRACKER_MATRIX_DIR/$label-junit.xml"; fi
  return "$status"
}

run_case 2664dd9 produce-v1 produce-v1 false
run_case 68c5947 read-v1-old first-rejection false
run_case 4447b6b produce-v15 produce-v15 false
run_case 4447b6b read-current app-0151 false
run_case cd35293649e1f165bbabf78f7684530632be7f25 read-current app-0160 false
run_case 7a0ea376399e6f8bf3401cbe713d61e61c432f96 read-current app-0170 false
run_case 4a154ae5f7696ba4763570afaa4595af3b95cc25 read-current prior-patched true
run_case "${TRACKER_MATRIX_HEAD:?}" read-current patched true

# Exercise the actual Android ContentResolver -> private staging -> ViewModel
# preview/notice path on those same synthetic bytes. The native library is a
# host test fixture, never an Android installation.
(cd "$repo"; bash scripts/build-cleanup-host.sh "$JAVA_HOME" "$root/libtracker_cleanup.so")
run_ui_case() {
  local revision=$1 label=$2 fixed=$3
  local directory="$root/sources/$revision"
  cp "$repo/scripts/restore-compat/read-ui.kt.txt" "$directory/app/src/test/kotlin/dev/notai/tracker/android/ui/LegacyUiMatrixTest.kt"
  local status=0
  (
    cd "$directory"
    export TRACKER_MATRIX_LABEL="$label" TRACKER_MATRIX_FIXED="$fixed"
    ./gradlew --no-daemon --console=plain -PtrackerUnsigned=true -PtrackerCleanupLibrary="$root/libtracker_cleanup.so" \
      :app:testDebugUnitTest --tests 'dev.notai.tracker.android.ui.LegacyUiMatrixTest'
  ) 2>&1 | tee "$TRACKER_MATRIX_DIR/ui-$label.log" || status=$?
  local xml="$directory/app/build/test-results/testDebugUnitTest/TEST-dev.notai.tracker.android.ui.LegacyUiMatrixTest.xml"
  if [ -f "$xml" ]; then cp "$xml" "$TRACKER_MATRIX_DIR/ui-$label-junit.xml"; fi
  return "$status"
}
run_ui_case 4447b6b app-0151 false
run_ui_case cd35293649e1f165bbabf78f7684530632be7f25 app-0160 false
run_ui_case 7a0ea376399e6f8bf3401cbe713d61e61c432f96 app-0170 false
run_ui_case "${TRACKER_MATRIX_HEAD:?}" patched true
sha256sum "$TRACKER_MATRIX_DIR/original-v1.json" "$TRACKER_MATRIX_DIR/original-v1-empty.json" "$TRACKER_MATRIX_DIR/app-v15.json" > "$TRACKER_MATRIX_DIR/sha256.txt"
cat "$TRACKER_MATRIX_DIR/v1-first-failure.txt" "$TRACKER_MATRIX_DIR/app-0151.txt" "$TRACKER_MATRIX_DIR/app-0160.txt" "$TRACKER_MATRIX_DIR/app-0170.txt" "$TRACKER_MATRIX_DIR/prior-patched.txt" "$TRACKER_MATRIX_DIR/patched.txt"
cat "$TRACKER_MATRIX_DIR/ui-app-0151.txt" "$TRACKER_MATRIX_DIR/ui-app-0160.txt" "$TRACKER_MATRIX_DIR/ui-app-0170.txt" "$TRACKER_MATRIX_DIR/ui-patched.txt"
