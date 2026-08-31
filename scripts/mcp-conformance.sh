#!/usr/bin/env bash
#
# Runs the official MCP conformance suite against the packaged server.
#
#   https://github.com/modelcontextprotocol/conformance
#
# What this proves that the Java tests do not: McpAssured asserts that *these tools* behave,
# against an in-process server. This drives the real binary over Streamable HTTP and checks
# the wire format against the specification's own schemas -- including the parts the Quarkus
# MCP extension generates rather than this codebase. That is what makes it worth running
# when the extension is upgraded: a regression in transport, session handling or the
# initialize handshake would surface here and nowhere else in the build.
#
# Usage:
#   scripts/mcp-conformance.sh                 # active suite, checked against the baseline
#   scripts/mcp-conformance.sh --suite all     # include pending scenarios
#   scripts/mcp-conformance.sh --scenario tools-list
#   JAR=/path/to/released.jar scripts/mcp-conformance.sh
#
# Exit status is the suite's own: non-zero when a scenario outside the baseline fails, and
# also when a scenario listed in the baseline starts passing (a stale entry). Both are
# things you want to know about, so neither is swallowed.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${JAR:-$REPO_ROOT/target/quarkus-app/quarkus-run.jar}"
CONFORMANCE="${CONFORMANCE:-@modelcontextprotocol/conformance@0.1.16}"
EXPECTED_FAILURES="${EXPECTED_FAILURES:-$REPO_ROOT/scripts/conformance-expected-failures.yaml}"
PORT="${PORT:-9099}"

if [[ ! -f "$JAR" ]]; then
  echo "No JAR at $JAR" >&2
  echo "Build it first:  ./mvnw package -DskipTests" >&2
  exit 1
fi

SERVER_LOG="$(mktemp -t mcp-conformance-XXXXXX.log)"
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f "$SERVER_LOG"
}
trap cleanup EXIT

# The suite is an unauthenticated client: it speaks plain MCP and knows nothing about
# Keycloak or Red Hat tokens. So the server runs here the way the protocol tests configure
# it -- OIDC off and the /mcp policy set to permit -- which is exactly the surface the
# scenarios are about. Neither override touches src/main/resources: the shipped defaults
# stay authenticated, and McpAuthenticationTest is what pins that.
#
# The token is a placeholder on purpose. Every scenario in the active suite stops at the
# protocol layer (initialize, tools/list, ping, SSE); none reaches Red Hat, so a real
# credential would add nothing and put a live token in a CI process.
REDHAT_TOKEN="${REDHAT_TOKEN:-conformance-placeholder-token}" \
java \
  -Dquarkus.http.port="$PORT" \
  -Dquarkus.http.host=127.0.0.1 \
  -Dquarkus.oidc.enabled=false \
  -Dquarkus.http.auth.permission.mcp.policy=permit \
  -Dquarkus.banner.enabled=false \
  -Dquarkus.log.level=WARN \
  -jar "$JAR" > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!

URL="http://127.0.0.1:$PORT/mcp"

# Poll the endpoint rather than sleeping a fixed interval: JVM start varies enough that a
# fixed wait is either flaky or slow. A successful initialize is the readiness signal --
# the port accepting a connection is not, since Quarkus binds before the MCP routes exist.
ready=""
for _ in $(seq 1 60); do
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "Server exited before becoming ready:" >&2
    cat "$SERVER_LOG" >&2
    exit 1
  fi
  if curl -sf -X POST "$URL" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json, text/event-stream' \
      -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"readiness","version":"1"}}}' \
      >/dev/null 2>&1; then
    ready=yes
    break
  fi
  sleep 1
done

if [[ -z "$ready" ]]; then
  echo "Server did not become ready at $URL" >&2
  cat "$SERVER_LOG" >&2
  exit 1
fi

echo "Server ready at $URL (pid $SERVER_PID)"
echo "Baseline: $EXPECTED_FAILURES"
echo

# --expected-failures is what turns 22 red lines into a pass/fail signal. Without it the
# suite reports every unimplemented-capability scenario as a failure and the run is noise
# that nobody reads -- which is the usual reason this tool gets wired up and then ignored.
npx -y "$CONFORMANCE" server \
  --url "$URL" \
  --expected-failures "$EXPECTED_FAILURES" \
  "$@"
