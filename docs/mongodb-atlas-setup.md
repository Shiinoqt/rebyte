# Optional MongoDB Atlas setup

This overlay connects `catalog-service` and `messaging-service` to Atlas through their existing `MONGO_URI` setting. Omitting it keeps the local Compose model. No Java changes are required.

The supplied cluster hostname is `rebyte-dev.fumysfe.mongodb.net`. The template targets separate `catalog_db` and `messaging_db` databases. Atlas changes only MongoDB: MySQL (including `mysql-app`), Redis, Meilisearch and RabbitMQ keep their existing configuration and state. Local Mongo volumes are also retained.

## 1. Prepare Atlas

1. Open your Atlas project. If the supplied `rebyte-dev` cluster already exists, use it. Otherwise create a **Free** cluster, selecting the nearest EU region offered for that tier and cloud provider. Use the actual hostname shown by **Connect > Drivers** if it differs from the supplied hostname; naming a new cluster `rebyte-dev` does not reproduce its DNS suffix. Region availability varies by tier and provider. See [Atlas deployment](https://www.mongodb.com/docs/atlas/tutorial/deploy-free-tier-cluster/).
2. In **Database Access**, create password database users (separate from your Atlas login):

   | Proposed user | Role | Database |
   | --- | --- | --- |
   | `rebyte_catalog` | `readWrite` | `catalog_db` |
   | `rebyte_messaging` | `readWrite` | `messaging_db` |

   Use **Specific Privileges** to scope `readWrite` to the indicated database, covering all its application collections. Remove any broader default role. Each service needs collection and index creation, including `createIndex`, as well as reads and writes for its domain and outbox. The database-scoped `readWrite` role includes these privileges; no cross-database or administrator role is needed. See [database users](https://www.mongodb.com/docs/atlas/security-add-mongodb-users/) and [readWrite privileges](https://www.mongodb.com/docs/manual/reference/built-in-roles/#readWrite).
3. In **Network Access**, add your current public outbound IP to the project IP access list. Docker connections from this machine use that outbound IP. Update the entry if your IP or VPN changes. See [Atlas connection security](https://www.mongodb.com/docs/atlas/setup-cluster-security/).

## 2. Create the private environment file

Run from the repository root. This creates the file only if it does not already exist:

```bash
if [ ! -e infra/atlas.env.local ]; then
  (umask 077; cp infra/atlas.env.example infra/atlas.env.local)
fi
chmod 600 infra/atlas.env.local
git check-ignore infra/atlas.env.local
```

Edit `infra/atlas.env.local` privately. Replace each password placeholder with that database user's percent-encoded password, and adjust usernames or the hostname if needed. Encode the password component only, not the whole URI. For example, the fictitious password `Example@Pass/word` becomes `Example%40Pass%2Fword`. Keep the single quotes around each value to avoid environment-file interpolation. Never paste real credentials into logs, screenshots or commits. The existing `*.env.local` rule ignores this file.

Preserve each database path and these query options: `authSource=admin&retryWrites=true&w=majority&appName=rebyte-dev`. `authSource=admin` identifies the Atlas authentication database; it does not grant administrator privileges. Atlas discovers its replica set through the SRV connection string. Do not append the local `replicaSet=rs0` or `directConnection=true` settings. See [connection strings and password encoding](https://www.mongodb.com/docs/manual/reference/connection-string/).

## 3. Validate the configuration

The overlay requires Compose support for `!reset` (tested with Docker Compose 5.6.0). It removes only the `mongo-init` dependency from catalog and messaging and profiles local `mongo`/`mongo-init` under `local-mongo`. Leave that profile inactive when using Atlas. Discovery, user-service, Redis, RabbitMQ and catalog's Meilisearch dependencies remain intact. See [Compose merge and reset](https://docs.docker.com/reference/compose-file/merge/#reset-value).

Load the existing infrastructure environment first, then the Atlas file. Later environment files take precedence; exported shell variables take precedence over both, so ensure they do not contain stale URI values. See [Compose environment interpolation](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/#substitute-with---env-file).

```bash
docker compose --env-file infra/.env --env-file infra/atlas.env.local \
  -f infra/docker-compose.yml -f infra/docker-compose.services.yml \
  -f infra/docker-compose.atlas.yml config --quiet
```

Both `CATALOG_MONGO_URI` and `MESSAGING_MONGO_URI` must be nonempty, even when targeting only one service. A missing or empty variable fails configuration. `config --quiet` validates the merged model without displaying credentials or connecting to Atlas. Do not use a rendered `config` or `config --environment` command with real secrets.

## 4. Start catalog, then messaging

These commands are for the later, deliberate switch to Atlas. `up` can recreate an existing service when its configuration changes; the configuration task itself does not run them. Existing local Mongo containers may remain running, but neither service depends on them with the overlay.

```bash
docker compose --env-file infra/.env --env-file infra/atlas.env.local \
  -f infra/docker-compose.yml -f infra/docker-compose.services.yml \
  -f infra/docker-compose.atlas.yml up -d --wait catalog-service

docker compose --env-file infra/.env --env-file infra/atlas.env.local \
  -f infra/docker-compose.yml -f infra/docker-compose.services.yml \
  -f infra/docker-compose.atlas.yml up -d --wait messaging-service
```

Compose starts each target's declared dependencies. Their Docker health checks cover the base stack and service readiness; passing health checks alone does not prove Atlas index creation or transactional outbox behavior. Atlas credentials do not supply or replace the existing AWS, media, MySQL, RabbitMQ or other infrastructure settings required by `infra/.env`.

## 5. Verify the live integration

Live verification completed on 2026-10-06 for both services against this cluster: the full Phase 2 collection and folder A of Phases 3, 3.1, 4 and 5 passed with 589 requests, 680 assertions and zero failures. Both services were healthy with their own Atlas database configured, and each database user was denied reads from the other database. Fresh application documents and published outbox events were confirmed directly in Atlas. Isolated transaction commit/rollback and unique/TTL index probes passed in both databases; messaging's application unique and TTL indexes were also confirmed.

This run did not repeat PayPal-approved paid flows, Phase 5 folders B/C/D, long-duration expiry paths or email inbox delivery checks. Catalog's category unique index exists; its listing, favourite and outbox collections currently have only the default `_id_` index. Full evidence and remaining coverage are recorded in [quick task 261006-nd3](../.planning/quick/261006-nd3-verify-catalog-and-messaging-live-agains/261006-nd3-SUMMARY.md). Review future connection errors privately and redact connection strings before sharing diagnostics.

Create fresh, coherent fixtures starting with phase 2 catalog listings, then exercise phases 3, 3.1, 4 and 5 using those identifiers. MySQL order/payment records and Meilisearch data may reference the old local Mongo catalog. Do not reuse those references as Atlas fixtures, clear volumes, or silently migrate data. Keep the same intended backend throughout a test run.

The existing collections expose HTTP behavior; no new collection is needed for the overlay. For future complete reruns, prepare the private Postman environment and any required payment approvals first. Keep generated reports private because they can contain access tokens:

```bash
./scripts/postman/verify-phase.sh 02 --run
./scripts/postman/verify-phase.sh 03 --run
./scripts/postman/verify-phase.sh 03.1 --run
./scripts/postman/verify-phase.sh 04 --run
./scripts/postman/verify-phase.sh 05 --run
```

The script also synchronizes each collection when both `POSTMAN_API_KEY` and `POSTMAN_WORKSPACE_ID` are configured. Keep Postman credentials, generated tokens and the private environment outside Git.

## Return to local Mongo

Omit the Atlas overlay and Atlas environment file to select the unchanged local Compose configuration:

```bash
docker compose --env-file infra/.env \
  -f infra/docker-compose.yml -f infra/docker-compose.services.yml config --quiet

docker compose --env-file infra/.env \
  -f infra/docker-compose.yml -f infra/docker-compose.services.yml \
  up -d --wait catalog-service

docker compose --env-file infra/.env \
  -f infra/docker-compose.yml -f infra/docker-compose.services.yml \
  up -d --wait messaging-service
```

The local model restores the original Mongo URIs and `mongo-init` dependencies. Returning to it may recreate the selected service containers and exposes the existing local Mongo data again; Atlas data remains in Atlas. This is a configuration rollback, not a data migration. Do not use `down -v`, purge volumes, or remove persistent data to switch backends.
