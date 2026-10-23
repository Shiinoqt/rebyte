# Demo deployment runbook

GitLab remains the development source of truth. The public GitHub demo repository
starts from an explicitly reviewed snapshot with a new root commit. Subsequent
releases update that snapshot deliberately. Record the source GitLab revision and
the distinct GitHub release revision together. Do not copy `.git`, private files,
generated reports or development branches/tags/stashes into the demo repository.
GitHub Actions builds release images; Coolify redeployment stays manual.

## Read-only preflight

Copy `infra/demo-targets.example.json` into ignored `.gsd/demo-06.1/targets.json`
and replace every placeholder with inspected demo identities. Keep passwords,
tokens and access keys out of the manifest. Preserve Cognito subjects exactly;
they are opaque identifiers. Record observations independently in the private
evidence file, then run:

```bash
node --test scripts/demo/preflight.test.mjs
node scripts/demo/preflight.mjs --targets .gsd/demo-06.1/targets.json --evidence .gsd/demo-06.1/evidence.json
```

The CLI reads those two JSON files and runs read-only Git commands with bounded
timeouts. It emits fixed check names and booleans, never private input values or
native error text. Exit 0 means matching reviewed inputs; exit 1 means refused;
missing required arguments return 2. `publicationAuthorized` always stays false.
The preflight validates operator observations; it does not itself authenticate to
cloud providers, collect host measurements or run a secret scanner.

The evidence object contains these sections, matching the tested contract in
`scripts/demo/preflight.test.mjs`:

- `observedAt`: ISO capture time within the last 24 hours.
- `resources`: the independently inspected resource identities in the same
  structure as the target manifest. Every value must match exactly.
- `host`: architecture, logical CPU count, total/available memory and available
  disk in bytes, OS, Docker/Coolify/proxy versions, observed network/address and
  listening-port arrays, existing-resource and scheduled-task arrays, timezone,
  `proxySanitizationVerified` and a private evidence `source` reference. Unknown
  resource/task observations must stay absent; use an empty array only after
  observing there are none.
- `cloud`: `adminSeedAuthenticationVerified`,
  `atlasOwnDatabaseAuthorizationVerified`, `vpsIpAllowlisted`,
  `cdnPublicPrefixesOnly`, `serviceIamDemoScoped`, `sesQueueIsolated`, the exact
  `paypalSandboxWebhook`, and a private evidence `source` reference. These are
  operator-reviewed proofs, not default switches to enable.
- `scan`: `historyFingerprint` returned by `inspectHistory`, scanner name
  `gitleaks`, version, `releaseChecksumVerified`, `allRefs`, `reviewedAt`,
  `unresolvedFindings`, `confirmedExposuresRotated` and private `report` reference.
  Set resolution/rotation fields only after actual review. The fingerprint
  includes sorted local refs, reachable commits and observed remote refs. Every
  advertised remote branch/tag must be represented locally, or readiness refuses.

This first scan describes the private source checkout. At the publication
checkpoint, repeat the scan on the actual fresh GitHub candidate and record its
different release revision. Source readiness never substitutes for export review.

## Observed host

Read-only SSH observations captured on 2026-10-19 at 16:50:52 UTC:

| Item | Observation |
|---|---|
| Architecture | x86_64 / Docker amd64 |
| Logical CPUs | 6 |
| Total memory | 12,541,493,248 bytes, approximately 11.68 GiB |
| Available memory | 11,318,751,232 bytes at the observation |
| Root filesystem available | 201,636,933,632 bytes |
| OS | Ubuntu 24.04.5 LTS |
| Docker Engine | 29.9.1 |
| Coolify image | coollabsio/coolify:4.4.6 |
| Proxy image | traefik:v3.7 |
| Server timezone | Europe/Berlin |
| Existing containers | Coolify, PostgreSQL, Redis, Sentinel and proxy |
| Existing Docker networks | bridge, host, none and coolify |
| Root crontab | Zero active entries observed |

The raw report is private at `.gsd/demo-06.1/host-observations.txt`. It includes
network ranges, host listeners, platform container usage and system timers.
This was the initial observation. The configured-resource inspection below
records the later application, task, proxy and timezone checks. An empty root
crontab alone does not prove no Coolify tasks exist.

The host currently publishes the platform listeners on ports 80, 443, 6001, 6002,
8000 and 8080, plus SSH on 22. These are observed bindings, not an external
reachability or firewall proof. Demo datastores, discovery and downstream services
must have no public listeners. Confirm the deployed gateway/proxy chain before
assigning trusted proxy addresses. The budget below defines container caps;
idle measurements do not establish capacity under demo traffic.

## Private history scan

Gitleaks 8.30.1 was downloaded from its official GitHub release and its Windows
x64 archive matched the official SHA256 checksum:
`d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e`.
The executable's `version` and `git --help` were checked before use.

The private diagnostic scan used `git --log-opts="--all --full-history"`,
`--redact=100`, `--ignore-gitleaks-allow` and a private JSON report. It examined
917 commits and reported 19 findings: 18 generic API-key detections and one
private-key detection. A subsequent 948-commit scan found the same 19 findings.
Each was reviewed against its exact historical source: repeated hexadecimal test
constants, a browser test password, environment-variable names, product slugs,
operator placeholders, planning/rate-limiter prose and the intentional
`SignedWebhookIT` private-key fixture. No live credential exposure was identified;
no rotation was required by this review. This is a reviewed-findings result,
not a zero-findings scanner result. Reports, access files and model responses stay under
ignored `.gsd/demo-06.1/`; never export that directory to GitHub or image contexts.

