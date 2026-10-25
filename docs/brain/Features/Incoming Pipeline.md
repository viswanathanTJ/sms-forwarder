---
tags: [feature]
updated: 2026-10-25
---
# Incoming Pipeline

> SMS receiver, PDU assembler, dedupe key, inbox catch-up scan; WhatsApp notification source.

## What the user can do
Nothing to operate: every incoming SMS is deduped, classified, matched against rules and queued for delivery automatically, even after a swipe from Recents or a reboot.

## Screens & entry points
- `pipeline/SmsReceiver` (manifest, `BROADCAST_SMS`, priority 999): `goAsync()`, assemble PDUs, process on `graph.appScope`, `finish()` in `finally`.
- `pipeline/InboxCatchUp.run(now)`: gap filler, called by later tasks (boot, service start, watchdog, app open).
- Debug only (`app/src/debug`): `InjectSmsReceiver`, `adb shell am broadcast -a com.viswa2k.smsforwarder.debug.INJECT_SMS -n com.viswa2k.smsforwarder/.debug.InjectSmsReceiver --es from VM-HDFCBK --es body '...'` (on-device shell needs the body quoted inside one `shell "..."` string).

## Data
- `LogEntity.key` = `MessageKey.of(sender, sentAt, body)` = `normalizedSender|sentAt/1000|body.trim().hashCode()`; unique index = dedupe.
- `type`, `senderKind`, `skip` stored as `.code`.
- `IncomingSmsAssembler` joins consecutive PDUs of one sender.

## How it works
1. `IncomingProcessor.process`: cheap pre-check `LogRepository.exists(key)` (and the loose-key check for inbox rows without `date_sent`) -> stop if already logged; otherwise master switch -> `ContactLookup.kind` -> `analyzer.analyze` -> `RuleEngine.decide` -> ONE Room transaction inserts the log row (unique key = the authority; a race loser writes nothing) and all outbox rows -> `Dispatcher.sendDue` inline on the app scope (it reschedules the worker).
2. Never throws: failures log the exception class only (no Log row is written, so catch-up can retry).
3. `InboxCatchUp`: one scan at a time (`InboxCatchUp.lock`, also held by Log "Clear"). `catchUpFloor == 0` means first run (set floor = now, process nothing). Otherwise reads inbox since `CatchUpWindow.since(now, floor)` = max(now-24h, floor), ascending, `sentAt` = `date_sent` (fallback `date`). Thanks to the pre-check an already-logged inbox costs one indexed lookup per message.
4. Triggers: service start (app open/resume, boot, START_STICKY restart) and the watchdog. The watchdog starts the service with `catchUp = false` and runs its own scan, so one scan per watchdog run.

## WhatsApp source
Spec: `docs/superpowers/specs/2026-10-25-whatsapp-source-design.md`.
- `pipeline/WhatsAppNotificationListener` (NotificationListenerService, `BIND_NOTIFICATION_LISTENER_SERVICE`): `onNotificationPosted` only. Package allow-list (`NotificationText.PACKAGES`) checked before anything is read; fast return when the cached `AppGraph.whatsApp` StateFlow says off; then on `appScope`: await the migration gate, re-check the toggle (cached prefs waited for at most 10 s; a DataStore read error reads as off), skip group summaries / ongoing / `CATEGORY_CALL`, read `NotificationCompat.MessagingStyle` / extras into plain fields and pick messages with pure `NotificationText.select` (own messages = no person, the style user's key, or a person named "You" (case-insensitive) skipped; group -> conversationTitle + member; 1:1 -> person name else title; fallback `EXTRA_BIG_TEXT`/`EXTRA_TEXT` under `EXTRA_TITLE`), filter with `NotificationText.accept` (blank, housekeeping incl. call notices, deletions, media placeholders and reactions (`Reacted 🙇 to "…"`, `Reacted 👍 to a message` / `to your photo`, `👍 reacted to your message`), older than 10 min before post, before `waFloor`) and call `IncomingProcessor.process` with `IncomingSms(origin = WHATSAPP)`. Never logs text. `onListenerDisconnected` -> `requestRebind`.
- Identity (`core/NotificationText`): address `WA:<chat>` / `WA:<group> · <person>`; titles cleaned (bidi marks, " (N messages)" counter) so the key is stable. `MessageKey` normalises WA addresses by trimmed lower-cased title. Sender kind: `ContactLookup.kindForName(chatOf(address))` (Contacts DISPLAY_NAME, case-insensitive) -> CONTACT / UNKNOWN_NUMBER / UNVERIFIED.
- `MessageAnalyzer.analyze(..., origin)`: WA senders take the business path, `senderName` = chat; WA + CONTACT + IMPORTANT -> PERSONAL. Formatter shows "WhatsApp · <chat>" (`%s` = "WhatsApp: <chat>").
- `LogEntity.origin` (0 SMS, 1 WhatsApp). Toggle on sets `waFloor = now` (`SettingsStore.setWhatsAppEnabled`); off keeps it. No catch-up for WhatsApp.
- Source type filter: `core/SourceFilter.allows(origin, type, waTypes)` runs BEFORE `RuleEngine.decide` in `IncomingProcessor` (and `MessageCheck`): WhatsApp passes only types in `SettingsStore.whatsAppTypes` (codes in a string set, default {OTP}, never empty, never SPAM); SMS always passes. Blocked -> `SkipReason.SOURCE_FILTERED` (5). `SourceFilter.shouldRecord(origin, decision)`: a WhatsApp decision with no sends (filtered, spam, no rule, contacts missing, paused, matched-but-nothing-sent) is dropped silently in `IncomingProcessor` right after the `logs.exists` pre-check and before the transaction: no Log row, no outbox rows, not remembered (a MessagingStyle re-post is just re-ignored); SMS always records. `setWhatsAppTypes` bumps `waFloor = now` when the selection changes so pre-change history never forwards. Test-a-message shows `CheckResult.ignored` as "Ignored (not logged)". `SOURCE_FILTERED` and its Log note `WhatsApp: only <types> forwarded` remain only for old rows. Settings > Sources chips edit it; Export/Import carry `whatsAppEnabled` + `whatsAppTypes` (missing -> off / OTP).
- Debug: `InjectSmsReceiver ... --es origin whatsapp --es from Amazon --es body '...'`.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- Whoever turns the master switch on must set `catchUpFloor = now` (UI task), or paused-time messages could be caught up. Log "Clear" also moves the floor to now (the cleared rows were the dedupe keys).
- Dedupe window is seconds: two identical texts from one sender in the same second collapse into one.
- Catch-up needs READ_SMS; without it the scan is a no-op.
- Log "Clear" moves `waFloor` to now together with `catchUpFloor` (`SettingsStore.setFloorsAfterClear`, one edit; the toggle is untouched): the cleared rows were the WhatsApp dedupe keys too, and MessagingStyle re-posts history up to 10 min old.
- WhatsApp: a message posted while the listener is unbound is lost (no history source); previews must be on; MessagingStyle re-posts history, which the 10-min / floor rule plus the unique key absorb.
