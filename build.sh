#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
root="$PWD"
out="$root/build"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$out" "$tmp/classes" "$tmp/tests" "$tmp/stubs" "$tmp/tools" "$tmp/deps"
unzip -q build-support/compile-libs.zip -d "$tmp/deps"
base="$root/build-support/base-dev.5.8.jar"
javac --release 21 -d "$tmp/stubs" core/compile-stubs/net/md_5/bungee/api/chat/BaseComponent.java
mapfile -t overlay < <(find core/src/phonon/nodes/keywar core/src/phonon/nodes/refinery/legacy1182 -name '*.java' | sort)
javac --release 21 -cp "$base:$tmp/deps/*:$tmp/stubs" -d "$tmp/classes" "${overlay[@]}" \
  core/src/phonon/nodes/imported/ForkFeatures.java core/src/phonon/nodes/map/MapEditApi.java
javac --release 21 -cp "$tmp/classes:$tmp/deps/gson-2.13.2.jar" -d "$tmp/tests" core/test/*.java
for test_name in KeyWarModelTest KeyWarConfigMigrationTest RefineryMathTest; do
  java -cp "$tmp/tests:$tmp/classes:$tmp/deps/gson-2.13.2.jar" "$test_name"
done
javac --release 21 -cp "$tmp/deps/asm-9.8.jar" -d "$tmp/tools" core/PatchKeyPointJar.java
java -cp "$tmp/tools:$tmp/deps/asm-9.8.jar" PatchKeyPointJar \
  "$base" "$tmp/classes" "$tmp/milzzynodes.jar" core/refinery-1182.yml
mv "$tmp/milzzynodes.jar" "$out/milzzynodes.jar"
