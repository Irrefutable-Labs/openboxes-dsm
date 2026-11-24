#!/bin/bash
# Rebuild OpenBoxes and restart both organizations. Their databases, accounts and DSM hosts keep running.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
O="$HERE/../.."
D="$O/.."
export JAVA_HOME="$D/jdk8-zulu/zulu8.98.0.107-ca-jdk8.0.512-macosx_aarch64/Contents/Home"
cd "$O" || exit 1
./gradlew --no-daemon --console=plain war bootRepackage -x npm_run_bundle -x npmInstall > "$HERE/logs/war.log" 2>&1
code=$?
grep -E 'BUILD |\.groovy: [0-9]+:' "$HERE/logs/war.log" | cut -c1-170
[ "$code" = 0 ] || exit 1
# This demo's instances only: each names its data root under run/, so another checkout's OpenBoxes is left alone.
pids() { ps -axo pid=,command= | grep 'openboxes.war' | grep -F "$HERE/run/" | grep -v grep | awk '{print $1}'; }
for p in $(pids); do kill "$p"; done
for _ in $(seq 1 90); do [ -z "$(pids)" ] && break; sleep 1; done
for p in $(pids); do echo "force stopping $p"; kill -9 "$p"; done
sleep 1
python3 "$HERE/up.py" a b
for i in $(seq 1 90); do
    a=$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/openboxes/health)
    b=$(curl -s -m 3 -o /dev/null -w '%{http_code}' http://127.0.0.1:8082/openboxes/health)
    [ "$a" = 200 ] && [ "$b" = 200 ] && { echo "SERVICES-UP after about $((i*3)) s"; exit 0; }
    sleep 3
done
echo "SERVICES-NOT-UP a=$a b=$b"; grep -E 'Caused by|Liquibase.*(error|fail)' "$HERE/logs/a.log" | tail -3 | cut -c1-200; exit 1