The source checkout is non-shallow. One branch ref was observed on GitLab using
`git ls-remote --refs origin`. The exact candidate fingerprint will be refreshed
after implementation. Per D-05, no source history will be transferred: scan the
actual fresh-history demo export, every ref subsequently published from that
repository and the image contexts again before the publication checkpoint.
Any confirmed exposed credential must be remediated and rotated before approval.

## Demo identity checkpoint

The user approved two separate gates on 2026-10-19. Plan 01 accepts inspected
cloud identities and working private admin authentication. Plan 09 fills the
actual configured Coolify/datastore identities and reruns the complete preflight
before maintenance authorization or publication. Plan 13 verifies the running
deployment, proxy trust, capacity peaks and reset behavior. Missing runtime fields
remain unresolved; the full CLI must continue to refuse readiness until all its
evidence is present. Configuration acceptance does not prove a deployed runtime.

The user accepted the concrete cloud identity checkpoint on 2026-10-19 by
instructing execution to continue. This accepts the identities below and the
private admin prerequisites, with the runtime and publication gates retained.

Cloud resources inspected or created on 2026-10-19 in account `577101744401`,
region `eu-south-1`:

| Resource | Observed demo identity or result |
|---|---|
| Cognito pool | `eu-south-1_tG4BJNs2r`, name `rebyte-demo-users` |
| Browser client | `1fvpct0cb1ui35rhmp54qesrrf`, secretless, `rebyte-demo-web` |
| Authentication | Buyer, seller, developer and private admin all authenticated; only the private account has ADMIN |
| Account preservation | Four distinct immutable subjects recorded privately in `targets.json` and `demo-identity-checks.json` |
| Signup and callbacks | Open signup; callback and logout restricted to `https://rebyte.tech` |
| Media bucket | `rebyte-demo-media-577101744401`, owner enforced, all public-access blocks enabled |
| CloudFront | `E1WLORXTJ4LN20`, `d1r2icevuc6b4b.cloudfront.net`, deployed, dedicated OAC |
| CDN HTTP probes | `listings` and `avatars`: 200 with expected content; `software` and `chat`: 403 |
| Media probe cleanup | Four temporary source objects deleted |
| Atlas | Existing `rebyte-dev` cluster, dedicated `catalog_demo` / `messaging_demo`, users `catalog-demo` / `messaging-demo` |
| Atlas VPS checks | 12 assertions passed: own-database access, cross-database denial, replica set and session support |
| Runtime IAM users | `rebyte-demo-user-service`, `rebyte-demo-catalog-service`, `rebyte-demo-messaging-service` |
| Runtime credentials | One key per demo service saved privately; three STS identity checks passed |
| IAM simulations | 51 bucket/pool scope assertions and 60 SES/SQS/sender assertions passed |
| SES configuration set | `rebyte-demo-transactional`, bounce/complaint/reject destination enabled |
| SNS topic | `rebyte-demo-ses-events`, SES publish restricted to the demo configuration set and account |
| SQS intake | `rebyte-demo-ses-events-messaging`, raw SNS delivery; source policy restricted to the demo topic/account |
| SQS DLQ | `rebyte-demo-ses-events-messaging-dlq`, redrive count 5, source restricted to the demo queue |
| Email configuration checks | 7 assertions passed; no email or delivery rehearsal performed |
| SES sending status | Domain verified, sending enabled, production access disabled; sandbox recipient restrictions apply |
| PayPal | Supplied app differs from dev; sandbox webhook registered for `https://api.rebyte.tech/payments/webhook` with the four existing capture events |

Atlas commit/rollback transactions have not been exercised. IAM simulations are
policy evidence; they do not replace runtime service requests. PayPal endpoint
delivery remains untested until the API is deployed. Existing dev cloud resources
were not changed. Atlas shares its existing cluster but uses isolated databases
and independently verified users.

Private reports and credentials remain under ignored `.gsd/demo-06.1/`, with
user-supplied Atlas, PayPal and SSH access files outside the repository. The
`accounts/*-private.json` files contain the generated account passwords. Never
copy those files, runtime key files or webhook secrets into commits or images.

The preflight has 41 passing Node tests. The prescribed gateway-only Maven gate
initially lacked local messaging artifacts; the reactor command
`./mvnw -q -pl api-gateway -am verify` passed with 667 tests across gateway,
messaging and commons, zero failures/errors/skips. The implementation draft came
from local `qwen2.5-coder:14b` and was reviewed before these checks.

At this first gate the concrete manifest and independent private evidence identify:

- Dedicated demo Cognito pool, secretless client, ADMIN group and preserved
  buyer, seller, developer and private-admin subjects. The local dev pool
  `eu-south-1_WPMqs7BW8` is excluded. Private admin authentication must work.
- Dedicated media bucket and CloudFront/OAC distribution. Only listing photos
  and avatars are public; software artifacts and chat images stay private.
- Atlas `catalog_demo` and `messaging_demo`, separate database-scoped users and
  working VPS access with cross-database denial. Transaction commit/rollback
  remains a later runtime proof.
- Per-service demo IAM permissions, isolated SES/SQS intake and a dedicated
  PayPal sandbox app whose webhook is
  `https://api.rebyte.tech/payments/webhook`.

