# Narration

One block per scene. **START** is the trigger: begin the block when the story bar at the bottom reads that scene
number. `[WAIT …]` means stop talking and let the picture carry it until the thing in the brackets happens. `[CUT …]`
is a change of picture that happens on its own while you talk. Spoken pace, about nine minutes in all.

This is a walkthrough of a live system, not a story. Talk about what is on the screen and what is happening under it.
The story bar already carries the headline for each scene; the narration says what the bar does not.

Before the first scene the screen is Northgate's sign-in page with the story bar at **1 of 14**.

---

## 1. The setup

**START: story bar reads 1 of 14, Northgate's sign-in page**

What's on screen is OpenBoxes, the open-source warehouse system. Hospitals and supply chains run it in dozens of countries. This build is the DSM version. Same application, same screens, same database. One thing is different: the application no longer gets to decide who may act or who holds what. DSM decides that, and OpenBoxes asks.

DSM is our deterministic state tracker. Every state change is signed, forward-only and immutable, with post-quantum cryptographic authority. There is no server in the middle and no validator set; the storage nodes you'll see referenced today are the ones this network actually runs on. It works at the edge: at the far end of a supply chain, with no internet, two phones over Bluetooth is enough to move custody.

The setup for the demo: two organizations, Northgate Medical and Lakeside District Hospital, each with its own copy of OpenBoxes and its own database. Four phones, four people, each with a DSM wallet. The panel on the right logs every request the application makes while we work: the ordinary web requests are tagged WEB2, and the ones that go to DSM are tagged DSM. Watch that panel. Nothing in this demo is mocked.

## 2. No passwords

**START: story bar reads 2 of 14; the username field is spotlit and "marcus.hale" is being typed**

Marcus Hale owns Northgate. He types a username and nothing else. There is no password field, because this build has no passwords: the password table is gone, the password endpoints answer "retired", and there is no page anywhere to set one. There is nothing to phish and nothing to leak.

`[CUT: Marcus's phone appears, bottom left, showing the request to sign]`

What happened on Enter: the server generated a one-time code for this browser and sent a signing request to the wallet connected to Marcus's account. His phone is showing him the exact sentence it is about to sign, with that browser code in it. When he approves, the wallet signs it with his key and the server verifies the signature against the key it already knows.

`[WAIT: the page changes to the dashboard. It stays only a few seconds: inventory value $1,440, one product in stock]`

That signature is the session. It is bound to this browser's code, so it can't be replayed from anywhere else, and nothing was stored on the server that could be stolen later.

## 3. A seat for Priya

**START: story bar reads 3 of 14; the DSM page, the "assign a seat" card spotlit**

This is the DSM page inside OpenBoxes. Marcus is assigning Priya Nair a seat as warehouse manager. In stock OpenBoxes this would be a role flag on a user row that any administrator can flip. Here it's a seat: Northgate's owner issued exactly five seat objects, and that's the cap on how many managers Northgate can have.

`[WAIT: the form fills itself, the button is pressed, and the row "Priya Nair" appears in the seats table]`

The request that just went out put seat number one into a vault on DSM with Priya's wallet named as holder. Look at the panel: a DSM entry, signed by Northgate's account. OpenBoxes keeps nothing authoritative about this; when it needs to know what Priya may do, it reads the vault. She can't pass the seat on. Only the owner's wallet can take it back, and you'll see that later.

## 4. 500 boxes arrive

**START: story bar reads 4 of 14; the "admit" card spotlit, AMX500 / 500 / GRN-77114 filling in**

A delivery arrives: five hundred boxes of amoxicillin, roughly nine thousand dollars. Marcus enters the goods-received note number, the product and the quantity. So far that's a normal receipt.

`[CUT: the registry's phone appears with "Pay 500" waiting]`

Now the part that's new. Each product is an object on DSM, one unit per box, and the issuer is the product registry. The registry's wallet is being asked to send five hundred units to Northgate's custody account. Until it does, Northgate owns none of them, and OpenBoxes will not let the count be recorded.

