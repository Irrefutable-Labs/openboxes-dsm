# DSM authority over custody, ownership, authorization and integrity: proposed architecture

Status: **proposal, nothing here is built unless section 1 says so.** Read from DSM `e78fd9208` plus the fleet pins
(`chore/fleet-reprovision-2026-11-03`) and this repository on 2026-11-03. Claims marked *measured* were run on the live beta
fleet. Everything else was read from code or specs and has not been run.

The goal: DSM establishes cryptographic authority over custody, ownership, authorization and integrity. OpenBoxes stays the
operational application. Synchronizing OpenBoxes is never what settles anything.

## 1. What is true today

| Area | State |
| --- | --- |
| Wallet connect | Built; *measured* |
| Phone sign-in by a signature over the words shown, naming the person and a code the asking browser shows | Built; *measured*: of two browsers asking for one person, approving one signed in only that one, and declining the other left it out. The wallet is DSM Amendment A28, branch `feat/connect-sign` |
| Passwords, signup, API login and impersonation, once the organization has an owner | Refused for everyone; *measured* at Northgate. Lakeside has no owner yet, so these still work there |
| Capped seat token, release on phone approval, seat in a vault, revocation by the authority alone, reassignment | Built; *measured* |
| Every request by a seat holder reads the vault from the storage nodes, with no timer or local copy | Built; *measured*: about 1.1 s per request, steady over 3 minutes, plus one 32 s request not explained |
| Revocation | The holder's very next request gets 403 and the session ends, with no job running and nobody pressing anything; *measured*: that request took 21 s, because it also carries out the return. A new sign-in gets `NO_SEAT` |
| DSM unreadable | Refused with 503 and the session kept; *measured* by freezing the host, after the 120 s client timeout. A freeze shorter than that only delays the request |
| Roles | From DSM alone, in the `User` domain, so every reader gets the same answer; *measured*: superuser rows written for a seat holder gave nothing |
| A revoked seat's row written back to `ASSIGNED` | Sign-in still gets `NO_SEAT`, because the vault decides; *measured* |
| Stock transaction written, changed or deleted anywhere in an organization on DSM, outside a DSM-backed flow | Refused; *measured* through GORM at a DSM warehouse, with a grant as the control |
| Record dated before a stock count | Refused before DSM moves; *measured* for a shipment, read only for receipts and stock changes |
| Record with no date | Refused before DSM moves; read only, not run live |
| Rollback of a shipment DSM moved | Refused, pointing to a return shipment; *measured* for shipments, read only for receipts |
| Generic API writes to stock, shipments, receipts, users, roles and DSM tables | Refused; *measured* for transactions, users and seats |
| Stock decrease | Burns units on a burn-enabled token; refused without changes on a burn-off token; *measured* |
| Stock refused beyond the registry-approved admission | Built; *measured* |
| Shipment moves units on DSM; hospital's account holds them before its books do | Built; *measured* |
| Receipt refused above what DSM moved | Built; *measured*, surfaced as HTTP 500 |
| Custody proof of a partner's holdings, no access to its systems | Built; *measured* |
| Ownership separate from custody; carriers | **Not built.** A balance is both |
| Books compared with DSM each minute, findings signed and append-only, correction from DSM by the owner | Built; *measured* (section 2). A finding DSM cannot sign is not saved, and the next check finds it again; read only, not run live |
| Direct edits to stock transactions or the availability table, when they change a total | Detected; *measured* |
| Commitments of individual database records; edits that leave totals unchanged or sit behind a count | **Not built** |
| Offline handoffs through OpenBoxes | **Not built** |
| Signed discrepancy reports | **Not built** |

The owner's wallet stood in as the registry, and the hospital's account stood in as a person's wallet, because
`RFGYB0PQ9FF` cannot be reached.

## 2. The Northgate discrepancy

*Measured:* admitted 500, recorded 101, shipped 40. DSM holds 460, so the books should read 61. They read 101.

