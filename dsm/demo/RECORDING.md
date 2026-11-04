# Recording the demo

About 9 minutes, 14 scenes, on the live storage fleet with the two real wallets. Claude drives the browser and both phones while
you record. The story bar, the live phone and the WEB2/DSM panel explain each step on screen.

1. Keep both phones plugged in or awake, with Wireless debugging on: `RFGYB0PQ8XK` is Marcus, `RFGYB0PQ9FF` is Priya.
2. Make the Browser pane as large as it goes, and start your screen recording on it.
3. Say "go".

To start over from clean data, about 5 minutes:

```bash
python3 /Users/cryptskii/Desktop/claude_workspace/d-s-m/openboxes/dsm/demo/setup.py
```

## How it is driven

`dsm/demo/play.py` does each beat's phone and account work and checks the result before the next beat starts
(`play.py check N`, `play.py do N`, `play.py scene N 'what to spotlight'`). The browser steps a viewer should see are
done in the browser. Nothing is left to chance: a beat whose check fails stops the run.

## What is staged

- The companies, people and prices are made up.
- Marcus's wallet also stands in as the product registry, because there are two phones.
- Priya owns Lakeside and also holds a seat at Northgate, for the same reason. She also drives the shipment over herself,
  so her wallet is the carrier's wallet: Northgate's custody token `NGCUST` is made in Marcus's wallet, and her wallet
  adopted it before the recording.
- The shipment is prepared off camera up to its send step. The send, and everything DSM does, happens on camera.
- Scenes 7 to 9 happen on Priya's phone: the door screen (`/dsmPhone`) in Chrome, shown large on the recording. She signs
  in at each organization with the wallet on the same phone. The phone keeps one session per host whatever the port, so
  the demo's two organizations on one address are used one tab at a time; `play.py do 7|8|9` drives it all.
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
| 6 | Priya ships 120 boxes: $2,208 | Send page |
| 7 | Priya takes the boxes: on her phone, Northgate hands them to her, 120 custody units in her wallet | Priya's phone, Send and Carry |
| 8 | Lakeside's door shows 0 of 120 handed over; she hands over from her phone, approves in her wallet, takes in | Priya's phone, Door |
| 9 | Counting 130 is refused on the phone, 120 is received | Priya's phone, Receive |
| 10 | Marcus revokes the seat, and Priya's next click is refused | Marcus's phone, Northgate |
| 11 | Northgate verifies what Lakeside holds | DSM page |
| 12 | Database edit: 40 boxes, $736, caught and signed | DSM page |
| 13 | Forward correction, and the finding stays | DSM page |
| 14 | Summary | |