`[WAIT, about a minute: the phone approves, and the page moves on its own to the Record Stock page, then to the stock card. This is the longest wait in the demo; the storage nodes are settling the transfer]`

The registry approved, the transfer settled on the storage nodes, and only then did the Record Stock page accept the count: lot number, expiry, five hundred. The stock card now shows five hundred on hand, and DSM's custody record says the same number. Those two numbers were made to agree by construction, not by someone checking.

## 5. Priya signs in

**START: story bar reads 5 of 14; Northgate's sign-in page again, "priya.nair" being typed**

Priya signs in the same way, from her own phone. Her session is her wallet's signature, and her permissions come from the seat in the vault. There's no role on her user row to edit.

`[WAIT: Priya's phone appears and approves; the dashboard shows briefly, now with inventory value $10,640 and two products in stock]`

One detail that matters later: OpenBoxes checks her seat against the storage nodes on every request she makes, not once at login.

## 6. Priya ships 120, from her phone

**START: story bar reads 6 of 14; the picture becomes Priya's phone, large, on the Send tab**

We're now on Priya's phone. This is OpenBoxes served to a phone browser: the door screen, with Send, Door and Receive tabs. Same server, same session, same wallet.

`[WAIT: the "New shipment" form opens: To Lakeside, the count goes to 120]`

She's building a shipment to Lakeside: one hundred and twenty boxes from the stock on hand. The picker only offers what's physically available to promise.

`[WAIT: the toast "Shipping. DSM moves the custody now.", then the card for the new shipment, "Needs a carrier"]`

Note the order of operations. The first thing the server did was move a hundred and twenty custody units from Northgate's account to Lakeside's account on DSM. The stock movement, the picklist, the shipment record, all of that was written only after DSM confirmed the move. If DSM had refused, OpenBoxes would have written nothing. The card says "Needs a carrier" because custody has left Northgate's books but the boxes are still on the dock.

## 7. The driver

**START: story bar reads 7 of 14; Priya's phone, the carrier picker shows "Dev Okafor, Meridian Freight"**

Priya picks the carrier. Dev Okafor drives for Meridian Freight. He has no OpenBoxes account at either organization and never will; what he has is a wallet, and that wallet added Northgate's custody object before today.

`[WAIT: "Hand to carrier" is tapped; the card changes to "With carrier"]`

Northgate's account just sent a hundred and twenty units of its custody object to the driver's wallet. One unit per box. That's not money and it isn't the product's object; it's a receipt of physical possession that only Lakeside's account will accept.

`[CUT: the picture becomes the driver's phone, his wallet's history, with the 120 NGCUST just received]`

This is the driver's wallet. The hundred and twenty NGCUST units are in his history. Between the two warehouses, the system knows exactly who has the goods, and that knowledge is a signed entry, not a line on a manifest.

## 8. At Lakeside's door

**START: story bar reads 8 of 14; the picture becomes Amara's phone, Lakeside's Door tab, "0 of 120 units handed over so far"**

Now Lakeside, and Amara Osei's phone. This is a different OpenBoxes on a different database, and it has no record of this shipment. What it does have is DSM: Lakeside's account can see a hundred and twenty units inbound, and that zero of them have been handed over yet. The Take In button isn't there, because there's nothing to take.

`[WAIT: she opens "Ask another carrier's wallet", picks the driver, taps "Ask them to hand over"]`

Amara picks the driver's wallet and asks it to hand over. That request goes out over DSM, not over anything Northgate operates.

`[CUT: the driver's phone, with "Pay 120 NGCUST" waiting; he approves]`

On the driver's phone it shows up as a payment request: a hundred and twenty NGCUST to Lakeside's account. He approves.

`[CUT back to Amara's phone. WAIT: the card changes to "Handed over" and a "Take in" button appears; she taps it]`

The units landed, the door screen flipped to "Handed over", and only now does Take In appear. Tapping it is the moment Lakeside's OpenBoxes creates its own inbound shipment record, from DSM's facts, with nobody at Northgate involved.

## 9. Counting

**START: story bar reads 9 of 14; Amara's phone, the Receive tab, "Count and receive"**

