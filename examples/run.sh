#!/usr/bin/env bash
# Runs one of the reference clients against the Brand Socket.
#
#   ./run.sh node|python|java [RUN_SECONDS]
#
# Reads BRAND_API_KEY from the environment, or from examples/.env (git-ignored; one line
# `BRAND_API_KEY=...`). Everything else is optional:
#   BRAND_SOCKET_URL   default https://brand-socket-api-dev-1.tradelocker.com, the direct development host
#                      (production: the dedicated hostname TradeLocker gave your brand, or https://api.tradelocker.com)
#   ACCOUNT_TYPE       LIVE (default) or DEMO
#   INSTRUMENTS        comma-separated instrument names to subscribe to, e.g. EURUSD,XAUUSD
#   RUN_SECONDS        exit after this many seconds (second argument does the same); default: run until Ctrl-C
# Installs the client's dependencies on first use (npm install / a Python venv / Maven compile).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
lang="${1:-}"; [[ -n "${2:-}" ]] && export RUN_SECONDS="$2"
if [[ -z "${BRAND_API_KEY:-}" && -f "$here/.env" ]]; then set -a; . "$here/.env"; set +a; fi
[[ -n "${BRAND_API_KEY:-}" ]] || { echo "BRAND_API_KEY is not set (export it, or put BRAND_API_KEY=... in $here/.env)" >&2; exit 2; }
export BRAND_SOCKET_URL="${BRAND_SOCKET_URL:-https://brand-socket-api-dev-1.tradelocker.com}" ACCOUNT_TYPE="${ACCOUNT_TYPE:-LIVE}"
echo "brand socket: $BRAND_SOCKET_URL  type: $ACCOUNT_TYPE  instruments: ${INSTRUMENTS:-none}  run: ${RUN_SECONDS:-until Ctrl-C} s" >&2
case "$lang" in
  node)
    command -v node >/dev/null || { echo "node is required" >&2; exit 2; }
    cd "$here/node"; [[ -d node_modules ]] || npm install --no-audit --no-fund --silent
    exec node brand-socket.mjs ;;
  python)
    command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
    cd "$here/python"
    [[ -d .venv ]] || { python3 -m venv .venv && .venv/bin/pip install -q -r requirements.txt; }
    exec .venv/bin/python brand_socket.py ;;
  java)
    command -v mvn >/dev/null && command -v java >/dev/null || { echo "mvn (Maven) and a JDK 17+ are required" >&2; exit 2; }
    cd "$here/java"
    # Build with Maven, run as a plain JVM: inside `mvn exec:java` the same client processed the
    # snapshot up to 20x slower from run to run (Maven's in-process class loading and logging).
    [[ -f target/cp.txt ]] || mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
    exec java -cp "target/classes:$(cat target/cp.txt)" com.example.brandsocket.BrandSocketClient ;;
  *)
    echo "usage: $0 node|python|java [RUN_SECONDS]" >&2; exit 2 ;;
esac