Keep credentials in private operator configuration. DNS changes are manual and
must preserve the existing SES records. At the later runtime gate, inspect the
isolated MySQL schemas, Redis, RabbitMQ, Meilisearch and exact Coolify resource and
network identities. Never use Coolify's own Redis or PostgreSQL. Approval of the
cloud manifest does not authorize a reset, publication or deployment.

## Manual DNS preparation

In the authoritative Route 53 hosted zone, inspect the existing records before
changing web routing. When the runtime is ready, point the `rebyte.tech` and
`api.rebyte.tech` A records to `169.58.65.61`; inspect any corresponding AAAA record
so IPv6 does not bypass the intended host. Preserve SES verification TXT, DKIM
CNAME, MX and mail authentication records. Configure both hostnames and TLS in
Coolify, then verify DNS and HTTPS externally. No DNS change or TLS success is
claimed by the cloud preflight.

## Configured Coolify resources

Inspected on 2026-10-19 at 23:24 UTC, with follow-up configuration checks on
2026-10-20 Europe/Rome. These resources are configured and stopped. Deployment
queue count is zero; no release images have been published or pulled on the VPS.
The public GitHub repository is reserved for the publication checkpoint and does
not exist yet. Loading its Compose file cannot succeed until that checkpoint.

| Setting | Inspected value |
|---|---|
| Server UUID | `mrmr7pu5a7hlwg4avmtlsn4p`, installed localhost server |
| Project / environment | `e0adse7jri1tqsaglr95uh4j` / `hbykscfmrhge4grnya9xgsxt` (`production`, Coolify environment name only) |
| Destination UUID | `ll3cdbsxq48bvxrb77sd9lqg` |
| Backend application UUID | `abrj06cdsga8lngmfbhyz3ek`, `rebyte-demo-backend` |
| Backend build strategy | Git-backed Docker Compose, raw Compose deployment enabled |
| Source URL / branch | `https://github.com/Shiinoqt/rebyte`, `main` |
| Base directory / Compose location | `/` / `/infra/docker-compose.demo.yml` |
| Server work directory | `/data/coolify/applications/abrj06cdsga8lngmfbhyz3ek` |
| Backend private network | `abrj06cdsga8lngmfbhyz3ek-backend`, `10.0.2.0/24` |
| Gateway proxy network / peer | `coolify` / `10.0.1.6` |
| Gateway origin and port | `https://api.rebyte.tech`; container `api-gateway:8080` |
| SPA application UUID | `x8xwz0gzgng5bdlpnvutpvou`, `rebyte-demo-web` |
| SPA strategy / image | Docker image / `ghcr.io/shiinoqt/rebyte/web` |
| SPA domains / port / memory | `https://rebyte.tech,https://www.rebyte.tech`, redirect to non-www; `80` / `128m` |
| Automatic deploy / preview deploy | Disabled on both applications, verified after creation |
| Connect to predefined network | Disabled on both applications |
| Host port mappings | None for the demo applications |
| Application scheduled tasks | None; runner and weekly schedule are later plans |
| Host timezone | `Europe/Rome`, set and inspected during plan 09 |

The installed Coolify 4.4.6 normal parser adds declared networks to services and
injects the complete resource `.env` through `env_file`. That would broaden
network access and give every service all integration secrets. Enable **Raw
Compose deployment** on this Git-backed application so the deployment job uses
the reviewed file unchanged. This remains a repository application, not a
one-click Service stack. The file owns explicit Traefik HTTPS labels on the
gateway only; do not enable the predefined-network option or add domain routing
to any datastore, discovery server or downstream service. Coolify's API remains
disabled. Configuration used its installed controller/model interfaces over
authenticated SSH; no public API access was enabled.

Only the gateway joins both networks. Every other backend service joins only
the dedicated backend bridge, with no host ports. The bridge allows outbound
connections for Cognito, Atlas, S3, SES/SQS and PayPal; it is not Docker's
`internal: true` network, which would prevent those integrations. The later
runner joins this same backend network and uses `http://api-gateway:8080`.
Do not attach it to the platform's datastore network or publish a seed listener.

Both networks must already exist. The backend network was created with
`rebyte.environment=demo` and `rebyte.coolify-resource=abrj06cdsga8lngmfbhyz3ek`
labels and inspected independently. Do not recreate it under a different name.
Four empty volumes were created with the same ownership labels:

- `abrj06cdsga8lngmfbhyz3ek-mysql-data`
- `abrj06cdsga8lngmfbhyz3ek-redis-data`
- `abrj06cdsga8lngmfbhyz3ek-rabbitmq-data`
- `abrj06cdsga8lngmfbhyz3ek-meili-data`

Keep `DEMO_RESOURCE_ID` fixed across releases. Normal Redeploy must reuse these
volumes. Never use `down -v`, remove a volume, reuse Coolify's Redis/PostgreSQL,
or create an alternate volume prefix as a deployment step. Persistence across
an actual redeploy remains a plan 13 acceptance check.

## Demo environment mapping

`infra/docker-compose.demo.yml` is JSON, which is also valid YAML, and is
independent of the local Compose files. Copy `infra/.env.demo.example` privately
and enter its 35 required keys as literal runtime variables in the backend
Coolify resource. Keep them out of build-time and preview environments. All
private values in the example are empty; the checker reads only its own fake
environment. Never print a resolved real Compose model or commit `.env.demo`.

