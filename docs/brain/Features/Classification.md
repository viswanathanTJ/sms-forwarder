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

## Missed calls
`core/MissedCallDetector.detect(sender, body): MissedCall?` returns `MissedCall(caller, time, count)` for carrier missed-call alerts (Airtel, Jio, Vi, BSNL).
- Fires only when `SenderIds.parse(sender).isBusiness` (header or short code); a friend's "missed your call" from a phone number is never a match.
- The number must be anchored to the cue via `PATTERNS`: `missed call(s) [alert] from <num>`, `(missed) call alert: <num>`, `<num> tried to call / tried calling you`, `<num> called you` (only if the body also says `missed call`/`call alert`). Number = `+91 99620 55555`, `99620 55555` or 7-13 digits (no `-`, so dates are skipped; spaces only in those groupings). Never the sender's own number, never 1800/1860 toll-free. Instruction texts (`give a missed call on ...`, `miss call to ...`) are rejected.
- `caller` = first such token with spaces removed; `time` = first `h:mm` / `hh:mm AM|PM` as written; `count` = `N missed calls`, default 1.
- Adding a carrier format: add a `Pattern` (anchored regex, group 1 = caller number via `$NUM`) to `PATTERNS` in `MissedCallDetector.kt` (and widen `NUM`/`TIME` only if the number or time looks different), then add a real sample to `MissedCallDetectorTest`. Samples can be pasted via the Test tool later.

## Message types (Task 4)
`core/MessageAnalyzer(lists?).analyze(sender, body, senderKind): Analysis` maps the classifier output to `MessageType` (codes stored, never renumber). Order: missed call (detector) first, then the table below. `lists == null` (asset failed): missed call, else business -> Other important, phone -> Personal, `classifierAvailable = false`.

| MessageType | Source |
|---|---|
| Missed call (1) | `MissedCallDetector` |
| Spam (9) | `FraudLevel.SCAM` or `inSpamStore` (PROMOTIONAL, BLOCKED_DELETE); checked before OTP so scam OTPs are never forwarded |
| OTP (2) | `isOtp` (OTP, OTP_TRANSACTION, OTP_VARIANT); a payment OTP is OTP only, no txn parse |
| Transaction (3) | TRANSACTION with parsed amount > 0, else Other important |
| Balance alert (4) | BALANCE_ALERT (txn parsed for balance) |
| Bills & dues (5) | SMART_ALERT |
| Delivery (6) | DELIVERY |
| Personal (8) | phone-number sender, none of the above |
| Other important (7) | anything else from a business sender |

`senderName` = `BankSenderResolver` name when bank/brand resolved, else `SenderInfo.canonicalId`. `SenderKind.CONTACT` feeds `isKnown`.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- `TestKeywords` finds `keywords.json` via `src/main/assets/...` relative to the `app/` test working dir; keep the asset path.
- Do not edit ported files for forwarder needs; wrap them in `core/` instead so syncing stays a plain copy.
