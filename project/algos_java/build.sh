#!/usr/bin/env bash
#
# Compiles the production sources *in place*, next to the .java files.
#
# This is required because irace/target-runner launches the solver with
#   java -cp <this directory> Main
# which resolves classes from the source tree itself. Editing a .java file
# without running this script leaves irace measuring the previous build.
#
# Usage: ./build.sh
set -euo pipefail

cd "$(dirname "$0")"

JAVAC="/usr/lib/jvm/java-21-openjdk-amd64/bin/javac"

# Drop the previous build so a renamed or deleted class cannot linger and be
# picked up by the classpath.
find . -maxdepth 2 -name '*.class' \
    -not -path './andre_feijo/*' -not -path './bin/*' -not -path './classes/*' \
    -delete

"$JAVAC" -d . -cp . -sourcepath . Main.java

echo "Build OK: $(find . -maxdepth 2 -name '*.class' \
    -not -path './andre_feijo/*' -not -path './bin/*' -not -path './classes/*' | wc -l) classes"