OpenBoxes computes on-hand by walking transactions newest first and stops at the first baseline. A Transfer Out dated before
a baseline is never counted (`InventoryService.groovy:616,644,705`). This test's shipment was backdated, then Record Stock
wrote a new baseline of 100 plus +1. **So 40 phantom units are on Northgate's books, and the normal screen allows it:** the
server rejects only future ship dates (`ShipmentService.groovy:1130`), and `releaseOnSend` has no date check.

DSM was right and OpenBoxes was wrong, and nothing noticed. The invariant that would have caught it is simple:

    DSM holding = undrawn admissions + books on hand        (460 = 399 + 61, books say 101)

**Resolved 2026-11-03.** `DsmReconcileService` runs that check each minute, also counting units here but not yet received,
and reads the books from the stock transactions rather than the availability table. It found the gap itself: DSM 440, undrawn
399, books 81, gap +40, worth $736.00. That finding stays on the record, as signed object `EP1VYMAGGN…`, read back from two
storage nodes. The owner's wallet session then matched the books to DSM with a new adjustment, `SKW-12G-TS6`, naming why. DSM
was not moved.

## 3. Findings the design must answer

**No chokepoint in OpenBoxes.** There are about 32 direct raw SQL or bulk sites plus 17 through `DataService`, 11 of 12 jobs
appear to write, and there is no general audit log. A GORM listener misses HQL bulk and raw SQL. Only MySQL itself sees every
write.

**Paths that change stock or authority with no DSM call today:**
- The generic API can write any domain, with create and update at the manager tier
  (`GenericApiService.groovy:29-31,73,92,102`, `RoleInterceptor.groovy:190`). `User` has no binding exclusion, so roles look
  writable. Not executed.
- Deleting transactions (`LocationController.groovy:298`, `InventoryItemController.groovy:760`, `InventoryController.groovy:676`).
- Shipment and receipt rollback (`ShipmentService.groovy:1796-1813`, `ReceiptService.groovy:412,421,453`). The DSM services
  contain no rollback at all.
- Baselines: `backStockChange` skips anything not CREDIT or DEBIT (`DsmCustodyService.groovy:376`), so a baseline of a
  different quantity changes on-hand with no DSM move.
- Requisition issue, packing-list import, shipment `synchronizeTransactions`, bulk importers.
- DSM moves before `save()`, with no compensation if the save fails (`InventoryService.groovy:1533-1535`).
- Impersonation records the impersonated user as `createdBy`, so the real actor is lost (`Transaction.groovy:41`).

**DSM has no mechanism to commit an arbitrary digest into an account's state.** `R_econ` has five leaf kinds, none for records
(`economic/state.rs:136`). The candidates:

| Candidate | Fit |
| --- | --- |
| `authored.publish` | Signed, no fee, 16 KiB. No position, no completeness proof, and anyone can append to its index |
| Escrow external commitment `Y` | Position-ordered, but locks units and costs a position per digest. A misuse of escrow |
| Supply-one token | Position-ordered, costs 10 ERA each, and its description is display only |
| Transfer memo | Salted, visible only to the recipient |
| ByteCommit | The storage member's accumulator, not the account's |

The position-by-position walk that would detect gaps exists (`economic_registers.rs:702`) but is internal, and no route
returns a peer's history.

**No Connect request signed application-chosen bytes.** None of the 15 kinds did, for an application's purposes, which also
blocked tying sign-in to the browser that asked. DSM Amendment A28 adds that request, and sign-in now uses it.

**Offline is narrow.** `offline.rs:70-107` admits only an offline-bearer `Transfer`, and refuses every vault operation.
It needs a chip ("offline = chips", `wallet_routes.rs:585`), a contact added online, and value loaded online. The receiver
holds the value as pending until online settlement, per tests read but not run. So **a plain phone cannot do an offline
handoff**, and escrow outcomes cannot finalize offline.

**A vault releases its whole amount, once,** to a recipient named per outcome. A verdict is permanent, and outcomes must exist
at lock. So "3 of 10 damaged" cannot split a vault.