The private operator mapping is `.gsd/demo-06.1/runtime-env-09.private.json`.
All 35 keys were read back from Coolify and matched. The release tag is
deliberately empty, which makes Compose refuse startup until the reviewed
GitHub release exists. The frontend image tag is `unpublished`, also requiring
replacement before manual deployment. Neither is release evidence.

| Consumer | Required mapping |
|---|---|
| All application services | `cognito,demo`; bounded JVM options; fixed service DNS/ports |
| Cognito consumers | `AWS_REGION=eu-south-1`, `COGNITO_USER_POOL_ID=eu-south-1_tG4BJNs2r` |
| User / order / payment | `SPRING_DATASOURCE_URL` selects `user_demo` / `order_demo` / `payment_demo`; app user `rebyte`, private `MYSQL_PASSWORD` |
| MySQL initialization | Inline Compose config creates/grants only the three demo schemas on an empty dedicated volume; never uses local development init SQL |
| Catalog / messaging Atlas | `CATALOG_MONGO_URI` / `MESSAGING_MONGO_URI` select `catalog_demo` / `messaging_demo`, users `catalog-demo` / `messaging-demo`; URL-encode passwords |
| Redis and its three consumers | Private `REDIS_PASSWORD`; gateway/catalog/messaging receive `SPRING_DATA_REDIS_PASSWORD`; connect/command timeout `2s` |
| RabbitMQ and its consumers | Private `RABBITMQ_USER` / `RABBITMQ_PASSWORD`; stable broker hostname `rabbitmq` |
| Meilisearch / catalog | Same private `MEILI_MASTER_KEY`; production mode, bounded indexing memory/threads |
| User media/Cognito IAM | `USER_AWS_ACCESS_KEY_ID` / `USER_AWS_SECRET_ACCESS_KEY`, mapped to the AWS SDK's `AWS_*` names only in user-service |
| Catalog IAM | `CATALOG_AWS_ACCESS_KEY_ID` / `CATALOG_AWS_SECRET_ACCESS_KEY` only in catalog-service |
| Messaging IAM | `MESSAGING_AWS_ACCESS_KEY_ID` / `MESSAGING_AWS_SECRET_ACCESS_KEY` only in messaging-service; no shared `AWS_*` keys |
| Media | Demo bucket `rebyte-demo-media-577101744401`, region `eu-south-1`; user/catalog CDN domain `d1r2icevuc6b4b.cloudfront.net` |
| PayPal | Private dedicated app/client secret/webhook ID; `PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com`, return base `https://rebyte.tech` |
| Messaging SES/SQS | Inspected verified sender, `rebyte-demo-transactional` configuration set and dedicated queue URL; events enabled; sandbox recipient restrictions remain |
| Messaging links | Both `MESSAGING_WEB_BASE_URL` and `MESSAGING_PUBLIC_API_URL` are `https://rebyte.tech`; signed unsubscribe GET/POST uses the SPA nginx exact proxy route |
| CORS and WebSocket | `SPA_ALLOWED_ORIGINS=https://rebyte.tech`, passed to gateway CORS and messaging WS allowlists; browser WS `wss://api.rebyte.tech/ws` |
| Gateway forwarding | `SERVER_FORWARD_HEADERS_STRATEGY=framework`; literal `GATEWAY_TRUSTED_PROXIES='^10\.0\.1\.6$'` |
| Signed unsubscribe | Unique private `MESSAGING_UNSUBSCRIBE_SECRET`, at least 32 bytes |
| Release selection | `GHCR_NAMESPACE=ghcr.io/shiinoqt/rebyte`; `DEMO_IMAGE_TAG` is the reviewed 40-character GitHub release SHA, identical for all eight backend images |

The fresh proxy probe verified peer address, HTTPS scheme, host, client IP,
real IP, port and removal of a forged prefix. Traefik passes RFC `Forwarded`
through, so the existing demo gateway transformer discards that header and
accepts sanitized forwarding only from the exact original peer. Recheck this
after any proxy/network change. Never replace the regex with `.*` or a broad
private-address range. This configuration evidence does not replace external
HTTP/WebSocket and rate-limit checks after deployment.

Redis requires authentication, uses AOF persistence, caps data at 96 MiB and
uses `noeviction` so pressure cannot silently discard rate-limit/dedup state.
The 256 MiB container cap leaves room for Redis overhead and AOF rewriting.
The Compose checker verifies the password reaches gateway, catalog and
messaging. A disposable private Docker-network tracer ran the real gateway JAR
with the Cognito/demo profiles: Redis rejected unauthenticated and wrong-password
requests, correct auth returned PONG, gateway health became UP, a real private
demo-admin JWT was accepted, `/internal/**` stayed 403, and a protected routed
request created rate-limiter keys. Changing the password and forcing reconnect
made gateway health DOWN. Neither container published a host port. Fixtures were
removed; tokens and raw logs stayed private.

Upload caps are explicit demo environment values: artifact 10,485,760 bytes,
listing photo 1,048,576 bytes, avatar 262,144 bytes and chat image 524,288 bytes.
Local defaults stay unchanged. The only local Compose edit removes the fixed
PayPal base URL line; the application retains its sandbox default and a separate
local environment override can supply another value.

## Capacity budget and verification limits

