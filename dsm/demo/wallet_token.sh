#!/bin/bash
# Create a token through the real wallet's wizard: wallet_token.sh SERIAL_PART TICKER "NAME" SUPPLY "DESCRIPTION". Prints its anchor.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
P="$1"; TICKER="$2"; NAME="$3"; SUPPLY="$4"; DESC="$5"
cdp() { python3 "$HERE/wallet_cdp.py" "$P" "$1"; }
front() {
    local s
    s=$(adb devices | awk -v p="$P" 'index($1,p){print $1}')
    adb -s "$s" shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q com.dsm.wallet || {
        adb -s "$s" shell input keyevent KEYCODE_BACK; sleep 2
        adb -s "$s" shell am start -n com.dsm.wallet/.ui.MainActivity > /dev/null 2>&1; sleep 4
    }
}
front
# Home: B until the menu shows, then Tokens.
for _ in 1 2 3 4; do
    [ "$(cdp "document.querySelectorAll('.dsm-menu-item').length")" != "0" ] && break
    cdp "document.querySelector('.button-b').click();1" > /dev/null; sleep 2
done
cdp "(function(){var e=[].slice.call(document.querySelectorAll('.dsm-menu-item')).filter(function(x){return (x.textContent||'').trim()==='TOKENS';})[0];e.click();return 'tokens';})()" > /dev/null
sleep 4
bash "$HERE/phone.sh" "$P" press '+ CREATE TOKEN' 3 > /dev/null
python3 "$HERE/wallet_fill.py" "$P" tcd-ticker "$TICKER" > /dev/null
python3 "$HERE/wallet_fill.py" "$P" tcd-alias "$NAME" > /dev/null
python3 "$HERE/wallet_fill.py" "$P" tcd-desc "$DESC" > /dev/null
sleep 1
bash "$HERE/phone.sh" "$P" press CONTINUE 2 > /dev/null
python3 "$HERE/wallet_fill.py" "$P" tcd-decimals 0 > /dev/null
python3 "$HERE/wallet_fill.py" "$P" tcd-supply "$SUPPLY" > /dev/null
sleep 1
bash "$HERE/phone.sh" "$P" press CONTINUE 4 > /dev/null
cdp "(function(){var d=document.querySelector('[role=dialog]');var t=d.innerText;return t.slice(t.indexOf('REVIEW',20),t.indexOf('REVIEW',20)+200).replace(/\n/g,' ');})()" | cut -c1-260
bash "$HERE/phone.sh" "$P" press 'BURN ERA' 5 > /dev/null
for _ in $(seq 1 40); do
    a=$(cdp "(function(){var d=document.querySelector('[role=dialog]');if(!d||d.getAttribute('aria-label')!=='Token created')return '';return d.innerText.match(/[0-9A-Z]{52}/g).pop();})()")
    [ "$a" != '""' ] && break
    sleep 4
done
echo "ANCHOR $a"
bash "$HERE/phone.sh" "$P" press DONE 2 > /dev/null
