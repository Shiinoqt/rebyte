# Phase 5 messaging and notifications live acceptance run

Run from the repository root in Bash. This follows the Phase 3 sandbox runbook:
prepare the private configuration, start Compose and the tunnel, perform the human
checks, then run Postman. Use two Rebyte users, an ADMIN user, a developer with an
active software listing, a PayPal sandbox business app and sandbox buyer, and an
SES-verified inbox. Automated integration tests use local fakes, not AWS.

Keep credentials, JWTs, signed unsubscribe URLs and the local Postman environment
outside Git and published evidence. Do not enable shell tracing or STOMP DEBUG
logging. Commands below never display key values. An operator maintains ignored
`infra/.env` privately; agents must never open, read, print or edit it.

The Compose messaging block, `infra/.env.example` messaging entries and
`scripts/messaging/stomp-listen.mjs` belong to the companion implementation of
05-21. Ensure they are integrated before starting. The Phase 5 Postman collection
under `postman/phases/05` is a separate acceptance prerequisite; no collection
filename is assumed here.

## (1) Human AWS setup: SES events and dedicated IAM identity

D-36 and D-44 require human provisioning. These deployment names come from the
phase plan, not hardcoded Java resources: IAM user `rebyte-messaging-service`, SNS
topic `rebyte-ses-events`, SQS queue `rebyte-ses-events-messaging` and DLQ
`rebyte-ses-events-messaging-dlq`, all regional resources in `eu-south-1`.
The application consumes the queue URL supplied through `SES_EVENTS_QUEUE_URL`.

Use an operator AWS CLI profile with provisioning rights. The runtime user must
have S3 PutObject/GetObject/DeleteObject on the media bucket's `chat/*` objects and
ListBucket limited to that prefix, plus SES SendEmail for the verified sender and
configuration set. Keep its identity separate from the avatar and catalog users.
The research records that user and those grants as existing; verify them in the
intended account before creating keys. The SES sender domain `rebyte.tech` and
configuration set `rebyte-transactional` must be verified/configured with an enabled
SNS destination for BOUNCE, COMPLAINT and REJECT. Do not assume the historical AWS
setup still exists.

Create the SQS DLQ, then the source queue and its redrive policy. Replace
`<account-id>` with the account owning the existing topic. The temporary files in
this setup block contain resource identifiers and policies, not credentials.

```bash
REBYTE_AWS_PROFILE='<operator-profile>'
REBYTE_SES_REGION='eu-south-1'
REBYTE_SES_TOPIC_ARN='arn:aws:sns:eu-south-1:<account-id>:rebyte-ses-events'
REBYTE_SETUP_DIR=$(mktemp -d /tmp/rebyte-ses-setup.XXXXXX)

REBYTE_SES_DLQ_URL=$(aws --profile "$REBYTE_AWS_PROFILE" --region "$REBYTE_SES_REGION" \
  sqs create-queue --queue-name rebyte-ses-events-messaging-dlq \
  --query QueueUrl --output text)
REBYTE_SES_DLQ_ARN=$(aws --profile "$REBYTE_AWS_PROFILE" --region "$REBYTE_SES_REGION" \
  sqs get-queue-attributes --queue-url "$REBYTE_SES_DLQ_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

jq -n --arg arn "$REBYTE_SES_DLQ_ARN" \
  '{RedrivePolicy: ({deadLetterTargetArn: $arn, maxReceiveCount: "5"} | tojson)}' \
  > "$REBYTE_SETUP_DIR/queue-attributes.json"
REBYTE_SES_QUEUE_URL=$(aws --profile "$REBYTE_AWS_PROFILE" --region "$REBYTE_SES_REGION" \
  sqs create-queue --queue-name rebyte-ses-events-messaging \
  --attributes "file://$REBYTE_SETUP_DIR/queue-attributes.json" \
  --query QueueUrl --output text)
REBYTE_SES_QUEUE_ARN=$(aws --profile "$REBYTE_AWS_PROFILE" --region "$REBYTE_SES_REGION" \
  sqs get-queue-attributes --queue-url "$REBYTE_SES_QUEUE_URL" \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)

jq -n --arg queue "$REBYTE_SES_QUEUE_ARN" --arg topic "$REBYTE_SES_TOPIC_ARN" \
  '{Policy: ({Version: "2012-10-17", Statement: [{Effect: "Allow",
    Principal: {Service: "sns.amazonaws.com"}, Action: "sqs:SendMessage",
    Resource: $queue, Condition: {ArnEquals: {"aws:SourceArn": $topic}}}]} | tojson)}' \
  > "$REBYTE_SETUP_DIR/queue-policy-attributes.json"
aws --profile "$REBYTE_AWS_PROFILE" --region "$REBYTE_SES_REGION" \
  sqs set-queue-attributes --queue-url "$REBYTE_SES_QUEUE_URL" \
  --attributes "file://$REBYTE_SETUP_DIR/queue-policy-attributes.json"

aws --profile "$REBYTE_AWS_PROFILE" --region "$REBYTE_SES_REGION" sns subscribe \
  --topic-arn "$REBYTE_SES_TOPIC_ARN" --protocol sqs \
  --notification-endpoint "$REBYTE_SES_QUEUE_ARN" --attributes RawMessageDelivery=true
```

