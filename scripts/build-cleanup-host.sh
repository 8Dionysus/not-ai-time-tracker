#!/usr/bin/env bash
set -eu
# Host-only POSIX fixture library. This does not package or validate an Android ABI.
test "$#" -eq 2 || { echo 'usage: build-cleanup-host.sh JDK_DIRECTORY OUTPUT.so' >&2; exit 2; }
task_jdk=$1
task_output=$2
mkdir -p "$(dirname "$task_output")"
cc -std=c11 -O2 -fPIC -shared -Wall -Wextra -Wno-misleading-indentation \
  -I"$task_jdk/include" -I"$task_jdk/include/linux" \
  core/src/main/c/private_copy_io.c -o "$task_output"
