#!/bin/bash
# Use the real DSM wallet on a connected phone, through its own screen:
#   phone.sh SERIAL_PART screen
#   phone.sh SERIAL_PART open CODE            hand it a dsm:connect/v1: link, as another app on the phone would
#   phone.sh SERIAL_PART press "TEXT" [WAIT]  press the button with that text, wait, then report the screen
# wallet_cdp.py refuses to read anything while a recovery-phrase screen is up.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
PART="$1"
ACTION="$2"
CDP="$HERE/wallet_cdp.py"
SCREEN="(function(){var b=[].slice.call(document.querySelectorAll('button')).map(function(x){return (x.innerText||x.getAttribute('aria-label')||'').trim();}).filter(function(t){return t&&t.length<30;});var m=document.querySelector('.screen-container');return {buttons:b.slice(0,24),text:(m?m.innerText:'').slice(0,900)};})()"

screen() { python3 "$CDP" "$PART" "$SCREEN" 2>&1 | cut -c1-1500; }

case "$ACTION" in
    screen)
        screen
        ;;
    open)
        serial=$(adb devices | awk -v p="$PART" 'index($1,p){print $1}')
        adb -s "$serial" shell am start -a android.intent.action.VIEW -d "'$3'" com.dsm.wallet 2>&1 | tail -1 | cut -c1-120
        sleep 3
        screen
        ;;
    press)
        python3 "$CDP" "$PART" "(function(){var want='$3'.toLowerCase();var b=[].slice.call(document.querySelectorAll('button')).filter(function(x){return (x.innerText||x.getAttribute('aria-label')||'').trim().toLowerCase()===want&&!x.disabled;});if(!b.length)return 'no enabled button named $3';b[0].click();return 'pressed $3';})()" 2>&1 | cut -c1-200
        sleep "${4:-5}"
        screen
        ;;
    click)
        # The wallet's shell buttons have no text: phone.sh SERIAL_PART click .button-b [WAIT]
        python3 "$CDP" "$PART" "(function(){var b=document.querySelector('$3');if(!b)return 'nothing matches $3';b.click();return 'clicked $3';})()" 2>&1 | cut -c1-200
        sleep "${4:-3}"
        screen
        ;;
    *)
        echo "usage: phone.sh SERIAL_PART screen|open CODE|press TEXT [WAIT]|click SELECTOR [WAIT]" >&2
        exit 2
        ;;
esac