The later host capture measured 12,541,493,248 bytes total RAM,
11,213,971,456 bytes available and 200,395,972,608 bytes available root disk.
Existing platform containers used approximately 535 MiB in the preceding
capture. These are measured idle observations, not startup or traffic peaks.

| Component | Memory allocation |
|---|---:|
| Seven JVM containers | 512 MiB each, 3,584 MiB total |
| MySQL | 768 MiB; 256 MiB InnoDB pool, 100 connections maximum |
| Redis | 256 MiB |
| RabbitMQ | 512 MiB |
| Meilisearch | 512 MiB; 256 MB indexing budget, one indexing thread |
| Backend total | 5,632 MiB, 5.5 GiB |
| Separate SPA nginx | 128 MiB |
| Reserved future maintenance runner | 768 MiB; apply/test when its image exists |
| Reserved Coolify, proxy and OS | 2,048 MiB |
| Reserved startup/reset/pull overhead | 2,048 MiB |
| Combined planning budget | 10,624 MiB, approximately 10.38 GiB |
| Remaining physical headroom | Approximately 1.31 GiB |

JVM options are `-XX:MaxRAMPercentage=35 -XX:+UseSerialGC -Xss512k
-XX:ReservedCodeCacheSize=64m`, giving roughly 179 MiB maximum heap per 512 MiB
container. The prior 400 MiB / 35% suggestion was a hypothesis, not host
evidence. The real gateway ran under the chosen 512 MiB cap in the private
tracer. Every backend service has a cap; the checker rejects a combined backend
budget above 5,632 MiB. Reserve at least 25 GiB free disk for image overlap,
bounded container logs, database growth and the weekly reset media wipe. Docker
logs rotate at 10 MiB times three per backend container.

The complete-stack startup, actual traffic, MySQL/indexing/AOF behavior,
concurrent pull/reset peaks and runner usage still require plan 13 measurements.
Do not claim those reservations are measured peaks. If a measured service fails
its cap or the host loses reserve, adjust the reviewed budget before accepting
the live deployment. Do not hide an OOM by removing the limit.

## Manual release and DNS procedure

After the publication checkpoint, record the source GitLab SHA and distinct
GitHub release SHA from the completed release manifest. Confirm all ten public
GHCR packages allow anonymous pull and each expected SHA/digest belongs to that
same manifest. Do not use moving `demo` tags for deployment selection.

1. Set backend `DEMO_IMAGE_TAG` and the SPA image tag to the reviewed GitHub
   release SHA. Keep `DEMO_RESOURCE_ID`, networks, volumes and the raw Compose
   setting fixed. Review the GitHub snapshot's Compose file before loading it.
2. Check `docker compose config --quiet` privately with the actual settings,
   then inspect only redacted identities, images, mounts, networks and caps.
   Re-run full preflight against fresh observations and reviewed history.
3. Use **Redeploy** manually on the backend Git-backed application and then
   the SPA image application. There is no deployment hook in CI. Inspect
   health, image digests and zero host port mappings before DNS changes.
4. In Route 53, record the existing zone entries. Set apex and API A records
   to `169.58.65.61`; point `www` to the apex and let the SPA resource redirect
   to the non-www host. Review existing AAAA entries explicitly. Preserve SES
   verification/DKIM, MX, SPF/DMARC and every unrelated record. No change-batch
   script or DNS mutation is part of this plan.
5. Verify Let's Encrypt HTTPS externally on apex, www and API; verify www's
   redirect, deep routes/config caching, unsubscribe GET/POST, CORS, WS and
   actual proxy trust. Run the later phase HTTP/Postman and live acceptance
   checks, both reset rehearsals and a volume-preserving redeploy.

Local gates are `node --test scripts/demo/*.test.mjs` and
`node scripts/demo/check-compose.mjs`. The latter invokes the real installed
Compose CLI with fake values, checks its rendered model and removes all 35
required values individually. Tests require a valid model before applying each
negative mutation, preventing a broken baseline from producing false passes.
Graph inspection uses the current checkout's `C-Users-damien-orca-rebyte` index;
the session MCP transport failed, but local CLI indexing recovered access.

## Guarded reset control (plan 10)

The reset boundary is `scripts/demo/reset-targets.mjs` plus the host-owned
`scripts/demo/demo-supervisor.sh`. No reset is authorized by installing them.
The supervisor accepts exactly `status`, `maintenance-on`, `stop-writers`,
`start-writers` and `maintenance-off`. It never accepts a command, container name
or target supplied by the runner. The runner receives neither the Docker socket
nor membership in the Docker group.

`validateTargets` compares the complete target object with the fixed demo
identities and independently observed accounts. It requires four distinct
immutable subjects, successful authentication, enabled/verified accounts, ADMIN
only on the private admin, and the two exact public/private origins. The review
fingerprint covers the targets, deletion/preservation scope, supervisor bytes,
installed policy, SSH key and fixed writer/protected service lists. Any changed
review requires a new explicit approval. `requireApprovedReset` also compares the
installed host grant fingerprint before a reset can mutate data.

The approved scope will cover only `user_demo`, `order_demo`, `payment_demo`,
Atlas `catalog_demo` and `messaging_demo`, the dedicated demo bucket, Redis DB 0,
dedicated RabbitMQ application/retry/dead-letter/delayed state and Meilisearch
indexes/tasks. Migration metadata and named volumes survive. The four reviewed
Cognito subjects survive; only the three public passwords may be reset. The
private admin password, development resources, Coolify resources and all
SES/SNS/SQS configuration and messages remain outside the reset scope.

