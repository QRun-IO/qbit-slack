# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Help bundle `help/qbit-slack.yaml` (stand-in for qqq#1198): every table and the app say what they are for and how rows get created, so an empty table explains itself.
- "Check channels" action on Registered Channels; readiness now writes the channel's Slack name, private, bot is member, archived, purpose and checked time back to the row.
- User group `handle` is resolved from `usergroups.list` during readiness (optional `usergroups:read` scope) and used in the mention (`<!subteam^ID|@handle>`).
- `SlackTablesGuardrailTest`: every table is used at runtime, seeded or listed as starting empty, and has help.
- Design (`docs/DESIGN.md`) and the Slack app manifest (`docs/slack-app-manifest.yaml`).
- Repository scaffold per the QRun qbit repository standard: `SlackQBitProducer` and `SlackQBitConfig`.
