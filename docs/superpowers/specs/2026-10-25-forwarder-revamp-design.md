# SMS Forwarder v2 — Revamp Design

**Date:** 2026-10-25
**Status:** Draft for owner review
**App:** `com.viswa2k.smsforwarder` (Android, Kotlin + Jetpack Compose)
**Clickable mock (approved by owner):** [`2026-10-25-forwarder-revamp-mock.html`](2026-10-25-forwarder-revamp-mock.html)

## 1. Why

Forwarding itself works. The problem is **noise**: v1 forwards every SMS (optionally minus
contacts), while the owner only wants three kinds of message to reach them:

- **OTPs**
- **Bank / UPI / card transaction alerts**
- **Missed-call alert SMS** (the carrier "you have a missed call from …" messages)

…and typically only from **non-contacts** (business sender IDs, unknown numbers). Personal
messages from contacts and spam/promotions must not be forwarded.

A strong message classifier already exists in the owner's **SMS app**
(`../SMS`, packages `classify/` and `finance/`): pure Kotlin, 584+ unit tests, categories for OTP,
transaction, balance alert, delivery, bills/reminders, promotional and fraud. v2 reuses it
instead of writing a new one.

The v1 Firebase **cloud encrypted sync** channel is **dropped** (owner decision). It was half the
codebase and is not used for this goal.

## 2. Success looks like

- The owner's Telegram receives OTPs, transactions and missed calls — and nothing else — with no
  configuration beyond the first-run defaults.
- An OTP sent while the phone is offline is delivered when connectivity returns; nothing is
  silently lost.
- From the Log, the owner can always answer "why was / wasn't this SMS forwarded?".

## 3. Goals, non-goals, anti-goals

**Goals**
- Rule-based forwarding: *message types + sender condition → destinations + message style*.
- Channels: **Telegram** (Bot API, multiple chats) and **SMS** (multiple numbers).
- Classification ported from the SMS app, plus a new **missed-call** detector.
- Readable "smart summary" messages (OTP code tap-to-copy, transaction amount/merchant/balance).
- Delivery queue with retries, a forwarding log, a "Test a message" tool, a health checklist.
- New UI per the mock: Home, Rules (+ editor), Log, Settings.

**Non-goals**
- Cloud sync / Firebase of any kind, accounts, sign-in, a "master" companion app. Telegram is the
  hub (owner decision, 2026-10-25). A future **master version** (see all phones' messages in one
  place) may come later as a separate project; keep the pipeline's "destination" abstraction
  clean so a new channel can be added without touching rules/classification.
- Other channels (email, webhooks, WhatsApp, push).
- MMS / RCS, outgoing SMS, back-filling old SMS from the inbox.
- Two-way: replying to SMS from Telegram.
- Becoming the default SMS app.

**Anti-goals (failure even if it "works")**
- Forwarding spam, scams or personal contact messages the owner did not ask for.
- Dropping an OTP/transaction silently (failure must be visible in the Log and retried).
- Forwarding loops (two phones forwarding SMS to each other).
- Leaking the bot token (logs, exports, backups).
- Noticeable battery drain.

## 4. Users & core stories

The **owner** sets up each phone once, by hand, and then manages it remotely through Telegram.
The phones belong to **elderly relatives** who never open the app or change settings. Today
**3 phones** (e.g. Samsung S24 Ultra, Realme) forward into Telegram; more may be added. Telegram
is the hub: the owner reads everything there and gets notified there.

