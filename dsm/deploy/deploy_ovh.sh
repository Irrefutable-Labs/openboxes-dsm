#!/usr/bin/env bash
# Deploys one organization's Open Boxes for DSM on the games host on OVH (dsm-games-1, 148.113.137.67), so anyone can
# reach it over the internet: the application, its MySQL and its own DSM account, behind the host's shared Caddy.
#
#   dsm/deploy/deploy_ovh.sh a|b [path to openboxes.war]
#
#   a: organization A, /opt/openboxes,   https://openboxes.<ip>.sslip.io/openboxes/,   wallet relay :8444
#   b: organization B, /opt/openboxes-b, https://openboxes-b.<ip>.sslip.io/openboxes/, wallet relay :8446
#
# Without a war, the host's current one (/opt/openboxes/openboxes.war) is used; build one with
# `JAVA_HOME=<JDK 8> ./gradlew war bootRepackage` (build/libs/openboxes.war). The DSM account image is dsm-app-host
# built from DSM main (DSM_APP_HOST_TAG, default 512759a5d). The fleet config is DSM main's
# dsm_client/frontend/public/dsm_env_config.toml: set DSM_ENV_CONFIG to send a new one.
#
# Kept across redeploys: run/ (the account's keys and RECOVERY_MNEMONIC, the ingress secret, uploads), mysql/ and .env
# (the database passwords). A new organization's first page asks for its one-time setup code (sudo cat <dir>/run/setup-code).
#
#   OVH_HOST (default 148.113.137.67), SSH_KEY (default ~/.ssh/dsm-deploy; SSH is allowed from the operator's address only)
set -euo pipefail
cd "$(dirname "$0")"
ORG="${1:?usage: deploy_ovh.sh a|b [openboxes.war]}"
WAR="${2:-}"
HOST="${OVH_HOST:-148.113.137.67}"
KEY="${SSH_KEY:-$HOME/.ssh/dsm-deploy}"
TAG="${DSM_APP_HOST_TAG:-512759a5d}"
[[ "$HOST" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ && "$TAG" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "OVH_HOST must be an IPv4 address and DSM_APP_HOST_TAG an image tag" >&2; exit 2; }
case "$ORG" in
  a) DIR=/opt/openboxes;   PROJ=openboxes;   APP=8081; DB=33061; INGRESS=8781; RELAY=8444; SITE="openboxes.$HOST.sslip.io" ;;
  b) DIR=/opt/openboxes-b; PROJ=openboxes-b; APP=8082; DB=33062; INGRESS=8782; RELAY=8446; SITE="openboxes-b.$HOST.sslip.io" ;;
  *) echo "usage: deploy_ovh.sh a|b [openboxes.war]" >&2; exit 2 ;;
esac
SSH=(ssh -o BatchMode=yes -i "$KEY" "ubuntu@$HOST")

echo "==> $DIR"
"${SSH[@]}" "sudo install -d -m 755 $DIR $DIR/network && sudo install -d -m 755 -o 10001 -g 10001 $DIR/home && sudo install -d -m 755 -o 10001 -g 10001 $DIR/home/.grails \
  && sudo install -d -m 700 -o 10001 -g 10001 $DIR/run"
sudo_put() { "${SSH[@]}" "sudo tee $1 >/dev/null && sudo chmod $2 $1"; }
sudo_put "$DIR/compose.yml" 644 < compose.yml
sudo_put "$DIR/openboxes-config.groovy" 644 < ../demo/dashboard-config.groovy
if [[ -n "${DSM_ENV_CONFIG:-}" ]]; then sudo_put "$DIR/network/dsm_env_config.toml" 644 < "$DSM_ENV_CONFIG"; fi
"${SSH[@]}" "sudo test -s $DIR/network/dsm_env_config.toml || sudo cp /opt/openboxes/network/dsm_env_config.toml $DIR/network/"
if [[ -n "$WAR" ]]; then
  "${SSH[@]}" "sudo tee $DIR/openboxes.war.new >/dev/null" < "$WAR" && "${SSH[@]}" "sudo mv $DIR/openboxes.war.new $DIR/openboxes.war"
elif [[ "$DIR" != /opt/openboxes ]]; then
  "${SSH[@]}" "sudo test -s $DIR/openboxes.war || sudo cp /opt/openboxes/openboxes.war $DIR/"
fi

echo "==> .env (database passwords kept, made once)"
"${SSH[@]}" "set -e; cd $DIR; sudo touch .env; sudo chmod 600 .env
  grep -q '^MYSQL_ROOT_PASSWORD=' <(sudo cat .env) || echo MYSQL_ROOT_PASSWORD=\$(openssl rand -hex 24) | sudo tee -a .env >/dev/null
  grep -q '^MYSQL_PASSWORD=' <(sudo cat .env) || echo MYSQL_PASSWORD=\$(openssl rand -hex 24) | sudo tee -a .env >/dev/null
  sudo sed -i '/^\(DSM_APP_HOST_TAG\|PUBLIC_IP\|SITE\|APP_PORT\|DB_PORT\|INGRESS_PORT\|RELAY_PORT\)=/d' .env
  printf 'DSM_APP_HOST_TAG=%s\nPUBLIC_IP=%s\nSITE=%s\nAPP_PORT=%s\nDB_PORT=%s\nINGRESS_PORT=%s\nRELAY_PORT=%s\n' $TAG $HOST $SITE $APP $DB $INGRESS $RELAY | sudo tee -a .env >/dev/null"

echo "==> firewall and HTTPS (once)"
"${SSH[@]}" "sudo ufw status | grep -q '^$RELAY/tcp' || sudo ufw allow $RELAY/tcp >/dev/null"
"${SSH[@]}" "grep -q '^$SITE ' /opt/https/Caddyfile || { printf '\n$SITE {\n    redir / /openboxes/ 302\n    reverse_proxy 127.0.0.1:$APP\n}\n' | sudo tee -a /opt/https/Caddyfile >/dev/null \
  && cd /opt/https && sudo docker compose -p https restart https; }"

echo "==> starting (the application takes a few minutes the first time: it creates its database)"
"${SSH[@]}" "cd $DIR && sudo docker compose -p $PROJ up -d --force-recreate app account mysql"
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "https://$SITE/openboxes/" || true)
  [[ "$code" == 200 || "$code" == 302 ]] && break
  sleep 10
done
echo "https://$SITE/openboxes/ answers $code   wallet relay: https://$HOST:$RELAY"
