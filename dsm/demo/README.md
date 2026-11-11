# Running the demo

Two organizations, Northgate and Lakeside, each with its own OpenBoxes, MySQL and DSM account (a `dsm-app-host`), on one
machine, plugged into DSM's beta network: the storage fleet the wallets ship configured for. No storage node runs here.

| Who | Browse | Database port | DSM ingress | DSM relay |
| --- | --- | --- | --- | --- |
| Northgate (organization A) | http://a.localhost:8081/openboxes/ | 33061 | 8781 | 8791 |
| Lakeside (organization B) | http://b.localhost:8082/openboxes/ | 33062 | 8782 | 8792 |

Use these hostnames. Cookies are not separated by port, so sign-ins on plain `localhost` overwrite each other.

The people are Marcus Hale (owns Northgate; his wallet also stands in as the product registry), Priya Nair (Northgate's
warehouse manager), Dev Okafor (a driver: a wallet only) and Amara Osei (owns Lakeside). RECORDING.md has the story and
NARRATION.md what is said over it.

## What it needs

- DSM checked out beside this checkout as `../dsm-main` (or named by `DSM_CHECKOUT`), at the revision in
  `src/main/proto/DSM_REVISION`, with its host built: `cargo build --release --locked -p dsm-app-host` there (or
  `DSM_BIN` naming where it is). The hosts and the wallets they connect to must be built from the same DSM code.
- JDK 8 (`JAVA_HOME`), Docker, and this application built: `./gradlew war bootRepackage`.
- `fleet/dsm_env_config.toml` is a copy of the DSM checkout's `dsm_client/frontend/public/dsm_env_config.toml`: the beta
  fleet, each member with its own CA. The fleet was reprovisioned on 2026-11-11 (node schema 3), so accounts and objects
  made on it before then are gone; refresh the copy whenever DSM repins the fleet.

## With phones

Four phones with the DSM wallet, connected over adb, as RECORDING.md says. The objects Marcus's wallet issued for the
phone run (the `AMX`, `ORS`, `SEATS` and `CUSTODY` anchors in `setup.py`) were issued before the reprovisioning: issue
them again in his wallet (`wallet_token.sh`) and put the new anchors there first.

```bash
python3 dsm/demo/setup.py
```

## Without phones

The four people's wallets are terminal wallets: each a `dsm-app-host` of its own on the same network, which is what
DSM's `make dev-wallet` runs (it runs two; the story needs four, so `network.sh` runs four the same way), answered
through DSM's own Python client (`terminal_wallet.py`). Each person's browser is a desktop Chrome of their own
(`browser.py`). Marcus's wallet issues the run's objects itself. Nothing touches adb.

```bash
(cd ../dsm-main && protoc -I proto --python_out=clients/python/dsm_app_client/generated proto/dsm_app.proto)
python3 -m venv /tmp/dsm-demo && /tmp/dsm-demo/bin/pip install 'protobuf>=6.33,<7'
DSM_DEMO_CONTAINERS=openboxes-demo-terminal /tmp/dsm-demo/bin/python dsm/demo/walk.py
```

`walk.py` resets (`setup.py`), then walks all thirteen beats, the browser steps included, and stops at the first check
that fails. Screens a reader should see are saved to `dsm/demo/run/shots/`. `walk.py beats` walks them again on the
run already made; `walk.py 6 7` walks only those.

What a run without phones leaves untested: the wallet's own screens on Android and iOS, Chrome on a phone (the door
screen is the same page, at desktop width), and the phones' key storage. The people's approvals are the same routes the
phones call (`connect.preview`, `connect.approve`, `connect.sync`, `connect.pending`, `connect.respond`,
`escrow.adjudicate`), made from the terminal.

## Starting over

Run `setup.py` (or `walk.py`) again. The old run is moved aside as `dsm/demo/run-before-<time>`, with its two databases
renamed beside it; two old runs are kept. Only this checkout's processes are stopped: the ones that name this demo's
directory. `DSM_DEMO_CONTAINERS` names the databases (default `openboxes-demo`), so a run never touches another
checkout's.
