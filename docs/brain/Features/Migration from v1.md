---
tags: [feature]
updated: 2026-10-25
---
# Migration from v1

> One-time import of v1 DataStore settings into rules and destinations.

## What the user can do
Nothing to do: on first v2 launch the v1 token, chats, SMS number, device name, master switch and channel flags become destinations, default rules and settings.

## Screens & entry points
No screen. `ForwarderApp.onCreate` launches `V1MigrationRunner.runOnce(graph)` on `appScope`.

## Data
Reads v1 DataStore `settings` (via the private `Context.dataStore` delegate in V1MigrationRunner.kt, the only one for "settings") keys `TELEGRAM_API_KEY`, `TELEGRAM_USER_IDS`, `SMS_TO_NUMBER`, `DEVICE_ALIAS`, `SMS_FORWARD_SERVICE`, `IS_BY_TELEGRAM_ENABLED`, `IS_BY_SMS_ENABLED`, `IS_SKIP_CONTACTS`. Writes Room destinations/rules and `settings_v2` (encrypted token, device name, masterOn, catchUpFloor=now, migrationDone).

## How it works
- Pure mapping in `core/V1Migration.plan(V1Settings?)`: chats "Chat 1..n" (TELEGRAM), SMS destination "SMS"; only destinations of channels whose v1 flag was on are assigned to the default rules; `skipContacts` gives NOT_IN_CONTACTS; blank alias gives `""`; `null` (no v1 keys) gives no destinations and master off.
- Runner: skip if `migrationDone`; DB step only if no rules exist (safe retry after partial run); then settings, delete `filesDir/cloud_upload_queue`, set `migrationDone` BEFORE the destructive cleanup (a crash after clearing v1 keys must not look like a fresh install), then delete `filesDir/cloud_upload_queue` and clear all v1 keys.
- `AppGraph.migrationGate` opens when the runner ends (success or failure); `V1MigrationRunner.awaitDone` is awaited by IncomingProcessor.process, ForwarderService startup pass and WatchdogWorker.
- A failed migration still opens the gate: nothing is forwarded (no rules gives NO_RULE) until the next launch retries.
- With rules present and no v1 keys only the flag is set, never fresh-install settings (`V1Migration.steps`).
- Failure: log class name only, flag unset, retried next launch.
- Tested on JVM only (pure mapping and steps); a v1 APK was not available for an on-device upgrade test.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- The v1 store file is not deleted (DataStore holds it open); all v1 keys are cleared except `STORED_UPDATE`, which `update/` still uses.
- The v1 UI and `UserPreferences` are gone, so nothing re-creates v1 keys.
- Fresh install: master off, `catchUpFloor=now` so old inbox is never forwarded.
