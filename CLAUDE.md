# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Android app (`com.viswa2k.smsforwarder`) that forwards incoming SMS to another phone number (via SMS) and/or one or more Telegram chats (via the Telegram Bot API). Single-module Kotlin + Jetpack Compose app. minSdk 29, targetSdk/compileSdk 34.

## Build & Test

```bash
./gradlew assembleDebug          # build debug APK
./gradlew assembleRelease        # build minified/shrunk release APK (R8 + proguard-rules.pro)
./gradlew installDebug           # build + install on connected device/emulator
./gradlew test                   # JVM unit tests (app/src/test)
./gradlew testDebugUnitTest --tests "com.viswa2k.smsforwarder.ExampleUnitTest"  # single test
./gradlew connectedAndroidTest   # instrumented tests (app/src/androidTest, needs device)
./gradlew lint                   # Android lint
```

There is no CI config and only the template example tests exist — the test suite is effectively empty.

## Architecture

The app has no DI framework, no Room, no networking library. State flows through three pieces:

- **`UserPreferences`** — the single source of truth, wrapping a DataStore Preferences store named `"settings"`. Exposes every setting as a `Flow` getter and a `suspend` saver. The store is created via the `Context.dataStore` extension property declared in `MainActivity.kt` (line ~30) — `SmsReceiver` reaches it through that same extension. `initializeDefaults()` seeds all keys on first launch.
- **`SmsForwarderService`** — a `START_STICKY` foreground service (`foregroundServiceType=dataSync`) that registers/unregisters `SmsReceiver` at runtime for the `android.provider.Telephony.SMS_RECEIVED` broadcast. It self-heals: `onTaskRemoved`/`onStartCommand` schedule an exact AlarmManager restart with exponential backoff (5 min → 4 h, retry count in the `service_prefs` SharedPreferences), and `MainActivity` also requests battery-optimization exemption and sets an hourly restart alarm.
- **`SmsReceiver`** — the actual forwarding logic. On each SMS it reads preferences, optionally skips senders found in Contacts (`isSkipContacts` + `READ_CONTACTS` query), then forwards via `SmsManager.sendTextMessage` and/or an HTTP POST to `https://api.telegram.org/bot<key>/sendMessage` (raw `HttpURLConnection`, `parse_mode=HTML`, comma-separated chat IDs).

`ServiceRestartReceiver` restarts the service on `BOOT_COMPLETED` and the custom `<applicationId>.RESTART_SERVICE` action.

UI is one Compose screen: `SettingsScreen` → `SettingsViewModel` (via `SettingsViewModelFactory`). The ViewModel mirrors every preference into `MutableStateFlow`s and writes them all back in `saveSettings()`. Toggling the service on/off only takes effect when `MainActivity.checkAndStartService()` runs (on next `onCreate`), which starts or stops `SmsForwarderService` based on `isSmsForwarderService`.

### Message format tokens

Forwarded message text is built by token replacement in `SmsReceiver.sendSms`/`sendTelegramMessage`:
- `%s` → original sender number
- `%m` → original message body
- `%r` → device alias (`deviceAlias`, defaults to `Build.MANUFACTURER Build.MODEL`)

There is a global format plus per-channel (SMS / Telegram) overrides; a blank channel format falls back to the global format, and a blank global format falls back to `%m`. Telegram additionally converts literal `\n` to real newlines.

## Conventions & gotchas

- **Filename vs. class name mismatch**: `SMSForwarderService.kt` defines class `SmsForwarderService`; `SMSReceiver.kt` defines `SmsReceiver`. The manifest and code reference the `Sms...` class names — match those, not the filenames.
- **Package vs. directory mismatch**: `SettingsScreen.kt` lives in `ui/screen/` but declares `package com.viswa2k.smsforwarder.ui` (the ViewModel files in the same dir use `...ui.screen`). Keep existing package declarations when editing.
- Secrets (Telegram bot token, target number) live unencrypted in DataStore. `allowBackup=false` and `usesCleartextTraffic=false` are set deliberately — preserve them.
- Version catalog is `gradle/libs.versions.toml`; DataStore and lifecycle-viewmodel-compose are declared as raw coordinate strings in `app/build.gradle.kts` rather than the catalog.

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
