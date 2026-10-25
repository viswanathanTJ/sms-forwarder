---
tags: [feature]
updated: 2026-10-25
---
# UI Screens

> Four-tab UI: Home, Rules, Log, Settings, plus onboarding (Tasks 12-15). Task 12 delivers the shell, theme, navigation and Home; Settings is a title-only stub (`ui/nav/StubScreens.kt`) until Task 15. Task 14 adds the Log screen. Task 13 adds Rules and the rule editor (`ui/rules/`, see [[Rules]]).

## What the user can do
- Home: switch forwarding on/off (hero switch), see today's Forwarded / Skipped / Not sent (Retrying + Failed), toggle each rule, open a rule, see the 3 latest messages, jump to Settings when something needs attention.
- With no destinations, a "Set up Telegram or SMS" card points to Settings.

- Log: filter chips All / Forwarded / Skipped / Failed-retrying, up to 200 newest entries, tap for a detail sheet (full body, detected line, per-destination status + error, **Resend** for FAILED sends), "Clear log" (confirm dialog).

## Screens & entry points
- `MainActivity` (edge-to-edge) -> `AppNavHost`: bottom bar Home, Rules, Log, Settings. Routes in `ui/nav/Routes.kt`: `home`, `rules`, `rules/{id}` (0 = new), `log`, `settings`, `onboarding` (not registered yet).
- Rules (`ui/rules/RulesScreen`) and editor (`RuleEditorScreen`; HTML preview rendered by `telegramHtmlToAnnotated`): see [[Rules]].
- Home (`ui/home/HomeScreen`): gradient hero (#2F5BEA to #5B3FE0), stats row, Rules card (type badge, "types · sender -> destinations", switch; tap opens `rules/{id}`), Recent card (sender, time, body line, type chip, status chip, note).
- Shared: `ui/common/TypeBadge`, `TypeChip`, `StatusChip(DeliveryState)`, `SectionTitle`.

- Log (`ui/log/`): `LogScreen`, `LogViewModel` (`logRows(limit)` shared with Home), `LogDetailSheet`, `LogRow` (shared row composable, also used by Home Recent), `LogLogic` (pure: `deliveryState`, `LogFilter.matches`, `noteLine`, `toRowData`; tested in `LogLogicTest`). Note line: "RuleName → Dest1, Dest2" for sent rows, else skip text / "Matched, nothing sent". Masked rows show the stored (masked) body. Resend: `OutboxRepository.resend` then `Dispatcher.schedule`, on `appScope`, one-shot per outbox id; clear also on `appScope`. Clear log is not behind the PIN.

## Data
- `HomeViewModel` (factory over `AppGraph`) combines `SettingsStore.masterOn/deviceName`, `LogRepository.todayCounts/recent(3)`, `RuleRepository.rules`, `DestinationRepository.destinations`. Health comes from `HealthChecker.items()` (suspend), refreshed in `viewModelScope` on every resume.
- Status of a log row (`deliveryState`, the one function behind Log chips AND Home tiles, so each row is in exactly one bucket): skip set or no sends -> Skipped; any pending send -> Retrying; any failed -> Failed; else Forwarded. Home tiles: Forwarded / Skipped / Not sent (= Retrying + Failed), computed from today's rows via `homeCounts` (no separate SQL count). Resend is hidden for masked rows (body no longer holds the code). Rows from earlier days show "Yesterday 10:41" / "24 Oct 10:41".

## How it works
- Master switch ON: `catchUpFloor = now`, `masterOn = true`, `ForwarderService.start`. OFF: `masterOn = false`, `ForwarderService.stop`. Both go through `requireUnlock { }` (defined in `AppNavHost`, currently just runs the action) so Task 15 can add PIN gating in one place; rule switches use the same hook.
- "Needs attention: N to fix" shows in the hero when any health item is not ok and links to Settings.
- Theme (`ui/theme`): M3 light/dark schemes from the mock plus `ForwarderTheme.extra` (type, ok/warn tones, Telegram blue). `values-night/themes.xml` removes the white flash in dark mode. Strings live in `strings_shell.xml` and `strings_home.xml`.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- A fresh install (no v1 data) starts with forwarding paused (migration plan gives masterOn false).
- Permission and battery prompts were removed from `MainActivity`; they return in onboarding (Task 15). Until then grant permissions via Settings/adb.
- The "Auto-start allowed" health item stays red until the user confirms it, so Home shows "needs attention" until Task 15 adds that confirmation.
