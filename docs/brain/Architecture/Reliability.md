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
- **HealthChecker**: SMS (3 perms), contacts, battery unrestricted, OEM auto-start (manual confirm), classifier loaded (`AppGraph.classifierLoaded`), plus an informational (grey, `HealthItem.informational`, not counted in Home "Needs attention") status-notification item. Status notification is optional: `ForwarderService` always calls `startForeground` (allowed without POST_NOTIFICATIONS on 33+; a denied permission or disabled channel just hides the notice and is never a failure); channel `forwarder_status` is created with IMPORTANCE_MIN on new installs only (channels are immutable; existing installs keep the user setting) by `ForwarderService.ensureChannel`, which `HealthChecker.statusNotificationIntents` also calls before opening the channel page (so it is never empty). No NOTIFICATIONS problem alert; the retired `notif` key is dropped silently from saved alert state (`ProblemAlerts.dropRetired`). Pure helper: `core/StatusNotification`.
- **OemSettings**: pure per-brand guide tables (`detectBrandKey`, `guide`, `OemGuide`/`OemStep` = string-resource id + `OemTarget` list; JVM-tested) for Samsung, realme/oppo/oneplus, vivo/iqoo, xiaomi/redmi/poco, other; `intents(targets)` builds vendor Intents and always ends with App info; `openFirst` tries each. Step text per brand in [[UI Screens]].

## Gotchas
- Offline retry, swipe-away and reboot behaviour has JVM coverage for the decisions only; the on-device smoke test is still pending (see [[Known Issues]]).
- `am broadcast BOOT_COMPLETED` is denied from adb shell; verify with an emulator reboot.
- MainActivity no longer schedules the v1 hourly alarm; it calls `ForwarderService.start` when master is on. `V1MigrationRunner` cancels v1's leftover `ServiceRestartReceiver` alarms once (cleanup step).

See [[Problem Alerts]] · [[Architecture Overview]] · [[00 Index]]
