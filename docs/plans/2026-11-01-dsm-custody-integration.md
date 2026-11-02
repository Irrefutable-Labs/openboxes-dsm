# DSM custody for OpenBoxes: integration-point map and design options

Date: 2026-11-01. Branch: `feat/dsm-custody` on the fork `cryptskii/openboxes` (default branch `develop`,
forked at `0493fec9`, "#6186 Support multiple local development environments simultaneously").

DSM read at `origin/main` of `deterministicstatemachine/dsm`, commit `ad831f53e` (a worktree of it sits beside
this checkout at `../dsm-main`). Everything below about DSM was read from that revision, nothing from the spike
branches.

## 1. What the demo is

One product. Two warehouse identities, each a DSM account of its own (an app-host process). One shipment that
warehouse A sends and warehouse B receives. Custody of the inventory units is established by DSM transitions;
OpenBoxes keeps every business record (product, lot, prices, people, the shipment's contents, the receipt)
in its own MySQL under its own access control, and records its inventory transactions only once DSM has
established the transition they describe.

Stance agreed with the owner:

- DSM holds authoritative custody transitions and the minimal proofs other parties need.
- Business records stay in OpenBoxes.
- Nothing private goes over DSM unencrypted. `authored.publish` signs a payload but does not encrypt it, so
  no business data goes through it. A transfer's memo, ticker and nonce travel inside the sealed spool
  payload and only commit to the public evidence by a salted hash (`dsm/src/types/operations.rs:119-127`),
  so a shipment reference in a transfer memo is visible to the two warehouses and to no one else.
- Reuse DSM Connect (Amendment A11, `dsm-app-host`). No separate service that claims to provide DSM
  guarantees.

## 2. OpenBoxes integration points, verified in this checkout

OpenBoxes asserts inventory authority by saving a `Transaction` with `TransactionEntry` rows against a
`Location`'s `Inventory` (`grails-app/domain/org/pih/warehouse/inventory/Transaction.groovy`,
`TransactionEntry.groovy`). Quantity on hand is derived from those rows (product availability is refreshed
from the `RefreshProductAvailabilityEvent` the transaction publishes in `afterInsert`). There is no other
place inventory is asserted: every path below ends in `Transaction.save()`.

| Lifecycle step | Where authority is asserted | Transaction type |
| --- | --- | --- |
| Shipment sent by A | `ShipmentService.sendShipment(Shipment, String, User, Location, Date, Boolean)` at `grails-app/services/org/pih/warehouse/shipping/ShipmentService.groovy:1125`. After validation and the `SHIPPED` event it calls `createOutboundTransaction(shipmentInstance)` (line 1154, guarded by `origin.isWarehouse() && debitStockOnSend`). `createOutboundTransaction` (line 1462) builds a `TRANSFER_OUT` transaction (`Constants.TRANSFER_OUT_TRANSACTION_TYPE_ID = "9"`) on the origin's inventory, one entry per `ShipmentItem` via `addTransactionEntries`, saves it, and adds it to `shipment.outgoingTransactions`. Then `ShipmentStatusTransitionEvent(SHIPPED)` is published (after the fact, used for e-mail). | `TRANSFER_OUT` on A's inventory |
| Shipment received by B | `ReceiptV2Service.completeReceipt(ReceiptCompleteRequestCommand)` at `grails-app/services/org/pih/warehouse/api/receiving/v2/ReceiptV2Service.groovy:333`. It marks the receipt `RECEIVED`, cancels remainders, saves the receipt, creates the shipment `RECEIVED`/`PARTIALLY_RECEIVED` event, then `receiptTransactionManager.createInboundTransaction(receipt)` (`src/main/groovy/org/pih/warehouse/receiving/ReceiptTransactionManager.groovy:43`) builds a `TRANSFER_IN` transaction (`TRANSFER_IN_TRANSACTION_TYPE_ID = "8"`) on the destination's inventory, one entry per `ReceiptItem` with `quantityReceived > 0`, saves it with `flush: true`, adds it to `shipment.incomingTransactions`. | `TRANSFER_IN` on B's inventory |
| Stock transfer (bins) | `StockTransferService.completeStockTransfer(StockTransfer)` at `grails-app/services/org/pih/warehouse/stockTransfer/StockTransferService.groovy:343` turns each item into a `TransferStockCommand` and calls `InventoryService.transferStock(command)` (`grails-app/services/org/pih/warehouse/inventory/InventoryService.groovy:1987`), which writes a `TRANSFER_OUT` transaction and a mirrored `TRANSFER_IN` and binds them in a `LocalTransfer` (`saveLocalTransfer`, line 2145). | `TRANSFER_OUT` + mirrored `TRANSFER_IN`, same instance |
| Legacy receive path | `ShipmentController.receiveShipment` (line 523) and `ReceiptService` (the v1 receiving flow) also end in a `TRANSFER_IN` transaction. Out of the demo: the v2 receipt API is the path the React receiving screen uses. | `TRANSFER_IN` |

