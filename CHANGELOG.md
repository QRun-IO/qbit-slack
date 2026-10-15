# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Complete host-neutral in-app help bundle with live labels and core `HelpBundleAssertions` coverage; registered through QQQ 4.1.0-RC.7 `HelpBundles`, replacing the temporary loader.
- "Check channels" action on Registered Channels; readiness now writes the channel's Slack name, private, bot is member, archived, purpose and checked time back to the row.
- User group `handle` is resolved from `usergroups.list` during readiness (optional `usergroups:read` scope) and used in the mention (`<!subteam^ID|@handle>`).
- `SlackTablesGuardrailTest`: every table is used at runtime, seeded or listed as starting empty, and has help.
- Design (`docs/DESIGN.md`) and the Slack app manifest (`docs/slack-app-manifest.yaml`).
- Repository scaffold per the QRun qbit repository standard: `SlackQBitProducer` and `SlackQBitConfig`.
