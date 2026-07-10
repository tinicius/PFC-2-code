#!/usr/bin/env bash
set -euo pipefail

date

cd "$(dirname "$0")"

JAVA_DIR="project/algos_java"
INSTANCE="datasets/b/instance_0012.txt"
OUTPUT="/tmp/sa_output.json"

echo "=== Compiling Java ==="
javac -d "$JAVA_DIR" \
    "$JAVA_DIR/Main.java" \
    "$JAVA_DIR/heuristic/Heuristic.java" "$JAVA_DIR/heuristic/SA.java" "$JAVA_DIR/heuristic/ILS.java" \
    "$JAVA_DIR/model/Problem.java" "$JAVA_DIR/model/Solution.java" \
    "$JAVA_DIR/neighborhood/Move.java" "$JAVA_DIR/neighborhood/AddAisle.java" \
    "$JAVA_DIR/neighborhood/RemoveAisle.java" "$JAVA_DIR/neighborhood/SwapAisle.java" \
    "$JAVA_DIR/neighborhood/SwapOrder.java" "$JAVA_DIR/neighborhood/AddOrder.java" \
    "$JAVA_DIR/neighborhood/RemoveOrder.java" \
    "$JAVA_DIR/constructive/AisleFirst.java"

echo ""
echo "=== Running SA on $INSTANCE ==="
java -cp "$JAVA_DIR" Main \
    --input="$INSTANCE" \
    --output="$OUTPUT" \
    --time-limit=600 \
    --seed=54507 \
    --algo=sa \
    --params='{"alpha":0.95,"saMax":10000,"maxIters":1000000}'