### Installation and unarmed proof

Inspect the actual server, resource UUID, two network IDs, volume names and
Coolify's Compose invocation first. The installed Coolify implementation uses
`--project-name` with the application's UUID, matching the supervisor's project
label. Do not guess a project name from a container display name.

Install the reviewed script as root at
`/usr/local/lib/rebyte-demo-supervisor/demo-supervisor.sh` (root:root, 0755),
with its adjacent `policy` (root:root, 0600). The installation directory is
root:root, 0755. The policy is literal `KEY=value` data, not a sourced shell file:

| Policy key | Required binding |
| --- | --- |
| `PROJECT` | Inspected backend application UUID |
| `DOCKER`, `CURL` | Inspected absolute executable paths |
| `BACKEND_NETWORK`, `BACKEND_NETWORK_ID` | Exact demo bridge name and immutable ID |
| `PROXY_NETWORK`, `PROXY_NETWORK_ID` | Exact platform proxy bridge name and ID |
| `MAINTENANCE_IMAGE` | Pinned nginx digest in the supervisor source |
| `DYNAMIC_DIRECTORY` | Root-owned `/data/coolify/proxy/dynamic/rebyte-demo` |
| `PROXY_HTTPS_PORT` | Actual proxy port, 443 on this host |
| `CA_FILE` | Empty for public certificate trust; fixture CA only in disposable tests |

Create the dedicated dynamic directory as root:root, 0700. The existing Coolify
proxy directories belong to UID 9999; retain their ownership. The dedicated child
keeps the supervisor's ownership guard valid without changing platform files.
After route publication/removal the supervisor touches that directory, notifying
the already watched parent even when the child was created after Traefik started.
An isolated `.invalid` host probe on the installed Coolify proxy confirmed both
route load and removal; it created no demo route or public listener.

Create `rebyte-reset` with a Bash shell, an impossible password (`*`) and only its
own primary group. Its home and `.ssh` directory are root:root, 0755; the
root-owned `authorized_keys` is 0644 so sshd can read it but the account cannot
change it. Each temporary or approved public key has this prefix:

```text
restrict,command="/usr/local/lib/rebyte-demo-supervisor/demo-supervisor.sh"
```

The sudoers entry grants `(root) NOPASSWD` for only the five exact script/action
pairs above, without wildcards, shell access or direct Docker commands. Validate
the candidate with `visudo -cf` before installing it as root:root, 0440 at
`/etc/sudoers.d/rebyte-demo-reset`.

Keep the adjacent `approved` file absent during installation. Through the actual
SSH key, `status` must return `unarmed`; every mutation, arbitrary command,
argument, shell, PTY and forwarding attempt must be refused. Confirm independently
that the account cannot run Docker or a shell through sudo and cannot write the
daemon socket. Disable the temporary key after the proof by emptying its
root-owned `authorized_keys`. A key fingerprint in a review is not authorization.

Only after the explicit decision checkpoint may root restore that exact key and
write `approved` (0600) containing the reviewed fingerprint, supervisor SHA-256
and policy SHA-256, separated by spaces. Never replace that file to silence a
target, hash or ownership mismatch. The live demo containers are still stopped;
the supervisor also refuses until every required writer/protected service exists
with the reviewed labels, network and named mounts.

### Maintenance, recovery and evidence

`maintenance-on` captures all writer/protected container IDs, starts the pinned
nonroot nginx helper and atomically publishes an exact-host Traefik route for the
API, apex and www. Both visitor traffic and `/payments/webhook` must return the
maintenance response before any writer stops. All nginx temporary directories
use the existing `/tmp` tmpfs; the container root stays read-only. Its 64 MiB cap,
64-process limit and 16 MiB tmpfs fit within the existing 2,048 MiB operational
reserve; this does not replace plan 13's live peak measurements.

`stop-writers` verifies the captured IDs and observes every writer stopped.
`start-writers` preserves public maintenance, starts domain writers then the
gateway, and waits for their real Docker health checks. Seeding uses normal
authenticated public API routes at the sole private origin
`http://api-gateway:8080`; missing auth, internal write routes and redirects are
not an alternative seed path. The coordinator calls `maintenance-off` only
after seed completion and explicit readiness. Wipe, seed, readiness or signal
failure leaves maintenance in place.

The reset lock records host, process and nonce and remains held through every
stage. A stale lock requires manual owner/process inspection. Never remove an
active lock, reopen ingress on an error, or discard a changed container snapshot.
After failure, inspect the host `state/phase`, `state/containers`, route and real
container health. Revalidate targets and approval, repair the failed stage and
rerun the locked lifecycle. Changing an installed policy or script requires a
new review fingerprint. Retain volumes; do not use `down -v` for recovery.

On 2026-10-20 the disposable Windows Docker suite and an independent Linux run on
the VPS proved maintenance, observed background-writer quiescence, restart,
authenticated private access, public/webhook exclusion and preserved volume
sentinels. The fixture uses local RSA signing keys and HTTP actors, not actual
Cognito or Spring services. Plan 09 separately proved the real gateway JAR and
real Cognito authentication; live demo acceptance remains in plan 13.
On Windows only, the fixture triggers a Linux file event on its disposable proxy
because Docker Desktop does not relay host writes to inotify. The Linux proof
uses the unchanged supervisor and a read-only proxy mount without that helper.

