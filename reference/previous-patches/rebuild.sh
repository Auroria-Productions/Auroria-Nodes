#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
base=${1:?Path to Nodes dev.5.3 JAR}
deps=${2:?Path to NodesMap deps directory}
output=${3:?Output JAR path}
imported=${4:?Path to the uploaded nodes-0.1.1.jar}
ginjer=${5:?Path to the uploaded ginjer-nodes-master.zip}
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes" "$tmp/tool"
jdk=()
if [[ -n ${JAVA_HOME:-} ]]; then jdk=("$JAVA_HOME/bin/"); fi
"${jdk[0]-}javac" --release 21 -cp "$base:$deps/*:$imported" -d "$tmp/classes" $(find src -name '*.java')
"${jdk[0]-}javac" --release 21 -cp "$deps/*" -d "$tmp/tool" BuildMilzzy.java GinjerPatch.java PerformancePatch.java
"${jdk[0]-}java" -cp "$tmp/tool:$deps/*" BuildMilzzy "$base" "$tmp/classes" "$output" "$imported" "$ginjer"