**A person is one device.** The device tree is a visible hole in DSM (`CONFORMANCE_GAPS.md:357,857,1008`).

## 4. The architecture

### Custody and ownership: two instruments

| Relationship | Instrument | Moves by |
| --- | --- | --- |
| Ownership | The product token, locked in a shipment vault | A vault outcome going final |
| Custody | A signed handoff chain, optionally a custody token | Each party signing at each handoff |

The carrier never holds product units, so it cannot send them anywhere. That is the only design that holds, because a plain
balance can be spent by its holder.

For each shipment case, the owner locks units in a vault with outcomes such as `delivered` (signed by the consignee, paying
the consignee) and `returned` (signed by carrier and origin, paying the origin). Ownership passes when `delivered` goes
final. Consignment follows from the same rule: custody passes while the vault stays undecided.

**Lock case by case,** because a vault cannot split. A short or damaged case takes a different outcome from the rest of the
shipment.

### Offline handoffs: a decision for the owner

The custody-without-ownership design conflicts with offline as DSM stands, since vaults are online. The options:

1. **Custody token (recommended to test).** A separate token per shipment, whose units move hand to hand by the existing
   offline-bearer path, warehouse to driver to terminal to pharmacy. The driver holds custody and can spend nothing of value.
   Product units stay in the vault. On reconnect, the application, named as a signer on `delivered` in the A12 pattern,
   confirms from a holdings proof that the consignee holds the custody token, and the vault settles. No Core change.
   Limits: every participant needs a chip; each token costs 10 ERA; app-issued tokens offline are untested; a live clone is
   indistinguishable offline, as the spec states.
2. **Offline signed statements.** Works on plain phones, but needs a new DSM capability and has no double-spend protection.
3. **Online only**, with offline as a later phase.

### Authorization: enforced at the operation

- **One authority.** Once an organization has an owner, DSM alone decides. There is no degraded mode, no second authority and
  no wall-clock window: the seat watcher and its 120 s window were built and then removed for that reason.
- **Operation gate:** a `matchAll` interceptor reads the holder's vault from the storage nodes on every request, reads included.
  A vault that does not stand ends the session, and a vault that cannot be read refuses the request.
- **Roles:** `User.getEffectiveRoles` and the other role readers ask `DsmAuthorityService`. Standing seats give roles, the
  owner's wallet is the root, and role rows give nothing.
- **Close the bypasses from section 3.** Impersonation is refused outright once an owner exists.

### Integrity: commit, capture, reconcile

1. **Capture at MySQL's binlog,** the only place that sees every write, including raw SQL and direct edits.
2. **Name the writer.** OpenBoxes writes a signed intent record for each security-relevant change. A binlog change with no
   intent is a detected direct edit.
3. **Commit in batches.** A Merkle root over canonical records and the prior head, per interval, since each commit has a
   cost. Between commits, an attacker controlling both MySQL and the host can suppress changes; shorter intervals narrow that
   window but cannot close it.
4. **Reconcile,** starting with the invariant in section 2, then per record.
5. **Report differences as new signed events,** never by editing history.

**This needs a DSM change to be real.** Without an account-level record head, a verifier detects gaps against a head it
already knows, but not forks or a withheld tail. Interim: `authored.publish` carrying a hash chain, with those limits stated.

Coverage in order: stock and custody; authority (users, roles, seats); shipments, receipts and their dates; products and
prices. `ProductAvailability` is a derived cache, so verify its source instead.

### Discrepancies: new events only

Never edit a custody record or a verdict. A shortage or damage is a signed report naming the shipment and vault. Units move
by the vault's outcome or a return shipment. A write-off is `token.burn`, which requires `burn_enabled` at creation: **the
tokens made so far have burn off, so product tokens need it on, or a stock decrease will fail.** Reports stand once a
reader's own check bears them out, following `vault_contradiction_locator`.

### Individual accountability

