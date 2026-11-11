#!/bin/bash
# One MySQL per organization, sharing nothing. Starts the containers, creating them the first time.
# DSM_DEMO_CONTAINERS names them (default openboxes-demo): another checkout's demo databases are never touched.
set -euo pipefail
PREFIX="${DSM_DEMO_CONTAINERS:-openboxes-demo}"
TZNAME="$(readlink /etc/localtime | sed 's#.*/zoneinfo/##')"
database() { # organization, host port
    local name="$PREFIX-$1"
    if docker inspect "$name" > /dev/null 2>&1; then
        docker start "$name" > /dev/null
    else
        docker run -d --name "$name" -p "127.0.0.1:$2:3306" -e TZ="$TZNAME" \
            -e MYSQL_ROOT_PASSWORD=openboxes -e MYSQL_DATABASE=openboxes \
            -e MYSQL_USER=openboxes -e MYSQL_PASSWORD=openboxes \
            mysql:8.0.36 --skip-log-bin --sql-mode=NO_ENGINE_SUBSTITUTION \
            --character-set-server=utf8mb3 --collation-server=utf8mb3_general_ci > /dev/null
    fi
}
database a 33061
database b 33062
for org in a b; do
    for _ in $(seq 1 120); do
        docker exec "$PREFIX-$org" mysqladmin ping -h 127.0.0.1 -uopenboxes -popenboxes --silent > /dev/null 2>&1 && break
        sleep 1
    done
done
echo "READY"