The installed unarmed control passed 12 actual SSH checks and its temporary key
was disabled after that proof. On 2026-10-20 the user explicitly approved
fingerprint `e2e2ae5b21d890ca37e57d06d4371191df2326ec1f4a503b42659a51500c33bc`.
Unchanged script/policy/network/key identities were revalidated, the matching
root-owned 0600 grant was installed and the reviewed forced-command key enabled.
No reset action was invoked. Post-approval target/control tests passed 40/40.
Current AWS observations also verified
all four subjects/groups and successful password authentication without changing
any password; the dedicated bucket's versioning is `Disabled`.

Private review and evidence stay under `.gsd/demo-06.1/`: target/control review,
observations, installed hashes, SSH proof and test logs. No private key, password,
token or full preserved subject belongs in Git. The checkpoint records the
redacted fingerprint, explicit decision and task evidence. Any changed review
still requires a new approval; runtime mutation must independently revalidate it.

Plan 11's adapters reject an approval whose code hash differs from the packaged
supervisor. The candidate adds delayed-message table cleanup to `stop-writers`.
Its exact review is in
`.planning/phases/06.1-demo-deployment/06.1-11-CHECKPOINT.md`. The original grant
remains installed until that revised fingerprint is explicitly approved.
On 2026-10-20 the user approved revised fingerprint
`aac0b3f6075f96f0ea4ea31f7c5d7e283584a0b98fe7ec70f1cd536db482520c`.
The matching source and root-owned 0600 grant are now installed. Preflight and
post-install checks verified unchanged policy, networks and restricted SSH key;
seven actual SSH refusal checks pass. The actual demo stores have not been reset.

Plan 11 disposable verification passes: 79 aggregate target/reset/seed/control
tests, then 40 task tests with final PNG assets, bootstrap HTTP timeout and
TCP-ready MySQL fixture; 44 frontend API helper tests also pass. Cloud deletes
use transport fixtures and the domain seed uses HTTP actors with local RSA JWTs.
Final-image native CLI/SSH checks and deployed Spring/Cognito acceptance remain
in plans 12 and 13. A failure at any wipe, seed or projection stage keeps the
visitor/provider ingress in maintenance. The seed makes no payment requests.

### Private maintenance runner

The release set includes `demo-runner` with the same full release SHA as the
other nine images. CI stages only the reset/seed modules, four PNG assets and
the runner Dockerfile. The final amd64 image contains Node and the actual AWS,
MySQL, mongosh, Redis and OpenSSH clients. No runtime package installation is
required. CI must pass `node scripts/demo/runner-image-smoke.mjs` before the
publish job can push any image.

Compose keeps the runner running as UID/GID 10001 on the private backend
network, with a read-only root filesystem, no capabilities, no host ports and
no Docker socket. Its 768 MiB limit consumes the existing maintenance reserve;
the explicit backend limits now total 6,400 MiB. Health checks validate local
configuration and the runner heartbeat. They do not perform a reset.

Provision `/etc/rebyte-demo-reset`, owned by UID/GID 10001 with mode 0700, and
`config.json`, `ssh-key` and `known_hosts` there as regular, non-symlink files
owned by UID 10001 with mode 0400 or 0600. Create the external Docker volume
`${DEMO_RESOURCE_ID}-reset-config` with the local driver options `type=none`,
`device=/etc/rebyte-demo-reset` and `o=bind,ro`. Compose mounts that volume
read-only with `nocopy` at `/run/secrets/demo-reset` and refuses a missing
external volume. The driver also enforces read-only access. This avoids the
installed Coolify parser losing long bind-mount options. Verify the parsed
Compose and a real non-root mount before deployment; never let Coolify manage
the private files as file storage. The private JSON has the reviewed `targets`, `review`, `approval`
and `config` objects. Its SSH paths must be
`/run/secrets/demo-reset/ssh-key` and `/run/secrets/demo-reset/known_hosts`.
Never put these contents in image contexts, environment examples or Git.

Configuration proof on 2026-10-20: the installed Coolify parser retained the
external-volume declaration and the runner's read-only, `nocopy` mount. A
real container running as UID/GID 10001 read the three protected files and
received `Read-only file system` when attempting a write, even without a
read-only mount flag. Coolify retained all 35 existing environment values,
12 configured backend services, manual deployment and zero deployments.
The 85 release, Compose and acceptance guard tests passed. These are
configuration checks; deployed health, reset and schedule acceptance remain
pending the publication decision.

The scheduled command is `node /app/scripts/demo/reset.mjs` in the running
`demo-runner` target. Native tools receive sensitive JSON and JavaScript
through temporary owner-only files in `/tmp`; the coordinator removes them
after success, error, timeout or cancellation. MySQL/Redis credentials remain
in process environment, with diagnostic output suppressed. Reset uses the
fixed private gateway origin and the approved SSH forced-command control.

Do not create the scheduled task during packaging. Plan 13 must verify the
installed Coolify task implementation and server timezone, then confirm the
Monday 04:00 Europe/Rome schedule (cron `0 4 * * 1`) from Coolify's computed
next-run time and the server timezone, including daylight-saving behavior. A
container `TZ` value alone does not establish server scheduling semantics. The
first actual reset and deployed Spring/Cognito acceptance also remain plan 13
work. The first real Monday run is recorded in the plan 13 summary afterwards.

