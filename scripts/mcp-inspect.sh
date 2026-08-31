#!/usr/bin/env bash
#
# Drives the server with the official MCP Inspector.
#
# The Inspector launches the server as a subprocess and speaks the protocol to it, so this
# exercises the packaged artefact the way a real client would -- catching things unit tests
# cannot, such as configuration keys the runtime silently ignores.
#
# Usage:
#   scripts/mcp-inspect.sh                          # list tools
#   scripts/mcp-inspect.sh tools/list
#   scripts/mcp-inspect.sh call searchKnowledgeBase query="CrashLoopBackOff"
#   scripts/mcp-inspect.sh call getArticle articleId=7129807
#   JAR=/path/to/mcp-redhat-kb.jar scripts/mcp-inspect.sh   # test a released JAR
#
# REDHAT_TOKEN is optional: without a valid one the tools still answer, they just fail at
# the Red Hat API rather than before it, which is enough to verify the protocol surface.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${JAR:-$REPO_ROOT/target/quarkus-app/quarkus-run.jar}"
INSPECTOR="${INSPECTOR:-@modelcontextprotocol/inspector@latest}"

if [[ ! -f "$JAR" ]]; then
  echo "No JAR at $JAR" >&2
  echo "Build it first:  ./mvnw package -DskipTests" >&2
  exit 1
fi

# The Inspector appends its own flags to the server command, so java would receive
# "--method tools/list" and print its usage instead of starting. A wrapper absorbs them.
LAUNCHER="$(mktemp -t mcp-server-XXXXXX.sh)"
trap 'rm -f "$LAUNCHER"' EXIT

cat > "$LAUNCHER" <<EOF
#!/bin/sh
# Baked in rather than inherited: the Inspector spawns the server with a clean
# environment, so a \$REDHAT_TOKEN read here would always be empty and every
# credentialed call would come back as "SSO rejected the token (HTTP 400)".
export REDHAT_TOKEN='${REDHAT_TOKEN:-inspector-placeholder-token}'
exec java \\
  -Dquarkus.http.host-enabled=false \\
  -Dquarkus.mcp.server.stdio.enabled=true \\
  -Dquarkus.banner.enabled=false \\
  -Dquarkus.log.level=WARN \\
  -jar "$JAR"
EOF
# 700, not +x: the file holds the token, so no other user may read it.
chmod 700 "$LAUNCHER"

run_inspector() {
  # The Inspector exits 5 when a tool returns isError:true. That is the tool reporting a
  # problem, not the Inspector failing, so it must not abort the script.
  npx -y "$INSPECTOR" --cli "$LAUNCHER" "$@" 2>/dev/null || true
}

method="${1:-tools/list}"

case "$method" in
  call)
    shift
    if [[ $# -eq 0 ]]; then
      echo "Usage: $0 call <tool-name> [name=value ...]" >&2
      exit 2
    fi
    tool="$1"; shift

    args=()
    for pair in "$@"; do
      args+=(--tool-arg "$pair")
    done
    run_inspector --method tools/call --tool-name "$tool" ${args[@]+"${args[@]}"}
    ;;
  *)
    run_inspector --method "$method"
    ;;
esac
