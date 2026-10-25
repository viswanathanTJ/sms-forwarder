---
tags: [log]
updated: 2026-10-25
---
# Log

> Dated work log. Newest first. See [[00 Index]].

## 2026-10-25 - Task 1: toolchain upgrade and cloud removal
- Upgraded to Kotlin 2.2.0, AGP 8.13.2, Gradle 8.13, KSP 2.3.4, SDK 36; versions inline, `libs.versions.toml` deleted. Added Room, WorkManager, Navigation, icons-extended; kept kotlinx-serialization for the updater.
- Removed Firebase, `cloud/` (crypto, data, fcm, ui), v1 settings UI, `firebase/`, the debug cleartext override, Tink/biometric/credentials dependencies and Example tests. Moved `cloud/update` to `update/`.
- `MainActivity` now shows `PlaceholderScreen`. Created this vault (see [[Architecture Overview]]).

## 2026-10-25 - Task 2: classifier and transaction parser port
- Copied SMS app `classify/` + `finance/` (minus OtpPolicy, OtpPaymentChecker, TransactionRecordBuilder, FinancePass), `MessageCategory`, `FraudLevel`, `keywords.json`, `AssetKeywordSource`, and 57 ported tests. See [[Classification]].

## 2026-10-25 - Task 3: missed-call detector
- Added core/MissedCallDetector (cue regexes + business-sender gate); see [[Classification]].
- Review fix: number now anchored to the cue, instruction phrasings and 1800/1860 rejected, spaces only in 2-5-5 / 5-5 groupings.
- 2026-10-25 Task 4: MessageType + MessageAnalyzer (type mapping in Features/Classification).
- 2026-10-25 Task 5: Rule model, RuleEngine, PhoneNumbers, DefaultRules (see [[Rules]]).
- 2026-10-25 Task 6: MessageFormatter (smart/original/custom, Telegram HTML + SMS), senderName title-casing (see [[Formatting]]).
- 2026-10-25 Task 7: Room storage, SettingsStore, TokenCipher, repositories, AppGraph (see [[Data Layer]]).
- 2026-10-25 Task 8: Telegram/SMS senders, RetryPolicy, Dispatcher + DeliveryWorker (see [[Delivery & Retries]]).
