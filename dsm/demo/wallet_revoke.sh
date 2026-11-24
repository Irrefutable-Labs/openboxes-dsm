#!/bin/bash
# Revoke a seat from the authority's real wallet: wallet_revoke.sh SERIAL_PART VERDICT_CELL. Finds the vault, decides "revoke".
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
P="$1"; CELL="$2"
cdp() { python3 "$HERE/wallet_cdp.py" "$P" "$1"; }
s=$(adb devices | awk -F'\t' -v p="$P" 'index($1,p) && $2=="device"{print $1}' | head -n1)
adb -s "$s" shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q com.dsm.wallet || {
    adb -s "$s" shell input keyevent KEYCODE_BACK; sleep 2
    adb -s "$s" shell am start -n com.dsm.wallet/.ui.MainActivity > /dev/null 2>&1; sleep 4
}
for _ in 1 2 3 4; do
    [ "$(cdp "document.querySelectorAll('.dsm-menu-item').length")" != "0" ] && break
    cdp "document.querySelector('.button-b').click();1" > /dev/null; sleep 2
done
cdp "(function(){var e=[].slice.call(document.querySelectorAll('.dsm-menu-item')).filter(function(x){return (x.textContent||'').trim()==='TRADE';})[0];e.click();return 1;})()" > /dev/null
sleep 5
bash "$HERE/phone.sh" "$P" press ESCROW 3 > /dev/null
python3 "$HERE/wallet_fill.py" "$P" escrow-cell "$CELL" > /dev/null
sleep 1
bash "$HERE/phone.sh" "$P" press FIND 2 > /dev/null
for _ in $(seq 1 40); do
    [ "$(cdp "document.querySelectorAll('button[aria-label=\"Decide revoke\"]').length")" != "0" ] && break
    adb -s "$s" shell dumpsys activity activities | grep -m1 topResumedActivity | grep -q com.dsm.wallet || { adb -s "$s" shell input keyevent KEYCODE_BACK; sleep 2; }
    sleep 3
done
cdp "(function(){var c=document.querySelector('section[aria-label^=\"Escrow vault\"]');return c?c.innerText.replace(/\n/g,' ').slice(0,120):'no vault';})()"
cdp "(function(){var b=document.querySelector('button[aria-label=\"Decide revoke\"]');if(!b)return 'no decide button';b.click();return 'pressed Decide revoke';})()"
for _ in $(seq 1 30); do
    sleep 3
    n=$(cdp "(function(){var n=document.querySelector('.sb-notice');return n?n.innerText.split('\n')[0]:'';})()")
    case "$n" in *Final*|*failed*) break;; esac
done
echo "$n"
