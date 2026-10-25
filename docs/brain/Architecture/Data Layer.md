---
tags: [architecture]
updated: 2026-10-25
---
# Data Layer

> Room database, settings DataStore ("settings_v2"), token cipher and repositories.

- **Room** `ForwarderDatabase` v1 (schema in `app/schemas`): `rules` (types as comma codes, destination ids as comma ids), `destinations`, `logs`, `outbox` (FK to logs, cascade delete).
- **Dedupe:** `logs.key` has a unique index; `LogRepository.insertIfNew` returns null for an SMS seen twice.
- **Log retention:** `prune(now)` deletes logs > 48 h (outbox rows cascade) and masks OTP rows > 1 h via `LogMasker` using the temporary `otpCode` column, which is nulled after masking. In the same transaction the masked log's still-PENDING outbox rows become FAILED "OTP expired (not sent within 1 hour)" (`core/OtpExpiry`, ruling 30), so masked text is never sent. Prune runs at the start of every `Dispatcher.sendDue` pass, on service start, on app open/resume (`ForwarderApp.ensureRunning`) and first in each watchdog run. `logs.skip` stores `SkipReason.code` (stored codes, never ordinals).
- **Clear log:** `LogViewModel.clear` holds `InboxCatchUp.lock`, moves `catchUpFloor` to now (`LogClearPolicy.floorAfterClear`; the deleted rows were the dedupe keys, so catch-up must not re-forward the inbox), then `LogDao.clear(floor)` deletes rows received before that floor except those with a PENDING send (rows arriving during the clear stay as dedupe keys) (`LogClearPolicy.toDelete`; deleting them would cascade-drop the queued send). Deletes are chunked (API 29 SQLite: 999 variables).
- **Resend:** `OutboxRepository.resend` refuses a row whose log is masked or an OTP older than 1 h (`OtpExpiry.mayResend`); the SQL also requires `masked = 0` as an atomic backstop.
- **Pre-check:** `LogRepository.exists(key)` lets `IncomingProcessor` skip an already-logged SMS before analysing it.
- **Outbox states:** 0 PENDING, 1 SENT, 2 FAILED. `markSent` / `markRetry` / `markFailed` apply only to a PENDING row (`AND state = 0`), so a late send outcome never revives or overwrites a row already FAILED ("OTP expired") or SENT. `due(now)` is oldest first; `failingSince(dest)` = oldest unresolved errored row newer than the destination's last success.
- **Counts (today):** each log lands in exactly one bucket via the same `deliveryState` as the Log chip: Forwarded, Skipped, or "Not sent" (Retrying + Failed) on the Home tiles. Window starts at local midnight.
- **SettingsStore** (`settings_v2`, separate from v1 `settings`): master switch (default on), device name, encrypted token, PIN hash, alerts destination, catch-up floor, migration/OEM flags, `alertState` JSON (org.json helpers `parseAlertState`/`encodeAlertState`). A corrupt file is replaced (`ReplaceFileCorruptionHandler`) with `migration_done = true`, `master_on = false` (paused so the owner notices); the v1 `settings` store reads as empty when corrupt.
- **Backup / transfer:** `allowBackup=false`; `data_extraction_rules.xml` also excludes `forwarder.db*`, `datastore/` and shared prefs from cloud backup and Android 12+ device-to-device transfer.
- **Destinations:** `DestinationRepository.save` of the FIRST destination also attaches it to default rules that have none and enables OTPs / Bank transactions / Missed calls (`DefaultRules.attachFirstDestination`), in one transaction.
- **TokenCipher:** Keystore AES-256/GCM, alias `forwarder_token`, Base64(iv + ct); `decrypt` returns null on any failure.
- **AppGraph:** `by lazy` DI created by `ForwarderApp`; `AppGraph.from(context)`. `analyzer` loads keywords from assets, falls back to null lists.
- **Testing gaps:** DAO SQL (counts, prune, failingSince, resend) has no JVM tests (no Robolectric). `IncomingProcessor` inserts a log row and its outbox rows in one transaction.
- **Stored codes:** `SkipReason.code`, `SenderKind.code`, `MessageType.code` are pinned by tests. OTP masking also covers `extracted` and the log's outbox `text`; if the code is not literally in the text it falls back to 4-8 digit runs.

See [[Architecture Overview]] · [[00 Index]]