Each worker, driver and pilot has their own wallet, connects the same way, and holds a seat scoped to their role. Third-party
carriers are their own organizations. Until the device tree is built, accountability stops at one device, not a person.

## 5. DSM changes this needs, for the owner to decide

| Change | Unlocks | Without it |
| --- | --- | --- |
| **A Connect request that shows and signs application-chosen bytes. Built: DSM Amendment A28** | Binding sign-in to a browser (done), signed handoffs and signed discrepancy reports (not yet using it) | |
| **An account record head**: a new leaf or operation in the closed write set | Verifiable, gap-proof commitments | Detection only against a known head |
| Device tree store (already a known hole) | One person, several devices | One device per person |

## 6. Order of work

1. **Close the holes (OpenBoxes only). Done, with the gaps listed in section 7.** One fail-closed rule now covers stock writes
   once an organization is on DSM (`DsmStockWrites`), with the generic API gated, rollback refused, backdating refused before DSM
   moves, an operation gate that reads the vault each request, clean refusals, and tokens with burn on.
2. **Reconcile. Done,** with the limits listed in section 7.
3. **Finish the demo:** second wallet, sample USD scenario, overlay, recording steps.
4. **DSM's signing request. Done,** and sign-in uses it. Handoffs and reports do not yet.
5. **Custody and ownership,** with one carrier, online; then offline per the owner's decision.
6. **Commitments,** starting with the interim, then the record head if approved.

## 7. Known limits

- The stock guard sits on GORM hooks. Raw SQL, HQL bulk writes and direct database edits do not reach it: only the integrity
  layer can catch those. The superuser console runs arbitrary code, and so can take a grant.
- Paths that write stock with no DSM flow, such as requisition issue, packing-list import and bulk importers, are now refused
  at a DSM warehouse rather than backed by DSM. That is deliberate, but those features do not work there until backed.
- Not done from section 3: DSM moving before `save()` has no compensation, beyond a retry recognizing its own move by memo;
  editing `TransactionType` through its own screens is not gated.
- Sign-in rests on the person comparing a code. The signature covers the words and the wallet shows them, but someone who
  approves without reading can still sign in a browser they do not control. It proves the wallet's key signed after approval on
  that device, not who pressed. The signature is not saved or published, so sign-ins are not yet an auditable record.
- **Which row says who holds authority is still a local database row.** The owner flag, and the binding of a seat row to its
  vault, can be edited by anyone with direct database access. Pointing a person's seat row at another standing vault was not
  attacked and would probably pass. Closing this needs the account record head (section 5).
- An owner's wallet holds no seat, so nothing here revokes the owner, and the owner's requests read nothing from DSM.
- A seat holder pays about 1.1 s per request, and a page with many calls pays it for each. A DSM outage makes a request wait up
  to 120 s before it is refused, and seat holders cannot use OpenBoxes during it. Offline handoffs are a separate path that
  settles between devices and syncs afterwards, and are not built.
- Roles from a seat are organization-wide. A seat names a location, but the role is not limited to it.
- `seed.py` signs in with a password, so it cannot run against an organization that has an owner.
- The reconciler compares totals. An edit that leaves a total unchanged (such as moving units between lots), an edit behind a
  stock count, and changes to lots, expiry dates, prices or permissions are invisible to it.
- A finding's signature proves who said it, not that nothing was withheld: `authored.publish` has no completeness proof, and
  whoever controls an organization's server can keep it quiet. Its own table can be edited with direct SQL, though a published
  copy would still exist on the storage nodes. Findings are signed by the organization's account, not a person; the
  correction records the signed-in user only in OpenBoxes.
- A correction trusts that DSM is right about custody. A real physical shortage is a count, which burns units, and nothing yet
  checks that a person chose the right one.
- A database superuser who also controls the application host can act between commits.
- OpenBoxes' write-path counts come from a static reading, and the generic API findings were not executed.
- What triggered the first retry on the jammed reserve cell is still unknown.
- Clearing the fleet left Wildstate's account unusable until it is rebuilt with the new pins.
