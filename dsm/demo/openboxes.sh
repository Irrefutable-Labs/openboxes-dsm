#!/bin/bash
# One organization's OpenBoxes: openboxes.sh a|b|registry. Runs in the foreground until stopped.
# JAVA_HOME must name a JDK 8. Build the application first (./gradlew war bootRepackage).
# Browse it at its own hostname: cookies are not separated by port, so sign-ins on one hostname would overwrite each other.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run"
ORG="$1"
case "$ORG" in
    a)        PORT=8081; DB=33061; ROOT="$RUN/org-a"; SITE=a.localhost ;;
    b)        PORT=8082; DB=33062; ROOT="$RUN/org-b"; SITE=b.localhost ;;
    registry) PORT=8083; DB=33063; ROOT="$RUN/registry"; SITE=registry.localhost ;;
    *) echo "usage: openboxes.sh a|b|registry" >&2; exit 2 ;;
esac
: "${JAVA_HOME:?JAVA_HOME must name a JDK 8}"
WAR="$(ls -t "$HERE"/../../build/libs/*.war | head -1)"
TZNAME="$(readlink /etc/localtime | sed 's#.*/zoneinfo/##')"
mkdir -p "$ROOT/app"
cd "$ROOT/app"
export SERVER_PORT="$PORT"
export GRAILS_SERVER_URL="http://$SITE:$PORT/openboxes"
export DATASOURCE_URL="jdbc:mysql://127.0.0.1:$DB/openboxes?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=$TZNAME"
export DATASOURCE_USERNAME=openboxes
export DATASOURCE_PASSWORD=openboxes
exec "$JAVA_HOME/bin/java" -Xms512m -Xmx1500m -Djava.awt.headless=true -Dgrails.env=prod \
    -jar "$WAR" "--openboxes.dsm.dataRoot=$ROOT"
