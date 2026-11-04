# Narration

Spoken over the recording, one block per scene. The cue in brackets says what is on screen when you start the block.
Pauses are marked where the system is doing something and you should let the picture speak. Read it at an easy pace;
the whole thing is a little under nine minutes.

---

## 1. The setup

*[Northgate's sign-in page, story bar reads 1 of 14]*

This is OpenBoxes. It's open-source warehouse software, used in hospitals and supply chains in dozens of countries. Nothing about how people use it has changed in what you're about to see.

What has changed is who's in charge of the truth. Two organizations: Northgate Medical, a distributor, and Lakeside District Hospital. They run separate copies of OpenBoxes, with separate databases. And underneath both of them sits DSM.

Every amount you'll see move today is a real state change on live storage nodes. No test network, no mock. Four phones, four people.

## 2. No passwords

*[Marcus types his username]*

Marcus Hale owns Northgate. Watch what he doesn't do: he doesn't type a password. There isn't one. OpenBoxes has no password to check and refuses them all.

*[The page shows the browser code; cut to Marcus's phone]*

His phone wallet is asked to sign a sentence. The sentence names this browser's code, and the wallet shows him exactly what it's signing. He approves it.

*[Pause until the dashboard appears]*

That signature is the login. Only this browser gets in, and nothing was stored that could be stolen and replayed.

## 3. A seat for Priya

*[The DSM page, the assign-seat card]*

Access isn't a role in a database row. It's a seat. Marcus is giving Priya Nair a warehouse-manager seat at Northgate.

*[The form fills, the seat is assigned]*

Seats are capped: Northgate has five, and they came from a token the owner minted. Each seat is locked in a vault on DSM that names Priya as the holder. She holds it. Only Marcus can take it back. OpenBoxes just reads what the seat allows.

## 4. 500 boxes arrive

*[The admit card: AMX500, 500, GRN-77114]*

A delivery. Five hundred boxes of amoxicillin, about nine thousand dollars of stock. Marcus records the goods-received note the way he always has.

*[Cut to the registry phone, "Pay 500"]*

Here's the difference. Nothing counts until the product registry approves it on its own phone. Units of amoxicillin exist on DSM as tokens, and five hundred of them are entering Northgate's custody right now.

*[Pause until "released"; then the record-stock page and the stock card]*

Only now is the count recorded on the books. If the registry had said no, the books could not have moved.

## 5. Priya signs in

*[Northgate sign-in page, Priya's phone]*

Priya signs in the same way, from her own phone. Her access comes from the seat she holds, not from anything in Northgate's database. And her seat is checked against the storage nodes on every single click.

## 6. Priya ships 120, from her phone

*[Priya's phone, large: the Send tab, "Send from stock"]*

From here on the work happens at the door, on phones. This is OpenBoxes on Priya's phone: the same system, signed in with the same wallet.

*[The new-shipment form: Lakeside, 120]*

She's sending a hundred and twenty boxes to Lakeside. She counts them from the stock on hand and ships.

*[Pause: "Shipping. DSM moves the custody now."]*

Watch the order of events. Custody moves on DSM first. The paperwork follows. If DSM had refused, nothing would have been recorded anywhere.

## 7. The driver

*[The card says "Needs a carrier"; the carrier picker shows Dev Okafor, Meridian Freight]*

The boxes don't drive themselves. Dev Okafor drives for Meridian Freight, and he has never logged into anything. He has a wallet.

*[Hand to carrier; pause]*

Northgate's account pays a hundred and twenty custody units to his wallet. Not money: custody. One unit, one box.

*[Cut to the driver's wallet, the history shows the units]*

He now holds the goods, provably, and nothing else of value. Normally this is a paper manifest and trust. Here it's a fact on the storage nodes.

## 8. At Lakeside's door

*[Amara's phone: the Door tab, "0 of 120 handed over"]*

Lakeside. Amara Osei owns the pharmacy and works its door. Her OpenBoxes knows nothing about this shipment yet. Her books say zero, and until custody arrives nothing can be taken in.

*[She asks the driver's wallet to hand over; cut to the driver's phone]*

She asks the driver's wallet to hand over. The request shows up on his phone, and he approves it.

*[Pause; back on Amara's phone: "Handed over", then Take in]*

His wallet paid the units to Lakeside's account. Now, and only now, the door screen lets her take the boxes in.

## 9. Counting

*[The Receive tab, the count set to 130]*

Amara counts. Let's say she counts wrong, or someone's hoping she will: a hundred and thirty.

*[The refusal toast]*

Refused on the spot. OpenBoxes would normally accept whatever you type. DSM moved a hundred and twenty, so a hundred and twenty is all that can be received.

*[The count set to 120; received]*

She counts again, a hundred and twenty, and it's received. The books at Lakeside are in line with the truth, and they never had the chance to drift.

## 10. Revoked

*[Northgate in the browser, Priya's session; cut to Marcus's phone]*

Back at Northgate. Say Priya leaves, or her phone is stolen. Nobody edits a user record. Nobody resets a password. Nobody waits for a nightly job.

*[The revoke on Marcus's wallet; pause until "Final"]*

Marcus revokes her seat from his wallet. The decision is final on the storage nodes.

*[Priya's next click; the refused page]*

Her very next click is refused. The seat is back with Northgate, ready for someone else.

## 11. Proof across companies

*[The DSM page, the transfers table, Verify partner]*

Northgate wants to know Lakeside still holds what was sent. No login into Lakeside's system. No export. No shared database.

*[Pause until the proven amount appears]*

Lakeside's account proves its holding against the storage nodes, and Northgate checks the proof itself. A hundred and twenty.

## 12. Someone edits the database

*[The reconcile card; the gap row appears: 40 boxes, $736]*

Now the part every warehouse system is quietly afraid of. Someone with database access adds forty boxes by hand. Seven hundred dollars of stock that doesn't exist. The stock screens show it. They have no way to know better.

*[Check now; the finding]*

DSM never moved those boxes. The check finds the gap, and it signs a finding that cannot be edited away. Not a log line. A signed record.

## 13. Corrected forward

*[The reason filled in; Match the books to DSM]*

The fix is a new entry, not a rewrite. Marcus brings the books back to the truth, and the finding stays on the record, with who corrected it and why.

## 14. Close

*[The summary bar]*

So: no passwords. Seats that can be counted and revoked. Custody that two companies can verify without trusting each other. And books that cannot quietly drift.

Same OpenBoxes. Same screens. Same people. Different foundation.
