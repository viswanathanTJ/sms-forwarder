---
tags: [log]
updated: 2026-10-25
---
# Log

> Dated work log. Newest first. See [[00 Index]].

## 2026-10-25 - Task 23: ignored WhatsApp messages not logged
- `SourceFilter.shouldRecord`; WhatsApp messages with no sends are dropped before the DB transaction; `waFloor` bumps on type-selection change; Test a message shows "Ignored (not logged)". See [[Incoming Pipeline]].

## 2026-10-25 - Task 18: fast shared-axis transitions
- New `ui/nav/Transitions.kt` (tab X 200 ms, push Z 220 ms, onboarding fade 250 ms, reduce-motion aware), AnimatedVisibility/animateItem for in-screen show/hide, Rules list now a LazyColumn, tab ViewModels `WhileSubscribed(30_000)`. See [[UI Screens]].

## 2026-10-25 - v2.0.0 release docs (Task 16)
- Rewrote CLAUDE.md and README.md for v2, added the v2.0.0 wiki changelog and "Superseded" notes on the cloud spec/plans, completed every brain note ([[Testing]], [[Known Issues]], [[00 Index]]). Version 2.0.0 / code 20 confirmed. Gate `testDebugUnitTest assembleDebug assembleRelease` passed.

## 2026-10-25 - Task 1: toolchain upgrade and cloud removal
- Upgraded to Kotlin 2.2.0, AGP 8.13.2, Gradle 8.13, KSP 2.3.4, SDK 36; versions inline, `libs.versions.toml` deleted. Added Room, WorkManager, Navigation, icons-extended; kept kotlinx-serialization for the updater.
- Removed Firebase, `cloud/` (crypto, data, fcm, ui), v1 settings UI, `firebase/`, the debug cleartext override, Tink/biometric/credentials dependencies and Example tests. Moved `cloud/update` to `update/`.
- `MainActivity` now shows `PlaceholderScreen`. Created this vault (see [[Architecture Overview]]).

## 2026-10-25 - Task 2: classifier and transaction parser port
- Copied SMS app `classify/` + `finance/` (minus OtpPolicy, OtpPaymentChecker, TransactionRecordBuilder, FinancePass), `MessageCategory`, `FraudLevel`, `keywords.json`, `AssetKeywordSource`, and 57 ported tests. See [[Classification]].

