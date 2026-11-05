# Narration

One block per scene. **START** is your trigger: begin the block when the story bar at the bottom of the screen reads
that scene number. Square brackets are things to wait for, or things that happen on their own while you talk: `[WAIT …]`
means stop talking and let the picture carry it until the thing in the brackets happens; `[CUT …]` is a change of
picture that happens by itself. Read at an easy pace; the whole thing is a little under nine minutes.

Before the first scene the screen shows Northgate's sign-in page with the story bar at **1 of 14**.

---

## 1. The setup

**START: story bar reads 1 of 14, Northgate's sign-in page**

This is OpenBoxes. It's open-source warehouse software, used in hospitals and supply chains in dozens of countries. What you're looking at is the DSM version of it: the same screens, the same work, nothing about how people use it has changed.

DSM is our company's ledger: immutable, forward-only, and quantum-resistant. It runs on independent storage nodes, and it answers two questions no database can answer honestly about itself: who is allowed to act, and who holds what. Today it's underneath OpenBoxes. It can sit underneath any system that keeps books on things of value.

Two organizations: Northgate Medical, a distributor, and Lakeside District Hospital. They run separate copies of OpenBoxes, with separate databases. And underneath both of them sits DSM.

Every amount you'll see move today is a real state change on live storage nodes. No test network, no mock. Four phones, four people.

## 2. No passwords

**START: story bar reads 2 of 14; the username field is spotlit and "marcus.hale" is being typed**

Marcus Hale owns Northgate. Watch what he doesn't do: he doesn't type a password. In the DSM version, password login is switched off the moment an organization has an owner. There is no password on file for Marcus, or for anyone here. Nothing to phish, nothing to leak, nothing to reset.

`[CUT: Marcus's phone appears, bottom left, showing the request to sign]`

Instead, the login is a signature. His phone wallet is asked to sign a sentence. The sentence names this browser's code, and the wallet shows him exactly what it's signing. He approves it.

`[WAIT: the page changes to the dashboard. It stays only a few seconds: inventory value $1,440, one product in stock]`

That signature is the login. Only this browser gets in, and nothing was stored that could be stolen and replayed.

## 3. A seat for Priya

**START: story bar reads 3 of 14; the DSM page, the "assign a seat" card spotlit**

Access isn't a role in a database row. It's a seat. Marcus is giving Priya Nair a warehouse-manager seat at Northgate.

`[WAIT: the form fills itself, the button is pressed, and the row "Priya Nair" appears in the seats table]`

Seats are capped: Northgate has five, and they came from a token the owner minted. Each seat is locked in a vault on DSM that names Priya as the holder. She holds it. Only Marcus can take it back. OpenBoxes just reads what the seat allows.

## 4. 500 boxes arrive

**START: story bar reads 4 of 14; the "admit" card spotlit, AMX500 / 500 / GRN-77114 filling in**

A delivery. Five hundred boxes of amoxicillin, about nine thousand dollars of stock. Marcus records the goods-received note the way he always has.

`[CUT: the registry's phone appears with "Pay 500" waiting]`

Here's the difference. Nothing counts until the product registry approves it on its own phone. Units of amoxicillin exist on DSM as tokens, and five hundred of them are entering Northgate's custody right now.

`[WAIT, about a minute: the phone approves, and the page moves on its own to the Record Stock page, then to the stock card. This is the longest wait in the demo; the storage nodes are settling the transfer]`

Only now is the count recorded on the books. If the registry had said no, the books could not have moved.

## 5. Priya signs in

**START: story bar reads 5 of 14; Northgate's sign-in page again, "priya.nair" being typed**

Priya signs in the same way, from her own phone. Her access comes from the seat she holds, not from anything in Northgate's database. And her seat is checked against the storage nodes on every single click.

`[WAIT: Priya's phone appears and approves; the dashboard shows briefly, now with inventory value $10,640 and two products in stock]`

## 6. Priya ships 120, from her phone

**START: story bar reads 6 of 14; the picture becomes Priya's phone, large, on the Send tab**

From here on the work happens at the door, on phones. This is OpenBoxes on Priya's phone: the same system, signed in with the same wallet.

`[WAIT: the "New shipment" form opens: To Lakeside, the count goes to 120]`

She's sending a hundred and twenty boxes to Lakeside. She counts them from the stock on hand and ships.

`[WAIT: the toast "Shipping. DSM moves the custody now.", then the card for the new shipment, "Needs a carrier"]`

Watch the order of events. Custody moves on DSM first. The paperwork follows. If DSM had refused, nothing would have been recorded anywhere.

## 7. The driver

**START: story bar reads 7 of 14; Priya's phone, the carrier picker shows "Dev Okafor, Meridian Freight"**

