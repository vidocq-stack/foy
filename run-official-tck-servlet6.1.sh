#!/bin/bash
set -e

# ==============================================================================
# Launcher for the official Jakarta Servlet 6.1 TCK against Foy
# ==============================================================================
#
# Prerequisites:
# 1. Install the official (non-public) TCK artifacts locally:
#    - jakarta.tck:servlet-tck-runtime:6.1.0
#    - jakarta.tck:servlet-tck-util:6.1.0
#    - jakarta.tck:servlet-tck:6.1.0 (pom)
#
# Usage:
#   ./run-official-tck-servlet6.1.sh                          # Runs a smoke test
#   ./run-official-tck-servlet6.1.sh --all                    # Runs the WHOLE suite (long!)
#   ./run-official-tck-servlet6.1.sh -Dtest=ServletTests      # Runs a whole test class
# ==============================================================================

# Make sure we run from the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Filter out the --all argument so it is not passed to Maven
MVN_ARGS=()
USE_ALL=false
for arg in "$@"; do
    if [[ "$arg" == "--all" ]]; then
        USE_ALL=true
    else
        MVN_ARGS+=("$arg")
    fi
done

echo "🚀 [1/3] Installing the Foy reactor into the local repository..."
./mvnw -ntp install -DskipTests

echo "📂 [2/3] foy-tck is in-reactor, enabled by the 'tck' Maven profile"

# Argument preparation
# If no test is given and --all is absent, run a single smoke test
DEFAULT_TEST=""
if [[ "$*" != *"-Dtest="* && "$USE_ALL" == "false" ]]; then
    DEFAULT_TEST="-Dtest=servlet.tck.api.jakarta_servlet.servlet.ServletTests#DoDestroyedTest"
    echo "💡 No test specified. Using the default test (smoke test) : $DEFAULT_TEST"
    echo "💡 To run the whole official TCK, use : ./run-official-tck-servlet6.1.sh --all"
fi

echo "🧪 [3/3] Running Maven with the tck-official profile..."
./mvnw -ntp -P"tck,tck-official" -pl foy-tck test $DEFAULT_TEST "${MVN_ARGS[@]}"