The queue policy permits only that SNS topic to send messages. `RawMessageDelivery`
delivers the SES JSON directly, as `SesEventProcessor` expects. If the queue already
has a policy, merge this statement with the existing policy before setting it;
`set-queue-attributes` replaces the Policy attribute. Confirm the redrive policy
has `maxReceiveCount=5` and the subscription has raw delivery enabled. See the
[SQS create-queue examples](https://github.com/aws/aws-cli/blob/develop/awscli/examples/sqs/create-queue.rst)
and [SNS subscription reference](https://docs.aws.amazon.com/cli/latest/reference/sns/subscribe.html).

Add these four intake actions on the source queue to the runtime IAM user. The
inline policy name below is an operator-chosen name, not a service setting; it
adds a separate policy without replacing the S3 or SES grants.

```bash
jq -n --arg queue "$REBYTE_SES_QUEUE_ARN" \
  '{Version: "2012-10-17", Statement: [{Effect: "Allow", Resource: $queue,
    Action: ["sqs:ReceiveMessage", "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility", "sqs:GetQueueAttributes"]}]}' \
  > "$REBYTE_SETUP_DIR/sqs-intake-policy.json"
aws --profile "$REBYTE_AWS_PROFILE" iam put-user-policy \
  --user-name rebyte-messaging-service --policy-name messaging-ses-events-intake \
  --policy-document "file://$REBYTE_SETUP_DIR/sqs-intake-policy.json"

umask 077
REBYTE_KEYS_FILE=$(mktemp /tmp/rebyte-messaging-keys.XXXXXX)
aws --profile "$REBYTE_AWS_PROFILE" iam create-access-key \
  --user-name rebyte-messaging-service --output json > "$REBYTE_KEYS_FILE"
REBYTE_UNSUBSCRIBE_FILE=$(mktemp /tmp/rebyte-unsubscribe-secret.XXXXXX)
openssl rand -base64 48 > "$REBYTE_UNSUBSCRIBE_FILE"
```

Transfer `AccessKeyId`, `SecretAccessKey` and the generated unsubscribe secret to
the settings in (2) using a private editor, without terminal output. Remove the
temporary secret files immediately after transfer. Never put them in Git. The
[IAM key reference](https://docs.aws.amazon.com/cli/latest/reference/iam/create-access-key.html)
documents that the secret is returned at creation.

## (2) Private configuration for the acceptance run

The human operator sets these values in ignored `infra/.env`. Every environment
name below is in `messaging-service/src/main/resources/application.yml`, except
`LOCAL_AUTH_TEST_EMAIL`, which belongs to user-service. Values in angle brackets
are placeholders; do not paste them as real configuration.

```dotenv
MESSAGING_AWS_ACCESS_KEY_ID=<dedicated-user-access-key-id>
MESSAGING_AWS_SECRET_ACCESS_KEY=<dedicated-user-secret-access-key>
SES_REGION=eu-south-1
SES_FROM_ADDRESS=no-reply@rebyte.tech
SES_CONFIGURATION_SET=rebyte-transactional
SES_EVENTS_ENABLED=true
SES_EVENTS_QUEUE_URL=<source-queue-url-from-section-1>
MESSAGING_UNSUBSCRIBE_SECRET=<generated-secret-from-section-1>
LOCAL_AUTH_TEST_EMAIL=<SES-verified-sandbox-inbox>
MESSAGING_WEB_BASE_URL=http://localhost:4200
MESSAGING_PUBLIC_API_URL=https://<current-tunnel-host>
MESSAGING_WS_ALLOWED_ORIGINS=http://localhost:4200
MESSAGING_EMAIL_NEW_MESSAGE_WINDOW=PT1M
MESSAGING_SES_MIN_SEND_INTERVAL_MS=1100
```

Use at least 32 UTF-8 bytes for `MESSAGING_UNSUBSCRIBE_SECRET`; the generator in
(1) satisfies this. The normal message-email window is `PT15M`; `PT1M` is for
this run. Compose defaults SES polling to disabled; this acceptance run enables
it explicitly and requires a queue URL. Until (1) is complete, leave
`SES_EVENTS_ENABLED=false` for development; this does not satisfy bounce
acceptance. Blank dedicated keys also fail startup;
shared `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` cannot substitute them.

Retain the existing required Compose settings from the Phase 3 runbook:
`MYSQL_ROOT_PASSWORD`, `MYSQL_PASSWORD`, `RABBITMQ_PASSWORD`, `MEILI_MASTER_KEY`,
`MEDIA_BUCKET` and `MEDIA_DISTRIBUTION_DOMAIN`, plus the existing PayPal sandbox
configuration. `MEDIA_REGION` defaults to `eu-south-1`. The media bucket must
match the runtime user's `chat/*` grant. Under local-auth, all Rebyte users resolve
to the same `LOCAL_AUTH_TEST_EMAIL`; mailbox evidence alone does not prove which
Rebyte user received a notification.

## (3) Start Compose and the public tunnel

Stop host-run services occupying the default ports. Once the companion Compose
block is integrated, start the stack using the proven Phase 3 command:

```bash
docker compose -f infra/docker-compose.yml -f infra/docker-compose.services.yml up -d --build --wait
docker compose -f infra/docker-compose.yml -f infra/docker-compose.services.yml ps
curl -fsS http://localhost:8080/actuator/health
cloudflared tunnel --url http://localhost:8080
```

Run the tunnel in its own terminal. Use `GATEWAY_HOST_PORT` if the default 8080 is
remapped, and `MESSAGING_HOST_PORT` for direct messaging access (default 8083).
Compose uses Mongo `messaging_db` with `replicaSet=rs0&directConnection=true`, Redis,
RabbitMQ, Eureka and the local user-service JWT issuer. Require healthy messaging
and gateway services before acceptance.

The quick tunnel hostname changes on restart. The operator updates
`MESSAGING_PUBLIC_API_URL` to its current HTTPS origin, then recreates messaging:

```bash
docker compose -f infra/docker-compose.yml -f infra/docker-compose.services.yml \
  up -d --no-deps --force-recreate messaging-service
```

Keep the PayPal webhook registered at this tunnel's `/payments/webhook` path and
its private `PAYPAL_WEBHOOK_ID` current, following the Phase 3 runbook. Frontend
deep links use `MESSAGING_WEB_BASE_URL`; until the frontend exists, verify the
link's resource and id rather than claiming the destination page works.

## (4) STOMP push through the gateway

Use Node 26 and the integrated listener. Obtain a current JWT from the local-auth
flow used by Postman. Enter it without terminal echo; the listener reads it only
from `REBYTE_TOKEN` and sends it in the STOMP CONNECT Authorization header.

```bash
node scripts/messaging/stomp-listen.mjs --self-test
read -r -s -p 'Rebyte JWT: ' REBYTE_TOKEN
export REBYTE_TOKEN
node scripts/messaging/stomp-listen.mjs ws://localhost:8080/ws \
  /user/queue/messages /user/queue/notifications /user/queue/unread \
  /user/queue/presence /user/queue/typing --seconds 120
unset REBYTE_TOKEN
```

Connect as the recipient. In another terminal or Postman, use the other user to
start a conversation with `POST /conversations` (`listingId`, required first
`text`, optional `imageKeys`) or send with `POST /conversations/{id}/messages`
(`text` and/or `imageKeys`). Require a message frame, its notification frame and
an unread summary. `{notifications, messages}` counts all unread notifications,
including MESSAGE, and unread messages separately; do not add those totals.

Presence is visible only to conversation partners. For a typing check, a STOMP
client sends to `/app/conversations/{conversationId}/typing`; the listener only
subscribes. The other participant gets a typing frame with `ttlMs=5000`. JWT
expiry closes the session; reconnect with a fresh JWT and re-fetch REST state.
There is no socket replay buffer, SockJS fallback or read receipt. Never put a
JWT in the WebSocket URL or command arguments.

## (5) Three live emails and unsubscribe

Keep global email preferences enabled for the three initial deliveries: GET and
PUT `/notifications/email-preferences`, with PUT body `{"emailEnabled":true}`.
Use fresh domain actions rather than replaying a delivered email job. Capture
the action time, intended Rebyte recipient, target id and inbox result privately.

1. **New message:** with an active listing, have the non-seller start a chat or
   send in an existing conversation. Leave the recipient's newest message page
   unopened until delivery. Expect an English sender/listing summary and a
   `/conversations/{id}` deep link, with no chat text or image URL. Further messages
   inside the one-minute test window produce in-app updates but no extra email.
   Opening `GET /conversations/{id}/messages` without a cursor marks received
   messages up to the newest sequence shown and resets that user's email throttle;
   later arrivals and older-page requests are unaffected.
2. **Order paid:** complete a fresh software purchase through the real PayPal
   sandbox approval/capture/webhook flow from Phase 3. Require payment success
   and software delivery in Rebyte. The seller gets the ORDER in-app notification;
   the buyer gets an English payment summary, `/orders/{id}` link and licence-ready
   wording. The licence key and message text must not appear in the email. This
   transactional email has no unsubscribe link and still sends when opted out.
3. **New version:** as the developer, release a new version of that software
   listing using its existing catalog release operation. An eligible licence
   holder or follower receives the title/version summary and `/listings/{id}`
   link. Overlap between holder and follower produces one recipient notification
   and one email job; the seller is excluded.

For all three, inspect HTML and the plain-text alternative in the inbox. Require
the expected sender, summary, target link and no message body. Do not publish raw
mail headers, addresses or signed links as evidence. A missing email requires
checking the recipient preference, unread state, throttle, verified address,
suppression and `email_dispatch` outcome, not assuming a sending failure.

After the three deliveries, open a non-transactional unsubscribe link. Anonymous
GET `/notifications/email-preferences/unsubscribe?token=...` must display a
confirmation page without changing the preference. Submit its form (POST to the
same URL); then verify GET `/notifications/email-preferences` for the signed user
returns `emailEnabled=false`. Repeat POST to verify idempotency. A fresh eligible
message/version email is skipped, while a fresh paid-order email still sends.
Restore `emailEnabled=true` before the bounce check. Do not use an expired,
tampered or another user's signed link as a success case.

## (6) SES bounce simulator and local suppression

Temporarily override the local-auth inbox in the shell, recreating user-service
because restarting a container alone does not change its environment:

```bash
LOCAL_AUTH_TEST_EMAIL=bounce@simulator.amazonses.com \
  docker compose -f infra/docker-compose.yml -f infra/docker-compose.services.yml \
  up -d --no-deps --force-recreate user-service
```

user-service's local-auth signing key changes on restart. Mint fresh JWTs for the
same users/groups after this recreation and after restoring the inbox; update
Postman and reconnect the STOMP listener. Allow Eureka/load-balancer caches to
refresh before continuing.

Trigger one fresh eligible new-message email (use a fresh conversation or reset
the read/throttle window first). Wait for SES -> SNS -> SQS intake. Require this
record, queried without listing real recipients:

```bash
docker exec rebyte-mongo mongosh --quiet messaging_db --eval \
  'db.email_suppression.find({_id:"bounce@simulator.amazonses.com"},{_id:1,reason:1,firstSeenAt:1,lastSeenAt:1,count:1}).forEach(printjson)'
```

The reason must be `BOUNCE_PERMANENT`. Then trigger another fresh eligible job for
the simulator and confirm a `SKIPPED_SUPPRESSED` outcome in `email_dispatch`, with
no additional SES send. A transient bounce or Reject event alone does not add a
suppression record. If intake is absent, check the configuration-set destination,
raw subscription, source queue policy, poller setting, IAM grants and SQS DLQ.
SQS messages are deleted only after processing; poison messages redrive to the
SQS DLQ after five receives. This AWS DLQ is separate from RabbitMQ email replay.

## (7) Phase 5 Postman run and evidence

Restore the normal inbox as described in (8) before running Postman. Ensure the
Phase 5 collection has been integrated under `postman/phases/05` and the ignored
`postman/local.postman_environment.json` contains its gateway, user, developer and
admin variables. The helper discovers exactly one collection; do not invent a
filename or bypass a missing collection.

```bash
./scripts/postman/verify-phase.sh 05
./scripts/postman/verify-phase.sh 05 --run
```

Require exit zero, all assertions passing and no skipped cases without an explicit
reason. The helper validates the collection, optionally synchronizes it when
`POSTMAN_API_KEY` and `POSTMAN_WORKSPACE_ID` are set, and runs the Postman CLI with
CLI/JSON reporters. Keep credentials and the local environment untracked.

Report the tested revision, commands and exit statuses, request/assertion totals,
and each failed or skipped case with its cause. Record the three inbox checks,
unsubscribe GET/POST behavior, simulator suppression and gateway STOMP pushes
separately: Postman REST results do not prove those manual checks. Mark any
unperformed live check pending, never passed on the basis of local fake tests.

## (8) Cleanup

Remove any shell override of `LOCAL_AUTH_TEST_EMAIL`. With the verified inbox still
set privately in `infra/.env`, recreate user-service to restore it:

```bash
unset LOCAL_AUTH_TEST_EMAIL
docker compose -f infra/docker-compose.yml -f infra/docker-compose.services.yml \
  up -d --no-deps --force-recreate user-service
unset REBYTE_TOKEN
```

Restore the desired email preferences and `MESSAGING_EMAIL_NEW_MESSAGE_WINDOW=PT15M`,
then recreate messaging-service as in (3). Stop the temporary tunnel when done.
Keep runtime keys only in ignored `infra/.env`; remove the temporary key/secret
files from (1), and keep Postman tokens and signed links out of shared reports.
Retain the simulator suppression record as evidence; do not clear real suppression
entries merely to make a test send. Do not delete persistent volumes as cleanup.

## (9) Email ownership recovery and one-message replay

SES external acceptance is not exactly-once. The
[SES SendEmail request](https://docs.aws.amazon.com/ses/latest/APIReference-V2/API_SendEmail.html)
has no idempotency token; local job and dispatch deduplication cannot atomically
commit a provider acceptance with Mongo. `EmailSendService` fences uncertain sends
instead of resending them automatically. Never infer no-send from a missing local
`sesMessageId`, an elapsed timeout, queue redelivery or the age of an owner.

Work from `messaging_db.email_dispatch` and, for NEW_MESSAGE, its matching
`messaging_db.conversation`. The dispatch `_id` is a SHA-256 of
`kind|dedupKey|recipientSub`, distinct from the email job's outbox `eventId`.
Correlate the DLQ job, recipient and conversation privately before editing. The
dispatch fields are `status`, `ownerToken`, `preparationStartedAt`, `sendStartedAt`,
`sesMessageId`, `updatedAt` and `expiresAt`. Conversation fields are:

| Recipient side | Exclusive slot | Successful-send stamp | Read-reset generation |
|---|---|---|---|
| Buyer | `buyerEmailDispatchId` | `buyerLastEmailAt` | `buyerEmailGeneration` |
| Seller | `sellerEmailDispatchId` | `sellerLastEmailAt` | `sellerEmailGeneration` |

Read marking clears the relevant stamp and increments its generation, but retains
the slot. Completion sets a stamp only when the slot still matches this dispatch
and the generation equals the generation captured at claim. That captured
generation is not persisted in `email_dispatch`; do not invent a dispatch field
or copy the current generation as if it proved the original claim.

1. **Fence the old owner first.** Establish that the owning process is stopped and
   cannot resume or make a delayed SES call. In the local stack, stop messaging
   before maintenance; in a multi-instance run fence every possible owner. Pause
   concurrent conversation reads/writes during the repair or include their
   observed values in the compare-and-set. A timestamp is not a lease expiry and
   `x-single-active-consumer` alone does not fence a previously running process.
2. **Dead PREPARING owner:** after fencing, re-read the dispatch and matching slot.
   In one Mongo replica-set transaction, compare `_id`, `status=PREPARING`, the
   observed `ownerToken` and `updatedAt`; reset to `PENDING`, set `updatedAt`, and
   unset `ownerToken`, `preparationStartedAt`, `sendStartedAt` and `expiresAt`.
   Clear only the conversation slot that equals this dispatch id, checking the
   observed recipient generation and stamp in the same transaction. Preserve that
   stamp and generation, including any read reset; preparation did not consume a
   throttle window. Abort the whole transaction if any expected match is lost.
3. **SENDING or DELIVERY_UNKNOWN:** retain the dispatch and conversation slot with
   no expiry until SES evidence establishes acceptance or definite non-acceptance.
   Transport uncertainty may leave either state, including SENDING when the
   uncertainty write failed. Do not release these slots because a retry is old.
   If evidence is inconclusive, keep them fenced and pending reconciliation.
4. **Proven accepted:** record `SENT` and the verified `sesMessageId`, set
   `updatedAt` and `expiresAt` to completion time plus 30 days, and unset
   `ownerToken`, with a compare-and-set on the observed state/owner. In the same
   transaction release only this dispatch's slot. Set the recipient's last-email
   stamp only if evidence establishes the original claim generation still equals
   the current generation. If it cannot be established, preserve the current
   stamp/generation rather than overwriting a possible read reset. Do not send
   again. A bounce proves acceptance of a send, even though delivery failed.
5. **Proven no acceptance:** after fencing, for a retryable failure reset to `PENDING` with the same field
   cleanup and guarded slot release as in step 2. Keep the current stamp and
   generation; do not roll back a concurrent read reset or advance the generation
   merely to unlock sending. Reconcile dispatch, slot, stamp and generation in one
   transaction, not independent manual updates. ORDER_PAID and NEW_VERSION have
   no conversation slot to repair. A proven permanent refusal belongs in
   `FAILED_PERMANENT` with terminal retention, not in the retry path.
6. **Resume and replay one letter:** restart messaging after the transaction and
   invoke the existing ADMIN endpoint through the gateway:
   `POST /admin/messaging/dead-letters/messaging.email-send.dlq/replay`.
   It replays one letter to its origin queue, returns 200 with `X-Audit-Id`, or 204
   if empty. The generic path is
   `POST /admin/messaging/dead-letters/{queue}/replay`, where `{queue}` is an
   allowlisted RabbitMQ DLQ name, including the `.dlq` suffix. Repaired PENDING
   dispatches may send; SENT dispatches are acknowledged without sending again.
   The endpoint does not repair ownership state or replay the AWS SQS DLQ.

Record the provider evidence, matched state/owner, conversation side, repair
outcome and replay audit id in a private operator record. If compare-and-set
matches zero documents, re-read and investigate rather than forcing an update.
Never blindly replay an unresolved sending dispatch or blanket-reset a queue's
owners.
