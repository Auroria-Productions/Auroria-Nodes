#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
BASE=${1:-../../milzzynodes.jar}
OUT=${2:-../../milzzynodes.jar.updated.jar}
DEPS=../map/deps
TEMP=$(mktemp -d)
trap 'rm -rf "$TEMP"' EXIT
mkdir -p "$TEMP/classes" "$TEMP/tools"
javac --release 21 -cp "$BASE:$DEPS/*" -d "$TEMP/classes" src/phonon/nodes/admin/AdminTools.java src/phonon/nodes/storage/JsonStorageLayout.java
javac --release 21 -cp "$DEPS/asm-9.8.jar" -d "$TEMP/tools" PatchJar.java
java -cp "$TEMP/tools:$DEPS/asm-9.8.jar" PatchJar "$BASE" "$TEMP/classes" "$OUT"
