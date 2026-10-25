---
tags: [feature]
updated: 2026-10-25
---
# Incoming Pipeline

> SMS receiver, PDU assembler, dedupe key, inbox catch-up scan.

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

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- Whoever turns the master switch on must set `catchUpFloor = now` (UI task), or paused-time messages could be caught up. Log "Clear" also moves the floor to now (the cleared rows were the dedupe keys).
- Dedupe window is seconds: two identical texts from one sender in the same second collapse into one.
- Catch-up needs READ_SMS; without it the scan is a no-op.
