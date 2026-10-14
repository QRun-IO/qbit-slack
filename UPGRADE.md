# Upgrade notes

## Unreleased

- No schema change and no Liquibase include. Hosts need do nothing.
- Registered channels now show real status. After upgrading, run "Check channels" on Registered
  Channels, or wait for the next readiness run, to fill `slackChannelName`, `isPrivate`,
  `botIsMember`, `isArchived`, `description` and `checkedAt`. `slackChannelName` is now set from
  Slack, so the label in the outbox follows the channel's current Slack name.
- A user group's `handle` is filled during readiness when the Slack app has the optional
  `usergroups:read` scope; mentions then read `<!subteam^ID|@handle>`.
