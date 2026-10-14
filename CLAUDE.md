# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Project Overview

**QBit Slack**: Slack for a QQQ system, with per-user and per-tenant connections, a core messaging
provider and delivery destination, a durable outbox, templates, readiness and PHI-safe payloads.
Design: `docs/DESIGN.md`.

## Build Commands

```bash
./mvnw compile          # always use the wrapper: Maven 3.10 cannot read the parent POM
./mvnw test
./mvnw verify           # tests + Checkstyle + guardrails + JaCoCo gate (70% instructions, 90% classes)
```

## Architecture

- **Entry point:** `SlackQBitProducer implements QBitMetaDataProducer<SlackQBitConfig>`, package `com.kingsrook.qbits.slack`.
- `api` is the only public package (mirrors qbit-email's `EmailApi`). Everything else is internal.

## Invariants

- Every message has an owner (a QQQ user) and is authorized as that owner when queued and again when sent.
- A message only ever uses its own workspace row's or connection row's token. There is no fallback.
- Tokens are encrypted, write-only and HIDDEN; they never reach a log, an exception, an audit or an API.
- Every send goes through the outbox (`slack_message`); nothing posts inline.
- Classified PHI and PII never enter a Slack payload.

## Naming (owner rule)

- Tables are lowercase snake_case, prefixed `slack_`; the backend name is the table name.
- Permissions use the full qbit name: `slack.view`, `slack.connect`, `slack.send`, `slack.admin`.
- Data lives in standard visible tables. Processes are table or record actions, never in the nav.
- UI goes through the `/qqq/v1` UI API only; Slack callbacks are under `/qqq/hooks/slack/...`.

## Code Style

3-space indent, braces on the next line, `return (x);`, flower-box Javadoc on every class and method, fluent `withX()` setters.

## Release

- CircleCI qqq-orb 0.6.9.
- `develop` publishes SNAPSHOTs.
- `release/*` publishes RCs (automated by `.github/workflows/release-rc.yml`).
- `vX.Y.Z` tags publish releases (GA tags only).
- Never push `hotfix/*` or `release/*` branches by hand; use `feature/` or `fix/`.