Callers of `sendShipment`: `StockMovementService` lines 3354 and 3370 (the stock-movement "send" step),
`StockTransferApiController.sendShipment` (line 245, via the `Order` overload), `ShipmentController.sendShipment`
(line 307) and `CreateShipmentWorkflowController` (line 918). All of them pass through the six-argument
overload at line 1125, so one hook there covers every send.

Observation on the stock-transfer path: a `StockTransfer` moves stock between bins of one facility, or
between two locations of the same OpenBoxes instance that share no inventory. For the demo's two
warehouse identities, the shipment/receipt lifecycle is the custody transition. `transferStock` between two
locations that are both DSM identities would need the same treatment as a shipment; it is left as a visible
hole, named in section 8, not stubbed.

## 3. The DSM side, verified at origin/main

- **An application is an account.** `crates/dsm-app-host` runs one DSM identity on the production SDK path
  against the pinned storage set. It serves a loopback protobuf ingress for the application (`POST
  /ingress`, `IngressRequest` in, `IngressResponse` out, `Authorization: Bearer <secret>` read from
  `<data-dir>/ingress-token`, `Host` must name the ingress itself) and a TLS relay wallets reach. It decides
  nothing; every route is the SDK's (`crates/dsm-app-host/src/{main,game,dispatch,identity,relay,tls}.rs`).
- **The real connection is tested as two app-host processes** against the storage node's own code on
  Postgres (`crates/dsm-app-host/tests/real_connection.rs`, harness
  `dsm_sdk/src/test_support/nodes.rs`, server named by `DSM_TEST_DATABASE_URL`). One process plays the
  application, the other the wallet; both are the same binary. This is the shape the warehouse demo takes.
- **Routes the demo needs**, all on the SDK's router (`handlers/app_router_impl.rs`): `token.create`
  (creator-signed policy, 0..18 decimals, whole supply released to the creator), `tokens.addByAnchor`,
  `wallet.sendSmart` (`OnlineTransferSmartRequest { recipient_device_id, amount, token_id, memo }`),
  `wallet.history` (`TransactionInfo { from_device_id, to_device_id, token_id, amount, tx_hash, memo,
  status, receipt_verified }`), `balance.list`, `inbox.startPoller`, `connect.app.offer`,
  `connect.preview`, `connect.approve`, `connect.app.sessions`, `connect.app.request`,
  `connect.app.status`, `connect.sync`, `authored.publish`, `authored.read`.
- **Connect scopes** (`proto/dsm_app.proto` `ConnectScopeKind`): `ACCEPT_ISSUED`, `PAY`, `SWAP`, `HOLDINGS`,
  `ESCROW`, `DUEL`, `CONTACTS`. Pairing roots the offer's `token_anchors` on the accepting side and adds each
  side as the other's contact. A `HOLDINGS` request is answered with a Sparse Merkle proof the asking account
  verifies against the holder's validated economic root (`connect/holdings.rs`): "never a statement, always a
  proof". `connect.app.status` reports `CONNECT_FACT_HOLDINGS` only when the proof verified.
- **Authored objects** (`sdk/authored_objects.rs`): signed, content-addressed, 16 KiB payload cap, appended
  under `H(DSM/object/authored-locator/v1 ‖ author ‖ topic)`. Not encrypted. "The payloads mean nothing
  here": they establish who said what and in what order per author, nothing about balances.
