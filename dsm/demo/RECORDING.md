# Recording the demo

About 9 minutes, 14 scenes, on the live storage fleet with four real wallets. Claude drives the browser and the phones while
you record. The story bar, the live phone and the WEB2/DSM panel explain each step on screen. To walk the same story
without phones, end to end, see README.md, Without phones.

1. Keep the four phones plugged in or awake, with Wireless debugging on: `RFGYB0PQ8XK` is Marcus, `RFGYB0PQ9FF` is Priya,
   `R5CW620MQVL` is the driver, `RF8Y90PX5GN` is Amara.
2. Make the Browser pane as large as it goes, and start your screen recording on it.
3. Say "go".

To start over from clean data, about 5 minutes:

```bash
python3 dsm/demo/setup.py
```

## How it is driven

`dsm/demo/play.py` does each beat's phone and account work and checks the result before the next beat starts
(`play.py check N`, `play.py do N`, `play.py scene N 'what to spotlight'`). The browser steps a viewer should see are
done in the browser. Nothing is left to chance: a beat whose check fails stops the run.

## What is staged

- The companies, people and prices are made up. Four phones, four people:
  - Marcus Hale owns Northgate. His wallet also stands in as the product registry.
  - Priya Nair is Northgate's warehouse manager: a seat, Send from stock, and the hand-over to the driver at the door.
  - Dev Okafor drives for Meridian Freight: a wallet and nothing else, never signed in anywhere. It is connected to both
    organizations and added Northgate's custody object `NGCUST` (issued in Marcus's wallet) before the recording.
  - Amara Osei owns Lakeside and works its door: she asks the driver's wallet to hand over, takes in, counts and receives.
- The shipment is prepared off camera up to its send step. The send, and everything DSM does, happens on camera.
- Scenes 6 to 9 happen on the phones: the door screen (`/dsmPhone`) in Chrome, shown large on the recording, each person
  signed in with the wallet on the same phone. Scene 7 ends on the driver's wallet, scene 8 cuts to it while he approves.
  `play.py do 6|7|8|9` drives it all, phone switches included.
- The 40 boxes are added by a real database edit from a terminal (`tamper.sh`), which the viewer does not see being typed.
- Lakeside's account answers the custody proof by a script standing in for its own schedule.

## Scenes

| # | What happens | Where |
| --- | --- | --- |
| 1 | The setup | Northgate sign-in |
| 2 | Marcus signs in on his phone, no password | Northgate, Marcus's phone |
| 3 | Marcus gives Priya a seat | DSM page |
| 4 | 500 boxes admitted on the registry's phone, recorded: $9,200 | DSM page, stock page |
| 5 | Priya signs in on her phone | Northgate, Priya's phone |
| 6 | Priya ships 120 boxes from her phone, from the stock on hand: $2,208 | Priya's phone, Send |
| 7 | Priya hands the boxes to the driver: 120 custody units land in his wallet | Priya's phone, Send; the driver's wallet |
| 8 | Lakeside's door shows 0 of 120 handed over; Amara asks the driver's wallet, he approves, she takes in | Amara's phone, Door; the driver's wallet |
| 9 | Counting 130 is refused on the phone, 120 is received | Amara's phone, Receive |
| 10 | Marcus revokes the seat, and Priya's next click is refused | Marcus's phone, Northgate |
| 11 | Northgate verifies what Lakeside owns | DSM page |
| 12 | Database edit: 40 boxes, $736, caught and signed | DSM page |
| 13 | Forward correction, and the finding stays | DSM page |
| 14 | Summary | |