| # | Story | Acceptance criteria |
|---|---|---|
| S1 | As the owner, I want OTPs forwarded to my Telegram so I can log in from another device. | Given the default "OTPs" rule is on and Telegram is set up, when an OTP SMS arrives, a Telegram message containing the code (as `<code>` for tap-to-copy), the sender name and the original text is sent within ~10 s when online. |
| S2 | I want bank transactions forwarded as a short summary. | For a debit/credit alert, the smart message shows direction + amount, counterparty/merchant (when parsed), account tail, bank and available balance (when parsed). If parsing fails, the original text is sent (never nothing). |
| S3 | I want missed-call alert SMS forwarded. | Carrier missed-call SMS are detected as **Missed call**; the caller number and time are extracted when present. |
| S4 | I don't want personal messages from my contacts or spam forwarded. | With defaults, an SMS from a saved contact with no OTP/transaction content is skipped, logged as "no rule matched". Promotional/scam messages are never forwarded, even by an "All messages" rule. |
| S5 | I want to choose per rule where it goes. | A rule has ≥1 destination chosen from configured Telegram chats and SMS numbers. A message hitting several rules is sent **once** per destination. |
| S6 | I want to know why something was or wasn't forwarded. | Every received SMS gets a Log entry with detected type, sender kind, matched rules (or skip reason) and per-destination status (sent / retrying / failed). |
| S7 | Forwarding must survive offline / errors. | Failed sends are queued and retried with backoff when the network is available; after 24 h they become **Failed** in the Log. |
| S8 | I want to test a rule without waiting for a real SMS. | Settings → Test a message: paste sender + text → shows detected type, extracted data, matched rules, destinations and the rendered preview. Nothing is sent. |
| S9 | I want to verify each destination. | Each Telegram chat / SMS number has a **Test** button that sends a test message and reports success/error inline. |
| S10 | Upgrading from v1 should keep working. | First launch of v2 migrates bot token, chat IDs, SMS number, device alias and the master switch, and creates the default rules pointing at the migrated destinations. |

## 5. Functional design

### 5.1 Message types (what the rule chips mean)

Classification runs once per incoming SMS. The ported `MessageClassifier` category maps to the
forwarder type as follows; **missed-call detection runs first** and wins.

| Forwarder type | Source |
|---|---|
| **Missed call** | New `MissedCallDetector` (see 5.2) |
| **OTP** | `MessageCategory.OTP`, `OTP_TRANSACTION`, `OTP_VARIANT` |
| **Transaction** | `MessageCategory.TRANSACTION` (and `TransactionParser` result with a valid amount) |
| **Balance alert** | `BALANCE_ALERT` |
| **Bills & dues** | `SMART_ALERT` |
| **Delivery** | `DELIVERY` |
| **Other important** | `SERVICE`, `IMPORTANT`, `IMPORTANT_OTHER`, `UNCLASSIFIED` from a business sender |
| **Personal** | phone-number sender with no other type (`IMPORTANT` / `KNOWN_NUMBER` from a phone number) |
| **Spam** (not selectable) | `PROMOTIONAL`, `BLOCKED_DELETE`, or `FraudLevel.SCAM` |
| **All messages** (chip) | every type except Spam |

Other fraud levels (`SUSPICIOUS`, `FINANCIAL_RISK`) are forwarded normally but the smart
message gets a "⚠ Suspicious" line. *(Builder-check: my call.)*

### 5.2 Missed-call detector (new)

Pure Kotlin, unit-tested. Matches carrier missed-call alert SMS, e.g. Airtel "Missed Call
Alerts", Jio "…tried to call you…", Vi/BSNL "missed call from …". Patterns: body contains
`missed call` / `tried to call you` / `tried calling you` / `call alert` together with a phone
number. Extracts **caller number** (first phone-like token not equal to the carrier short code),
**time** and **count** ("2 missed calls") when present. Implementation should be a list of regexes
that's easy to extend; collect real samples during development (owner can paste via Test tool).

### 5.3 Rules

```
Rule { id, name, enabled, types: Set<Type> | ALL, sender: ANYONE | NOT_IN_CONTACTS | CONTACTS_ONLY,
       destinations: Set<DestinationId> (≥1), style: SMART | ORIGINAL | CUSTOM, customTemplate?, position }
```

- **Sender kinds:** business sender ID (e.g. `VM-HDFCBK`) and unknown numbers count as
  *not in contacts*. Contact lookup via `ContactsContract.PhoneLookup`.
- **No contacts permission:** rules whose sender is not *Anyone* do **not** match (fail safe),
  and Health shows the problem.
- **Evaluation:** all enabled rules are checked; the union of their destinations is sent to,
  once each. If several matching rules share a destination, the **first rule in list order**
  decides the style for that destination. Rules can be reordered (drag handle or move up/down).
- **Spam** never matches any rule.
- **Master switch** off → SMS are logged as "Paused" and not processed further.
- **Loop guard:** an SMS whose sender equals one of the configured SMS destination numbers
  (normalised) is never forwarded by SMS (Telegram still allowed).