On 2026-10-20 the final amd64 runner passed 14 behavioral gates, including
356 native signed AWS CLI requests to isolated protocol endpoints, real
MySQL/MongoDB/Redis/RabbitMQ/Meilisearch stores, real constrained SSH and private
JWT API seeding. Seed and readiness failures retained real public maintenance.
There were also 76 release/Compose tests, 69 target/reset/seed tests and 11
supervisor tests passing. This is disposable rehearsal evidence; it does not
establish deployed Spring behavior or live AWS acceptance.

- Built image ID: `sha256:f17e567ade286c2cf4cc750c5fe45b798ba9f5306d05571ea42afe1ef3a9ea84`.
- Exact staged recipe/context SHA-256: `2ba1456ba64286fda8cd7af6b0f32869e3738d060672f96ef59aeb8f3ca38222`.
- Native versions: Node 24.21.0, AWS CLI 2.38.2, MySQL client 8.4.11,
  mongosh 2.12.0, Redis client 7.4.11 and OpenSSH 9.2p1 (Debian security build).

The ignored `target/demo-runner-smoke/report.json` retains the assertion names
and fingerprints. Changed packaged inputs invalidate this proof and require
another successful smoke before the publication checkpoint.

### Live acceptance preparation

`node scripts/demo/live-check.mjs --self-test` runs offline checks only. The
normal command is read-only and requires the frozen demo DNS, runtime config,
TLS origins, authentication/CORS boundaries and closed private host ports.
It rejects redirects and unexpected statuses and bounds response time/size.
Its public configuration validator is shared with the release gate.

Validate the single HTTP collection with
`./scripts/postman/verify-phase.sh 06.1`. Postman credentials enable the existing
workspace synchronization; without them only local validation runs. The
collection has 44 requests, disables redirect following and defaults to
read-only requests. Keep preserved-user `demoBuyerToken`, `demoSellerToken`,
`demoDeveloperToken` and `demoAdminToken` in the ignored environment. Never
save generated policies or signed URLs into the committed collection.

Mutations require private environment `demoAllowMutations=true`; S3 boundary
uploads also require `demoAllowMediaBoundary=true`. For each `Avatar`, `Photo`,
`Artifact` and `Chat`, provide private `demo<Class>ZeroFile`,
`demo<Class>MaxFile` and `demo<Class>OversizedFile` paths containing exactly zero,
the configured limit, and limit plus one bytes. Verify file sizes locally
before the run. Use the approved demo hardware listing, an owned disposable
draft software version and a demo conversation. Maximum objects are confirmed
or attached through normal APIs; a full reset subsequently removes them.

Sandbox capture additionally requires `demoAllowPayments=true`,
`demoBrowserApproved=true`, matching `demoOrderId`/`demoApprovedOrderId`, and
the exact saved sandbox `demoApprovalUrl`/`demoApprovedPaypalOrderId`. Set these
only after the human approves that provider checkout. Capture must return
`ACCEPTED_AWAITING_CONFIRMATION`; the bounded follow-up polls the order for
`PAID`. Actual accepted signed-webhook evidence is a separate live requirement.

The remote browser suite is isolated from the normal development E2E suite:

```bash
npm --prefix web run e2e -- --config e2e/demo.config.ts --list
npm --prefix web run e2e -- --config e2e/demo.config.ts
```

It provisions no users and starts no local server. `DEMO_ACCEPTANCE_FILE` names
a private JSON containing `accounts` (four roles with `email` and `password`),
`subjects` (the four approved immutable subjects), `hardwareListingId` and
`softwareListingId` from the completed demo seed. Keep the file local and
owner-only; never copy it into the snapshot. The suite validates live public
configuration before filling credentials and keeps traces, screenshots and
video disabled. It checks desktop/phone browsing, four real preserved logins,
and actual authenticated WebSocket delivery. That mutation test requires
`DEMO_ALLOW_MUTATIONS=true`; missing approval fails before sending a write.

Guest-card acceptance additionally requires `DEMO_ALLOW_PAYMENTS=true` and
`DEMO_CARD_HANDOFF=true`. This opens a visible browser, creates checkout through
the deployed SPA and requires the guest-card option on the actual sandbox
provider. The human completes the authorized test card in that browser; the
test then observes the exact capture and authoritative paid order. No provider
stub, PayPal account-login fallback or automatic approval flag is used. Missing
guest-card eligibility blocks acceptance.

The private runner needs an unattended credential, separate from the temporary
setup login. The approved `rebyte-demo-reset` identity uses
`infra/iam/demo-reset-policy.json`: 16 actions on the dedicated bucket and pool,
with no IAM administration or access to development resources. Its reviewed
SHA-256 is `fe9337d562349e73612f48fb9921328ffd8e51c1e5bb7efbc85db7fd46bcc58b`.
AWS policy simulation passed 16 positive and 54 negative cases. The user
approved this exact policy and private configuration on 2026-10-20. The
dedicated identity passed 18 actual read-only checks, including denied reads
of the development bucket and pool. Provisioning
requires explicit authorization; generated keys remain private. The coordinator
continues to enforce the separately approved preserved-user/reset contract.

Preparation evidence on 2026-10-20: six offline acceptance tests passed; native
Postman executed 23 refusal scripts with zero HTTP requests; remote Playwright
lists eight tests and passes strict TypeScript checking; 1,584 frontend tests
passed. These preparation results do not establish any live acceptance outcome.
