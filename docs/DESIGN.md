# qbit-slack design

**Status:** approved design, built in phases (section 15).

## Owner requirements (verbatim)

- 2026-10-14: "like we have an email qbit, we need one for slack too, so other processes etc can send
  messages to users via email or via slack etc etc"
- 2026-10-14: "we should be able to send messages to channels etc as output from scheduled jobs and scheduled
  reports etc and from running things, etc or even as exports from the system (download is an option, but
  so is slack, etc). Slack should be tied to the user logged in, as with some deployments, we have users
  from MANY different companies logged in at once"

## Owner decisions (2026-10-14): all recommendations accepted (standing approval)

The owner approved all open designs on 2026-10-12 ("they are approved. we will adjust as needed once we
can view them working"). Every recommendation below is decided as written. The hard requirements from the
coordinator brief of 2026-10-14 are written into this design as requirements, not options (section 1).

**Superseded decision.** On 2026-10-13, qbit-alerts `docs/NOTIFICATIONS.md` section 1.1 recorded "Slack
stays a group or monitor channel only, never per user." The 2026-10-14 requirements replace it: Slack is a
per-user channel and a delivery destination. Alert-group and monitor Slack channels (incoming webhooks in
qbit-alerts) keep working unchanged.

## 0. Summary

qbit-slack connects QQQ to Slack **per user and per tenant**.

- **Connections are per user.** Each user connects their own Slack account through Slack OAuth ("Connect
  Slack"). An organization admin may install the app's bot into their own workspace, and that install is
  scoped to their organization (tenant) in QQQ. Tokens are encrypted at rest, write-only, and never shown,
  logged or returned.
- **Messages are posted for a QQQ user (the owner).**
  - They use that user's own connection, or their tenant's bot.
  - They go only to workspaces the owner connected, and to channels the owner is a member of. Membership
    is checked at send time.
  - There is never a fallback to another user's or tenant's token.
- **Core messaging provider** (type `QBIT_SLACK`, name `slack`). Any process sends with core
  `SendMessageAction`, so callers depend only on core.
- **Delivery destination** `slack`, through the new core output-delivery SPI (section 9). Process results,
  durable jobs, scheduled reports and jobs, exports, saved views and assistant scheduled tasks can deliver
  to a Slack DM or channel, next to Download and Email. Large outputs are uploaded as files, or sent as a
  link.
- **Durable outbox** (`slack_message`). It retries, honours Slack rate limits (`Retry-After`), and
  dedupes by idempotency key. Message metadata reconciliation stops a crashed runner from posting twice.
- **Readiness and health.** A public API package `SlackApi` mirrors `EmailApi`, and the qbit registers a
  health check.
- **Notifications.** A Slack column in each user's notification preferences, so `QAlerts.notify(...)` can
  reach them in Slack (section 10).
- **PHI.** Slack is not HIPAA-eligible by default, so classified PHI and PII are blocked or left out of
  every Slack payload and export, with a clear message (section 11).
- **Phase 2: interactivity.** Buttons such as Approve and Acknowledge run QQQ actions as the clicking
  user, with permissions enforced.

## 1. Hard requirements

| # | Requirement |
|---|---|
| R1 | Slack is tied to the signed-in user. Every outgoing message has an **owner** (a QQQ user id), and is authorized as that owner at send time. Ownerless sends exist only for system notices to a registered channel of a tenant's workspace (section 6.2). |
| R2 | Multi-tenant safe. Users of many companies share one deployment. Workspace installs belong to exactly one tenant (a QQQ security key value). Cross-tenant posting is impossible by construction, and is tested with two tenants and two workspaces. |
| R3 | A user posts only to workspaces they connected, and to channels they are a member of. At send time `conversations.members` (or `users.conversations`) must include the owner's Slack user id. |
| R4 | Scheduled and background deliveries run as the owning user, with that user's connection and permissions. A revoked or missing connection fails the delivery and alerts the owner. Delivery never falls back to another connection, user or tenant. |
| R5 | Tokens are encrypted (AES-256-GCM, bound to their row), write-only, never displayed, logged, audited or returned by any API. |
| R6 | Channel pickers show only what the current user's connection can see, as contingent dropdowns: workspace, then channel (or "Direct message to me"). |
| R7 | Classified PHI and PII are redacted or blocked for Slack, exports included. The user sees which fields and why. |
| R8 | Output delivery is a core concept with a thin SPI. Slack, Email and Download are destinations; object storage and SFTP come later. |

## 2. Flow

```
caller ─ SendMessageAction("slack") ─┐
caller ─ SlackApi.send / sendOnce ───┤
DeliverOutputAction ("slack") ───────┼─> SlackService (as the OWNER's session)
QAlerts.notify ─> qbit-alerts ───────┘     1. resolve workspace under owner's tenant lock
                                           2. resolve connection (owner) + target; membership check
                                           3. render, PHI guard
                                           4. insert slack_message (QUEUED, ownerUserId, workspaceId)
SlackDispatcher (scheduled) ─ claim + lease ─ re-authorize as owner ─ decrypt that row's token
     └─ chat.postMessage / chat.update / files upload (with message metadata)
        ok -> SENT · 429 -> wait Retry-After · transient -> RETRY_WAIT · permanent -> DEAD_LETTER
        revoked/not a member -> FAILED_AUTH + QAlerts.notify(owner) (never another token)
```

## 3. Workspaces, connections and secrets

### 3.1 `slack_workspace` (Workspaces)

There is one row per Slack workspace install **per tenant**, unique (`teamId`, `tenantKey`). Two tenants that
use the same Slack workspace get two rows, each locked to its own tenant.

| Field | Notes |
|---|---|
| `teamId`, `teamName`, `enterpriseId`, `appId` | From `oauth.v2.access` |
| `tenantKey` | The tenant security value. It's the host's configured security field (for example `clientId` or `organizationId`), applied through `SlackQBitConfig.securityFields` and `recordSecurityLocks`, the same as qbit-email. Required when the host declares a tenant lock |
| `installedByUserId`, `installedAt` | |
| `botUserId`, `botScopes` | |
| `botToken` | Encrypted (`v1:<keyId>:<base64>`). It's write-only, and its tier is HIDDEN for every role (core field access). Set only by the OAuth callback; it can't be edited |
| `botTokenFingerprint` | The first 8 hex characters of SHA-256, for support ("ends …"). Not reversible |
| `status` | ACTIVE / REVOKED / UNINSTALLED / ERROR |
| `readinessStatus`, `readinessCheckedAt`, `readinessSummary` | Section 8 |
| `health`, `consecutiveFailures`, `ratePerMinute` | Section 5.3 |

Only the tenant's admins (`slack.admin`, within their tenant lock) see or change it.

### 3.2 `slack_connection` (Connections)

There is one row per QQQ user and workspace row, unique (`userId`, `workspaceId`). The row is owner-locked on
`userId`. Admins see their tenant's rows read-only, without tokens, and can revoke them.

| Field | Notes |
|---|---|
| `userId` | The QQQ user (owner lock) |
| `workspaceId` | The tenant-scoped workspace row |
| `slackUserId`, `slackUserName` | From OAuth (`authed_user.id`). This is the verified identity, so there's no email matching |
| `userToken` | Encrypted user token (`xoxp-`), present when user scopes were granted. Write-only and HIDDEN |
| `userTokenFingerprint`, `userScopes` | |
| `status` | ACTIVE / REVOKED / ERROR |
| `connectedAt`, `lastVerifiedAt`, `revokedAt`, `lastError` | `lastError` is scrubbed |

### 3.3 Connect Slack (OAuth v2)

1. **Start** (UI API only). It's a preference-section action, **Connect Slack**, in the user's Slack
   section (core #1162), or the same action on the Connections table.
   - It returns the Slack authorize URL.
   - The `state` is a server-signed, single-use token over the user id, tenant key, nonce, mode
     (`user` or `user+bot`) and a 10-minute expiry.
   - Users who hold `slack.workspace.install` in their tenant may also request the bot scopes.
2. **Callback.** `GET /qqq/hooks/slack/oauth/callback` is a qbit route through the route extension point,
   like qbit-email's webhooks. It is a browser redirect from Slack.
   - It requires a valid signed-in session whose user and tenant equal the `state`'s.
   - It exchanges the code with `oauth.v2.access`, using the app's client id and secret from named
     environment variables.
   - It upserts the tenant's `slack_workspace` (bot token, when granted) and the user's `slack_connection`
     (user token and Slack user id).
   - Then it redirects to the Preferences page with a result notice.
   - Errors redirect with a plain message, never token text.
3. **Disconnect** calls `auth.revoke` on the user token, then marks the row REVOKED and clears the token.
   **Uninstall** (admin) calls `apps.uninstall` or revokes the bot token, then marks the workspace
   UNINSTALLED.
4. **Revocation from Slack** (phase 2, Events API): `tokens_revoked` and `app_uninstalled` mark the rows.
   On any send, `invalid_auth`, `token_revoked` or `account_inactive` also mark them.

### 3.4 Token encryption

`SlackTokenCipher` is AES-256-GCM, with a random 96-bit nonce per value. The associated data is
`table:rowId:userId|tenantKey`, so a ciphertext copied to another row (or another tenant's row) doesn't
decrypt. The stored format is `v1:<keyId>:<base64(nonce|ct|tag)>`.

- **Keys** come from named environment variables, never from the database: `SLACK_TOKEN_KEY` (base64, 32
  bytes), plus optional previous keys (`SLACK_TOKEN_KEY_PREVIOUS`) for rotation. This is the same model as
  core `RefreshTokenCipher`.
- **Decryption** happens in the dispatcher immediately before the HTTP call. The plaintext is kept only on
  the stack, and is never in a log, an exception, a table, an audit or an API response.
- **Scrubbing.** `SlackRedaction.scrub` removes `xox[abeprs]-…`, `Authorization` headers and secrets from
  every error text.
- **Test.** A known fake token is driven through every failure path. The test asserts that it never reaches
  a log line, a `lastError`, an exception message or an API projection.

App-level secrets are named environment variables, allow-listed in `allowedEnvNames`:
`SLACK_CLIENT_ID`, `SLACK_CLIENT_SECRET`, `SLACK_SIGNING_SECRET` and `SLACK_TOKEN_KEY`.

### 3.5 Slack app

`docs/slack-app-manifest.yaml` defines one distributable app, used by every tenant.

| Kind | Scopes |
|---|---|
| Bot | `chat:write`, `im:write`, `channels:read`, `groups:read`, `users:read`, `files:write`, `files:read` (file reconcile), `usergroups:read` (optional), `chat:write.customize` (optional) |
| User | `chat:write`, `channels:read`, `groups:read`, `im:write`, `files:write`, `files:read`, `users:read` |

- **Redirect URL:** `https://<host>/qqq/hooks/slack/oauth/callback`.
- **Interactivity URL (phase 2):** `https://<host>/qqq/hooks/slack/interactions`.
- **Distribution:** public distribution is on when tenants use their own workspaces. For a single company,
  it's a normal workspace app.

## 4. Sending

### 4.1 Who posts, and the authorization check (R1-R4)

Every send carries `ownerUserId`. If it's missing, it's the current session user. System sessions must name
an owner, or use an ownerless channel (6.2).

**At queue time**, under the owner's session: the session is built with `createAutomatedSessionForUser`
when the caller is a system or job context, and the owner must equal the session user. Then:

1. Load the workspace **through a normal tenant-locked query** under the owner's session. A row of another
   tenant is invisible, so it's REFUSED as "This Slack workspace isn't available to you".
2. Load the owner's ACTIVE connection in that workspace. If there isn't one, it's REFUSED with "Connect Slack
   to <team>".
3. Resolve the target:
   - **DM to the owner:** use the owner's Slack user id.
   - **Channel:** check that the owner's Slack user id is in `conversations.members` for the channel, using
     the workspace bot token, or the owner's user token when there's no bot. A result is cached for 10
     minutes. If not, it's REFUSED with "You aren't a member of #x".
   - **DM to another QQQ user** (system notices, 4.2): only when that user has an ACTIVE connection **in
     the same workspace row**.
4. Pick the **posting identity**:
   - **BOT** (the tenant's bot) when the workspace has an ACTIVE bot that's a channel member, or for DMs.
   - **USER** (posts as the owner, with the owner's user token) otherwise, or when the caller asks for
     `postAs(USER)`.
   - There is never a different row's token.

**At send time** the dispatcher repeats steps 1 to 4 under a fresh session for the owner, because access may
have changed since the message was queued. Then it decrypts **only** the token of the chosen row.

- **Owner lost access:** the outcome is FAILED_AUTH, and the owner is notified through `QAlerts.notify` with
  category SYSTEM: "Your Slack delivery to #x failed: <reason>. Reconnect Slack." The alert
  `slack.delivery.unauthorized` is raised, deduplicated per owner per day.
- **The test** uses tenants A and B with workspaces WA and WB, a fake Slack server and a different token
  for each. It checks all of these:
  - A cannot target WB or WB's channels: the address, the picker, the API and the destination all refuse.
  - A scheduled delivery of A, after A's connection is revoked, fails with an alert to A.
  - The fake server never sees WB's token on A's traffic.
  - Copying A's ciphertext into B's row fails to decrypt.
  - A tenant admin of B cannot see A's connections or messages.

### 4.2 Core messaging provider (low coupling)

`SlackOutboxMessagingProvider implements MessagingProviderInterface`, type `QBIT_SLACK`, is registered under
`bridgeProviderName` (default `slack`).

| Address (`Party.address`) | Target |
|---|---|
| `me` | DM to the owner |
| `user:<qqqUserId>` | DM to that user through **their** connection in a workspace of the owner's tenant. Used for notifications, where owner and recipient are the same user (section 10) |
| `channel:<workspaceId>/<slackChannelId>` | A channel the owner is a member of (pickers produce this) |
| `channel:<name>` or `#<name>` | A registered channel (6.2) of the owner's tenant |
| `group:<name>` | A registered user group: a post with a mention, in its channel |

There is no addressing by email, and no raw Slack ids without a workspace. `MultiParty` gives one outbox row
per recipient. An unknown or unauthorized target is REFUSED with a reason, never an exception.

- **Capabilities** (core #1194): `TEMPLATES`, `IDEMPOTENCY`, `CATEGORIES`, `LINKED_RECORDS`, `ASYNC_DELIVERY`.
- **Output:** `QUEUED` with the id, `DUPLICATE` with the earlier id, or `REFUSED` with reasons.
  For multiple recipients, `messageId` contains the accepted IDs (a list when more than one), and
  idempotency keys are scoped by recipient. A partial refusal returns `REFUSED` with accepted IDs;
  otherwise the status is `QUEUED` if any row is new, or `DUPLICATE` if all already existed.
- **Content:** `subject` becomes the bold first line and the notification text. Content items are mrkdwn,
  escaped. A Block Kit JSON body needs `slack.send.blocks`. A template wins.
- **Dependency:** QQQ 4.1.0-RC.7. The bridge maps template name, variables, idempotency key,
  category, linked record and sender name directly to the outbox. Locale remains advisory and is ignored.

### 4.3 Public API `com.kingsrook.qbits.slack.api` (mirrors `EmailApi`)

This is the only public surface. It's additive within a 0.x minor line.

| Method | Meaning |
|---|---|
| `isInstalled(QInstance)` | The qbit was produced |
| `send(SlackApiRequest)` | Queue: `SlackApiResult(status, messageId, reasons)` |
| `sendOnce(SlackApiRequest, String effectKey)` | One durable handoff keyed by the effect key. It refuses a caller transaction and returns `SlackApiEffectResult(QUEUED_OR_DUPLICATE or REFUSED, message)` after commit. This is the jobs_effect primitive |
| `reply(Integer parentMessageId, SlackApiRequest)` | A thread reply, which waits for the parent's `ts`. The same owner is required |
| `update(Integer messageId, SlackApiRequest)` | `chat.update` of a sent message. The same owner, or `slack.admin` in the tenant |
| `getMessage(id)`, `getMessageByIdempotencyKey(key)`, `findMessagesByIdempotencyKeyPrefix(prefix, limit)` | The metadata projection, never the body |
| `readiness(QInstance)` | `SlackApiReadiness(ready, checks)` for the deployment (app secrets and key) |
| `readinessFor(String userId)` | That user's view: connected workspaces, token health and scopes |
| `connectionsFor(String userId)` | `List<SlackApiConnection>` (workspace label, team, status). No tokens |
| `listTargets(String userId, Integer workspaceId)` | The channels that user can post to (picker data, R6) |
| `ensureTemplate(SlackApiTemplate)` | Seed a template once |

`SlackApiRequest` fields:

- `owner(userId)` and `to(...)`, using the 4.2 forms;
- `text`, `templateName`, `variables`, `idempotencyKey`, `category`;
- `linkedRecord(table, id)`, `senderName`, `threadOf(messageId)`;
- `postAs(BOT|USER)`, `attachment(...)` (9.3), `unfurl` (default off);
- `actions` (phase 2), `withTransaction(transaction)`.

### 4.4 Permissions

| Permission | Lets the holder |
|---|---|
| `slack.view` | Use the app and its tables, scoped by tenant and owner locks |
| `slack.connect` | Connect their own Slack (granted to ordinary users by default) |
| `slack.workspace.install` | Install the bot for their tenant |
| `slack.admin` | Administer workspaces, registered channels, groups and templates, and revoke connections, in their own tenant |
| `slack.send` | Queue through the provider, API or destination for themselves. Automated sessions for an owner use the owner's permissions |
| `slack.send.blocks` | Send raw Block Kit |
| `slack.message.body` | See message text |

## 5. Outbox and delivery

### 5.1 `slack_message` (Messages)

Each message is owner-locked on `ownerUserId` and tenant-locked through the workspace.

| Field | Notes |
|---|---|
| `ownerUserId`, `workspaceId`, `connectionId`, `postAs` | The authorization context (4.1) |
| `operation` | POST / UPDATE / FILE |
| `targetType`, `targetRef`, `slackChannelId`, `channelLabel` | `targetType`: ME, USER, CHANNEL, REGISTERED_CHANNEL, USER_GROUP |
| `status` | QUEUED, WAITING_PARENT, SENDING, RETRY_WAIT, SENT, DEAD_LETTER, FAILED_AUTH, REFUSED, SUPPRESSED, CANCELLED |
| `category`, `templateName`, `text`, `blocksJson`, `variablesSnapshot` | Rendered at queue time. `text` is masked without `slack.message.body` |
| `idempotencyKey` | Unique when present, up to 500 characters |
| `parentMessageId`, `threadTs`, `targetMessageId` | Threads and updates |
| `attachmentStorageRef`, `attachmentName`, `attachmentBytes`, `slackFileId` | For FILE messages |
| `slackTs`, `permalink` | |
| `attemptCount`, `nextAttemptDate`, `leaseExpiresDate`, `lastErrorCode`, `lastError`, `sentDate`, `deadLetterDate` | `lastError` is scrubbed |
| `linkedTableName`, `linkedRecordId`, `senderName`, `deliveryLogId` | `deliveryLogId` links to core `delivery_log` (9.2) |

The child `slack_message_attempt` (`messageId` + `attemptNumber` unique; outcome, error code, HTTP status,
`retryAfterSeconds`, the posting identity BOT or USER, claimedBy, and dates) shows as a relation.

### 5.2 Claim, lease and exactly-once posting

This is the qbit-email and qbit-webhooks pattern.

- **Claim and lease.** A runner inserts (`messageId`, *n*) into `slack_message_attempt` to claim attempt *n*.
  The pair is unique, so one runner wins. The winner sets SENDING with a lease (`leaseSeconds`, default
  120).
- **Our own posts are recognisable.** Every post carries Slack message metadata: `event_type: qqq_message`,
  `event_payload: {id, key}`.
- **Expired leases.** Before retrying an expired SENDING row, the runner reads `conversations.history`
  (`include_all_metadata`, from the claim time) in that channel, using the same row's token.
  - If our id is there, the runner records that `ts` as SENT.
  - If history can't be read, it waits and retries the read. It doesn't post blind.
  - So a job retry or a dead pod never double-posts.
  - For files, the upload's `external_id`-style title marker plays the same role.
- **Dedupe at queue time.** A repeated idempotency key returns DUPLICATE. A `sendOnce` replay returns
  QUEUED_OR_DUPLICATE.

### 5.3 Retries, rate limits and health

| Slack response | Result |
|---|---|
| HTTP 429 | Wait `Retry-After` (capped at 15 minutes); it isn't an attempt. The workspace limiter pauses that method |
| HTTP 5xx, timeouts, network errors; `ratelimited`, `service_unavailable`, `internal_error`, `fatal_error`, `request_timeout` | Transient: RETRY_WAIT after `retryMinutes` (1, 5, 15, 60, 240, 720). DEAD_LETTER after `maxAttempts` (6) |
| `channel_not_found`, `is_archived`, `msg_too_long`, `invalid_blocks`, `no_text`, `user_not_found`, `cannot_dm_bot`, `user_disabled` | Permanent: DEAD_LETTER at once |
| `not_in_channel`, the owner no longer a member, `invalid_auth`, `token_revoked`, `account_inactive`, `missing_scope` | FAILED_AUTH: mark the connection or workspace, and notify the owner (4.1). Never retried with another token |

There's a client-side limiter per workspace row: at most one post per second per channel, and
`ratePerMinute` overall (default 50). Waiting under a limit isn't an attempt.

**Health:**

- After 10 consecutive transient failures a workspace is UNHEALTHY. While unhealthy, one message is tried
  every 5 minutes.
- Optional alerts go through the core `QAlerts` producer: `slack.workspace.unhealthy`,
  `slack.message.dead_letter` and `slack.delivery.unauthorized`. There's no dependency on qbit-alerts.

### 5.4 Record actions and retention

**Record actions** are standard streamed-ETL processes with Review and Result lines:

- **Resend** (DEAD_LETTER or FAILED_AUTH; re-authorized as the owner);
- **Cancel**;
- **Send Now**.

**Retention:** a daily job clears bodies and variables after `bodyRetentionDays` (30) once a message is
final. It deletes final rows after `messageRetentionDays` (0 means keep).

## 6. Channels, groups and templates

### 6.1 Live channels (pickers)

There's no channel table for user deliveries. Pickers and validation ask Slack, as the user:

- **The user token:** `users.conversations` (public and private channels the user is in);
- **Otherwise:** the bot's channel list filtered by `conversations.members` containing the user.

Results are cached for 5 minutes per connection, in memory only, with no disk. The picker shows
`#name (private)` with its workspace.

### 6.2 `slack_channel` (Registered Channels) and `slack_user_group` (User Groups)

These are tenant-scoped named targets for **system** and ownerless notices, for example monitors or
"#ops-alerts" for a tenant.

**`slack_channel`:**

- Fields: `name` (logical key, unique per workspace), `label`, `workspaceId`, `slackChannelId`,
  `slackChannelName`, `isPrivate`, `botIsMember`, `isArchived`, `enabled`, `description`, `checkedAt`.
- The row needs the workspace's bot.
- A tenant admin who is a member of the channel registers it. The picker offers only their channels.
- Record actions: **Check channel** and **Send test message**.
- An ownerless send must come from a system session. It is still tenant-checked: the signal's tenant key must
  equal the workspace's.

**`slack_user_group`:**

- Fields: `name`, `label`, `workspaceId`, `slackUsergroupId`, `handle`, `channelId`, `enabled`.
- A group target posts once, starting with `<!subteam^ID>`.
- There's no DM fan-out.

### 6.3 `slack_template` (Templates)

The variable model is shared with qbit-email:

- `{{name}}` and dotted paths;
- `{{#if}}` and `{{#each}}`;
- `{{name|fallback}}`;
- a declared variable schema (name, label, type, required, classification, and an optional bound table and
  field), with the same rules as qbit-email section 5.2.

The engine is the qbit's own, with no dependency on qbit-email internals (G1).

| Field | Notes |
|---|---|
| `name`, `label`, `description`, `category`, `enabled`, `tenantKey` (optional; null means shared) | |
| `text` | mrkdwn. Values are escaped (`&`, `<`, `>`), so variables can't inject mentions or links |
| `blocksTemplate` | Optional Block Kit JSON, with variables in string positions only. Validated on save: structure, at most 50 blocks, under 40,000 characters |
| `variablesSchema` | JSON |

The built-in templates are:

- `notification`: severity emoji, title, body, a "View" button and a context line;
- `digest`;
- `delivery`: title, summary, link or file, source and run time.

A **Preview** widget renders sample variables to Block Kit and sends nothing.

## 7. Users without a connection

A user with no ACTIVE connection gets no Slack messages. Their preferences show **Connect Slack**. There's
no email-based linking, because in a multi-tenant deployment an email match in somebody else's workspace
proves nothing (R2). For a single-tenant deployment, `allowEmailLinking(true)` lets the tenant bot link a
user with `users.lookupByEmail`, but only when the IdP marked the session email verified. It's off by default.

## 8. Readiness and health

| Check | Scope | How |
|---|---|---|
| `app` | Deployment | The client id and secret, signing secret and token key are set (presence only). The token key decodes to 32 bytes |
| `workspace:<id>` | Tenant | `auth.test` on the bot: ok, `team_id` matches, scopes include the required set (optional ones are WARN) |
| `connection:<id>` | User | `auth.test` on the user token, run lazily (on send failure, and daily) |
| `registeredChannels` | Tenant | Each enabled registered channel exists, isn't archived, and the bot is a member |
| `outbox` | Deployment | No DEAD_LETTER in the last hour, and the oldest QUEUED is younger than 10 minutes |

- **Where results show:** stored on the rows and shown on the home page. A tenant admin sees their tenant's
  results, and a user sees their own in Preferences.
- **Checks run:** every `readinessIntervalMinutes` (15), on the **Check readiness** action, and from
  `SlackApi.readiness*`. Messages never include token values or exception text.
- **Health:** when `qqq-middleware-health` is present, the qbit registers `HealthCheckMetaData` `slack`.
  - It reports the stored deployment-level readiness (`app` and `outbox`), without calling Slack on each
    probe.
  - Tenant workspace problems are details, not a pod-down signal.
  - The payload follows the health program's conventions; qbit-email's check is the pattern.

## 9. Output delivery (core SPI, Slack destination)

The core part is specified in QRun-IO/qqq `docs/specs/OUTPUT-DELIVERY.md` (its own PR). Summary:

### 9.1 Core (thin, generic)

- **`DeliveryDestinationInterface`**, in core package `delivery`:
  - `getType()`;
  - `describeTargets(context, chosenValues)`: the next contingent picker field with its options, as the
    current user;
  - `validate(request)`;
  - `deliver(request)`, returning `DeliveryResult`.
- **Metadata.** `QDeliveryDestinationMetaData` (name, label, icon, provider code reference,
  `maxClassification`, `maxAttachmentBytes`, `acceptsLinks`) is registered in the supplemental
  `DeliveryDestinationsMetaData`.
- **`DeliveryRequest`** carries:
  - `destinationName`, `targetValues`, `ownerUserId`;
  - `sourceType` (PROCESS / JOB / SCHEDULED_REPORT / SCHEDULED_JOB / EXPORT / SAVED_VIEW / ASSISTANT_TASK)
    and `sourceKey`;
  - `title`, `summary` (Markdown), `link`;
  - `attachments` (name, MIME type, size, storage table and reference; streamed, no filesystem);
  - `contentClassification` (the highest field classification in the payload) and `restrictedFields`
    (their labels);
  - `idempotencyKey`.
- **`DeliverOutputAction`** runs as the owner. It:
  1. checks permissions;
  2. refuses content above the destination's `maxClassification`, naming the fields;
  3. turns an over-size attachment into a link (an authenticated app link, or a presigned storage URL only
     for content below PII);
  4. writes `delivery_log`;
  5. calls the destination.
- **`delivery_log`** (core table, optional provider): one row per delivery and attempt, with owner, source,
  destination, a target summary (never secrets), status, attempts, external reference, error, and a unique
  idempotency key. Durable jobs deliver through the `jobs_effect` outbox, so it's exactly once.
- **`output_delivery`** (core table): saved delivery choices attached to a scheduled report, scheduled job
  or saved view. It's owner-locked.
- **Built-in destinations:** DOWNLOAD (today's behaviour), and EMAIL through core `SendMessageAction`.
  qbit-email can register a richer one.
- **UI API:** `GET /qqq/v1/delivery/destinations` and
  `POST /qqq/v1/delivery/destinations/{name}/targets` (contingent options).

### 9.2 Slack destination (this qbit)

`SlackDeliveryDestination`, name `slack`, `maxClassification` CONFIDENTIAL. That means PII and PHI are refused,
or left out (R7).

- **Picker:**
  1. **Workspace:** the current user's ACTIVE connections, tenant-filtered.
  2. **Where:** "Direct message to me", then the channels from 6.1.
- **Deliver:** queues a `slack_message` for the owner, with the `delivery` template.
  - Attachments use the files upload flow: `files.getUploadURLExternal`, a streamed PUT from storage, then
    `files.completeUploadExternal` into the channel or DM.
  - Over `maxAttachmentBytes` (default 50 MB), the attachment becomes a link (9.1).
  - The `delivery_log` id is stored on the message, and the message status maps back to the log row
    (QUEUED, then DELIVERED, FAILED or FAILED_AUTH).
- **Scheduled deliveries** (`output_delivery` rows) store the workspace row id and channel id, never a
  token. Each run re-authorizes as the owner (4.1, R4).

### 9.3 Exports and PHI (R7)

For an export or report delivered to Slack, the producer computes `contentClassification` from the
exported columns.

- **Above CONFIDENTIAL:** the user sees "This export includes restricted fields (Patient name, Date of birth).
  Slack can't receive them. Leave them out, or choose Download or Email."
- **"Leave out restricted columns"** is the option, saved on the delivery. Core re-renders the export without
  them.
- **Links:** a presigned URL is never used for PII or PHI. An authenticated app link is.

## 10. Unified "notify a user" routing

**Decision:** the one call stays core `QAlerts.notify(NotificationSignal)` with an audience and a
`NotificationCategory`. It's in core already (RC.6) and is the thin contract. **The fan-out lives in
qbit-alerts**, which owns the per-category preferences, recipients, quiet hours and digests. Building it in
core would create a second preference store.

| Piece | Where | Change |
|---|---|---|
| Producer contract | core `QAlerts.notify` | None |
| Transport | core `SendMessageAction` provider `slack`, address `user:<id>`, owner = recipient | No qbit-slack import (G1) |
| Preferences | `alerts_notification_preference` | Add `slackMode` (OFF / IMMEDIATE / DAILY_DIGEST, default OFF) and `slackMinSeverity` (default WARNING). SECURITY gets the title and link only |
| Quiet hours | `alerts_user_preference` | Apply to email and Slack alike. CRITICAL bypasses them |
| Digest | Digest job | Also one Slack DM per user at the digest hour (`digest` template) |
| Availability | qbit-alerts | The Slack column shows when `SendMessageAction.isAvailable("slack")`. For a user without a connection it reads "Connect Slack first" |
| Recipient state | `alerts_notification_recipient` | Add `slackStatus` and `slackMessageId` |
| Idempotency | qbit-alerts | `notify:<notificationId>:<userId>:slack` |

The DM goes through the recipient's own connection and their tenant's bot (4.1), so a user's notifications
never travel through another tenant's install.

qbit-slack's own Preferences section (core #1162) has:

- the list of Connections, read-only;
- the actions **Connect Slack**, **Disconnect** and **Send me a test message**;
- with `slack.workspace.install`, **Install for my organization**.

The section requires `slack.connect`, appears in the user menu, and rejects PATCH requests.
Actions are filtered per session; test messages also require `slack.send`. RC.7 has no action-level
permission property, so the section returns a filtered metadata view without mutating its shared
registrations. Connect and Install return their authorize URL in `UserPreferenceActionResult.message`;
RC.7 has no external-link or redirect result field, so this does not automatically navigate the client.

Without qbit-alerts, callers use `SendMessageAction` (`slack`, `me` or `user:<id>`) directly.

## 11. PHI and PII

Slack is not HIPAA-eligible on standard plans, so every Slack payload is non-PHI.

1. **Template variables** classified PHI or PII (declared, or from a bound field through core
   `ClassifiedValues`) are refused when the template is saved. At send time, such a value becomes its
   `ClassifiedValueTokens` token if the variable allows `tokenize`. Otherwise the send is REFUSED: "Variable
   x is classified PHI and can't be sent to Slack."
2. **Linked records** become a "View" link into the app, where normal permissions apply. Field values are
   never copied.
3. **Free text:** `SlackPayloadGuard` refuses values the caller marked classified, and `phiPatterns` matches
   (default SSN and MRN-style). It refuses rather than silently redacting.
4. **Exports and reports** follow 9.3. Attachments are checked through `contentClassification`, never by
   reading their bytes.
5. **SECURITY** category: title and link only.
6. **Storage:** `slack_message` is classified PII (owner, channel). Tokens are encrypted and HIDDEN.
7. **Canary test:** a fixture table has a PHI field holding a unique canary string. The test sends it
   through every path: template variable, linked record, alerts body, update, and export delivery. It
   asserts that the canary appears in no table column, no fake-Slack HTTP body or upload, and no log line.

## 12. Interactivity (phase 2)

- **Route:** `POST /qqq/hooks/slack/interactions`, verified with the signing secret.
  - `X-Slack-Signature` must equal `v0=` + HMAC-SHA256 over `v0:{ts}:{raw body}`.
  - The timestamp must be within 5 minutes, and the comparison is constant-time.
  - A failure gets 401 and is logged as rejected, without the body.
- **Buttons:** `SlackApiRequest.withAction(...)` names a registered `SlackActionMetaData`, which has:
  - `name` and `label`;
  - `style` and an optional `confirm` dialog;
  - `processName` and `permissionName`.

  The button value is an opaque token that the server signs over the message id, action, table, record id
  and expiry.
- **Execution:**
  1. Acknowledge within 3 seconds, then queue the work.
  2. Map (`team_id`, Slack `user.id`) to an ACTIVE `slack_connection` in a workspace row **of the message's
     workspace row**, then to the QQQ user. An unconnected user gets an ephemeral "Connect Slack in
     Preferences".
  3. Run the process under that user's automated session, through the normal process path, so permissions,
     tenant locks and record locks apply. Denied gets an ephemeral "You don't have permission to Approve".
  4. On success, `chat.update` (an UPDATE row) adds "Approved by Ada at 14:02".
- **Audit:** `slack_interaction` (Interactions) records workspace, Slack user, QQQ user, message, action,
  outcome (DONE / DENIED / FAILED / REJECTED / EXPIRED), error and dates. It's a child list on the message.
- **Built-in actions:** `acknowledge_alert` and `resolve_alert`, by process name. `approve` and `reject` are
  for apps to register.
- **Revocation events:** `tokens_revoked` and `app_uninstalled` arrive through the Events API at
  `/qqq/hooks/slack/events`.
- **Later:** slash commands and the App Home tab.

## 13. App and UI

The UI goes through the `/qqq/v1` UI API only.

- **App:** `slack`, labelled "Slack".
- **Home (the only dashboard):**
  - For a user: their connections and recent deliveries.
  - For a tenant admin: workspace readiness with fix hints, counts (queued, retrying, sent in 24 h,
    dead letters and auth failures in 24 h), and recent failures linked to their records.
- **Nav:** Messages, Connections, Workspaces, Registered Channels, User Groups, Templates.
- **Relations:** attempts and interactions on a message. Connections, channels and groups as child lists
  on the workspace.
- **Record actions** have plain labels and never appear in the nav:
  - Check readiness, Check channel and Send test message;
  - Disconnect and Revoke;
  - Resend, Cancel and Send Now.
- **Tables:** all are standard visible tables with record screens, the API (matching the admin), audit,
  ESB events, and Quick Search on Messages and Connections. They're secured by permissions, owner locks
  and tenant locks.
- **Layout:** readable at every width, light and dark.

## 14. Data model and configuration

### 14.1 Tables

| Table | Label | Lock | Nav |
|---|---|---|---|
| `slack_workspace` | Workspaces | Tenant | Yes |
| `slack_connection` | Connections | Owner (`userId`) and tenant through the workspace | Yes |
| `slack_channel` | Registered Channels | Tenant through the workspace | Yes |
| `slack_user_group` | User Groups | Tenant through the workspace | Yes |
| `slack_template` | Templates | Tenant (null means shared) | Yes |
| `slack_message` | Messages | Owner and tenant | Yes |
| `slack_message_attempt` | Message Attempts | Through the message | Relation |
| `slack_interaction` | Interactions | Through the message | Relation (phase 2) |

DDL is `docs/ddl/slack-postgres.sql`. kof22 applies it as a Liquibase changeset.

### 14.2 `SlackQBitConfig` settings

| Setting | Default |
|---|---|
| `backendName` | Required |
| `schedulerName` | Required |
| `bridgeProviderName` | `slack` |
| `destinationName` | `slack` |
| `secretResolver` | `System::getenv` |
| `allowedEnvNames` | The five names in 3.4 |
| `slackApiBaseUrl` | Overridden in tests |
| `publicBaseUrl` | |
| `oauthCallbackPath` | `/qqq/hooks/slack/oauth/callback` |
| `securityFields` | The tenant key field |
| `recordSecurityLocks` | |
| `tableMetaDataCustomizer` | |
| `dispatchIntervalSeconds` | 10 |
| `batchSize` | 50 |
| `maxAttempts` | 6 |
| `retryMinutes` | 1, 5, 15, 60, 240, 720 |
| `leaseSeconds` | 120 |
| `bodyRetentionDays` | 30 |
| `messageRetentionDays` | 0 |
| `readinessIntervalMinutes` | 15 |
| `membershipCacheMinutes` | 10 |
| `maxAttachmentBytes` | 50 MB |
| `allowEmailLinking` | false |
| `phiPatterns` | |
| `appName` | `slack` |
| `appLabel` | `Slack` |

## 15. Phases and acceptance

**Phase 1a: spec and scaffold (this PR).**

- The repo standard is met.
- `./mvnw verify` passes.

**Phase 1b: tables, cipher, outbox, dispatcher and provider.**

- Tables, owner and tenant locks, `SlackTokenCipher` and `SlackRedaction`.
- Queue-time and send-time authorization (4.1).
- Claim, lease and reconcile; 429, transient and permanent handling; FAILED_AUTH with the owner alert.
- The core provider under `slack`.
- Fake-Slack-server tests:
  - 429 with `Retry-After`;
  - the crash and reconcile case, which posts once;
  - duplicate keys;
  - a thread waiting for its parent;
  - an update keeping the same ts;
  - a revoked connection;
  - **two tenants and two workspaces** (4.1);
  - token non-leakage.

**Phase 1c: OAuth connect, `SlackApi`, readiness and health.**

- Connect, Disconnect and Install through the signed state and callback route.
- The API surface (4.3).
- Readiness checks against the fake server (good, missing scope, revoked, bot not in channel).
- Health registration.

**Phase 1d: templates, the PHI guard and the canary test.**

**Phase 1e: delivery destinations.**

- The core PR: SPI, `DeliverOutputAction`, `delivery_log`, `output_delivery`, the DOWNLOAD destination and
  the UI API endpoints.
- The qbit's `SlackDeliveryDestination`, with contingent pickers and file upload.
- The qbit side builds on a `STAND-IN(core=#N, removeBy=RC.N)` copy until core releases.
- Tests: picker isolation, a PHI export refused, then delivered with the restricted columns left out, and
  over-size becoming a link.

**Phase 1f: app, home and preferences section.**

- Built directly on the core #1162 preferences SPI (RC.7), with a class-availability guard for registration.
- Screenshots: home, a message and a connection, light and dark, at 390 and 1440 px.

**Phase 1g: qbit-alerts routing (section 10).** A separate qbit-alerts PR.

**Phase 1h: kof22 adoption.** A draft Website-Backend PR, plus the Praetor Infisical PR (section 17).

**Phase 2: interactivity and the Events API.**

- Tests:
  - a bad signature or a stale timestamp gives 401;
  - a forged value is refused;
  - an unconnected user is prompted;
  - a user without permission is DENIED;
  - a Slack user of another workspace row cannot act on this message;
  - Approve runs and updates the message.

## 16. Decisions (all recommendations accepted)

1. The qbit is a core messaging provider and a core delivery destination. Callers use only core.
2. Connections are per user, through OAuth. Bot installs are per tenant. Tokens are encrypted, write-only
   and HIDDEN, and the keys come from environment variables.
3. Every message has an owner. Authorization runs at queue time and again at send time. There is never a
   token fallback.
4. Membership is checked with `conversations.members` at send time, cached for 10 minutes.
5. There's no email-based linking by default. It's an opt-in for single-tenant deployments, with an
   IdP-verified email.
6. The outbox posts exactly once, using message-metadata reconciliation.
7. The template variable model is shared with qbit-email, with the qbit's own engine.
8. Routing goes through core `QAlerts.notify`, with the fan-out in qbit-alerts. This supersedes the
   2026-10-13 "never per user" decision.
9. Core output delivery is a thin SPI plus a log, owned by core, and specified in qqq.
10. No PHI or PII goes to Slack. Exports are blocked, or sent without the restricted columns. There's a
    canary test.
11. User groups are mentions in a registered channel.
12. kof22 adopts through a draft PR. The secrets are seeded write-only in Infisical.

## 17. Owner steps (outside code)

1. **Workspace.** Use the kof22 Slack workspace, or create one (slack.com/get-started). You need to be a
   workspace admin.
2. **App.**
   1. At api.slack.com/apps, choose **Create New App**, then **From a manifest**.
   2. Pick the workspace and paste `docs/slack-app-manifest.yaml`, after replacing `<host>` with
      `admin.kof22.com`.
   3. Create the app. Leave public distribution off for kof22, which is single-tenant.
3. **Credentials.** From **Basic Information**, copy the **Client ID**, **Client Secret** and **Signing
   Secret**. Generate the token key with `openssl rand -base64 32`. Don't paste any of them into chat, an
   issue or a commit. You don't need to copy a bot token: it's created when you click **Connect Slack**,
   then **Install for my organization**, in kof22 Preferences.
4. **Infisical.** A Praetor PR adds the folder `/kof22-website-slack` in the `k8s-galaxy` project, following
   the `kof22-webhooks.tf` pattern: write-only, ephemeral variables, `value_wo`. The keys are `client-id`,
   `client-secret`, `signing-secret` and `token-key`. Seed only those resources:

   ```bash
   TF_VAR_kof22_slack_client_id='…' TF_VAR_kof22_slack_client_secret='…' \
   TF_VAR_kof22_slack_signing_secret='…' TF_VAR_kof22_slack_token_key="$(openssl rand -base64 32)" \
     tofu apply -target=infisical_secret_folder.kof22_website_slack \
                -target=infisical_secret.kof22_slack_client_id \
                -target=infisical_secret.kof22_slack_client_secret \
                -target=infisical_secret.kof22_slack_signing_secret \
                -target=infisical_secret.kof22_slack_token_key
   ```

   The token key has no other copy. Losing it means every user reconnects.
5. **cluster-gitops.** An ExternalSecret maps the keys to `SLACK_CLIENT_ID`, `SLACK_CLIENT_SECRET`,
   `SLACK_SIGNING_SECRET` and `SLACK_TOKEN_KEY` in `kof22-website-prod`. The coordinator sequences it with
   the Website-Backend PR.
6. **First use.** In kof22, open Preferences, then Slack, then **Install for my organization**, and approve
   in Slack. Invite the app to any channel the bot should post in (`/invite @<app>`).
