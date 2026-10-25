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
