---
tags: [moc]
updated: 2026-10-25
---
# SMS Forwarder v2 - Index

> Map of content for **SMS Forwarder** (`com.viswa2k.smsforwarder`, v2.0.0, versionCode 20000). Rule-based forwarding of classified SMS to Telegram chats and SMS numbers, built to run unattended 24x7. Open `docs/brain/` as an Obsidian vault. Spec: `docs/superpowers/specs/2026-10-25-forwarder-revamp-design.md`.

## At a glance
- Kotlin 2.2.0, Compose + Material 3, Room, DataStore, WorkManager; minSdk 29, compile/target SDK 36.
- Pure `core/` decides (classify, match, format, retry, migrate); thin Android layer wires it. Manual DI via `AppGraph`.
- Network only to `api.telegram.org` and GitHub releases. No Firebase, no cloud. Bot token encrypted with Keystore AES-GCM.
- 286 JVM tests (see [[Testing]]); debug inject hook for emulator checks.
- Reliability: manifest receiver, `specialUse` foreground service, 15-min watchdog, 24 h catch-up, Telegram problem alerts.

**How to use:** follow the `[[links]]`. Feature notes share the sections *What the user can do*, *Screens & entry points*, *Data*, *How it works*, *Connections*, *Gotchas*.

## Feature catalogue
| Feature | Note | What it does |
|---|---|---|
| Classification | [[Classification]] | OTP, Transaction, Balance, Bills, Delivery, Missed call, Other important, Personal, Spam |
| Rules | [[Rules]] | Types x sender x destinations x style; union of matches; loop guard |
| Formatting | [[Formatting]] | Smart / Original / Custom for Telegram HTML and SMS |
| Delivery & retries | [[Delivery & Retries]] | Outbox, Telegram/SMS senders, backoff 30 s to 1 h, fail after 24 h |
| Incoming pipeline | [[Incoming Pipeline]] | Receiver, PDU joining, dedupe, catch-up scan, inject hook |
| Problem alerts | [[Problem Alerts]] | Telegram alerts: permissions, battery, failing destination, paused, reboot |
| Migration from v1 | [[Migration from v1]] | Token, chats, number, alias, master switch to rules and destinations |
| UI screens | [[UI Screens]] | Home, Rules, Log, Settings, Health, Test a message, onboarding, PIN, export/import |

## Architecture
[[Architecture Overview]] · [[Data Layer]] · [[Reliability]] · [[Testing]]

## Cross-cutting
- [[Known Issues]]
- [[Log]]
