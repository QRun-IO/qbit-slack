# QBit Slack

Slack for a QQQ system. Users connect their own Slack accounts, and organization admins install the
app for their own workspace. The qbit registers a core messaging provider (`slack`) and a delivery
destination, so any process, scheduled job, report or export can send to a Slack DM or channel through
core alone. Every message is authorized as its owning user at send time, so the qbit is safe for
deployments with many tenants. Delivery uses a durable outbox with Slack rate-limit handling, and
classified PHI and PII never reach Slack.

Design: [docs/DESIGN.md](docs/DESIGN.md).

## Requirements

- Java 21
- QQQ 4.1 (currently built against 4.1.0-RC.6)
- A Slack app created from [docs/slack-app-manifest.yaml](docs/slack-app-manifest.yaml)

## Install

```java
new SlackQBitProducer()
   .withQBitConfig(new SlackQBitConfig()
      .withBackendName("rdbms")
      .withSchedulerName("scheduler"))
   .produce(qInstance);
```

## Tables, permissions and configuration

See [docs/DESIGN.md](docs/DESIGN.md), sections 3 to 14. The tables are prefixed `slack_` and the
permissions `slack.`. Secrets are environment-variable names only: `SLACK_CLIENT_ID`,
`SLACK_CLIENT_SECRET`, `SLACK_SIGNING_SECRET` and `SLACK_TOKEN_KEY`.

## Setup and starter data

No Liquibase changelog or host include is needed: the qbit seeds its own data at startup. The three
built-in templates (`notification`, `digest`, `delivery`) are inserted by `SlackTemplates.seed()`
when the host starts and on each readiness run. It is insert-only by name, so edits survive.

Everything else is host or runtime data, and every table explains itself when empty (help bundle
`help/qbit-slack.yaml`):

1. An administrator runs "Install for my organization" (Connections). This creates the workspace.
2. Each user runs "Connect Slack" so messages go out as them.
3. Register the channels and user groups processes post to (Registered Channels, User Groups), then
   run "Check channels". It records the Slack name, private, bot is member, archived, purpose and
   checked time. Readiness refreshes them on a schedule.
4. Review the built-in Templates.

Messages, Attempts and Interactions are logs and start empty.

## Adopting in another app

Send with core `SendMessageAction` and the provider name `slack`, or deliver output to the `slack`
destination. Don't import this qbit's internal packages; `com.kingsrook.qbits.slack.api` is the only
public surface.

## Build

```bash
./mvnw verify
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