**Default rules** (first run / migration):

| Name | Types | Sender | Destinations | On |
|---|---|---|---|---|
| OTPs | OTP | Anyone | all Telegram chats (else SMS numbers) | ✓ |
| Bank transactions | Transaction, Balance alert | Anyone | same | ✓ |
| Missed calls | Missed call | Anyone | same | ✓ |
| Unknown senders | All messages | Not in contacts | same | ✗ |

If no destination is configured yet, the rules are created with none and the Home screen shows
a "Set up Telegram or SMS" call to action; a rule without destinations cannot be enabled.

### 5.4 Destinations

- **Telegram:** one bot token (stored encrypted with an Android Keystore AES key), many chats
  `{label, chatId, topicId?}`. `topicId` (Bot API `message_thread_id`) lets every phone post into
  its own topic of one forum-style group, so the 3+ phones stay separated in one place. *Add chat* offers **Detect**: calls `getUpdates` and lists chats that have
  messaged the bot (title/username + id) to pick from; manual id entry stays available.
- **SMS:** many numbers `{label, number, simSubscriptionId?}`. On dual-SIM devices a SIM can be
  chosen; default = system default SMS subscription.
- Each has a **Test** button (sends "✅ Test from <device name>").

### 5.5 Message styles

Tokens for **Custom**: `%s` sender, `%m` message, `%r` device name, `%o` OTP code, `%a` amount,
`%t` type label, literal `\n` = newline. Unknown/unavailable tokens render empty.

**Smart (Telegram, HTML parse mode, all user text HTML-escaped):**

```
🔐 OTP <code>482913</code>
Amazon · AD-AMAZON · Pixel 8
<i>original text</i>

💸 Debited ₹1,250.00            (Credited for credits)
To Swiggy · UPI                 (counterparty/merchant + mode, when parsed)
A/c XX4521 · HDFC Bank
Balance ₹23,410.55              (when parsed)
Pixel 8 · VM-HDFCBK

📞 Missed call
+91 99620 55555 at 10:11
Pixel 8 · via Airtel
```

Other types: `<emoji> <Type>\n<sender> · <device>\n<original text>`.
**Smart (SMS):** same content as plain text, no HTML, kept short (OTP: `OTP 482913 - Amazon (Pixel 8)`).
**Original:** `<b>sender</b> · device` + original text (SMS: `From <sender>: <text>`).

Multipart incoming SMS (several PDUs from one sender in one broadcast) are **joined into one
body** before classification (v1 forwarded fragments separately).

### 5.6 Delivery, queue & retries

- Each (SMS × destination) becomes an **outbox row** in Room, attempted immediately.
- Telegram: `HttpURLConnection` POST `sendMessage`; 200 = sent; 429 → honour `retry_after`;
  401/400 "chat not found" → **Failed** immediately with the error shown (no retry).
  Network/5xx → retry.
- SMS: `SmsManager` (`divideMessage` + multipart); sent-status `PendingIntent` updates the row.
- Retries via a unique WorkManager job, network-constrained, exponential backoff (30 s → max
  ~1 h), give up after 24 h → **Failed**. Home shows the retrying count.
- The bot token never appears in logs, the Log screen or exceptions (redact URLs).

### 5.7 Log

Room table, newest first. Entry: received time, sender, body, detected type, extracted fields,
sender kind, matched rules / skip reason (`Paused`, `Spam`, `No rule matched`,
`Contacts permission missing`), per-destination status. Filters: All / Forwarded / Skipped /
Failed-retrying. Retention: **48 hours** (owner only needs the current day; 48 h keeps "yesterday" visible) *(my call)*; OTP codes and OTP
bodies are masked in the Log one hour after receipt *(my calls)*. "Clear
log" action. Tapping an entry shows details and offers **Resend** for failed destinations.

### 5.8 Background reception & 24×7 reliability

**Context (owner):** the phone belongs to an elderly person. They won't force-stop the app, but
they will swipe it away from Recents and reboot the phone. Forwarding must keep working 24×7
with no one touching the app, and must not drain the battery.

