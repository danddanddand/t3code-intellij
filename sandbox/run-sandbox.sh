#!/usr/bin/env bash
# Reproduces the embedded-browser failure in an isolated sandbox IDE.
#
#   sandbox/run-sandbox.sh [minutes]
#
# Starts an isolated `t3 serve` (own data dir, port 3799), launches a sandbox IntelliJ with the plugin and
# JCEF diagnostics forced on, auto-opens the T3 Code tool window, and samples the cef_server / jcef Helper
# processes every 5 s. Everything lands in sandbox/out/: jcef.log, ide-stdout.log, t3-serve.log,
# processes.csv and the sandbox IDE's own idea.log.
set -euo pipefail
cd "$(dirname "$0")/.."
MINUTES="${1:-6}"
OUT="$PWD/sandbox/out"; T3_HOME="$PWD/sandbox/t3-home"; PORT=3799
rm -rf "$OUT"; mkdir -p "$OUT" "$T3_HOME"

# --- isolated T3 server ---------------------------------------------------------------------------------
# T3_SANDBOX_URL=<url> skips the T3 server and points the pane at any page (control experiments).
# T3_SANDBOX_INPROCESS=true runs JCEF in-process instead of via cef_server.
if [ -n "${T3_SANDBOX_URL:-}" ]; then
  URL="$T3_SANDBOX_URL"; T3_PID=""
else
t3 serve --mode web --host 127.0.0.1 --port "$PORT" --base-dir "$T3_HOME" --no-browser \
  --auto-bootstrap-project-from-cwd "$PWD/sandbox/project" >"$OUT/t3-serve.log" 2>&1 &
T3_PID=$!
for _ in $(seq 1 30); do curl -sf -o /dev/null "http://127.0.0.1:$PORT/" && break; sleep 1; done
curl -sf -o /dev/null "http://127.0.0.1:$PORT/" || { echo "t3 serve did not come up"; cat "$OUT/t3-serve.log"; kill $T3_PID; exit 1; }
echo "t3 serve up on :$PORT (pid $T3_PID)"
URL="http://127.0.0.1:$PORT/"
fi

# --- sandbox IDE -----------------------------------------------------------------------------------------
# The sandbox is Ultimate (Community is no longer published), so it needs your licence: reuse the real IDE's key.
SANDBOX_CONFIG=".intellijPlatform/sandbox/IntellijT3Code/IU-2026.2/config_runSandboxIde"
KEY="$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.2/idea.key"
[ -f "$KEY" ] && { mkdir -p "$SANDBOX_CONFIG"; cp "$KEY" "$SANDBOX_CONFIG/idea.key"; }
./gradlew runSandboxIde -Pt3SandboxUrl="$URL" -Pt3SandboxInProcess="${T3_SANDBOX_INPROCESS:-false}" --console=plain >"$OUT/ide-stdout.log" 2>&1 &
GRADLE_PID=$!
echo "sandbox IDE starting (gradle pid $GRADLE_PID)"
for _ in $(seq 1 180); do grep -q "Task :runSandboxIde" "$OUT/ide-stdout.log" 2>/dev/null && break; sleep 5; done
grep -q "Task :runSandboxIde" "$OUT/ide-stdout.log" || { echo "IDE never launched"; tail -20 "$OUT/ide-stdout.log"; kill $GRADLE_PID $T3_PID; exit 1; }
echo "sandbox IDE launched, sampling for $MINUTES min"

# --- process sampler -------------------------------------------------------------------------------------
echo "ts,pid,rss_kb,cpu,etime,comm" >"$OUT/processes.csv"
END=$((SECONDS + MINUTES * 60)); SEEN=0; DIED_AT=""
while [ $SECONDS -lt $END ]; do
  NOW=$(date +%H:%M:%S)
  ROWS=$(ps -axo pid=,rss=,%cpu=,etime=,comm= | grep -i "cef_server\|jcef Helper" | grep -v grep || true)
  if [ "${T3_SANDBOX_INPROCESS:-false}" = "true" ]; then ROWS=$(ps -axo pid=,rss=,%cpu=,etime=,comm= | grep -i "jcef Helper" | grep -v grep || true); fi
  if [ -n "$ROWS" ]; then
    SEEN=1
    echo "$ROWS" | awk -v t="$NOW" '{c=$5; for(i=6;i<=NF;i++) c=c" "$i; printf "%s,%s,%s,%s,%s,%s\n", t,$1,$2,$3,$4,c}' >>"$OUT/processes.csv"
  elif [ $SEEN -eq 1 ] && [ -z "$DIED_AT" ]; then
    DIED_AT="$NOW"; echo "cef_server gone at $DIED_AT"; echo "$NOW,DIED,,,," >>"$OUT/processes.csv"
    sleep 20; break
  fi
  sleep 5
done

# --- collect and stop ------------------------------------------------------------------------------------
SANDBOX_LOG=$(ls -d .intellijPlatform/sandbox/*/*/log_runSandboxIde 2>/dev/null | head -1 || true)
[ -n "$SANDBOX_LOG" ] && cp -R "$SANDBOX_LOG" "$OUT/ide-log" || true
pkill -f "config_runSandboxIde" || true; kill $GRADLE_PID 2>/dev/null || true
if [ -n "$T3_PID" ]; then kill $T3_PID 2>/dev/null || true; fi
sleep 3
echo "--- summary"; echo "cef_server died at: ${DIED_AT:-never}"
echo "first/last samples:"; sed -n '2p;$p' "$OUT/processes.csv"
echo "outputs in $OUT"
