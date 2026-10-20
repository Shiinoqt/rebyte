# Phase 3 PayPal sandbox acceptance run

The Phase 3 collection has two folders and a browser pause between them. Use a
PayPal sandbox business app and sandbox buyer. Keep client credentials, JWTs,
provider IDs and the local Postman environment outside Git. `./mvnw verify`
uses isolated tests and requires no PayPal credentials.

## Prepare the stack

1. Set the private values in ignored `infra/.env`. For the Compose path,
   `MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD`, `RABBITMQ_PASSWORD`,
   `MEILI_MASTER_KEY`, `MEDIA_BUCKET` and `MEDIA_DISTRIBUTION_DOMAIN` are
   required. Set the PayPal sandbox
   client ID and secret there too. Start the infrastructure and all six
   Spring services with:

   ```bash
   docker compose -f infra/docker-compose.yml -f infra/docker-compose.services.yml up -d --build --wait
   docker compose -f infra/docker-compose.yml -f infra/docker-compose.services.yml ps
   ```

   This works from fish without sourcing `.env`. Compose uses a separate
   persistent `mysql-app-data` volume for its three application databases,
   preserving the MySQL volume used by host-run services. It uses Docker
   service names for Eureka and the local JWT issuer. Mongo uses a direct
   connection to the existing single-node replica set. The default host
   ports are 8761, 8080, 8081, 8082, 8084 and 8085; stop host-run services
   on those ports first. For a parallel run, set `DISCOVERY_HOST_PORT`,
   `GATEWAY_HOST_PORT`, `USER_HOST_PORT`, `CATALOG_HOST_PORT`,
   `ORDER_HOST_PORT` and `PAYMENT_HOST_PORT` before the Compose command,
   then use that gateway port for the tunnel and Postman environment.
   To keep running services on the host instead, start only
   `infra/docker-compose.yml` and run each module's `spring-boot:run`
   command in a separate terminal with the needed environment.
2. Start `cloudflared tunnel --url http://localhost:8080` for the default
   gateway port, or use the configured `GATEWAY_HOST_PORT`. Its public HTTPS
   hostname must point to `/payments/webhook`; a quick tunnel receives a new
   hostname on restart.
3. Register that exact HTTPS URL in the sandbox app, subscribing to
   `PAYMENT.CAPTURE.COMPLETED`, `PAYMENT.CAPTURE.DENIED`,
   `PAYMENT.CAPTURE.REFUNDED` and `PAYMENT.CAPTURE.PENDING`. From fish, load
   the private Compose values only in the child Bash process:

   ```fish
   bash -c 'set -a; source infra/.env; set +a; exec python3 scripts/paypal/sandbox-spike.py register-webhook --webhook-url "$1"' _ 'https://<current-host>/payments/webhook'
   ```

   Put its returned ID in `PAYPAL_WEBHOOK_ID` and set
   `CHECKOUT_RETURN_BASE_URL` in `infra/.env`, then recreate payment-service:

   ```bash
   docker compose -f infra/docker-compose.yml -f infra/docker-compose.services.yml up -d --no-deps --force-recreate payment-service
   ```

   Remove stale subscriptions in the PayPal dashboard so they do not obscure
   evidence.
4. Check `http://localhost:8080/actuator/health` and the `ps` health column.
   For host-run services, `bash scripts/checkout/check-env.sh` prints variable
   names and pass/fail only. Resolve every failure before Folder A. Prepare the
   untracked `postman/local.postman_environment.json` with gateway, user,
   catalog, buyer and admin values required by the collection; keep JWTs
   there only for this local run.

## Folder A: checkout and guards

Validate or synchronize the collection with `./scripts/postman/verify-phase.sh
03`. Then run only Folder A:

```bash
postman collection run postman/phases/03/Rebyte-03-Checkout-Payments.postman_collection.json \
  -e postman/local.postman_environment.json -i 'A - Checkout and guards'
```

Require exit code zero and one labelled handoff line with the **exact**
`orderId`, `listingId` and `approvalUrl` of the fresh hardware checkout at
the end. Folder A checks shipping versus software, self-purchase, rejected
bank transfer, stale expected total, idempotent replay, buyer cancellation
and release, the two-buyer unique guard, and rejection of a forged webhook.
The collection's process variables disappear when this CLI process exits.
Copy the three handoff values into the untracked environment file as
`handoffOrderId`, `handoffListingId` and `handoffApprovalUrl`; set
`browserApproved=false`. Do not create a second order for Folder B.

Open the saved `approvalUrl` in a browser as the sandbox buyer and approve
that exact order. Only after observing approval, set `browserApproved=true`
in the local environment file. A return redirect by itself does not mark
the order paid.

