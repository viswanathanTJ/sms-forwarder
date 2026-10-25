---
tags: [feature]
updated: 2026-10-25
---
# UI Screens

> Four-tab UI: Home, Rules, Log, Settings, plus onboarding (Tasks 12-15). Task 12: shell, theme, navigation, Home. Task 13: Rules + editor (`ui/rules/`, see [[Rules]]). Task 14: Log. Task 15: Settings, Test a message, Health, onboarding, PIN, export/import, update check.

## What the user can do
- Home: switch forwarding on/off (hero switch), see today's Forwarded / Skipped / Not sent (Retrying + Failed), toggle each rule (a rule without destinations cannot be switched on), a "No rule has a destination" warning in the hero (opens Rules) when forwarding is on but no enabled rule has a destination, open a rule, see the 3 latest messages, jump to Settings when something needs attention.
- With no destinations, a "Set up Telegram or SMS" card points to Settings.

- Settings > **Sources**: SMS (always on) and a WhatsApp switch with Notification access status (Allowed / Not allowed + **Allow** opening the system screen; switching on without access opens it). Test a message has a Source chip pair (SMS / WhatsApp; WhatsApp takes a chat name). Log rows and the detail sheet show a "WhatsApp" chip and the chat name for WhatsApp rows. Health adds "WhatsApp notification access" while the source is on.
- Settings: Telegram bot token (saved encrypted; never shown again), chats with Test / delete (confirm), **Add chat** with Detect (`getUpdates`) or manual chat id + optional topic id, problem-alerts chat picker; SMS numbers with Test / delete and a SIM picker on multi-SIM phones; **Test a message**; Health checklist with Fix; device name; PIN lock (set / change / remove); Export / Import JSON; version + Check for update.
- Onboarding: permissions (Grant) -> battery unrestricted -> brand auto-start guide (brand chips, numbered steps with Open buttons, Done) -> Ready ("Start forwarding" / "Not now", or "Continue" when already on).
- Log: filter chips All / Forwarded / Skipped / Failed-retrying, up to 200 newest entries, tap for a detail sheet (full body, detected line, per-destination status + error, **Resend** for FAILED sends), "Clear log" (confirm dialog).

