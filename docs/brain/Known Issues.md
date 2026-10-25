---
tags: [issues]
updated: 2026-10-25
---
# Known Issues

> Deferred minors collected from the v2 task reviews (summarised). None block release. Nothing is known to lose or duplicate messages in normal use.

## Verification status
- The v2.0.0 emulator smoke test (inject OTP/debit/missed call/personal/promo, airplane mode retry, swipe from Recents, reboot, contacts revoke) and the README screenshots (`screenshots/v2/`) were not done: emulator `127.0.0.1:6562` was in use by another app. README image links point at files that still need capturing.
- v1 to v2 migration is covered by JVM tests only; no v1 APK was available for an on-device upgrade test ([[Migration from v1]]).
- Telegram problem alerts (contacts revoked, reboot) cannot be verified without a real bot; the logic is unit-tested ([[Problem Alerts]]).

## Classification and formatting
- Missed-call detector: count regex misses "2 missed call(s)" / "Missed calls: 3"; hyphen-grouped numbers unsupported; time takes the first h:mm; instruction veto drops "missed call at 10:11 from N"; patterns are unverified against real carrier text ([[Classification]]).
- UNVERIFIED phone sender with the classifier switched on is untested.
- Formatter: escaping tests cover the body only; no hard 4096 cap inside the formatter (the Dispatcher caps it); "·" and "₹" make SMS UCS-2; custom template can render empty (Dispatcher falls back to the original body); carrier display name is not resolved (raw sender shown) ([[Formatting]]).

## Data and pipeline
- `failingSince` approximates with `firstAttemptAt`; logs with only FAILED rows count in no bucket on older queries; midnight is fixed at call time; mapper fallbacks can hide bad rows; analyzer asset load may run on the main thread ([[Data Layer]]).
- Log "Clear" moves the catch-up floor to now: an SMS the live receiver missed shortly before Clear is no longer caught up. Rows with a still-queued send survive Clear.
- `DefaultRules.attachFirstDestination` recognises default rules by their default names; a renamed default rule is not auto-attached. Deleting every destination and then adding one re-runs the attach/enable (default rules without destinations get the new one and the first three are switched back on).
- An OTP send already in flight when its hour passes still completes (prune fails only PENDING rows).
- A corrupt `settings_v2` resets every setting (token, PIN, device name) and starts paused.
- Literal plus separated OTP both present: the separated form stays unmasked in the Log.
- `LogEntity.simSlot` holds a subscription id (naming only).
- READ_SMS missing makes catch-up silently do nothing (Health shows it); a pipeline failure leaves no Log row; `schedule()` throwing after commit delays send until the next trigger; loose-duplicate read sits outside the transaction (tiny race) ([[Incoming Pipeline]]).
- Fresh-install crash between DB import and settings writes leaves defaults that forward nothing until a destination is added (the first destination then enables them).

## WhatsApp source
- Not verified on a device in Task 19 (emulators busy; the real phone is the controller's). MessagingStyle shapes, housekeeping texts and title counters are from WhatsApp behaviour as documented, not captured samples.
- Chat titles containing " · " make `chatOf` cut the title, so such a 1:1 chat cannot match a contact name. Two chats with the same title share one dedupe space (same text in the same second collapses).
- An unsaved number appears as its phone number title -> UNKNOWN_NUMBER (no PhoneLookup on titles). Group messages use the group name for the contact check (usually UNKNOWN_NUMBER).
- Export / Import carry the WhatsApp toggle and types (absent in an older file = left untouched) but not `waFloor` (importing "on" moves the floor to now). Importing "on" does not open the notification-access screen.
- The WhatsApp Log note lists the CURRENT type selection, not the one in force when the row was logged.
- Messages missed while the listener is unbound are lost (no catch-up source).
- The non-MessagingStyle fallback uses the notification `when` as the message time; if WhatsApp ever re-posts such a notification with a new `when`, the same text could be forwarded twice (WhatsApp normally uses MessagingStyle).

## Delivery
- Generic SMS failure codes are treated as fatal although some are transient; a partially delivered multipart SMS resends all parts on retry; a Telegram read timeout after acceptance can duplicate (inherent) ([[Delivery & Retries]]).

## Reliability
- Watchdog repeats the send pass already started by service start (safe; catch-up is no longer repeated); alert HTML truncation could cut an entity; `Plan.newState` and `ProblemAlerter.info()` unused; a paused service can flash its notification on a race; a second boot overwrites a pending reboot alert ([[Reliability]]).
- Cold launch calls `ensureRunning` twice; background-started processes rely on the watchdog / onResume if the FGS start is refused.

## UI
- Rule editor state is lost on process death and has no unsaved-changes prompt; fast taps on move use a stale index; mismatched Mutexes (the DAO transactions are the real guard) ([[UI Screens]]).
- Log filter applies after `LIMIT 200`; Home counts flow loads all rows and maps on Main; midnight rollover while foregrounded does not refresh counts; filter chips lack keys.
- Health titles, TypeBadge fallback emoji and hero white-alpha are literals rather than resources/tokens; recent rows show the raw sender; some unused imports and a duplicate cancel string.
- Onboarding is not PIN-gated (it only enables forwarding); PIN back-off can be bypassed by moving the clock forward; a forgotten PIN requires clearing app data; PIN failure persistence runs in a composable scope; device-name toast shows before the write completes.

## Build
- kotlinx-serialization-json 1.6.3 is old for Kotlin 2.2 (updater only).

Back to [[00 Index]].