## Folder B: capture and refund

Run Folder B in a second CLI process:

```bash
postman collection run postman/phases/03/Rebyte-03-Checkout-Payments.postman_collection.json \
  -e postman/local.postman_environment.json -i 'B - Capture and refund'
```

Folder B refuses capture unless all three saved handoff values and
`browserApproved=true` are present. It captures the saved order, polls for
the signed provider callback to make the order `PAID`, repeats capture to
prove the same operation outcome, requests an admin full refund with a
reason, and polls for `REFUNDED` and the saved hardware listing to return to
`ACTIVE`. Inspect `/admin/orders/stuck` for awaiting timeout, captured
awaiting webhook and failed-create diagnostics, plus the saga DLQ depth.
Replay only one parked message after fixing its cause. Keep redacted order,
capture, webhook event and refund IDs with timestamps as acceptance evidence;
the provider's HTTP capture/refund response alone is not proof of an order
state transition.

## Separate disconnected-webhook proof

Use a different sandbox order. Approve it, then stop tunnel delivery before
capture. Capture once and confirm the order stays `AWAITING_PAYMENT` while
the admin stuck view reports captured awaiting webhook. Restore the tunnel
and resend the **signed** event from PayPal's webhook dashboard, or wait for
its provider retry. Poll until the order becomes `PAID`. A forged payload
must still be rejected with no state change. Record the redacted provider
event ID and the before/after order status.

The [official Postman CLI collection options](https://learning.postman.com/docs/postman-cli/postman-cli-collections/)
specify `postman collection run <collection> -i <folderName>` for one folder
and `-e` for an environment file. Do not
use `verify-phase.sh 03 --run` for this acceptance run: that option runs the
whole collection without the required browser pause.

## Setup pitfalls observed in the live run

- Register the webhook with the full path: the subscription URL must end in
  `/payments/webhook`. A bare tunnel host makes PayPal deliver to `/`, where
  the gateway answers 401 and payment-service never sees the event.
- user-service's `local-auth` profile generates a new signing key on every
  start. Mint fresh tokens (`POST /local-auth/token` with the same `sub` and
  groups) after any user-service restart; old tokens fail with 401.
- After recreating a service, callers can report `No servers available` for
  up to about a minute while Eureka and load-balancer caches refresh. Wait
  for a routed request to succeed before running a folder.
- PayPal resends reuse the original event ID. An event that payment-service
  already stored is dropped as a duplicate on resend.

## Acceptance evidence (2026-09-25)

Provider IDs are masked. All runs used the Compose stack on default ports
and a quick cloudflared tunnel to the gateway.

| Check | Order | Evidence |
|---|---|---|
| Folder A (unattended) | handoff `5f606cfb` | 20 requests, 20 assertions, 0 failed, exit 0; one `PHASE03_HANDOFF` line |
| Browser approval | `5f606cfb` | Sandbox buyer approved the exact handoff URL (token `03A9...5310`) |
| Folder B normal path | `5f606cfb` | Capture 200, PAID via signed webhook, repeated capture same outcome, admin refund `REFUND_PENDING`, REFUNDED and listing `fa8eaf1f` ACTIVE via signed refund webhook |
| Folder B steps 07-08 | n/a | Stuck view 200 (7 `failed_create`), DLQ replay 200 |
| Browser gate | `5f606cfb` | `browserApproved=false`: 0 requests sent, gate assertion failed, exit 1 |
| Disconnected webhook (D-26) | `746108e6` | Captured 13:30:14Z (`provider_capture_status=COMPLETED`); the misregistered subscription URL prevented delivery and the order stayed `AWAITING_PAYMENT` until the signed `PAYMENT.CAPTURE.COMPLETED` was resent at 13:46:39Z, then PAID |
| Refund of D-26 order | `746108e6` | Signed `PAYMENT.CAPTURE.REFUNDED` (refund `1P08...382K`, capture `3441...533T`) stored 14:06:07Z; order REFUNDED, listing `1d4384fb` ACTIVE |
| Forged webhook | Folder A | 400, software order unchanged `AWAITING_PAYMENT` |

The stuck view did not list `746108e6` as captured awaiting webhook during
the gap: the reconciliation sweep only flags rows past the payment timeout,
and delivery was restored before then.

Defects found by this run and fixed before acceptance: order-service payment
read timeout (`f558e46`), Resilience4j time limiter (`513ccda`), synchronous
capture never confirmed by webhook (`55c6a6a`), refund webhook capture link
(`32ee541`), Feign 404 behind the circuit breaker (`3c28786`), no-charge
outcomes for cancelled orders (`41a4fca`), software lifecycle events in
catalog (`e887b76`) and DLQ replay routing key (`878c076`).
