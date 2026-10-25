---
tags: [feature]
updated: 2026-10-25
---
# Problem Alerts

> Health checks and Telegram problem alerts.

## What the user can do
Gets a Telegram message when forwarding is at risk, and a "resolved" message when fixed. Chooses the alerts destination in Settings (Settings > Telegram > Alerts destination).

## Screens & entry points
`HealthChecker.items()` feeds the Settings Health screen and Home warnings. Alerts run from `WatchdogWorker` every 15 min.

## Data
`SettingsStore.alertState` JSON: problem key -> last alert time. `alertsDestinationId`, `deviceName`.

## How it works
`ProblemAlerts.plan(state, current, now)` (pure, tested): raise when new or last sent >= 24 h ago; resolve when in state but no longer current. Problems: sms, contacts, notif, battery, classifier, `paused` ("Forwarding is switched off" while the master switch is off; other alerts continue while paused), `notif_access` ("WhatsApp notification access was removed", only while the WhatsApp source is on; `HealthChecker.problems()` is now suspend), and `dest:<id>` when `OutboxRepository.failingSince` is over 1 h (text includes label and last error). `ProblemAlerter` sends `⚠ <device>: <text>`, `✅ <device>: resolved — <text>`, `ℹ <device>: <text>` straight through `TelegramClient` (not rules/outbox), HTML-escaped. A failed send leaves the key out of state so it retries next run.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]] · [[Reliability]]

## Gotchas
- Target = alerts destination, else first Telegram destination (ruling 14); none -> skipped silently, state untouched.
- `check()` runs under a process-wide `Mutex` (state read inside it): boot, service start and watchdog can call it at once without sending an alert twice.
- Resolved text for a destination that is gone falls back to a generic line.
