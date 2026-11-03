# Recording the demo

About 8 minutes, 13 scenes, on the live storage fleet with the two real wallets. Claude drives the browser and both phones while
you record. The story bar, the live phone and the WEB2/DSM panel explain each step on screen.

1. Keep both phones plugged in or awake, with Wireless debugging on: `RFGYB0PQ8XK` is Marcus, `RFGYB0PQ9FF` is Priya.
2. Make the Browser pane as large as it goes, and start your screen recording on it.
3. Say "go".

To start over from clean data, about 5 minutes:

```bash
python3 dsm/demo/setup.py
```

## What is staged

- The companies, people and prices are made up.
- Marcus's wallet also stands in as the product registry, because there are two phones.
- Priya owns Lakeside and also holds a seat at Northgate, for the same reason.
- The shipment is prepared off camera up to its send step. The send, and everything DSM does, happens on camera.
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
| 7 | Lakeside's account holds 120, its books say 0 | Lakeside DSM page |
| 8 | Receiving 130 is refused, receiving 120 works | Receive page |
| 9 | Marcus revokes the seat, and Priya's next click is refused | Marcus's phone, Northgate |
| 10 | Northgate verifies what Lakeside holds | DSM page |
| 11 | Database edit: 40 boxes, $736, caught and signed | DSM page |
| 12 | Forward correction, and the finding stays | DSM page |
| 13 | Summary | |
