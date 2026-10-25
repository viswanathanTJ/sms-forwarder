# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

SMS Forwarder v2 (`com.viswa2k.smsforwarder`, versionName 2.0.0 / versionCode 20). Android app (Kotlin 2.2.0, Jetpack Compose, minSdk 29, compile/target SDK 36) that classifies each incoming SMS (OTP, Transaction, Balance alert, Bills, Delivery, Missed call, Other important, Personal, Spam) and forwards it according to **rules** to **destinations**: Telegram chats (Bot API, optional forum topic) and/or SMS numbers. Built for phones that run unattended 24x7: delivery queue with retries, Log, Health checks and Telegram problem alerts. No Firebase, no cloud sync; network only to `api.telegram.org` and the GitHub releases API (updater).

Spec: `docs/superpowers/specs/2026-10-25-forwarder-revamp-design.md`. Old cloud spec/plans (`2026-06-20-*`) are history only.

## Build & Test

```bash
./gradlew testDebugUnitTest assembleDebug   # the per-change gate (JVM tests + debug APK)
./gradlew assembleRelease                   # R8 + proguard-rules.pro (unsigned locally unless signing env is set)
./gradlew installDebug                      # install on the emulator
./gradlew testDebugUnitTest --tests 'com.viswa2k.smsforwarder.core.*'   # focused tests
```

Version defaults live in `app/build.gradle.kts` (overridable with `-PVERSION_NAME` / `-PVERSION_CODE`). `gradle.properties` pins `org.gradle.java.home=/opt/android-studio/jbr`.

Debug-only SMS inject hook (feeds `IncomingProcessor` exactly like a real SMS; lives in `app/src/debug`):

```bash
adb -s 127.0.0.1:6562 shell "am broadcast -a com.viswa2k.smsforwarder.debug.INJECT_SMS -n com.viswa2k.smsforwarder/.debug.InjectSmsReceiver --es from VM-HDFCBK --es body 'Rs 1250.00 debited from A/c XX4521 ... UPI Swiggy'"
```

## Architecture

Pure-Kotlin `core/` holds every decision (classify, match, format, retry, migrate) and is JUnit-tested; a thin Android layer wires broadcasts, Room, DataStore, WorkManager and HTTP to it. Manual DI through `AppGraph` (created in `ForwarderApp`), no Hilt/Koin.

Flow: `SmsReceiver` (manifest, `goAsync`) -> `IncomingSmsAssembler` joins PDUs -> `IncomingProcessor` (analyze -> `RuleEngine` -> one Room transaction writing the Log row + outbox rows) -> `Dispatcher` sends each outbox row (Telegram / SMS) -> failures retry through a unique `DeliveryWorker` (30 s doubling, max 1 h, give up after 24 h = FAILED). `ForwarderService` (foreground, `specialUse`) + `WatchdogWorker` (15 min) + `InboxCatchUp` (24 h window) keep it alive; `ProblemAlerter` sends problem alerts to Telegram.

| Package (`app/src/main/java/com/viswa2k/smsforwarder/`) | Contents |
|---|---|
| `core/` | PURE logic: MessageType, MissedCallDetector, MessageAnalyzer, Rule/RuleEngine, MessageFormatter, PhoneNumbers, RetryPolicy, DeliveryOrder, TelegramResponse, ProblemAlerts, LogMasker, MessageKey, CatchUpWindow, V1Migration, DefaultRules, IncomingSmsAssembler, SettingsExport, PinLock, PinBackoff, MessageCheck |
| `classify/`, `finance/` | Ported from the SMS app (first-line "Ported from SMS app" comment): MessageClassifier, OtpExtractor, TransactionParser, BankSenderResolver; assets `keywords.json` |
| `data/db`, `data/prefs`, `data/repo` | Room (rules, destinations, log, outbox), `V1MigrationRunner`, `SettingsStore` (DataStore `settings_v2`), `TokenCipher` (Keystore AES-GCM), repositories |
| `delivery/` | TelegramClient, SmsSender, Dispatcher, DeliveryWorker |
| `pipeline/` | SmsReceiver, IncomingProcessor, ContactLookup, InboxCatchUp |
| `service/` | ForwarderService, BootReceiver, WatchdogWorker, HealthChecker, OemSettings, ProblemAlerter |
| `update/` | GitHub releases updater (UpdateChecker/Installer/Manager/Dialog) |
| `ui/` | theme, nav, home, rules, log, settings, onboarding, common (4 tabs: Home, Rules, Log, Settings) |
| `app/src/debug/.../debug/` | `InjectSmsReceiver` (adb hook) |

Tests mirror packages under `app/src/test` (JVM only: JUnit 4 + org.json; no Robolectric, no androidTest).

## Conventions & gotchas