**Design:**
- **Event-driven, no polling.** Reception is a *manifest-registered* `SMS_RECEIVED` receiver
  (exempt from Android's implicit-broadcast limits). Android starts the app process when an SMS
  arrives, even after swipe-from-Recents or a reboot. Nothing runs between messages.
- **Foreground service** (`dataSync` / `specialUse` as appropriate for target 36) with a quiet,
  low-importance ongoing notification ("Forwarding SMS · tap to open"). It keeps the process in
  a protected state on aggressive OEM ROMs. It does no periodic work (no wake locks, no timers),
  so its battery cost is close to zero. Restarted from `onTaskRemoved` (swipe from Recents),
  `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` (after updates). Service type `specialUse`
  (Android 15+ forbids starting `dataSync` from boot and caps it at ~6 h/day).
- **Catch-up scan (gap filler):** SMS that arrive before the first unlock after a reboot, or while
  the process was dead, are not delivered to the receiver. On `BOOT_COMPLETED`, service start and
  each watchdog run, read the SMS inbox for messages newer than the last processed timestamp
  (bounded to the last 24 h, shorter than the 48 h log retention that holds the dedupe keys; never earlier than first run or the last time forwarding was switched on) and run them through the pipeline; dedupe by sender + timestamp +
  body hash. This is a scoped exception to the "no back-fill" non-goal *(my call)*. No
  direct-boot storage, so the token stays credential-encrypted.
- **Watchdog:** a 15-minute periodic WorkManager job (min. interval, inexact, batched by the OS)
  that only checks the service is running and the outbox is drained. It replaces v1's hourly
  exact alarm + exponential restart backoff. Cost: a few ms of CPU per run.
- **Remote problem alerts to Telegram (owner decision):** nobody looks at the phone, so every
  problem is pushed to the owner's Telegram (an "Alerts" destination, default = first chat):
  a permission revoked, battery optimisation turned back on, a destination failing for > 1 h,
  classifier unavailable, phone rebooted (info). Each alert is sent once per problem (re-sent
  daily while unresolved) and a "✅ resolved" message is sent when it clears.
- **No heartbeat / "alive" messages** (owner decision). Only problems are sent.
- **Opening the app always (re)starts the service** and runs the catch-up scan, so "please open
  the app" over the phone is a reliable recovery step for the elder.
- **Must work on every brand.** The core (manifest receiver + foreground service + watchdog +
  catch-up scan) is standard Android. Brand only matters for the one-time setup step "Allow
  auto-start / don't sleep this app": Health deep-links to the vendor screen for Samsung (Never
  sleeping apps), Realme/Oppo/OnePlus (ColorOS auto-launch), Xiaomi (Autostart), Vivo, with a
  generic App-info fallback for everything else, and a short "what to tap" hint. The owner ticks
  "done" during setup; afterwards the watchdog checks what it can (battery optimisation, permissions,
  service alive) and alerts via Telegram.
- **Setup by the owner, once:** the owner grants permissions and sets battery to *Unrestricted* /
  auto-start manually on each phone; the Health screen is a checklist to help with that.
- **Locked for the elder:** the master switch and rules are behind an optional PIN in Settings
  *(my call)*, so accidental taps don't stop forwarding. Notification has no "Stop" action.
- Receiver work uses `goAsync()` and must finish quickly: classify, write log + outbox, attempt
  send, hand leftovers to WorkManager.

