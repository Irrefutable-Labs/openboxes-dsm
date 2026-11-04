#!/bin/bash
# One dsm-app-host per organization, on the live beta storage fleet DSM pins: the same network the phones' wallets use.
# This starts no storage nodes. The relay listens on this machine's LAN address so wallets on phones can reach it.
# Runs until stopped.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run"
# The host must be built from the same DSM code as the wallets it connects to: their signed messages are compared byte for
# byte. Set DSM_BIN to where that code's dsm-app-host was built (DSM_CHECKOUT names the code itself).
# The fleet it pins is described by fleet/dsm_env_config.toml, with the fleet's CA beside it: a copy kept here, because
# the host reads the file on every storage call, and a checkout it was read from once went away under running hosts.
DSM="${DSM_CHECKOUT:-$HERE/../../../dsm-main}"
BIN="${DSM_BIN:-$DSM/target/release}"
CONFIG="${DSM_ENV_CONFIG:-$HERE/fleet/dsm_env_config.toml}"
LAN="${LAN_ADDRESS:-$(ipconfig getifaddr en0)}"
[ -n "$LAN" ] || { echo "no LAN address: set LAN_ADDRESS" >&2; exit 1; }
mkdir -p "$RUN/org-a" "$RUN/org-b"

pids=()
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT

host() { # data root, ingress port, relay port
    RUST_LOG=info "$BIN/dsm-app-host" \
        --data-dir "$1/host" \
        --ingress-secret-dir "$1/ingress" \
        --env-config "$CONFIG" \
        --game-bind "127.0.0.1:$2" \
        --relay-bind "0.0.0.0:$3" \
        --relay-endpoint "https://$LAN:$3" \
        --relay-name "$LAN" 2>&1 | python3 "$HERE/boundedlog.py" "$1/host.log" &
    pids+=($!)
}
host "$RUN/org-a" 8781 8791
host "$RUN/org-b" 8782 8792

for root in org-a org-b; do
    for _ in $(seq 1 180); do
        [ -f "$RUN/$root/ingress/ingress-token" ] && break
        sleep 1
    done
    [ -f "$RUN/$root/ingress/ingress-token" ] || { echo "$root's host did not start:"; tail -20 "$RUN/$root/host.log"; exit 1; }
done
echo "READY on the beta fleet, relays at https://$LAN:8791 and :8792"
wait