## 2026-10-25 - Task 3: missed-call detector
- Added core/MissedCallDetector (cue regexes + business-sender gate); see [[Classification]].
- Review fix: number now anchored to the cue, instruction phrasings and 1800/1860 rejected, spaces only in 2-5-5 / 5-5 groupings.
- 2026-10-25 Task 4: MessageType + MessageAnalyzer (type mapping in Features/Classification).
- 2026-10-25 Task 5: Rule model, RuleEngine, PhoneNumbers, DefaultRules (see [[Rules]]).
- 2026-10-25 Task 6: MessageFormatter (smart/original/custom, Telegram HTML + SMS), senderName title-casing (see [[Formatting]]).
- 2026-10-25 Task 7: Room storage, SettingsStore, TokenCipher, repositories, AppGraph (see [[Data Layer]]).
- 2026-10-25 Task 8: Telegram/SMS senders, RetryPolicy, Dispatcher + DeliveryWorker (see [[Delivery & Retries]]).
- 2026-10-25 Task 9: incoming pipeline (SmsReceiver, IncomingProcessor, InboxCatchUp, debug inject hook); see [[Incoming Pipeline]].
- 2026-10-25 Task 10: ForwarderService (specialUse), BootReceiver, WatchdogWorker, HealthChecker, ProblemAlerts (see [[Reliability]], [[Problem Alerts]]).
- 2026-10-25 Task 11: v1 migration (V1Migration, V1MigrationRunner, migration gate); see [[Migration from v1]].
- 2026-10-25 Task 12: UI shell, theme, navigation and Home; UserPreferences removed, stored update moved to SettingsStore (see [[UI Screens]]).
- 2026-10-25 Task 13: Rules list and rule editor (RuleEditorState reducer, live preview with real analyzer/formatter); see [[Rules]], [[UI Screens]].
- 2026-10-25 Task 14: Log screen (filters, detail sheet with Resend, clear with confirm), shared LogRow with Home; see [[UI Screens]].
- 2026-10-25 Task 14 fixes: Home counts derived via deliveryState (third tile "Not sent"), no Resend on masked rows, dated timestamps.
- 2026-10-25 Task 15: Settings (Telegram/SMS destinations, Test a message, Health, device, PIN, export/import, update), onboarding, PIN gating via requireUnlock; core SettingsExport/PinLock/MessageCheck; see [[UI Screens]].
- 2026-10-25 Task 15 fixes: import localizes SIM ids, update-check failure distinct (stored update kept), PIN back-off (PinBackoff), Grant only for missing runtime permissions, IO for detect/test, privacy notice after onboarding.
- 2026-10-25 Final review fixes: Log "Clear" moves the catch-up floor and keeps rows with queued sends (LogClearPolicy); expired OTP sends are FAILED before masking, Resend refused for expired/masked OTPs, prune also before each dispatch pass, on service start and app resume (OtpExpiry); catch-up pre-checks the dedupe key and runs once per watchdog run; ProblemAlerter mutex; loop guard covers any SMS destination; first destination auto-attaches to default rules, Home rule switch guarded + "No rule has a destination" warning; device-transfer excludes, v1 alarm cancel, DataStore corruption handlers, proguard cleanup.
- 2026-10-25 Re-review fixes: outbox outcome updates only touch PENDING rows (an expired OTP cannot be revived by a late Retry), Dispatcher re-reads each row before sending, Log Clear deletes only rows received before the new floor.
- 2026-10-25 Task 17: per-brand auto-start guide (brand chips, numbered steps with Open buttons, bold spans) for Samsung, Realme/Oppo/OnePlus, Vivo/iQOO, Xiaomi, Other; shared by onboarding step 3 and Settings > Health; OemSettings tables pure and tested; README lists the steps.
- 2026-10-25 Task 19: WhatsApp notification source (NotificationListenerService, allow-list com.whatsapp/.w4b, MessagingStyle extraction, 10-min + waFloor filter, `WA:` identity, WA+CONTACT+IMPORTANT -> PERSONAL), Room schema v2 `logs.origin` with non-destructive MIGRATION_1_2, Settings Sources card, Test-a-message source, Log WhatsApp chip, Health + NOTIF_ACCESS alert; see [[Incoming Pipeline]].
- 2026-10-25 Task 19 review fixes: Log Clear also moves waFloor; NOTIF_ACCESS dropped silently when the source is off ("access is not allowed"); pure `NotificationText.select`; housekeeping covers calls, deletions, media placeholders; cached prefs catch read errors, listener waits at most 10 s.
- 2026-10-25 Task 20: WhatsApp type filter before rules (`SourceFilter`, `SettingsStore.whatsAppTypes` default OTP, `SkipReason.SOURCE_FILTERED`=5, Sources chips, Test-a-message honours it, Export/Import carry WhatsApp toggle + types); see [[Incoming Pipeline]].
- 2026-10-25 Task 21: status notification optional everywhere (onboarding does not require it, Health item informational, NOTIFICATIONS alert removed with silent key drop), Settings > Device "Status notification" row with Hide notification shortcut, `forwarder_status` channel IMPORTANCE_MIN on new installs; see [[Reliability]].
- 2026-10-25 Task 22: Settings is a short grouped row list (Forwarding / Tools / Phone / Data; live status lines from pure `SettingsSummary`) opening `settings/{section}` sub-pages (TopAppBar + back, push transition, PIN-gated like the root, shared ViewModel); onboarding is a 6-page button-driven pager (Welcome, Permissions, Battery, Auto-start, Connect Telegram, Ready; rules in pure `OnboardingSteps`); Task 21 follow-ups (Allow hidden when only notifications are missing, "Show notification" link, `ensureChannel` before the channel page, `isProblem` removed); WhatsApp skips "You" messages and reactions; see [[UI Screens]], [[Incoming Pipeline]].
- 2026-10-25 Task 22 review fixes: onboarding Next/Back only from the visible page (`OnboardingSteps.advance/back`, ignored while sliding), Start forwarding behind the PIN, Telegram Skip only without chats; Settings always reopens at the root; locked sub-page keeps its top bar; channel created only on click; more reaction forms skipped.
- 2026-10-25 Task 24: Home "Sources" strip under the hero (SMS · On; WhatsApp Off / On (types) / Access needed amber), tap toggles WhatsApp via `requireUnlock` + `setWhatsAppEnabled`, access refreshed on resume; pure `HomeSources` + test; see [[UI Screens]].
- 2026-10-25 Task 25: roomier Settings rows (72 dp min, 44 dp icon, titleMedium/bodyMedium, labelLarge section labels with 24/8 dp spacing, 72 dp divider inset); see [[UI Screens]].
- 2026-10-25 Task 25: Home title now "SMS Forwarder".
- 2026-10-25 Task 26: release prep: README rewritten for v2 with 16 emulator screenshots (`screenshots/v2/`), wiki changelog extended, release workflow derives versionCode from the tag (MAJOR*10000+MINOR*100+PATCH) and drops google-services/Firebase steps, default versionCode 20000; emulator verification recorded in [[Known Issues]].