The boxes don't drive themselves. Dev Okafor drives for Meridian Freight, and he has never logged into anything. He has a wallet.

`[WAIT: "Hand to carrier" is tapped; the card changes to "With carrier"]`

Northgate's account pays a hundred and twenty custody units to his wallet. Not money: custody. One unit, one box.

`[CUT: the picture becomes the driver's phone, his wallet's history, with the 120 NGCUST just received]`

He now holds the goods, provably, and nothing else of value. Normally this is a paper manifest and trust. Here it's a fact on the storage nodes.

## 8. At Lakeside's door

**START: story bar reads 8 of 14; the picture becomes Amara's phone, Lakeside's Door tab, "0 of 120 units handed over so far"**

Lakeside. Amara Osei owns the pharmacy and works its door. Her OpenBoxes knows nothing about this shipment yet. Her books say zero, and until custody arrives nothing can be taken in.

`[WAIT: she opens "Ask another carrier's wallet", picks the driver, taps "Ask them to hand over"]`

She asks the driver's wallet to hand over.

`[CUT: the driver's phone, with "Pay 120 NGCUST" waiting; he approves]`

The request shows up on his phone, and he approves it.

`[CUT back to Amara's phone. WAIT: the card changes to "Handed over" and a "Take in" button appears; she taps it]`

His wallet paid the units to Lakeside's account. Now, and only now, the door screen lets her take the boxes in.

## 9. Counting

**START: story bar reads 9 of 14; Amara's phone, the Receive tab, "Count and receive"**

Amara counts.

`[WAIT: the count is set to 130 and "Receive 130 units" is tapped]`

Let's say she counts wrong, or someone's hoping she will: a hundred and thirty.

`[WAIT: the red toast: "receipts record 130 … but DSM moved 120 … cannot receive units it has no custody of"]`

Refused on the spot. OpenBoxes would normally accept whatever you type. DSM moved a hundred and twenty, so a hundred and twenty is all that can be received.

`[WAIT: the count goes to 120, "Receive 120 units" is tapped, the green toast "Received shipment …"]`

She counts again, a hundred and twenty, and it's received. The books at Lakeside are in line with the truth, and they never had the chance to drift.

## 10. Revoked

**START: story bar reads 10 of 14; back in the browser, Northgate's Browse Inventory page in Priya's session, Marcus's phone bottom left**

Back at Northgate. Say Priya leaves, or her phone is stolen. Nobody edits a user record. Nobody resets a password. Nobody waits for a nightly job.

`[WAIT, up to a minute: Marcus's phone works through the revoke in his wallet]`

Marcus revokes her seat from his wallet. The decision is final on the storage nodes.

`[WAIT: the page tries the dashboard and lands on the sign-in page instead: Priya is out]`

Her very next click is refused. The seat is back with Northgate, ready for someone else.

## 11. Proof across companies

**START: story bar reads 11 of 14; the sign-in page, "marcus.hale" typed again and approved on his phone; then the DSM page with the transfers table spotlit**

Northgate wants to know Lakeside still holds what was sent. No login into Lakeside's system. No export. No shared database.

`[WAIT: "Verify partner" is pressed; the message says "Asking the partner's account to prove what it holds." Then, up to half a minute, "Proven: they hold 120 units." and the Proven column fills in]`

Lakeside's account proves its holding against the storage nodes, and Northgate checks the proof itself. A hundred and twenty.

## 12. Someone edits the database

**START: story bar reads 12 of 14; the "books and DSM" card spotlit**

Now the part every warehouse system is quietly afraid of. Someone with database access adds forty boxes by hand. Seven hundred dollars of stock that doesn't exist. The stock screens show it. They have no way to know better.

`[WAIT: "Check now" is pressed twice, a few seconds apart; a row appears: AMX500, gap 40, $736; then a signed GAP finding in the table below]`

DSM never moved those boxes. The check finds the gap, and it signs a finding that cannot be edited away. Not a log line. A signed record.

## 13. Corrected forward

**START: story bar reads 13 of 14; the reason field is filled: "Books edited by hand; matched to DSM"**

The fix is a new entry, not a rewrite.

`[WAIT: "Match the books to DSM" is pressed; the message names the adjustment; a CORRECTED row joins the GAP row, which stays]`

Marcus brings the books back to the truth, and the finding stays on the record, with who corrected it and why.

## 14. Close

**START: story bar reads 14 of 14**

So: no passwords. Seats that can be counted and revoked. Custody that two companies can verify without trusting each other. And books that cannot quietly drift.

Same OpenBoxes. Same screens. Same people. DSM underneath, on a ledger built to outlast quantum computers. What DSM did for a warehouse today, it does for any system that keeps books on things of value.
