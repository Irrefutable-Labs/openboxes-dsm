# Running the three organizations

Organization A, organization B and the product registry as running services, each with its own OpenBoxes, MySQL and
DSM host, on one machine, against real storage nodes.

| Who | Browse | Database port | DSM ingress |
| --- | --- | --- | --- |
| Organization A | http://a.localhost:8081/openboxes/ | 33061 | 8781 |
| Organization B | http://b.localhost:8082/openboxes/ | 33062 | 8782 |
| Registry | http://registry.localhost:8083/openboxes/ | 33063 | 8783 |

Use these hostnames. Cookies are not separated by port, so sign-ins on plain `localhost` overwrite each other.

Needs: JDK 8, Docker, a local Postgres with the demo's database, and the DSM binaries built beside this checkout (the
plan document's "Building and running").

```bash
createdb -h 127.0.0.1 dsm_openboxes_demo
JAVA_HOME=<a JDK 8> ./gradlew war bootRepackage
bash dsm/demo/mysql.sh
bash dsm/demo/network.sh            # stays running; wait for READY
JAVA_HOME=<a JDK 8> bash dsm/demo/openboxes.sh a          # stays running
JAVA_HOME=<a JDK 8> bash dsm/demo/openboxes.sh b          # stays running
JAVA_HOME=<a JDK 8> bash dsm/demo/openboxes.sh registry   # stays running
python3 -u dsm/demo/seed.py         # once, when all three are starting or up
```

The seed sets up names, a product per catalog, accounts, fees, the registry's token, adoption, connections, a second
registry operator, and 100 admitted units of stock at organization A. Sign in with the default admin login from the
project's install data (`grails-app/migrations/install`). The second operator's login is in
`dsm/demo/run/registry/second-operator.txt`, readable by this user alone.

## One transfer

1. **At A:** Outbound, Create. Destination: search "Organ" and pick Organization B. Add product `ORSA`, quantity 10. Go
   through Edit and Pick. On Send, choose a shipment type other than Default and an expected delivery date, then Send
   shipment.
2. **At B, in the browser console or any HTTP client signed in there:** `GET /openboxes/api/dsm/inbound` shows what its
   own account holds, and `POST` the `peerDeviceId` and `memo` to the same path to make B's shipment record. This step
   has no screen yet.
3. **At B:** Inbound, List, open the shipment, Receive. Enter 10, Next, Receive shipment.
4. **At A:** `POST /openboxes/api/dsm/transfers/<id>/proof` has A verify B's holdings itself.

## Starting over

The storage set takes its data with it when it stops, so a run serves one demo. To start over, stop everything, move
`dsm/demo/run` aside, and remove the `openboxes-demo-*` containers.
