# WhatsApp notification source — design addendum to the v2 spec

**Date:** 2026-10-25 · **Status:** approved by owner ("design and implement for WhatsApp alone for now") · extends `2026-10-25-forwarder-revamp-design.md`

## Why
OTPs and bank/merchant alerts increasingly arrive on WhatsApp instead of SMS. The owner wants those forwarded to Telegram the same way SMS are. WhatsApp data is encrypted and unreachable; the only legitimate source is the **notification** WhatsApp posts.

## Goals / non-goals
- Goal: a second message **origin** — WhatsApp notifications (`com.whatsapp`, `com.whatsapp.w4b`) — flowing through the existing classify → rules → destinations pipeline, with the same Log, retries and alerts.
- Non-goals (now): other apps (RCS/Google Messages, bank apps), per-rule origin filters, reading WhatsApp history, replying.

## Behaviour
1. **Settings → Sources card:** "SMS" (always on) and "WhatsApp" toggle. Turning it on opens the system *Notification access* screen if access is missing; the row shows status (Allowed / Not allowed → **Allow** button). Off by default. PIN-gated like other settings.
2. **Capture:** a `NotificationListenerService` (`pipeline/WhatsAppNotificationListener`) handles only the allow-listed packages and only while the toggle is on. From each posted notification it extracts messages via `MessagingStyle` (each message: sender person, text, timestamp); fallback `EXTRA_BIG_TEXT` → `EXTRA_TEXT` with title as chat name. Skipped: group summaries, ongoing/call/category-call notifications, empty text, WhatsApp housekeeping texts (`N new messages`, `Checking for new messages`, `N messages from M chats`, backup/call notices), and any message whose timestamp is older than **10 min** before the post time or earlier than the moment the toggle was switched on (`waFloor`) — MessagingStyle re-posts history, which must never be forwarded.
3. **Identity:** sender address = `WA:<chat title>` (group: `WA:<group> · <person>`); display name "WhatsApp · <chat title>". `SenderKind`: CONTACT when the chat title equals a contact's display name (case-insensitive `ContactsContract` DISPLAY_NAME lookup), else UNKNOWN_NUMBER; UNVERIFIED without READ_CONTACTS.
4. **Classification:** same `MessageAnalyzer`; `WA:` senders take the business path (OTP/transaction detection works on text). Rule: a WhatsApp message from a CONTACT that ends up IMPORTANT becomes PERSONAL, so personal chats aren't forwarded by the defaults.
5. **Dedupe:** `MessageKey.of("WA:<chat>", timestamp, text)` + `looseKey` as for SMS; the unique index absorbs MessagingStyle re-posts. No catch-up scan exists for WhatsApp (no history source) — a message missed while the listener was disconnected is lost; stated in docs.
6. **Rules/destinations/formatting:** unchanged. Smart layouts show "WhatsApp · Amazon" where SMS shows the sender ID; `%s` renders "WhatsApp: Amazon". Loop guard not applicable.
7. **Log:** `LogEntity.origin` (0 SMS, 1 WHATSAPP; Room schema **v2** with `MIGRATION_1_2` `ALTER TABLE logs ADD COLUMN origin INTEGER NOT NULL DEFAULT 0` — v2.0.0 schema v1 is installed on the owner's S24, so no destructive migration). Log rows/detail show a "WhatsApp" chip. Test-a-message gets a Source toggle (SMS / WhatsApp).
8. **Health & alerts:** Health item "WhatsApp notification access" (red when the toggle is on and access is missing; Fix opens the system screen). Problem alert `NOTIF_ACCESS("notif_access", "WhatsApp notification access was removed")`; resolves when restored. Health also warns if WhatsApp's notification previews are hidden? — not detectable; documented in README instead ("keep message previews on").
9. **Privacy:** notifications from any other package are ignored in `onNotificationPosted` before any text is read into app memory; nothing from them is logged. README states this.
10. **Battery:** event-driven; the listener does no polling.

## Setup (README): Settings → Sources → WhatsApp → Allow → system list → SMS Forwarder → Allow notification access. Keep WhatsApp notification previews on and don't mute chats that carry OTPs.
