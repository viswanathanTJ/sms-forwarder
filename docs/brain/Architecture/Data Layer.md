---
tags: [architecture]
updated: 2026-10-25
---
# Data Layer

> Room database, settings DataStore ("settings_v2"), token cipher and repositories.

- **Room** `ForwarderDatabase` v1 (schema in `app/schemas`): `rules` (types as comma codes, destination ids as comma ids), `destinations`, `logs`, `outbox` (FK to logs, cascade delete).
- **Dedupe:** `logs.key` has a unique index; `LogRepository.insertIfNew` returns null for an SMS seen twice.
- **Log retention:** `prune(now)` deletes logs > 48 h (outbox rows cascade) and masks OTP rows > 1 h via `LogMasker` using the temporary `otpCode` column, which is nulled after masking. `logs.skip` stores `SkipReason.code` (stored codes, never ordinals).
- **Outbox states:** 0 PENDING, 1 SENT, 2 FAILED. `due(now)` is oldest first; `failingSince(dest)` = oldest unresolved errored row newer than the destination's last success.
- **Counts (today):** each log lands in exactly one bucket via the same `deliveryState` as the Log chip: Forwarded, Skipped, or "Not sent" (Retrying + Failed) on the Home tiles. Window starts at local midnight.
- **SettingsStore** (`settings_v2`, separate from v1 `settings`): master switch (default on), device name, encrypted token, PIN hash, alerts destination, catch-up floor, migration/OEM flags, `alertState` JSON (org.json helpers `parseAlertState`/`encodeAlertState`).
- **TokenCipher:** Keystore AES-256/GCM, alias `forwarder_token`, Base64(iv + ct); `decrypt` returns null on any failure.
- **AppGraph:** `by lazy` DI created by `ForwarderApp`; `AppGraph.from(context)`. `analyzer` loads keywords from assets, falls back to null lists.
- **Testing gaps:** DAO SQL (counts, prune, failingSince, resend) has no JVM tests (no Robolectric). `IncomingProcessor` inserts a log row and its outbox rows in one transaction.
- **Stored codes:** `SkipReason.code`, `SenderKind.code`, `MessageType.code` are pinned by tests. OTP masking also covers `extracted` and the log's outbox `text`; if the code is not literally in the text it falls back to 4-8 digit runs.

See [[Architecture Overview]] · [[00 Index]]
