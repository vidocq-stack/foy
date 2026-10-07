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
# ==============================================================================

usage() {
    cat <<'EOF'
Usage: run-official-tck-servlet6.1.sh [options] [maven-args...]

Runs the official Jakarta Servlet 6.1 TCK (foy-tck) against Foy. With no
selection option, a single smoke test is run.

Options:
  --all                    Run the WHOLE suite (long, ~11 min).
  --family <name>          Run one TCK family: api | spec | pluggability | compat.
  --failing [tally-file]   (combine with --family to narrow) Run only the classes whose "bad" count (3rd column) is
                           > 0 in the tally (default: foy-tck/tck-baseline.txt;
                           format "<fqcn> <run> <bad>", see foy-tck/tck-tally.sh).
  --no-install             Skip the reactor install step (code unchanged since
                           the last build).
  --dry-run                Print the Maven commands without running them.
  -h, --help               Show this help.
  -Dtest=<pattern>         Forwarded to surefire (class, class#method, ...).
  Any other -D... argument is forwarded to Maven.

Examples:
  run-official-tck-servlet6.1.sh                               # smoke test
  run-official-tck-servlet6.1.sh --all                         # full suite
  run-official-tck-servlet6.1.sh --family compat               # one family
  run-official-tck-servlet6.1.sh --failing                     # known failures only
  run-official-tck-servlet6.1.sh --no-install -Dtest=ServletTests
  run-official-tck-servlet6.1.sh --no-install --family api --dry-run
EOF
}

# Make sure we run from the project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

MVN_ARGS=()
USE_ALL=false
DO_INSTALL=true
DRY_RUN=false
FAMILY=""
USE_FAILING=false
TALLY_FILE="foy-tck/tck-baseline.txt"
HAS_TEST=false

while [ $# -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --all) USE_ALL=true ;;
        --no-install) DO_INSTALL=false ;;
        --dry-run) DRY_RUN=true ;;
        --family)
            if [ $# -lt 2 ]; then echo "error: --family needs a value" >&2; usage >&2; exit 2; fi
            FAMILY="$2"; shift
            case "$FAMILY" in
                api|spec|pluggability|compat) ;;
                *) echo "error: unknown family '$FAMILY'" >&2; usage >&2; exit 2 ;;
            esac
            ;;
        --failing)
            USE_FAILING=true
            # Optional tally file argument (anything that is not an option)
            if [ $# -ge 2 ] && [[ "$2" != -* ]]; then TALLY_FILE="$2"; shift; fi
            ;;
        -Dtest=*) HAS_TEST=true; MVN_ARGS+=("$1") ;;
        -*)
            case "$1" in
                -D*) MVN_ARGS+=("$1") ;;
                *) echo "error: unknown option '$1'" >&2; usage >&2; exit 2 ;;
            esac
            ;;
        *) echo "error: unexpected argument '$1'" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

# Build the -Dtest selection from --family / --failing
SELECTED_TEST=""
if [ -n "$FAMILY" ]; then
    SELECTED_TEST="servlet/tck/$FAMILY/**/*"
fi
if [ "$USE_FAILING" = true ]; then
    if [ ! -f "$TALLY_FILE" ]; then
        echo "error: tally file not found: $TALLY_FILE" >&2
        exit 2
    fi
    # With --family, keep only the failing classes of that family
    FAILING_LIST="$(awk -v prefix="servlet.tck.$FAMILY." \
        'NF >= 3 && $3 > 0 && index($1, prefix) == 1 { printf "%s%s", sep, $1; sep="," }' "$TALLY_FILE")"
    if [ -z "$FAILING_LIST" ]; then
        echo "No failing class in $TALLY_FILE: nothing to run."
        exit 0
    fi
    SELECTED_TEST="$FAILING_LIST"
fi

EXTRA_ARGS=()
if [ -n "$SELECTED_TEST" ]; then
    if [ "$HAS_TEST" = true ]; then
        echo "error: -Dtest cannot be combined with --family/--failing" >&2
        exit 2
    fi
    EXTRA_ARGS+=("-Dtest=$SELECTED_TEST" "-Dsurefire.failIfNoSpecifiedTests=false")
    HAS_TEST=true
fi

# If no test is given and --all is absent, run a single smoke test
if [ "$HAS_TEST" = false ] && [ "$USE_ALL" = false ]; then
    EXTRA_ARGS+=("-Dtest=servlet.tck.api.jakarta_servlet.servlet.ServletTests#DoDestroyedTest")
    echo "💡 No test specified. Using the default test (smoke test)."
    echo "💡 To run the whole official TCK, use : ./run-official-tck-servlet6.1.sh --all"
fi

run() {
    if [ "$DRY_RUN" = true ]; then
        echo "[dry-run] $*"
    else
        "$@"
    fi
}

if [ "$DO_INSTALL" = true ]; then
    echo "🚀 [1/3] Installing the Foy reactor into the local repository..."
    run ./mvnw -ntp install -DskipTests
else
    echo "⏭️  [1/3] Skipping the reactor install (--no-install)"
fi

echo "📂 [2/3] foy-tck is in-reactor, enabled by the 'tck' Maven profile"

echo "🧪 [3/3] Running Maven with the tck-official profile..."
run ./mvnw -ntp -Ptck,tck-official -pl foy-tck test "${EXTRA_ARGS[@]}" "${MVN_ARGS[@]}"