**Battery expectation:** idle cost is effectively zero (no polling; a service holding a
notification doesn't consume CPU). Each SMS costs one short wake-up plus one HTTPS request.
Expect well under 1% per day for normal SMS volume.

### 5.9 Screens (see mock)

- **Home:** master switch hero (state + device name + service health), today's counts
  (Forwarded / Skipped / Retrying), rules with on/off switches, 3 most recent log entries.
- **Rules:** list + FAB "New rule"; fixed card "Never forwarded: spam/promotions and anything no
  rule matches". **Rule editor:** name, type chips (All messages is exclusive), sender segmented
  control, destination checklist, style segmented control (+ custom template field with token
  help), live Telegram preview rendered with the real formatter, Save / Delete.
- **Log:** filter chips + list; entry detail sheet.
- **Settings:** Telegram (token, chats, Test, Add/Detect), SMS numbers (Test, Add), Test a
  message, Health (SMS, contacts, notifications, battery optimisation — each with Fix), Device
  name, Export/Import settings (JSON via SAF; bot token excluded unless a checkbox is ticked),
  app version + update check.
- **First run:** a short permission checklist (the Health card, full-screen) before Home.
- Material 3, light + dark theme, colours as in the mock (type colours: OTP violet, Transaction
  teal, Missed call orange; Telegram blue for chats).

### 5.10 Migration from v1

On first v2 launch, if v1 DataStore keys exist: `TELEGRAM_API_KEY` → token;
`TELEGRAM_USER_IDS` (comma list) → chats labelled "Chat 1…n"; `SMS_TO_NUMBER` → SMS destination;
`DEVICE_ALIAS` → device name; `SMS_FORWARD_SERVICE` → master switch. Create default rules (5.3),
pointing only at channels whose v1 flag (`IS_BY_TELEGRAM_ENABLED` / `IS_BY_SMS_ENABLED`) was on;
disabled-channel destinations are still created but unassigned. If v1 `IS_SKIP_CONTACTS` was on,
default rules use *Not in contacts* *(my calls)*.
Remove all cloud keys and files (`cloud_upload_queue`, cloud prefs). v1 global/per-channel
formats are discarded (smart style replaces them). Migration is idempotent (flag in DataStore).

## 6. Technical decisions

- **Rebuild in place** in `sms-forwarder` on a new branch `revamp/v2`; same `applicationId` and
  signing so it installs over v1. Version **2.0.0**.
- **Delete:** `cloud/` package, Firebase deps + `google-services` plugin + `google-services.json`,
  the `firebase/` folder, cloud docs/plans stay in git history only. **Keep** the GitHub-releases
  in-app updater (`cloud/update/*` → move to `update/`) and `.github/workflows/release.yml`,
  `scripts/release.py` *(my call: it is the distribution channel, not cloud sync)*.
- **Stack aligned with the SMS app:** Kotlin 2.2.0, AGP 8.13.2, KSP, compileSdk/targetSdk 36,
  minSdk 29 (unchanged), Compose + Material 3, Navigation-Compose, Room (rules, destinations,
  log, outbox), DataStore (settings), WorkManager. No DI framework: a manual `AppGraph` like the
  SMS app. JVM 17.
- **Classifier port:** copy (snapshot, not a shared module) `classify/` and the pure parts of
  `finance/` from `../SMS` into `com.viswa2k.smsforwarder.classify` / `.finance`, plus
  `assets/keywords.json`. Excluded: `TransactionRecordBuilder`, `OtpPaymentChecker` and anything
  touching Room entities/DAOs. Copy the small enums they need (`MessageCategory`, `FraudLevel`,
  `OtpDigitSize` or drop its use) into the forwarder. Each copied file gets a header line naming
  the source path so future fixes can be synced. Port the matching unit tests.
- **Pure logic in Android-free classes** with JUnit tests: `MissedCallDetector`, `TypeMapper`,
  `RuleEngine`, `MessageFormatter` (Telegram/SMS/custom), `V1Migration` mapping, phone-number
  normalisation, Telegram response handling.
- Keep `allowBackup=false`, `usesCleartextTraffic=false`. Permissions: drop
  `REQUEST_INSTALL_PACKAGES` only if the updater is removed (it is kept, so keep it).

## 7. Edge cases

- Same SMS matching OTP and Transaction (OTP-in-payment): **primary type only = OTP**. It is
  never sent through a Transaction rule, so payment OTPs don't reach e.g. a family group
  *(my call, security)*.
- Classifier asset fails to load → every SMS is typed by the missed-call detector or as Other
  important / Personal; Health shows "Classifier unavailable"; *All messages* rules still work.
- Very long bodies: Telegram limit 4096 chars → truncate original text with "…".
- Dual-SIM incoming: include SIM slot in the log; not used for rules.
- Contacts lookup with country-code variants (`+91…` vs `0…` vs bare) — use `PhoneLookup` which
  normalises.

## 8. Left to the builder

Exact Compose component structure, Room schema details, WorkManager backoff constants within
the stated bounds, regex wording for missed-call samples, icon choices, copy text beyond what is
quoted here.
