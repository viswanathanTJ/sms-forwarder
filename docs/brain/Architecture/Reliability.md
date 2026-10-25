---
tags: [architecture]
updated: 2026-10-25
---
# Reliability

> Foreground service, boot receiver, watchdog worker, health checks, OEM settings.

## Pieces (`service/`)
- **ForwarderService**: foreground, type `specialUse` (manifest property subtype + `FOREGROUND_SERVICE_SPECIAL_USE`; `ServiceCompat.startForeground` passes the type on API 34+). Channel `forwarder_status` (low), text "Forwarding SMS · tap to open", `START_STICKY`, `onTaskRemoved` restarts itself. No timers or wake locks. On start: `WatchdogWorker.ensure`, then `prune`, `InboxCatchUp` (skipped when started by the watchdog: `start(context, catchUp = false)`), `Dispatcher.sendDue` and `ProblemAlerter.check` on `appScope`. `start(context)` never throws: a refused start (`ForegroundServiceStartNotAllowedException`) falls back to the watchdog.
- **BootReceiver**: `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` -> watchdog ensure + service start (if master on); boot also sends a `REBOOTED` info alert.
- **WatchdogWorker**: unique periodic `"watchdog"`, 15 min, `KEEP`, **no network constraint** (catch-up works offline; Dispatcher can mark >24 h rows FAILED). Each step is isolated: start service (without its own catch-up), `prune` (first, so expired OTP sends are failed before dispatch), catch-up, sendDue, `Dispatcher.schedule`, `ProblemAlerter.check`.
- **HealthChecker**: SMS (3 perms), contacts, notifications, battery unrestricted, OEM auto-start (manual confirm), classifier loaded (`AppGraph.classifierLoaded`).
- **OemSettings**: intents per vendor (Samsung, realme/oppo/oneplus, xiaomi, vivo/iqoo) + app-details fallback; caller tries each until one resolves.

## Gotchas
- Offline retry, swipe-away and reboot behaviour has JVM coverage for the decisions only; the on-device smoke test is still pending (see [[Known Issues]]).
- `am broadcast BOOT_COMPLETED` is denied from adb shell; verify with an emulator reboot.
- MainActivity no longer schedules the v1 hourly alarm; it calls `ForwarderService.start` when master is on. `V1MigrationRunner` cancels v1's leftover `ServiceRestartReceiver` alarms once (cleanup step).

See [[Problem Alerts]] · [[Architecture Overview]] · [[00 Index]]