## Screens & entry points
- `MainActivity` (edge-to-edge) -> `AppNavHost`: bottom bar Home, Rules, Log, Settings. Routes in `ui/nav/Routes.kt`: `home`, `rules`, `rules/{id}` (0 = new), `log`, `settings`, `onboarding`. Start route is decided once per activity (off Main): `onboarding` when `onboardingDone` is false or SMS / contacts permission is missing (notifications are optional), else `home`. Tabs pop up to `home`.
- Rules (`ui/rules/RulesScreen`) and editor (`RuleEditorScreen`; HTML preview rendered by `telegramHtmlToAnnotated`): see [[Rules]].
- Home (`ui/home/HomeScreen`): gradient hero (#2F5BEA to #5B3FE0), stats row, Rules card (type badge, "types · sender -> destinations", switch; tap opens `rules/{id}`), Recent card (sender, time, body line, type chip, status chip, note).
- Settings (`ui/settings/`): `SettingsScreen` (scroll column of section cards, toasts from `SettingsViewModel.messages`), `TelegramSection`, `SmsSection`, `TestMessageSection`, `HealthSection` (`HealthList`, `OemItem`, `openFirst` also used by onboarding), `DeviceSection` (device name, PIN, `AboutSection`), `PinDialog` (`AppLock`, `PinEntryDialog`, `SetPinDialog`, `LockedPane`), `ExportImport`.
- Onboarding (`ui/onboarding/OnboardingScreen` + `OnboardingViewModel`, `needsOnboarding(graph)`).
- Shared: `ui/common/TypeBadge`, `TypeChip`, `StatusChip(DeliveryState)`, `SectionTitle`.

- Log (`ui/log/`): `LogScreen`, `LogViewModel` (`logRows(limit)` shared with Home), `LogDetailSheet`, `LogRow` (shared row composable, also used by Home Recent), `LogLogic` (pure: `deliveryState`, `LogFilter.matches`, `noteLine`, `toRowData`; tested in `LogLogicTest`). Note line: "RuleName → Dest1, Dest2" for sent rows, else skip text / "Matched, nothing sent". Masked rows show the stored (masked) body. Resend: `OutboxRepository.resend` then `Dispatcher.schedule`, on `appScope`, one-shot per outbox id (refused for masked / expired OTPs). Clear (on `appScope`, under `InboxCatchUp.lock`): catch-up floor and WhatsApp floor -> now (one edit), then delete rows received before it except those with a still-queued send (see [[Data Layer]]). Clear log is not behind the PIN.

## Data
- `HomeViewModel` (factory over `AppGraph`) combines `SettingsStore.masterOn/deviceName`, `LogRepository.todayCounts/recent(3)`, `RuleRepository.rules`, `DestinationRepository.destinations`. Health comes from `HealthChecker.items()` (suspend), refreshed in `viewModelScope` on every resume.
- Status of a log row (`deliveryState`, the one function behind Log chips AND Home tiles, so each row is in exactly one bucket): skip set or no sends -> Skipped; any pending send -> Retrying; any failed -> Failed; else Forwarded. Home tiles: Forwarded / Skipped / Not sent (= Retrying + Failed), computed from today's rows via `homeCounts` (no separate SQL count). Resend is hidden for masked rows (body no longer holds the code). Rows from earlier days show "Yesterday 10:41" / "24 Oct 10:41".

## How it works
- Master switch ON: `catchUpFloor = now`, `masterOn = true`, `ForwarderService.start`. OFF: `masterOn = false`, `ForwarderService.stop`. One path, `Forwarding.set(graph, on)` (in `HomeViewModel.kt`), used by Home and onboarding's "Start forwarding".
- PIN: `core/PinLock` stores `hex(salt):hex(SHA-256(salt + PIN))` (16-byte random salt per PIN) in `SettingsStore.pinHash`; 4-6 ASCII digits; verify uses a constant-time compare. Wrong-PIN back-off (`core/PinBackoff`, persisted in `SettingsStore.pinAttempts`): after 5 wrong tries entry locks for 30 s, doubling per further miss up to 5 min; a correct PIN or setting/removing the PIN resets it. `PinEntryDialog` reads the record and back-off itself. `requireUnlock { }` in `AppNavHost` guards the master switch, rule switches, rule save/delete/move and the Settings tab (a `LockedPane` replaces Settings while locked). While the PIN setting is still loading, guarded taps are ignored. One correct PIN unlocks (`AppLock.unlocked`) until the process goes to the background (`ProcessLifecycleOwner` ON_STOP). Opening the app's own file picker / Fix screens calls `AppLock.keepForExternalScreen()` so a stop within 5 s does not re-lock. Change / Remove need the current PIN.
- Test a message: `core/MessageCheck.run(..., masterOn)` = `ContactLookup.kind` -> `analyzer.analyze` -> `RuleEngine.decide(masterOn = true)` (always decides as if on; `paused` in the result adds a "Forwarding is paused" note) -> `MessageFormatter` (first Telegram and first SMS send) on `Dispatchers.IO`. Nothing is written or sent. `MessageCheck.extractedLine` is also what `IncomingProcessor` stores in the log.
- Token: `TokenCipher.encrypt` -> `SettingsStore.encryptedToken`; the field shows a "saved (hidden)" hint, never the decrypted value. Detect errors: "Bot token not set", Telegram's reason (e.g. "Unauthorized"), or "No messages yet — send /start to your bot, then tap Detect again".
- Destination delete: confirm dialog -> `DestinationRepository.delete` (also removes the id from rules); resets the alerts chat when it was that one.
- SIM picker: `READ_PHONE_STATE` is requested only when "+ Add number" is tapped; denied -> no picker ("Default SIM"). Picker shows only with > 1 active subscription.
- Export / Import: SAF `CreateDocument` / `OpenDocument`, `core/SettingsExport` JSON `{"version":2, deviceName, destinations, rules, token?}`. Token written only when "Include bot token" is ticked; on import it is used only when present AND ticked in the confirm dialog. `DestinationRepository.replaceAll` deletes and re-inserts destinations and rules in one transaction with fresh ids (rule references remapped); alerts chat reset to default; the imported device name applies only when this phone has none. SMS destinations keep their SIM only if it is one of this phone's active subscriptions (`SettingsExport.localizeSims`; none known without READ_PHONE_STATE → all reset); the confirm dialog says how many fell back to the default SIM and that pending sends to removed destinations will be marked failed.
- Update: "Check for update" runs `UpdateManager.refresh` and shows `UpdatePromptDialog` when newer, "latest version" when up to date, "Could not check" on failure. `UpdateChecker.checkForUpdate` throws `IOException` on network/HTTP/parse failure (404 = no release = up to date), so a failed check never clears a stored newer release.
- Detect / Test run token decrypt + network on `Dispatchers.IO`. The typed token uses plain `remember` (never in saved state).
- Onboarding "Grant" requests only runtime permissions that are actually missing; the notification permission is requested but optional, so Next works without it. Settings > Device has a "Status notification" row (explanation, Shown / Hidden status refreshed on resume, **Hide notification** opening the `forwarder_status` channel settings, falling back to app notification settings, then App info). Health shows the notification item as neutral grey info. The privacy notice (`ui/nav/PrivacyNotice`) shows once, after onboarding, never over it.
- "Needs attention: N to fix" shows in the hero when any health item is not ok and links to Settings.
- Theme (`ui/theme`): M3 light/dark schemes from the mock plus `ForwarderTheme.extra` (type, ok/warn tones, Telegram blue). `values-night/themes.xml` removes the white flash in dark mode. Strings live in `strings_shell.xml`, `strings_home.xml`, `strings_rules.xml`, `strings_log.xml`, `strings_settings.xml`, `strings_onboarding.xml`.

## Transitions
Declared once in `ui/nav/Transitions.kt` (all <= 250 ms; constants in `Transitions`, asserted by `TransitionsTest`). `AppNavHost` passes them to `NavHost` and per-`composable` overrides.
- Tabs: 200 ms shared-axis X (`tabEnter`/`tabExit`, direction from `tabDirection` = tab index order Home, Rules, Log, Settings); slide 1/10 width + fade; exit fade ends at 120 ms. Bottom bar sits outside the NavHost, so it never re-animates.
- Rule editor (pushed): 220 ms shared-axis Z (`pushEnter`/`pushPopExit`, fade + scale 0.94); the screen under it stays put (`ExitTransition.None`/`EnterTransition.None` for non-tab pairs).
- Onboarding to Home: 250 ms fade.
- Reduce motion: `Settings.Global.TRANSITION_ANIMATION_SCALE == 0` (read once in `AppNavHost`) gives `None` everywhere.
- In-screen show/hide: `AnimatedVisibility(contentEnter(), contentExit())` (150 ms fade + expand/shrink) for Home setup card, hero warning, attention row, Log empty/list; `animateItem()` on Rules (now a `LazyColumn`, one card per rule) and Log rows.
- No empty flash: empty states gated on `loaded`/null first emission; the four tab ViewModels use `WhileSubscribed(30_000)`; tab nav uses `saveState`/`restoreState` so the ViewModel survives tab switches.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- A fresh install (no v1 data) starts with forwarding paused (migration plan gives masterOn false).
- Permission and battery prompts live in onboarding (and Health Fix buttons), not in `MainActivity`.
- Auto-start guide (`OemItem`, shared by onboarding step 3 and Settings > Health): chips Samsung / Realme-Oppo-OnePlus / Vivo-iQOO / Xiaomi-Redmi-Poco / Other preselected from `Build.MANUFACTURER`; each step is a string resource with **bold** spans (`boldMarkdown`) and an Open button (`openFirst`: vendor screens, then App info). Steps per brand (strings in `strings_oem.xml`):
  - Samsung: Apps > SMS Forwarder > Battery > Unrestricted; Battery > Background usage limits > Never sleeping apps > + add; turn off "Put unused apps to sleep" and "Auto-disable unused apps"; reboot, Done.
  - Realme/Oppo/OnePlus: App management > SMS Forwarder > Battery usage (auto launch, background activity, Unrestricted); Battery > More settings > turn off sleep standby / optimise; older: Phone Manager > Startup manager; optional Lock.
  - Vivo/iQOO: Apps > SMS Forwarder > Battery > Unrestricted; i Manager > Autostart manager; Battery > Background power consumption management > Allow high consumption.
  - Xiaomi/Redmi/Poco: Manage apps > SMS Forwarder > Autostart on; Battery saver > No restrictions; Lock in Recent apps.
  - Other: Apps > Battery > Unrestricted; turn off Adaptive/Optimise limits.
- The "Auto-start allowed" health item stays red until the user ticks Done (onboarding or Settings > Health).
- Onboarding returns on every launch while a required permission is missing (back from it exits the app; Next works without granting).
- Locking happens on process ON_STOP, so leaving for any non-app screen (Home button, recents) re-locks.
