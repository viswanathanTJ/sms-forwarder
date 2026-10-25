---
tags: [architecture]
updated: 2026-10-25
---
# Testing

> JVM-only JUnit tests mirroring the package layout; no Robolectric, no androidTest. Each task adds its tests here.

## Layout
Tests mirror the main package under `app/src/test/java/com/viswa2k/smsforwarder/` (JUnit 4.13.2 + org.json; no Robolectric, no androidTest). 286 tests pass with `./gradlew testDebugUnitTest`.

| Package | Test classes | Tests | Covers |
|---|---|---|---|
| `core` | 20 | 182 | analyzer, missed-call detector, rule engine, formatter, phone numbers, retry policy, delivery order, Telegram response parsing, problem alerts, log masking, message key / dedupe, catch-up window, v1 migration, default rules, SMS assembler, export, PIN lock and back-off, message check, stored enum codes |
| `classify` | 5 | 38 | ported classifier, OTP extractor, sender ids, folding |
| `finance` | 3 | 17 | ported transaction parser stages |
| `ui/rules`, `ui/log` | 2 | 29 | rule editor reducer, log filters and delivery state |
| `delivery`, `data/db`, `update` | 3 | 18 | Telegram client parsing, converters, updater |

(Per-package numbers count `@Test` annotations and add up to 284; the runner reports 286.)

## Not covered (no Robolectric)
DAO SQL (counts, prune, failingSince, resend), receivers, services, WorkManager workers, Compose screens and the Keystore cipher. The on-device smoke test (emulator, debug inject hook) was NOT run for v2.0.0 because the shared emulator was occupied by another app; see [[Known Issues]].

## Smoke test
`adb shell "am broadcast -a com.viswa2k.smsforwarder.debug.INJECT_SMS -n com.viswa2k.smsforwarder/.debug.InjectSmsReceiver --es from VM-HDFCBK --es body '...'"` injects an SMS through `IncomingProcessor` (debug builds only).

Pinned gate before each commit: `./gradlew testDebugUnitTest assembleDebug`; release: `assembleRelease` (R8).

See [[Architecture Overview]] · [[00 Index]]