- **Rules from the DSM repo's `CLAUDE.md`** that bind this fork's DSM-facing code: nothing fake or stubbed in
  any shape, no `TODO` markers, protobuf on every DSM wire path (no JSON), Base32 Crockford at display edges
  (no hex), no wall-clock time in DSM validity, BLAKE3 domain-separated and SPHINCS+ only, no `Co-Authored-By`
  trailers, descriptive branch names. Leave a visible hole rather than a stand-in.

## 4. Topology

```
OpenBoxes (Grails, one instance, two Locations A and B)
   │ protobuf over HTTP on loopback, Bearer secret from each host's data dir
   ├──▶ dsm-app-host "warehouse-a"  ──┐  TLS relay (Connect)        ┌──▶ storage set (pinned register)
   └──▶ dsm-app-host "warehouse-b"  ──┘  each side's inbox poller   └──▶   on Postgres
```

Each warehouse `Location` is mapped in OpenBoxes config to one app-host: its ingress origin and its data
directory (where the launch's `ingress-token` is read). OpenBoxes never holds a DSM key; each app-host holds
its own mnemonic-rooted identity in its data directory. OpenBoxes speaks to each host exactly as the game
server speaks to its own host: `IngressRequest` with a `RouterInvoke`/`RouterQuery`, `ArgPack` with
`codec = PROTO`, answers framed `0x03` + `Envelope`. The `.proto` is `proto/dsm_app.proto` at the DSM
revision above, compiled to Java in this build (the tree already forces `protobuf-java:3.21.11`).

## 5. Representation of inventory units on DSM: the options

The decision the owner makes. Each option is laid out with what it proves and what it does not.

### Option A: one fungible token per product (recommended)

The warehouse that first takes custody from outside DSM (in the demo, A) commits a policy for the product with
`token.create`: ticker from the product code, 0 decimals, supply equal to the units it holds, transferable,
burnable (consumption and disposal are burns). The policy anchor is the product's DSM identity and is stored
against the product in OpenBoxes.

- Shipment A to B of N units = `wallet.sendSmart` of N from A's account to B's device id, memo = the
  shipment number (sealed; only the two parties read it). OpenBoxes writes A's `TRANSFER_OUT` only after
  the send returned the transfer hash, and stores that hash (Base32 Crockford) against the shipment.
- Receipt at B = B's inbox poller accepts the transfer onto B's relationship with A. OpenBoxes, before
  `createInboundTransaction`, reads B's `wallet.history` for a completed inbound transfer from A's device of
  the product token, of the shipped amount, carrying the shipment number, and writes B's `TRANSFER_IN` only
  then; otherwise completion is refused with the reason ("custody of shipment X is not yet established on
  DSM"). Partial receipt: the transfer carries the shipped quantity; a short receipt is a discrepancy B
  records in OpenBoxes and settles with a return transfer (B pays A back, which the `PAY` scope covers), not
  a change to what DSM established.
- Proof for third parties = A (or an auditor's account connected to B) asks B for a `HOLDINGS` proof of the
  product anchor; the fact is established only when the Sparse Merkle path recomputes B's validated root.
  This is "the minimal proof other parties need": a balance, bound to B's identity and economic position,
  no shipment contents.

Proves: conservation (units are neither created nor duplicated by a shipment), exclusive custody (only the
holder's device can move them), bilateral acceptance (B's receipt is B's own transition, not A's claim),
issuance authority (the policy names its creator), and third-party verifiability (holdings proof).
Does not prove: lot or serial identity of the units (every unit of a product is interchangeable), or physical
reality. Cost: one SPHINCS+ signed transition per shipment, whatever the quantity.

Variant A′: one token per product and lot (ticker from product code + lot). Proves which lot's units moved.
One `token.create` per lot received from outside DSM; otherwise identical.

### Option B: one supply-one asset per serialized unit

Per Amendment A11, "an application-issued state object is an asset of supply one". Each physical unit is a
token of supply one whose policy commits the product and the unit's serial; ownership is a balance of one.

Proves everything A proves, per individual unit, and which exact units moved. Cost: one policy commit per
unit on receipt from outside, N signed transfers per shipment of N units (each transfer carries a SPHINCS+
signature of tens of KiB), N anchors rooted at pairing or by `ACCEPT_ISSUED`. Right for serialized,
high-value goods (devices, cold-chain vials with serials); wrong for bulk consumables.

### Option C: authored custody statements

Each side publishes signed, content-addressed statements under a per-product topic: A publishes "released
N of product P to device B against commitment H(shipment)", B publishes "accepted N of P from A against the
address of A's object".

Proves: attribution (A signed it), order per author, and content addressing. Does not prove conservation
(A can state anything, twice), does not stop a double-release, and gives no balance a third party can
verify. Payloads are public to the storage set, so only commitments can go in them. On its own this is a
signed log, not custody. It can sit beside A as a public, append-only trail of commitments if the owner
wants one, but it adds no custody fact a transfer does not already establish bilaterally.

### What Connect adds to each option

Pairing (one warehouse offers, the other previews and approves the `dsm:connect/v1:` code) is how the two
accounts become each other's verified contacts and how B roots A's product anchors before the first
transfer; a `HOLDINGS` scope is how A, or an auditor, asks B for proofs without B's operator approving each
one; a `PAY` scope capped per request and in total is how returns go back without approval each time.
`ACCEPT_ISSUED` is how later anchors (new products, option A′ lots, option B units) are rooted on the
receiving side after pairing. The relay is transport, never evidence; every fact OpenBoxes acts on is read
from the warehouse's own account.

### Recommendation

Option A for the demo, with Connect pairing between the two warehouse accounts and a `HOLDINGS` scope so A
can establish B's custody as a verified fact after the receipt. A′ when the product is lot-tracked. B is a
later option for serialized goods and needs nothing in this design changed, only more anchors.

## 6. Lifecycle under option A

1. Operator starts `dsm-app-host` for A and for B (separate data directories, ports), both on the pinned set.
2. OpenBoxes, for location A: `token.create` for the product (once), records the policy anchor on the product.
3. Pairing (once per pair of warehouses): A's host `connect.app.offer` with `token_anchors = [product anchor]`
   and scopes `HOLDINGS` (the product) and `PAY` (the product, capped); B's host `connect.preview` then
   `connect.approve`. A's `connect.app.sessions` holds the session; B holds A as a contact and the anchor.
4. Send (`ShipmentService.sendShipment`, before `createOutboundTransaction`): `wallet.sendSmart` from A to B,
   memo = shipment number. On success record the transfer hash on the shipment and write `TRANSFER_OUT`.
   On refusal, the shipment is not sent (the Grails transaction rolls back, the user sees DSM's reason).
5. Receive (`ReceiptV2Service.completeReceipt`, before `createInboundTransaction`): read B's
   `wallet.history`; require a completed inbound transfer from A's device, the product token, the shipped
   quantity and the shipment number. Then write `TRANSFER_IN`. Otherwise refuse completion with the reason.
6. Proof: A asks `connect.app.request { holdings: [product anchor] }` over the session; polls
   `connect.app.status` until `CONNECT_FACT_HOLDINGS`; records the proven amount and position against the
   shipment. This is what A shows anyone who asks whether B has custody.

Only steps 4 and 5 touch existing OpenBoxes code, each at one line. Steps 2, 3 and 6 are new services and
one admin action each.

## 7. What was built (2026-11-02, option A as the owner chose, then every hole of section 9 closed)

The model as built: a **registry account** (the organization's own `dsm-app-host`, no warehouse) issues each
product's token with the whole supply the organization will ever take in; a **warehouse account's balance** of a
token is exactly the units in that warehouse's custody. Every OpenBoxes inventory transaction at a DSM warehouse
is backed by a DSM transition made before it is written:

| OpenBoxes event | DSM transition | Where |
| --- | --- | --- |
| Shipment sent, DSM warehouse to DSM warehouse | transfer origin account to destination account, memo `openboxes:shipment:<number>` | `ShipmentService.sendShipment` → `releaseOnSend` |
| Shipment received (v2 receiving, v1 partial receipt, legacy `receiveShipment`) | the destination's own history must hold the transfer accepted (`storage.sync`, then `wallet.history`) | `ReceiptV2Service.completeReceipt`, `ReceiptService.saveAndCompletePartialReceipt`, `ShipmentService.receiveShipment` → `requireEstablishedOnReceive` |
| Shipment received from a location that is not on DSM | units enter custody: transfer registry to destination of the received quantity, memo `openboxes:receipt:<number>`, held before the TRANSFER_IN | same hooks |
| Stock transfer between two DSM warehouses | transfer origin to destination, held at the destination before both mirrored transactions | `InventoryService.transferStock` → `transferBetweenWarehouses` |
| Consumption, expiry, damage, debit adjustment, a count that found less | burn at the warehouse (`token.burn`), memo `openboxes:transaction:<number>` | `backStockChange`, from `InventoryService.adjustStock`, `saveRecordInventoryCommand`, `CycleCountTransactionService`, `InventoryController.saveDebitTransaction/saveAdjustmentTransaction/saveCreditTransaction` |
| Credit adjustment, a count that found more | transfer registry to warehouse, held before the transaction | same |
| Proof for a third party | holdings proof over the two accounts' Connect session, recorded only on `CONNECT_FACT_HOLDINGS` | `establishHoldingsProof`, `POST /api/dsm/transfers/<id>/proof` |

A shipment from a DSM warehouse to a location without an account is refused (custody cannot leave DSM). A
shipment from a non-DSM location to a DSM warehouse is sent as OpenBoxes always sent it; the units enter custody
at the receipt. Warehouses with no account are untouched.

Before any move into an account, the destination roots the token's anchor itself (`tokens.addByAnchor`: its host
fetches the policy and re-hashes it to the anchor; already rooted is the idempotent case), so a token issued after
two warehouses connected moves like any other. Pairing still roots every anchor at approval and grants the
`HOLDINGS` scope over them, for the proofs.

Idempotence: the origin account's own history is the record of what it sent, so a move retried after a failed
OpenBoxes save is not sent twice; a burn is recorded with the transaction number's memo and refused if the memo
was backed as something else. The three hashes involved are different objects (the sender's state hash, the
sender's row hash, the recipient's row named by its recomputed submission id), so nothing is matched by hash
across the two sides; the sealed memo, which the signed operation commits to, is.

| Piece | Where |
| --- | --- |
| The DSM wire, compiled to Java at build time | `src/main/proto/dsm_app.proto` (vendored from the DSM revision in `src/main/proto/DSM_REVISION`), `com.google.protobuf` Gradle plugin 0.8.10 with protoc `protobufVersion` (3.21.11, the runtime the tree already forces) |
| The ingress client | `src/main/groovy/org/pih/warehouse/dsm/DsmHostClient.groovy` (frames requests, reads answers, decides nothing), `Base32Crockford.groovy` (DSM's encoder mirrored), `DsmHostException`, `DsmCustodyException` |
| OpenBoxes' records | `grails-app/domain/org/pih/warehouse/dsm/`: `DsmWarehouseAccount` (a warehouse's host, or a registry), `DsmProductToken`, `DsmWarehouseLink` (a Connect session), `DsmCustodyTransfer` (one transition: SHIPMENT, STOCK_TRANSFER, REGISTRY_CREDIT or BURN); migration `grails-app/migrations/0.9.x/changelog-2026-11-01-1200-create-dsm-custody-tables.xml` |
| The service | `grails-app/services/org/pih/warehouse/dsm/DsmCustodyService.groovy` |
| The API | `grails-app/controllers/org/pih/warehouse/api/DsmCustodyApiController.groovy`: `GET/POST /api/dsm/accounts(/warehouses,/registries)`, `GET/POST /api/dsm/tokens`, `GET/POST /api/dsm/links`, `GET /api/dsm/transfers`, `POST /api/dsm/transfers/<id>/proof`; a DSM refusal is a 400 with DSM's reason |
| The storage set for tests | `dsm/fleet/`: a Rust crate that includes the SDK's own node harness from the DSM checkout beside this one and runs it as a process |
| The test | `src/integration-test/groovy/org/pih/warehouse/slice/spec/dsm/DsmCustodyShipmentSpec.groovy`, with `common/dsm/DsmTestFleet` and `DsmTestHost`: a registry and two warehouse hosts on the storage set, a Testcontainers MySQL, every path above driven through the application's own services and the API controller, with the balances of all three accounts checked after each step |

### Building and running

```bash
# DSM at origin/main beside this checkout, with the two binaries the tests start
git -C ../dsm worktree add --detach ../dsm-main origin/main
(cd ../dsm-main && cargo build --release --locked -p dsm-app-host)
(cd dsm/fleet && CARGO_TARGET_DIR=../../../dsm-main/target cargo build --release)

# Java 8 (Grails 3.3.16), Docker (Testcontainers MySQL), a Postgres server with the named database created
createdb -h 127.0.0.1 dsm_openboxes_demo
export JAVA_HOME=<a JDK 8>
export DSM_FLEET_BIN=$PWD/../dsm-main/target/release/openboxes-dsm-fleet
export DSM_APP_HOST_BIN=$PWD/../dsm-main/target/release/dsm-app-host
export DSM_TEST_DATABASE_URL='postgresql://<user>@127.0.0.1:5432/dsm_openboxes_demo?sslmode=disable'
./gradlew integrationTest --tests 'org.pih.warehouse.slice.spec.dsm.DsmCustodyShipmentSpec' -x npm_run_bundle -x npmInstall
```

Running the demo by hand is the same shape: one `dsm-app-host` for the registry and one per warehouse against the
network's pinned set (`--env-config dsm_client/frontend/public/dsm_env_config.toml` in the DSM repo), the
accounts registered, the product's token issued by the registry and the warehouses connected through
`/api/dsm/...`, then the ordinary OpenBoxes screens: record stock, send, receive, transfer, consume.

## 8. The test plan as written before building

- A Rust helper crate in this fork (`dsm/fleet/`) that includes `dsm_sdk/src/test_support/nodes.rs` by
  `#[path]` from the DSM checkout beside this one and depends on `dsm_storage_node` and `dsm_sdk` by path:
  it starts the storage set on `DSM_TEST_DATABASE_URL`, writes the env config the hosts read, prints the
  config path, and runs until killed. This is the SDK's own harness, the one `real_connection.rs` uses.
- A Grails integration spec (`src/integration-test/groovy/org/pih/warehouse/dsm/...Spec.groovy`, on the
  Testcontainers MySQL every OpenBoxes integration test uses) that starts the fleet helper and two
  `dsm-app-host` processes (`DSM_APP_HOST_BIN`, built from the DSM checkout), creates two warehouse
  locations mapped to them, one product, one shipment; then drives `sendShipment` and `completeReceipt`
  through the real services and asserts: the transfer hash on the shipment, B's `TRANSFER_IN` written only
  after DSM shows the accepted transfer, B's balance, A's `HOLDINGS` fact, and the negative cases (receipt
  completion refused before the transfer lands; a send refused when A holds fewer units than shipped).
- Prerequisites on this machine: Docker (present), Postgres (present, the SDK's own test databases are on
  it), the DSM checkout at origin/main beside this one (present, `../dsm-main`), Java 8 (absent: Grails
  3.3.16 and the project's CI run on Java 8 only; 17, 21 and 24 are installed). Without Java 8 the Grails
  side cannot be compiled or tested here.

## 9. What is still a decision, not a hole

- **Partial receipts.** DSM moves the shipped quantity; a receipt that records fewer units leaves the remainder
  in the destination account's custody while OpenBoxes shows fewer. Both resolutions are backed today: a return
  shipment to the origin moves them back; a debit adjustment at the destination burns them. Which one a site uses
  is its own process.
- **The registry's supply.** A token's supply is fixed at creation ("the whole supply that will ever exist");
  the registry must issue enough for every unit the organization will ever take in. A product that exhausts it
  needs a second token, which `DsmProductToken` (one token per product) does not hold yet.
- **Several OpenBoxes instances.** All of this runs inside one OpenBoxes instance that holds every account. Two
  organizations each with their own OpenBoxes would connect their warehouses across instances with the same
  Connect pairing; the memo and the shipment number would then have to be agreed between them.
