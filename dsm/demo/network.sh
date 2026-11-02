#!/bin/bash
# The DSM side of the demo: a storage set of real storage nodes and one dsm-app-host per organization. Runs until
# stopped. Stopping the storage set takes its data with it, so a run directory serves one demo: to start over, stop
# everything, move dsm/demo/run aside, and remove the demo's MySQL containers (README.md).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run"
BIN="${DSM_BIN:-$HERE/../../../dsm-main/target/release}"
: "${DSM_TEST_DATABASE_URL:=postgresql://$USER@127.0.0.1:5432/dsm_openboxes_demo?sslmode=disable}"
export DSM_TEST_DATABASE_URL

if [ -e "$RUN/fleet" ]; then
    echo "$RUN already holds a demo, and its storage set's data went when it stopped: move it aside to start over" >&2
    exit 1
fi
mkdir -p "$RUN/fleet" "$RUN/org-a" "$RUN/org-b" "$RUN/registry"

pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT

# The storage set stops when its stdin closes, so it gets a pipe that stays open as long as this script runs.
sleep 2147483647 | "$BIN/openboxes-dsm-fleet" --out "$RUN/fleet" > "$RUN/fleet/fleet.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 240); do
    grep -q '^READY ' "$RUN/fleet/fleet.log" && break
    kill -0 "${pids[0]}" 2>/dev/null || { echo "the storage set stopped:"; tail -20 "$RUN/fleet/fleet.log"; exit 1; }
    sleep 1
done
CONFIG="$(grep '^READY ' "$RUN/fleet/fleet.log" | head -1 | cut -d' ' -f2-)"
[ -n "$CONFIG" ] || { echo "the storage set never became ready"; exit 1; }

host() { # name, data root, ingress port, relay port
    RUST_LOG=info "$BIN/dsm-app-host" \
        --data-dir "$2/host" \
        --env-config "$CONFIG" \
        --game-bind "127.0.0.1:$3" \
        --relay-bind "127.0.0.1:$4" \
        --relay-endpoint "https://127.0.0.1:$4" \
        --relay-name 127.0.0.1 > "$2/host.log" 2>&1 &
    pids+=($!)
}
host warehouse-a "$RUN/org-a" 8781 8791
host warehouse-b "$RUN/org-b" 8782 8792
host registry "$RUN/registry" 8783 8793

for root in org-a org-b registry; do
    for _ in $(seq 1 120); do
        [ -f "$RUN/$root/host/ingress-token" ] && break
        sleep 1
    done
    [ -f "$RUN/$root/host/ingress-token" ] || { echo "$root's host did not start:"; tail -20 "$RUN/$root/host.log"; exit 1; }
done
echo "READY"
wait