The receiving count.

`[WAIT: the count is set to 130 and "Receive 130 units" is tapped]`

She enters a hundred and thirty. Could be a miscount, could be deliberate. Stock OpenBoxes takes the number.

`[WAIT: the red toast: "receipts record 130 … but DSM moved 120 … cannot receive units it has no custody of"]`

Refused, with the reason on screen: DSM moved a hundred and twenty, and the application cannot receive units it has no custody of. That's a hard stop in the write path, not a warning.

`[WAIT: the count goes to 120, "Receive 120 units" is tapped, the green toast "Received shipment …"]`

A hundred and twenty is accepted. Lakeside's books now match the custody record, and there was no way for them not to.

## 10. Revoked

**START: story bar reads 10 of 14; back in the browser, Northgate's Browse Inventory page in Priya's session, Marcus's phone bottom left**

Back in the browser, and this is still Priya's session at Northgate, signed in and working. Now suppose Priya leaves, or her phone goes missing.

`[WAIT, up to a minute: Marcus's phone works through the revoke in his wallet]`

Marcus is on his own wallet, taking seat number one back from the vault. No one touched Northgate's database, no user row was edited, no session was flushed. The revoke is a signed state change on the storage nodes, and it's final.

`[WAIT: the page tries the dashboard and lands on the sign-in page instead: Priya is out]`

Her next request. The seat check fails, the session is refused, and the page tells her why: the owner revoked the seat from their own wallet. That seat is back in Northgate's pool for someone else.

## 11. Proof across companies

**START: story bar reads 11 of 14; the sign-in page, "marcus.hale" typed again and approved on his phone; then the DSM page with the transfers table spotlit**

Marcus signs back in. The question now is one that two separate companies normally can't answer without a phone call or a shared login: does Lakeside still own what Northgate sent?

`[WAIT: "Verify partner" is pressed; the message says "Asking the partner's account to prove what it owns." Then, within a few seconds, "Proven: they own 120 units." and the Proven column fills in]`

Northgate's account asked Lakeside's account for a proof of what it owns. Lakeside's account answered with a proof against the storage nodes, and Northgate's account verified that proof itself before the page showed the number. Northgate never read Lakeside's database and never got a login. It got a verified fact: a hundred and twenty.

## 12. Someone edits the database

**START: story bar reads 12 of 14; the "books and DSM" card spotlit**

Now the failure every warehouse system has and nobody likes to demo. Someone with database access just ran an UPDATE on Northgate's inventory, off camera: forty boxes of amoxicillin that were never received, about seven hundred dollars. The stock screens show four hundred and twenty. Nothing in a conventional system can tell those forty from the real ones.

`[WAIT: "Check now" is pressed twice, a few seconds apart; a row appears: AMX500, gap 40, $736; then a signed GAP finding in the table below]`

The reconciler adds up the books from the transactions and compares them with DSM's custody record. The books say four twenty, custody says three eighty, gap forty. It writes that as a signed finding on DSM, under Northgate's account. Deleting the rogue row now wouldn't help; the finding exists outside the database.

## 13. Corrected forward

**START: story bar reads 13 of 14; the reason field is filled: "Books edited by hand; matched to DSM"**

Correction is a new entry, not a rewrite. Marcus gives a reason.

`[WAIT: "Match the books to DSM" is pressed; the message names the adjustment; a CORRECTED row joins the GAP row, which stays]`

An adjustment transaction brings the books back to three eighty, and a second signed record goes to DSM: who corrected it, why, and which adjustment did it. The GAP finding is still there above it. That is the whole model: history is only ever added to.

## 14. Close

**START: story bar reads 14 of 14**

What you watched: sign-in by signature, with no passwords anywhere in the system. Access as seats, issued by the owner, capped, and revocable from a phone. Custody that moved between two companies and a driver with every step signed, and that one company proved to the other without access. And books that were caught the moment they drifted, with the finding kept.

OpenBoxes didn't change for the people using it. DSM sits underneath it: post-quantum authority, no server in the middle, working at the edge where the internet ends. It does the same for any system that keeps books on things of value.