- Decisions belong in pure `core/` classes with tests; Android classes stay thin.
- Enums persisted in Room/DataStore use stored string **codes**, never ordinals.
- No Firebase, Google services or cloud code. Keep `allowBackup=false`, `usesCleartextTraffic=false`, `network_security_config`.
- Bot token rules: never logged, never in exceptions or the Log (bot-token URLs redacted), stored encrypted, excluded from export unless the user ticks the box.
- Telegram text is `parse_mode=HTML`, all SMS/user text escaped, hard cap 4096 chars; blank text falls back to the original body.
- Log retention 48 h; OTP codes masked 1 h after receipt (pending OTP rows older than 1 h are failed, not sent masked).
- The v1 DataStore `"settings"` delegate exists only in `V1MigrationRunner.kt` (two delegates for one file crash DataStore).
- Foreground service type is `specialUse` (Android 15 forbids `dataSync` from boot). No heartbeat messages: problem alerts only.
- One Gradle invocation at a time (low-RAM machine). Git: `git -c safe.directory=$PWD ...` (NTFS); never push.
- Devices: use ONLY the emulator `127.0.0.1:6562`. Never touch `adb-RZCX51XRGKZ...` (owner's real phone) or other devices.
- Brain vault: `docs/brain/` is an Obsidian vault (start at `00 Index.md`). Update the relevant feature note and add a dated line to `Log.md` for every behaviour change; deferred minors go in `Known Issues.md`.

## Hard rules

- Never forward Spam/scams; never add network endpoints beyond Telegram and GitHub releases.
- Never log or display the bot token; never commit secrets or `local.properties`.
- Keep package/applicationId `com.viswa2k.smsforwarder` and the signing config so v2 installs over v1.
- Gate before every commit: `./gradlew testDebugUnitTest assembleDebug`.

---

# context-mode — MANDATORY routing rules

You have context-mode MCP tools available. These rules are NOT optional — they protect your context window from flooding. A single unrouted command can dump 56 KB into context and waste the entire session.

## BLOCKED commands — do NOT attempt these

### curl / wget — BLOCKED
Any Bash command containing `curl` or `wget` is intercepted and replaced with an error message. Do NOT retry.
Instead use:
- `ctx_fetch_and_index(url, source)` to fetch and index web pages
- `ctx_execute(language: "javascript", code: "const r = await fetch(...)")` to run HTTP calls in sandbox

### Inline HTTP — BLOCKED
Any Bash command containing `fetch('http`, `requests.get(`, `requests.post(`, `http.get(`, or `http.request(` is intercepted and replaced with an error message. Do NOT retry with Bash.
Instead use:
- `ctx_execute(language, code)` to run HTTP calls in sandbox — only stdout enters context

### WebFetch — BLOCKED
WebFetch calls are denied entirely. The URL is extracted and you are told to use `ctx_fetch_and_index` instead.
Instead use:
- `ctx_fetch_and_index(url, source)` then `ctx_search(queries)` to query the indexed content

## REDIRECTED tools — use sandbox equivalents

### Bash (>20 lines output)
Bash is ONLY for: `git`, `mkdir`, `rm`, `mv`, `cd`, `ls`, `npm install`, `pip install`, and other short-output commands.
For everything else, use:
- `ctx_batch_execute(commands, queries)` — run multiple commands + search in ONE call
- `ctx_execute(language: "shell", code: "...")` — run in sandbox, only stdout enters context

### Read (for analysis)
If you are reading a file to **Edit** it → Read is correct (Edit needs content in context).
If you are reading to **analyze, explore, or summarize** → use `ctx_execute_file(path, language, code)` instead. Only your printed summary enters context. The raw file content stays in the sandbox.

### Grep (large results)
Grep results can flood context. Use `ctx_execute(language: "shell", code: "grep ...")` to run searches in sandbox. Only your printed summary enters context.

## Tool selection hierarchy

1. **GATHER**: `ctx_batch_execute(commands, queries)` — Primary tool. Runs all commands, auto-indexes output, returns search results. ONE call replaces 30+ individual calls.
2. **FOLLOW-UP**: `ctx_search(queries: ["q1", "q2", ...])` — Query indexed content. Pass ALL questions as array in ONE call.
3. **PROCESSING**: `ctx_execute(language, code)` | `ctx_execute_file(path, language, code)` — Sandbox execution. Only stdout enters context.
4. **WEB**: `ctx_fetch_and_index(url, source)` then `ctx_search(queries)` — Fetch, chunk, index, query. Raw HTML never enters context.
5. **INDEX**: `ctx_index(content, source)` — Store content in FTS5 knowledge base for later search.

## Subagent routing

When spawning subagents (Agent/Task tool), the routing block is automatically injected into their prompt. Bash-type subagents are upgraded to general-purpose so they have access to MCP tools. You do NOT need to manually instruct subagents about context-mode.

## Output constraints

- Keep responses under 500 words.
- Write artifacts (code, configs, PRDs) to FILES — never return them as inline text. Return only: file path + 1-line description.
- When indexing content, use descriptive source labels so others can `ctx_search(source: "label")` later.

## ctx commands

| Command | Action |
|---------|--------|
| `ctx stats` | Call the `ctx_stats` MCP tool and display the full output verbatim |
| `ctx doctor` | Call the `ctx_doctor` MCP tool, run the returned shell command, display as checklist |
| `ctx upgrade` | Call the `ctx_upgrade` MCP tool, run the returned shell command, display as checklist |
