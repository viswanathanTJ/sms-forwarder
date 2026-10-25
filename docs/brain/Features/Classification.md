---
tags: [feature]
updated: 2026-10-25
---
# Classification

> Ported classifier and transaction parser, plus the pure analyzer and missed-call detector (Tasks 2-4).

## What the user can do
Nothing directly; this is the engine that decides a message's type (OTP, transaction, promotional, ...) so rules can match on it.

## Screens & entry points
No UI. Entry points: `MessageClassifier(lists).classify(ClassifierInput(sender, body, isKnown))`, `TransactionParser(lists).parse(address, body)`, `SenderIds.parse(raw)`, `KeywordLists.load(AssetKeywordSource(context))`.

## Data
`app/src/main/assets/keywords.json` (copied from the SMS app) holds all keyword lists. `MessageCategory` (with `CategoryCode`) and `FraudLevel` (with `FraudCode`) live in `classify/`.

## How it works
Ported from the SMS app (2026-10-25 snapshot) into `classify/` and `finance/`; package `com.viswa2k.sms.` rewritten to `com.viswa2k.smsforwarder.`, each file starts with `// Ported from SMS app: <path>`. Pure Kotlin, JUnit tested (57 tests, same as the SMS app for the copied classes).

Excluded (need the SMS app's Room/prefs): `finance/TransactionRecordBuilder.kt`, `finance/OtpPaymentChecker.kt`, `finance/FinancePass.kt` (depends on the builder), `classify/OtpPolicy.kt` (needs `OtpDigitSize`). Their tests were skipped too (`OtpPolicyTest`, `AmountCapTest`, `FinancePassTest`, `TransactionRecordBuilderTest`, `InMemoryFinanceStore`). No kept file referenced them.

### Syncing future fixes from the SMS app
Diff each file against `../SMS/app/src/main/java/com/viswa2k/sms/{classify,finance}` ignoring the header line and package/import rewrite (`MessageCategory`/`FraudLevel` moved from `data.db` to `classify`). Re-copy changed files with the same rewrite, refresh `keywords.json`, run `./gradlew testDebugUnitTest`, and bump the snapshot date in the header.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- `TestKeywords` finds `keywords.json` via `src/main/assets/...` relative to the `app/` test working dir; keep the asset path.
- Do not edit ported files for forwarder needs; wrap them in `core/` instead so syncing stays a plain copy.
