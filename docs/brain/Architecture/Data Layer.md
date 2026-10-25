---
tags: [architecture]
updated: 2026-10-25
---
# Data Layer

> Room database, settings DataStore ("settings_v2"), token cipher and repositories.

- **Room** `ForwarderDatabase` v1 (schema in `app/schemas`): `rules` (types as comma codes, destination ids as comma ids), `destinations`, `logs`, `outbox` (FK to logs, cascade delete).
- **Dedupe:** `logs.key` has a unique index; `LogRepository.insertIfNew` returns null for an SMS seen twice.
- **Log retention:** `prune(now)` deletes logs > 48 h (outbox rows cascade) and masks OTP rows > 1 h via `LogMasker` using the temporary `otpCode` column, which is nulled after masking. `logs.skip` stores `SkipReason.ordinal` (never reorder).
- **Outbox states:** 0 PENDING, 1 SENT, 2 FAILED. `due(now)` is oldest first; `failingSince(dest)` = oldest unresolved errored row newer than the destination's last success.
- **Counts (today):** forwarded = >= 1 SENT row; skipped = skip reason or no outbox rows; retrying = any PENDING row. Window starts at local midnight, no upper bound.
- **SettingsStore** (`settings_v2`, separate from v1 `settings`): master switch (default on), device name, encrypted token, PIN hash, alerts destination, catch-up floor, migration/OEM flags, `alertState` JSON (org.json helpers `parseAlertState`/`encodeAlertState`).
- **TokenCipher:** Keystore AES-256/GCM, alias `forwarder_token`, Base64(iv + ct); `decrypt` returns null on any failure.
- **AppGraph:** `by lazy` DI created by `ForwarderApp`; `AppGraph.from(context)`. `analyzer` loads keywords from assets, falls back to null lists.

See [[Architecture Overview]] · [[00 Index]]
