---
tags: [architecture]
updated: 2026-10-25
---
# Architecture Overview

> SMS Forwarder v2 (`com.viswa2k.smsforwarder`, 2.0.0): rule-based forwarding of classified SMS to Telegram and SMS. Pure-Kotlin core holds every decision; a thin Android layer wires it to broadcasts, Room, DataStore, WorkManager and HTTP. This is the **target** structure; Task 1 only removed cloud sync and upgraded the toolchain.

## Layers
- **core/** (pure, JUnit-tested): MessageType, MissedCallDetector, MessageAnalyzer, Rule/RuleEngine, MessageFormatter, PhoneNumbers, RetryPolicy, TelegramResponse, ProblemAlerts, LogMasker, MessageKey, CatchUpWindow, V1Migration, DefaultRules, IncomingSmsAssembler. See [[Rules]], [[Formatting]], [[Delivery & Retries]], [[Migration from v1]].
- **classify/** and **finance/** (ported from the SMS app): classifier, OTP extractor, transaction parser. See [[Classification]].
- **data/db**, **data/prefs**, **data/repo**: Room, `SettingsStore` (DataStore "settings_v2"), `TokenCipher`, repositories. See [[Data Layer]].
- **delivery/**: TelegramClient, SmsSender, Dispatcher, DeliveryWorker. See [[Delivery & Retries]].
- **pipeline/**: SmsReceiver, IncomingProcessor, ContactLookup, InboxCatchUp. See [[Incoming Pipeline]].
- **service/**: ForwarderService, BootReceiver, WatchdogWorker, HealthChecker, OemSettings, ProblemAlerter. See [[Reliability]], [[Problem Alerts]].
- **update/**: GitHub-releases updater (UpdateChecker, UpdateInstaller, UpdateManager, UpdateDialog). Already in place (moved from `cloud/update` in Task 1).
- **ui/**: theme, nav, home, rules, log, settings, onboarding, common. See [[UI Screens]].
- `ForwarderApp` creates `AppGraph`, a manual lazy DI container (db, settings, cipher, analyzer, clients, repos, appScope).

## Current state (after Task 1)
- Kotlin 2.2.0, AGP 8.13.2, Gradle 8.13, KSP 2.3.4, compile/target SDK 36, min SDK 29. Versions are inline in `app/build.gradle.kts` (no version catalog).
- Firebase and all cloud sync code are removed. Network is only `api.telegram.org` and the GitHub releases API.
- The v1 files `MainActivity`, `SMSReceiver`, `SMSForwarderService`, `ServiceRestartReceiver`, `UserPreferences` remain until later tasks replace them. `MainActivity` shows `ui/PlaceholderScreen` and keeps the single `Context.dataStore` ("settings") that [[Migration from v1]] will read.

Back to [[00 Index]].
