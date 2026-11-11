#!/bin/bash
# One dsm-app-host per organization, on the live beta storage fleet DSM pins: the same network the phones' wallets use.
# This starts no storage nodes. Runs until stopped.
#
# With phones (the default), each relay listens on this machine's LAN address so the wallets on the phones can reach it.
# With DSM_DEMO_WALLETS=terminal there are no phones: the four people's wallets are dsm-app-hosts of their own here too
# (what DSM's `make dev-wallet` runs, four times; terminal_wallet.py answers them), and every relay stays on 127.0.0.1.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run"
# The host must be built from the same DSM code as the wallets it connects to: their signed messages are compared byte for
# byte. Set DSM_BIN to where that code's dsm-app-host was built (DSM_CHECKOUT names the code itself).
# The fleet it pins is described by fleet/dsm_env_config.toml, each member's CA in its own entry: a copy of the DSM
# checkout's dsm_client/frontend/public/dsm_env_config.toml kept here, because the host reads the file on every storage
# call, and a checkout it was read from once went away under running hosts.
DSM="${DSM_CHECKOUT:-$HERE/../../../dsm-main}"
BIN="${DSM_BIN:-$DSM/target/release}"
CONFIG="${DSM_ENV_CONFIG:-$HERE/fleet/dsm_env_config.toml}"
WALLETS="${DSM_DEMO_WALLETS:-phones}"
if [ "$WALLETS" = terminal ]; then
    LAN=127.0.0.1
    BIND=127.0.0.1
else
    LAN="${LAN_ADDRESS:-$(ipconfig getifaddr en0)}"
    BIND=0.0.0.0
fi
[ -n "$LAN" ] || { echo "no LAN address: set LAN_ADDRESS" >&2; exit 1; }
mkdir -p "$RUN/org-a" "$RUN/org-b"

pids=()
roots=()
trap 'kill "${pids[@]}" 2>/dev/null || true' EXIT

host() { # data root, ingress port, relay port
    RUST_LOG=info "$BIN/dsm-app-host" \
        --data-dir "$1/host" \
        --ingress-secret-dir "$1/ingress" \
        --env-config "$CONFIG" \
        --game-bind "127.0.0.1:$2" \
        --relay-bind "$BIND:$3" \
        --relay-endpoint "https://$LAN:$3" \
        --relay-name "$LAN" 2>&1 | python3 "$HERE/boundedlog.py" "$1/host.log" &
    pids+=($!)
    roots+=("$1")
}
host "$RUN/org-a" 8781 8791
host "$RUN/org-b" 8782 8792
if [ "$WALLETS" = terminal ]; then
    # Ports as in terminal_wallet.py: Marcus, Priya, the driver (Dev) and Amara.
    for wallet in marcus:8801:8811 priya:8802:8812 dev:8803:8813 amara:8804:8814; do
        IFS=: read -r name ingress relay <<<"$wallet"
        mkdir -p "$RUN/wallets/$name"
        host "$RUN/wallets/$name" "$ingress" "$relay"
    done
fi

# Ready when its ingress answers with this launch's secret: a host holds its ingress until the network can find its
# account (DSM's dsm-app-host, wait_published), so the secret file alone is not enough.
answers() { # data root, ingress port
    [ -s "$1/ingress/ingress-token" ] &&
        curl -fsS --max-time 5 -H "Authorization: Bearer $(cat "$1/ingress/ingress-token")" \
            "http://127.0.0.1:$2/activity/0" > /dev/null 2>&1
}
ports=(8781 8782 8801 8802 8803 8804)
for i in "${!roots[@]}"; do
    root="${roots[$i]}"
    for _ in $(seq 1 240); do
        answers "$root" "${ports[$i]}" && break
        sleep 1
    done
    answers "$root" "${ports[$i]}" || { echo "${root#"$RUN"/}'s host did not start:"; tail -20 "$root/host.log"; exit 1; }
done
echo "READY on the beta fleet, relays at https://$LAN:8791 and :8792 ($WALLETS)"
wait
