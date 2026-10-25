# SMS Forwarder v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild `com.viswa2k.smsforwarder` as v2.0.0: rule-based forwarding of classified SMS (OTP / transaction / missed call / …) to Telegram and SMS, with a delivery queue, log, health checks, Telegram problem alerts and the new 4-tab UI.

**Architecture:** Pure-Kotlin core (`core/`, ported `classify/` + `finance/`) holds every decision — classification, rule matching, formatting, retry, migration — and is JUnit-tested. A thin Android layer (`pipeline/`, `delivery/`, `service/`, `data/`) wires broadcasts, Room, DataStore, WorkManager and HTTP to it. Compose UI (`ui/`) reads repositories through ViewModels built from a manual `AppGraph`.

**Tech Stack:** Kotlin 2.2.0, AGP 8.13.2, Gradle 8.13, KSP 2.3.4, compileSdk/targetSdk 36, minSdk 29, Compose 1.10.0 + Material3 1.4.0 + material-icons-extended 1.7.8, Navigation-Compose 2.9.6, Lifecycle 2.10.0, Room 2.8.4, WorkManager 2.10.5, DataStore 1.1.7, kotlinx-serialization (updater only), JUnit 4.13.2 + org.json 20240303.

**Spec:** `docs/superpowers/specs/2026-10-25-forwarder-revamp-design.md` (mock: `docs/superpowers/specs/2026-10-25-forwarder-revamp-mock.html`). Read both before any task.

## Global Constraints

- Repo `/mnt/SSD/WorkSpace/Android/Finance/sms-forwarder`, branch `revamp/v2`. Git: `git -c safe.directory=$PWD …` (NTFS). Never push.
- Package/applicationId stay `com.viswa2k.smsforwarder`; versionName default `2.0.0`, versionCode default `20` (still overridable by `-PVERSION_NAME/-PVERSION_CODE`).
- **One Gradle invocation at a time** (low-RAM machine). Add `org.gradle.java.home=/opt/android-studio/jbr` to `gradle.properties`.
- Per-task gate: `./gradlew testDebugUnitTest assembleDebug` must pass before commit.
- Devices: use only the emulator/virtual device `127.0.0.1:6562` (`adb -s 127.0.0.1:6562 …`). The other attached device (`adb-RZCX51XRGKZ…`) is the owner's real Samsung phone: never install, uninstall or change anything on it.
- Brain vault: every task that adds or changes behaviour updates `docs/brain/` (Obsidian vault, created in Task 1) — the relevant feature note plus a dated line in `docs/brain/Log.md`.
- No Firebase, no Google services, no cloud sync code. Network only to `api.telegram.org` and the GitHub releases API (updater).
- Keep `allowBackup=false`, `usesCleartextTraffic=false`, `network_security_config`.
- Bot token: never logged, never in exceptions/Log screen (redact `bot<token>` in URLs), stored encrypted (Android Keystore AES-GCM), excluded from export unless the user ticks the box.
- Pure logic lives in Android-free classes with JUnit tests mirroring the package in `app/src/test`. No Robolectric, no androidTest.
- Ported files keep their original KDoc and get a first-line comment `// Ported from SMS app: <original path> (2026-10-25 snapshot)`.
- Telegram text uses `parse_mode=HTML`; all user/SMS text HTML-escaped (`&`, `<`, `>`); max 4096 chars.
- Log retention 48 h; OTP codes/bodies masked in the Log 1 h after receipt.
- Retry: backoff 30 s doubling, capped at 1 h; give up 24 h after first attempt → FAILED.
- Watchdog: 15-min periodic WorkManager job. Catch-up window: 24 h (must stay shorter than the 48 h log retention so dedupe keys still exist).
- Foreground service type `specialUse` (Android 15 forbids `dataSync` from boot).
- No heartbeat/"alive" messages. Problem alerts only.
- UI colours from the mock: primary `#2F5BEA`, OTP `#7A3FE0`, Transaction `#0B7D73`, Missed call `#C2410C`, Telegram `#229ED9`; light + dark themes.

## Review Focus

1. **Long SMS split into several PDUs** (most bank alerts are > 160 chars) → forwarded once as one joined body, classified on the joined text. Test in Task 9 (`IncomingSmsAssemblerTest`).
2. **Same SMS seen twice** (live receiver + catch-up scan, or a duplicate broadcast) → forwarded once. Test in Task 9 (`MessageKeyTest`) + unique index in Task 7.
3. **SMS text containing `<`, `&`, `>` or a 5,000-char body** → Telegram accepts it (escaped, truncated with "…"), never a 400 loop. Test in Task 6.
4. **Offline for hours, then back** → queued sends delivered in order; after 24 h shown as Failed with a problem alert. Tests in Task 8 (`RetryPolicyTest`) + Task 10 (`ProblemAlertsTest`).
5. **First install / un-pausing** → catch-up never forwards old inbox history or messages received while paused. Test in Task 9 (`CatchUpWindowTest`).

---

## File Structure

```
app/src/main/java/com/viswa2k/smsforwarder/
  ForwarderApp.kt               Application; creates AppGraph
  AppGraph.kt                   manual lazy DI (db, settings, cipher, analyzer, clients, repos, appScope)
  classify/  (ported)           MessageClassifier, OtpExtractor, SenderId(s), KeywordLists, MiniJson, … + MessageCategory.kt, FraudLevel.kt
  finance/   (ported)           TransactionParser + its stages, FinanceModels, BankSenderResolver, …
  core/                         PURE: MessageType, MissedCallDetector, MessageAnalyzer, Rule/RuleEngine,
                                MessageFormatter, PhoneNumbers, RetryPolicy, TelegramResponse, ProblemAlerts,
                                LogMasker, MessageKey, CatchUpWindow, V1Migration, DefaultRules, IncomingSmsAssembler
  data/db/                      ForwarderDatabase, entities, DAOs, converters
  data/prefs/                   SettingsStore (DataStore "settings_v2"), TokenCipher
  data/repo/                    RuleRepository, DestinationRepository, LogRepository, OutboxRepository
  delivery/                     TelegramClient, SmsSender, Dispatcher, DeliveryWorker
  pipeline/                     SmsReceiver, IncomingProcessor, ContactLookup, InboxCatchUp
  service/                      ForwarderService, BootReceiver, WatchdogWorker, HealthChecker, OemSettings, ProblemAlerter
  update/                       (moved from cloud/update) UpdateChecker, UpdateInstaller, UpdateManager, UpdateDialog
  ui/theme, ui/nav, ui/home, ui/rules, ui/log, ui/settings, ui/onboarding, ui/common
app/src/debug/java/.../debug/InjectSmsReceiver.kt    adb hook into IncomingProcessor
app/src/main/assets/keywords.json                     copied from SMS app
```

---

### Task 1: Toolchain upgrade and cloud removal

**Files:**
- Modify: `build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` (delete it; versions inline like the SMS app), `gradle/wrapper/gradle-wrapper.properties` (8.13), `gradle.properties`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- Delete: `app/src/main/java/com/viswa2k/smsforwarder/cloud/` (except `update/`), `app/google-services.json` (if present), `firebase/`, the cloud nav/screens references, `ExampleUnitTest`/`ExampleInstrumentedTest`, `app/src/androidTest/`
- Move: `cloud/update/*` → `update/` (package `com.viswa2k.smsforwarder.update`)
- Create: `app/src/main/java/com/viswa2k/smsforwarder/ui/PlaceholderScreen.kt` (temporary: app name + version text; replaced in Task 12)
- Create: `docs/brain/00 Index.md` (map of content), `docs/brain/Log.md`, `docs/brain/Known Issues.md`, `docs/brain/Architecture/{Architecture Overview,Data Layer,Reliability,Testing}.md`, `docs/brain/Features/{Classification,Rules,Formatting,Delivery & Retries,Incoming Pipeline,Problem Alerts,Migration from v1,UI Screens}.md` — modelled on `../SMS/docs/brain/` (frontmatter `tags`/`updated`, `[[wiki links]]`; feature-note sections: *What the user can do*, *Screens & entry points*, *Data*, *How it works*, *Connections*, *Gotchas*). Stub sections are filled by the task that builds the feature.

**Interfaces:**
- Produces: a building app with no Firebase; `update.UpdateManager` unchanged API.

- [ ] **Step 1:** Rewrite Gradle files with the Tech Stack versions above, plugins `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`, `com.google.devtools.ksp`; keep the existing signing config, `prop()` helper, release minify + proguard, `testOptions { unitTests.isReturnDefaultValues = true }`; drop `google-services`, Firebase BOM, `GOOGLE_WEB_CLIENT_ID`, `USE_EMULATOR`, credential/play-services deps. Add Room (+ `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`), WorkManager, Navigation, icons-extended, `testImplementation("org.json:json:20240303")`.
- [ ] **Step 2:** Delete cloud code and the FCM `<service>` from the manifest; move the updater; make `MainActivity` show `PlaceholderScreen` (keep its service-start + battery code for now). Remove cloud-only proguard rules.
- [ ] **Step 3:** Run `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL. `grep -ri firebase app/src` → no hits.
- [ ] **Step 4:** Commit `chore: upgrade toolchain to Kotlin 2.2/AGP 8.13/SDK 36 and remove cloud sync`.

### Task 2: Port the classifier and transaction parser

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/classify/*` and `.../finance/*` copied from `../SMS/app/src/main/java/com/viswa2k/sms/{classify,finance}` with package rewritten (`com.viswa2k.sms.` → `com.viswa2k.smsforwarder.`).
- Create: `classify/MessageCategory.kt` (copy of `SMS/.../data/db/MessageCategory.kt` incl. `CategoryCode`), `classify/FraudLevel.kt` (copy incl. `FraudCode`).
- Copy: `SMS/app/src/main/assets/keywords.json` → `app/src/main/assets/keywords.json`
- Exclude: `finance/TransactionRecordBuilder.kt`, `finance/OtpPaymentChecker.kt`, `classify/OtpPolicy.kt`, and any file that then fails to compile only because it depends on those (e.g. `FinancePass.kt` if it does). Record excluded files in the commit message.
- Test: copy `SMS/app/src/test/java/com/viswa2k/sms/classify/{KeywordListsTest,LinkExtractorTest,MessageClassifierTest,OtpExtractorTest,SenderIdsTest,TestKeywords}.kt` and `finance/{BankSenderResolverTest,MerchantCategorizerTest,TransactionCorpus,TransactionParserTest}.kt` into the same packages under `app/src/test/java/com/viswa2k/smsforwarder/`. Skip tests that import excluded code (`OtpPolicyTest`, `AmountCapTest`, `FinancePassTest`, `TransactionRecordBuilderTest`, `InMemoryFinanceStore`).
- Create: `data/repo/AssetKeywordSource.kt` (`class AssetKeywordSource(context: Context) : KeywordSource` reading `KeywordSource.ASSET_NAME`).

**Interfaces:**
- Produces: `MessageClassifier(lists).classify(ClassifierInput(sender, body, isKnown)): Classification`, `TransactionParser(lists).parse(address, body): ParsedTransaction`, `OtpResult`, `SenderIds.parse(raw): SenderInfo`, `KeywordLists.load(source)`, `MessageCategory`, `FraudLevel`.

- [ ] **Step 1:** Copy sources, tests and asset; rewrite packages; add the "Ported from" header line to each file.
- [ ] **Step 2:** Run `./gradlew testDebugUnitTest` → all ported tests pass (same counts as in the SMS app for those classes).
- [ ] **Step 3:** Commit `feat(classify): port SMS app classifier and transaction parser`.

### Task 3: Missed-call detector

**Files:**
- Create: `core/MissedCallDetector.kt`
- Test: `core/MissedCallDetectorTest.kt`

**Interfaces:**
- Produces: `data class MissedCall(val caller: String?, val time: String?, val count: Int)`; `object MissedCallDetector { fun detect(sender: String, body: String): MissedCall? }`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test fun airtel() = assertEquals(MissedCall("+919962055555", "10:11", 1),
    MissedCallDetector.detect("JM-AIRTEL", "You have a missed call from +919962055555 at 10:11 on 25-Oct. -Airtel"))
@Test fun jio() = assertEquals("9840012345",
    MissedCallDetector.detect("JK-JIOINF", "9840012345 tried to call you on 25-10-2026 at 08:05 PM. You can now call back.")?.caller)
@Test fun vi_count() = assertEquals(2,
    MissedCallDetector.detect("VK-VIALRT", "You have 2 missed calls from 9876543210. Last call at 09:40")?.count)
@Test fun bsnl() = assertNotNull(MissedCallDetector.detect("BZ-BSNLIN", "Missed call alert: 04423456789 called you at 14:02"))
@Test fun otpMentioningCall_isNot() = assertNull(MissedCallDetector.detect("AD-HDFCBK", "Do not share OTP 123456 with anyone, even if they call you"))
@Test fun personalChat_isNot() = assertNull(MissedCallDetector.detect("+919840012345", "sorry I missed your call, will call later"))
```

- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests '*MissedCallDetectorTest'` → FAIL (unresolved).
- [ ] **Step 3:** Implement as an ordered list of regexes (cue: `missed call`, `tried to call you`, `tried calling you`, `call alert`, `called you`) that only fires for business/short-code senders (`SenderIds.parse(sender).isBusiness`) and requires a phone-like token (7–13 digits, optional `+`) ≠ sender digits; time `\d{1,2}:\d{2}( ?[AP]M)?` kept as written; count from `(\d+) missed calls`, default 1.
- [ ] **Step 4:** Run the test → PASS.
- [ ] **Step 5:** Commit `feat(core): missed-call alert detector`.

### Task 4: Message types and analyzer

**Files:**
- Create: `core/MessageType.kt`, `core/MessageAnalyzer.kt`
- Test: `core/MessageAnalyzerTest.kt` (uses `TestKeywords` from Task 2)

**Interfaces:**
- Consumes: Task 2 classifier/parser, Task 3 detector.
- Produces:
  - `enum class MessageType(val code: Int, val label: String, val emoji: String) { MISSED_CALL(1,"Missed call","📞"), OTP(2,"OTP","🔐"), TRANSACTION(3,"Transaction","💸"), BALANCE(4,"Balance alert","🏦"), BILL(5,"Bills & dues","🧾"), DELIVERY(6,"Delivery","📦"), IMPORTANT(7,"Other important","⭐"), PERSONAL(8,"Personal","👤"), SPAM(9,"Spam","🚫") }` — `SPAM` is not user-selectable; codes are stored, never renumber.
  - `enum class SenderKind { BUSINESS, CONTACT, UNKNOWN_NUMBER, UNVERIFIED }` (`UNVERIFIED` = phone number but contacts permission missing)
  - `data class Analysis(val type: MessageType, val sender: SenderInfo, val senderName: String, val otp: OtpResult?, val txn: ParsedTransaction?, val missedCall: MissedCall?, val fraud: FraudLevel, val classifierAvailable: Boolean)`
  - `class MessageAnalyzer(lists: KeywordLists?) { fun analyze(sender: String, body: String, senderKind: SenderKind): Analysis }` — `lists == null` means the asset failed to load.

- [ ] **Step 1: Write failing tests** covering the spec 5.1 mapping table, e.g.:

```kotlin
@Test fun otp() = assertEquals(MessageType.OTP, a("AD-AMAZON", "482913 is your Amazon OTP. Do not share it.").type)
@Test fun paymentOtp_isOtpOnly() = assertEquals(MessageType.OTP, a("VM-HDFCBK", "OTP 551234 for txn of Rs 2,000.00 at FLIPKART on HDFC Bank card XX1234").type)
@Test fun debit() { val r = a("VM-HDFCBK", "Rs.1250.00 debited from A/c XX4521 on 25-10-26 to VPA swiggy@icici (UPI Ref 4291). Avl Bal Rs.23410.55 -HDFC Bank")
    assertEquals(MessageType.TRANSACTION, r.type); assertEquals(1250.0, r.txn!!.amount, 0.001) }
@Test fun missedCallWins() = assertEquals(MessageType.MISSED_CALL, a("JM-AIRTEL", "You have a missed call from +919962055555 at 10:11").type)
@Test fun promo_isSpam() = assertEquals(MessageType.SPAM, a("BX-MYNTRA-P", "FLAT 70% OFF! End of season sale is LIVE. Shop now bit.ly/x").type)
@Test fun contactChat_isPersonal() = assertEquals(MessageType.PERSONAL, a("+919840012345", "Lunch ready, come home by 1", SenderKind.CONTACT).type)
@Test fun noKeywords_stillWorks() = assertEquals(MessageType.MISSED_CALL, MessageAnalyzer(null).analyze("JM-AIRTEL", "You have a missed call from +919962055555", SenderKind.BUSINESS).type)
```

- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement: missed-call first; else classify (`isKnown = senderKind == CONTACT`); map per spec 5.1 (`isOtp` → OTP; `TRANSACTION` → TRANSACTION only if `txn.amount > 0`, else IMPORTANT; `FraudLevel.SCAM` or `inSpamStore` → SPAM; phone-number sender with `IMPORTANT`/`KNOWN_NUMBER`/`UNCLASSIFIED` → PERSONAL). Parse transaction only for TRANSACTION/BALANCE. `senderName` = `txn.sender.name` when a bank was resolved, else the OTP brand if the classifier exposes one, else `sender.canonicalId`. With `lists == null`: business → IMPORTANT, phone → PERSONAL, `classifierAvailable = false`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(core): message types and analyzer`.

### Task 5: Rules and rule engine

**Files:**
- Create: `core/Rule.kt`, `core/RuleEngine.kt`, `core/PhoneNumbers.kt`, `core/DefaultRules.kt`
- Test: `core/RuleEngineTest.kt`, `core/PhoneNumbersTest.kt`, `core/DefaultRulesTest.kt`

**Interfaces:**
- Produces:
  - `enum class SenderCondition(val code: Int) { ANYONE(0), NOT_IN_CONTACTS(1), CONTACTS_ONLY(2) }`
  - `enum class Style(val code: Int) { SMART(0), ORIGINAL(1), CUSTOM(2) }`
  - `enum class Channel(val code: Int) { TELEGRAM(0), SMS(1) }`
  - `data class Destination(val id: Long, val channel: Channel, val label: String, val address: String /* chatId or phone */, val topicId: Long? = null, val simSubscriptionId: Int? = null)`
  - `data class Rule(val id: Long, val name: String, val enabled: Boolean, val allMessages: Boolean, val types: Set<MessageType>, val sender: SenderCondition, val destinationIds: List<Long>, val style: Style, val customTemplate: String?, val position: Int)`
  - `enum class SkipReason { PAUSED, SPAM, NO_RULE, CONTACTS_PERMISSION_MISSING }`
  - `data class PlannedSend(val destination: Destination, val ruleId: Long, val style: Style, val template: String?)`
  - `data class Decision(val matchedRuleIds: List<Long>, val sends: List<PlannedSend>, val skip: SkipReason?)`
  - `object RuleEngine { fun decide(type: MessageType, senderKind: SenderKind, senderAddress: String, masterOn: Boolean, rules: List<Rule>, destinations: List<Destination>): Decision }`
  - `object PhoneNumbers { fun normalize(raw: String): String /* last 10 digits for Indian numbers, else all digits */; fun same(a: String, b: String): Boolean }`
  - `object DefaultRules { fun create(destinationIds: List<Long>, sender: SenderCondition): List<Rule> }` (spec 5.3 table: OTPs, Bank transactions [TRANSACTION, BALANCE], Missed calls, Unknown senders [allMessages, NOT_IN_CONTACTS, disabled]; ids 0, positions 0..3; first three enabled only when `destinationIds` non-empty).

- [ ] **Step 1: Write failing tests**, including:
  - master off → `skip = PAUSED`, no sends; SPAM with an `allMessages` rule → `skip = SPAM`.
  - two rules hitting the same destination → one `PlannedSend`, style from the lower `position`.
  - disabled rule ignored; rule with empty `destinationIds` ignored.
  - `NOT_IN_CONTACTS` matches BUSINESS and UNKNOWN_NUMBER, not CONTACT; `UNVERIFIED` matches only `ANYONE` rules, and when that leaves nothing → `skip = CONTACTS_PERMISSION_MISSING`.
  - loop guard: sender `+91 98400 12345` with an SMS destination `09840012345` → that SMS send dropped, Telegram send kept.
  - no match → `skip = NO_RULE`.
  - `PhoneNumbers.same("+919840012345", "09840012345")` true; `same("VM-HDFCBK", "HDFCBK")` false.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit `feat(core): rule engine`.

### Task 6: Message formatter

**Files:**
- Create: `core/MessageFormatter.kt`
- Test: `core/MessageFormatterTest.kt`

**Interfaces:**
- Consumes: `Analysis` (Task 4), `Style`, `Channel` (Task 5).
- Produces: `data class IncomingSms(val sender: String, val body: String, val sentAt: Long, val receivedAt: Long, val subscriptionId: Int?)`; `object MessageFormatter { fun format(sms: IncomingSms, a: Analysis, style: Style, template: String?, channel: Channel, deviceName: String, zone: java.time.ZoneId = ZoneId.systemDefault()): String; fun escapeHtml(s: String): String; const val TELEGRAM_MAX = 4096 }`

- [ ] **Step 1: Write failing tests** with the exact layouts from spec 5.5:

```kotlin
// Telegram SMART OTP
assertEquals("🔐 OTP <code>482913</code>\nAmazon · AD-AMAZON · Pixel 8\n<i>482913 is your Amazon OTP. Do not share it.</i>", fmt(otpSms, SMART, TELEGRAM))
// Telegram SMART debit starts with "💸 Debited ₹1,250" and contains "A/c XX4521 · HDFC Bank" and "Balance ₹23,410.55"
// Telegram SMART missed call
assertEquals("📞 Missed call\n+919962055555 at 10:11\nPixel 8 · via JM-AIRTEL", fmt(callSms, SMART, TELEGRAM))
// SMS SMART OTP
assertEquals("OTP 482913 - Amazon (Pixel 8)", fmt(otpSms, SMART, SMS))
// ORIGINAL: Telegram "<b>AD-AMAZON</b> · Pixel 8\n<body>", SMS "From AD-AMAZON: <body>"
// CUSTOM "%r · %s\\n%t %o %a\\n%m" → tokens replaced, literal \n → newline, missing %a → ""
// escaping: body "a<b & c>d" → Telegram contains "a&lt;b &amp; c&gt;d"
// 5,000-char body → Telegram result length <= 4096 and ends with "…</i>" (tags stay balanced)
// fraud SUSPICIOUS → SMART Telegram contains "⚠ Suspicious"
```

- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement. Money via ported `Money.format(paise)` with "₹" prefix; "Debited"/"Credited" from `TxnDirection`; omit lines whose data is missing; truncation shortens only the original-text part. Transaction with unparsed amount falls back to the generic layout (`<emoji> <Type>\n<sender> · <device>\n<original>`).
- [ ] **Step 4:** Run → PASS. **Step 5:** Commit `feat(core): smart/original/custom message formatter`.

### Task 7: Storage — Room, settings, token cipher, repositories, AppGraph

**Files:**
- Create: `data/db/ForwarderDatabase.kt` (v1, `exportSchema = true`), `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/Converters.kt`, `data/prefs/SettingsStore.kt`, `data/prefs/TokenCipher.kt`, `data/repo/{RuleRepository,DestinationRepository,LogRepository,OutboxRepository}.kt`, `core/LogMasker.kt`, `ForwarderApp.kt`, `AppGraph.kt`; register `android:name=".ForwarderApp"`.
- Test: `core/LogMaskerTest.kt`, `data/db/ConvertersTest.kt`

**Interfaces:**
- Produces:
  - Entities: `RuleEntity` (fields of `Rule`, `types` as comma codes, `destinationIds` as comma ids), `DestinationEntity` (fields of `Destination`), `LogEntity(id, key: String UNIQUE, sender, body, receivedAt, type: Int, senderKind: Int, extracted: String /* one display line, e.g. "Code 482913" / "₹1,250 debit" */, matchedRules: String, skip: Int? , simSlot: Int?, masked: Boolean)`, `OutboxEntity(id, logId, destinationId, channel, text, state: Int /* 0 PENDING 1 SENT 2 FAILED */, attempts, firstAttemptAt, nextAttemptAt, lastError: String?)`.
  - `LogRepository`: `suspend fun insertIfNew(entry: LogEntity): Long?` (null when `key` exists), `fun recent(limit: Int): Flow<List<LogWithSends>>`, `fun todayCounts(now: Long): Flow<Counts(forwarded, skipped, retrying)>`, `suspend fun prune(now: Long)` (delete > 48 h, mask OTP rows > 1 h via `LogMasker`), `suspend fun clear()`.
  - `OutboxRepository`: `insert`, `due(now): List<OutboxEntity>`, `markSent(id)`, `markRetry(id, nextAt, error)`, `markFailed(id, error)`, `failingSince(destinationId): Long?`, `resend(outboxId)`.
  - `RuleRepository`: `rules: Flow<List<Rule>>`, `suspend fun all(): List<Rule>`, `save(rule): Long`, `delete(id)`, `move(id, newPosition)`, `setEnabled(id, on)`.
  - `DestinationRepository`: `destinations: Flow<List<Destination>>`, `all()`, `save`, `delete` (also removes the id from rules).
  - `SettingsStore` (DataStore name `settings_v2`): `masterOn`, `deviceName`, `encryptedToken`, `pinHash`, `alertsDestinationId`, `catchUpFloor: Long`, `migrationDone`, `setupConfirmedOem`, `alertState: String` (JSON map problemKey→lastSentAt), each as `Flow` + `suspend fun set…`.
  - `TokenCipher(context) { fun encrypt(plain: String): String; fun decrypt(cipher: String): String? }` (Keystore alias `forwarder_token`, AES/GCM/NoPadding, Base64(iv+ct)).
  - `object LogMasker { fun mask(body: String, otp: String?): String }` → every occurrence of the code replaced with `•` × length; when `otp == null` mask 4–8-digit runs.
  - `class AppGraph(context)` with `by lazy` members for all of the above plus `analyzer: MessageAnalyzer` (keywords loaded via `AssetKeywordSource`, null on failure), `appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`; `companion fun from(context): AppGraph` (from `ForwarderApp`).

- [ ] **Step 1:** Write failing `LogMaskerTest` (`mask("482913 is your OTP", "482913") == "•••••• is your OTP"`) and `ConvertersTest` (types set ↔ "2,3" round-trip; empty set ↔ "").
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement everything in the file list. **Step 4:** `./gradlew testDebugUnitTest assembleDebug` → PASS; `app/schemas/.../1.json` generated and committed.
- [ ] **Step 5:** Commit `feat(data): Room storage, settings, token cipher, AppGraph`.

### Task 8: Delivery — Telegram, SMS, retry worker

**Files:**
- Create: `core/RetryPolicy.kt`, `core/TelegramResponse.kt`, `delivery/TelegramClient.kt`, `delivery/SmsSender.kt`, `delivery/Dispatcher.kt`, `delivery/DeliveryWorker.kt`
- Test: `core/RetryPolicyTest.kt`, `core/TelegramResponseTest.kt`

**Interfaces:**
- Produces:
  - `object RetryPolicy { fun nextAttemptAt(attempts: Int, firstAttemptAt: Long, now: Long, retryAfterMs: Long? = null): Long? }` — `null` = give up (now − first ≥ 24 h); delay = `retryAfterMs ?: min(30_000 * 2^attempts, 3_600_000)`.
  - `sealed interface SendResult { data object Sent; data class Retry(val reason: String, val retryAfterMs: Long?); data class Fatal(val reason: String) }`
  - `object TelegramResponse { fun classify(httpCode: Int, body: String?): SendResult; fun parseChats(getUpdatesJson: String): List<DetectedChat> }`, `data class DetectedChat(val chatId: Long, val title: String, val topicId: Long?)`. Rules: 200+`"ok":true` → Sent; 429 → Retry with `parameters.retry_after`×1000; 400/401/403/404 → Fatal with `description`; 5xx/IOException → Retry.
  - `class TelegramClient(tokenProvider: suspend () -> String?) { suspend fun send(chatId: String, topicId: Long?, html: String): SendResult; suspend fun detectChats(): Result<List<DetectedChat>>; suspend fun test(chatId: String, topicId: Long?, deviceName: String): SendResult }` — `HttpURLConnection`, 15 s timeouts, form-encoded `chat_id`, `text`, `parse_mode=HTML`, `message_thread_id` when set, `disable_web_page_preview=true`; never put the token in exceptions/logs.
  - `class SmsSender(context) { suspend fun send(number: String, text: String, subscriptionId: Int?): SendResult }` — `SmsManager.createForSubscriptionId` when set; multipart; awaits the sent `PendingIntent` result (unique request code per send, 60 s timeout → Retry); `RESULT_OK` → Sent, radio off/no service → Retry, others → Fatal.
  - `class Dispatcher(graph) { suspend fun sendDue(now: Long) }` — loads due outbox rows, sends, applies `RetryPolicy`; **`DeliveryWorker`** (CoroutineWorker) calls it; `Dispatcher.schedule(context)` enqueues unique one-time work `"delivery"` with `ExistingWorkPolicy.REPLACE` (so the delay tracks the earliest due row), network constraint, initial delay = earliest `nextAttemptAt`.

- [ ] **Step 1: Write failing tests:** `RetryPolicy` (attempt 0 → +30 s; attempt 10 → +1 h cap; retryAfter wins; first+24 h → null); `TelegramResponse.classify` for 200 ok, 429 `{"ok":false,"parameters":{"retry_after":7}}` → Retry(…, 7000), 400 `chat not found` → Fatal, 502 → Retry; `parseChats` on a getUpdates sample with a private chat, a group, and a forum-topic message (dedupe by chatId+topicId; title = `title` or `first_name`/`username`).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Run gate → PASS.
- [ ] **Step 5:** Commit `feat(delivery): Telegram/SMS senders with retrying outbox`.

### Task 9: Incoming pipeline, receiver, catch-up scan

**Files:**
- Create: `core/IncomingSmsAssembler.kt`, `core/MessageKey.kt`, `core/CatchUpWindow.kt`, `pipeline/ContactLookup.kt`, `pipeline/IncomingProcessor.kt`, `pipeline/InboxCatchUp.kt`, `app/src/debug/java/com/viswa2k/smsforwarder/debug/InjectSmsReceiver.kt` (+ debug manifest)
- Replace: `SMSReceiver.kt` → `pipeline/SmsReceiver.kt` (manifest: `.pipeline.SmsReceiver`, keep `BROADCAST_SMS` permission + priority)
- Test: `core/IncomingSmsAssemblerTest.kt`, `core/MessageKeyTest.kt`, `core/CatchUpWindowTest.kt`

**Interfaces:**
- Produces:
  - `data class Pdu(val sender: String, val body: String, val sentAt: Long, val subscriptionId: Int?)`; `object IncomingSmsAssembler { fun assemble(pdus: List<Pdu>, receivedAt: Long): List<IncomingSms> }` — joins consecutive parts from the same sender in order.
  - `object MessageKey { fun of(sender: String, sentAt: Long, body: String): String }` = `"${PhoneNumbers.normalize-or-uppercase sender}|${sentAt / 1000}|${body.trim().hashCode()}"`.
  - `object CatchUpWindow { fun since(now: Long, floor: Long): Long = max(now - 24h, floor) }` (`floor` = `SettingsStore.catchUpFloor`, set to `now` at first run/migration and whenever the master switch turns on).
  - `class IncomingProcessor(graph) { suspend fun process(sms: IncomingSms, source: Source /* LIVE, CATCH_UP, DEBUG */) }`: key → `insertIfNew` (stop on duplicate) → master switch → `ContactLookup.kind(sender)` → `analyzer.analyze` → `RuleEngine.decide` → update log row → render each `PlannedSend` with `MessageFormatter` → outbox rows → `Dispatcher.sendDue(now)` inline → `Dispatcher.schedule` if anything left.
  - `class ContactLookup(context) { fun kind(sender: String, info: SenderInfo): SenderKind }` (BUSINESS for `info.isBusiness`; `UNVERIFIED` without `READ_CONTACTS`; else `PhoneLookup`).
  - `class InboxCatchUp(graph) { suspend fun run(now: Long) }` — queries `Telephony.Sms.Inbox` (`address`, `body`, `date_sent`, `date`, `sub_id`) with `date >= CatchUpWindow.since(...)`, ascending, feeds `process(…, CATCH_UP)`; uses `date_sent` (fallback `date`) as `sentAt` so keys match live PDUs (`SmsMessage.timestampMillis`).
  - `SmsReceiver`: `goAsync()`, `Telephony.Sms.Intents.getMessagesFromIntent`, assemble, process on `graph.appScope`, `finish()` in `finally`.
  - Debug: `adb shell am broadcast -a com.viswa2k.smsforwarder.debug.INJECT_SMS -n com.viswa2k.smsforwarder/.debug.InjectSmsReceiver --es from VM-HDFCBK --es body "text"`.

- [ ] **Step 1: Write failing tests:** two PDUs `("VM-HDFCBK","Rs.1250.00 debited from A/c XX4521 … ")` + `("VM-HDFCBK","Avl Bal Rs.23410.55")` → one `IncomingSms` with the joined body; PDUs from two senders → two messages; `MessageKey.of("+919840012345", 1_000_500, " hi ") == MessageKey.of("09840012345", 1_000_900, "hi")`; `CatchUpWindow.since(now, floor = now - 1h) == now - 1h`, `since(now, 0) == now - 24h`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Gate → PASS.
- [ ] **Step 5:** Commit `feat(pipeline): incoming SMS processor, receiver and catch-up scan`.

### Task 10: Reliability — service, boot, watchdog, health, problem alerts

**Files:**
- Create: `service/ForwarderService.kt`, `service/BootReceiver.kt`, `service/WatchdogWorker.kt`, `service/HealthChecker.kt`, `service/OemSettings.kt`, `service/ProblemAlerter.kt`, `core/ProblemAlerts.kt`
- Delete: `SMSForwarderService.kt`, `ServiceRestartReceiver.kt` (manifest updated: `FOREGROUND_SERVICE_SPECIAL_USE`, `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Forwards incoming SMS to the user's Telegram/SMS destinations"/>`, boot receiver for `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`)
- Test: `core/ProblemAlertsTest.kt`

**Interfaces:**
- Produces:
  - `ForwarderService.start(context)` (idempotent; `startForeground` with low-importance channel `forwarder_status`, text "Forwarding SMS · tap to open", no actions; `START_STICKY`; `onTaskRemoved` → re-start via `ForwarderService.start`). On start: `WatchdogWorker.ensure(context)`, run `InboxCatchUp` + `Dispatcher.sendDue` on `appScope`.
  - `BootReceiver` → `ForwarderService.start`, and on `BOOT_COMPLETED` records a `REBOOTED` info alert.
  - `WatchdogWorker.ensure(context)`: unique periodic work `"watchdog"`, 15 min, `KEEP`. `doWork`: start service if master on, `InboxCatchUp.run`, `Dispatcher.sendDue`, `LogRepository.prune`, `ProblemAlerter.check()`.
  - `enum class Problem(val key: String, val text: String)`: `SMS_PERMISSION("sms","SMS permission was removed")`, `CONTACTS_PERMISSION("contacts","Contacts permission was removed")`, `NOTIFICATIONS("notif","Notifications are off")`, `BATTERY_OPTIMIZED("battery","Battery optimisation is back on")`, `CLASSIFIER("classifier","Message filter failed to load")`, plus `destination failing > 1 h` keyed `"dest:<id>"` (text "Sending to <label> has failed for over an hour: <lastError>").
  - `object ProblemAlerts { data class Plan(val raise: List<String>, val resolve: List<String>, val newState: Map<String, Long>); fun plan(state: Map<String, Long>, current: Set<String>, now: Long): Plan }` — raise when new or last sent ≥ 24 h ago; resolve when in state but not current.
  - `class ProblemAlerter(graph) { suspend fun check(); suspend fun info(text: String) }` — sends `"⚠ <device>: <text>"` / `"✅ <device>: resolved — <text>"` / `"ℹ <device>: <text>"` straight via `TelegramClient` to `alertsDestinationId` (default: first Telegram destination); not via rules/outbox.
  - `data class HealthItem(val id: String, val title: String, val ok: Boolean, val fix: Intent?)`; `class HealthChecker(context) { fun items(): List<HealthItem> }` — SMS (receive/read/send), contacts, notifications, battery unrestricted (`PowerManager.isIgnoringBatteryOptimizations`), OEM auto-start (manual confirm from `setupConfirmedOem`), classifier loaded.
  - `object OemSettings { fun intents(manufacturer: String): List<Intent>; fun hint(manufacturer: String): String }` — Samsung (battery → "Never sleeping apps"), realme/oppo/oneplus (`com.coloros.safecenter` startup manager), xiaomi/redmi/poco (`com.miui.securitycenter` autostart), vivo/iqoo (`com.iqoo.secure`/`com.vivo.permissionmanager`), fallback `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`; caller tries each until one resolves.

- [ ] **Step 1: Write failing `ProblemAlertsTest`:** new problem → raised; same problem 2 h later → not raised; 25 h later → raised again; problem gone → resolved and removed from state.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement all files; `MainActivity.onCreate` calls `ForwarderService.start` when master on. **Step 4:** Gate → PASS.
- [ ] **Step 5:** Commit `feat(service): 24x7 service, boot/watchdog, health checks, Telegram problem alerts`.

### Task 11: v1 migration

**Files:**
- Create: `core/V1Migration.kt`, `data/prefs/V1MigrationRunner.kt`
- Test: `core/V1MigrationTest.kt`

**Interfaces:**
- Produces: `data class V1Settings(val token: String, val chatIds: List<String>, val smsNumber: String, val deviceAlias: String, val serviceOn: Boolean, val telegramOn: Boolean, val smsOn: Boolean, val skipContacts: Boolean)`; `data class MigrationPlan(val token: String?, val destinations: List<Destination> /* ids 0 */, val assignToRules: List<Int> /* indexes into destinations */, val sender: SenderCondition, val deviceName: String, val masterOn: Boolean)`; `object V1Migration { fun plan(v1: V1Settings?): MigrationPlan }`. `V1MigrationRunner.runOnce(graph)` reads old keys (`TELEGRAM_API_KEY`, `TELEGRAM_USER_IDS`, `SMS_TO_NUMBER`, `DEVICE_ALIAS`, `SMS_FORWARD_SERVICE`, `IS_BY_TELEGRAM_ENABLED`, `IS_BY_SMS_ENABLED`, `IS_SKIP_CONTACTS`) from the v1 DataStore `"settings"`, applies the plan (encrypt token, insert destinations, `DefaultRules.create`), sets `catchUpFloor = now`, `migrationDone = true`, deletes v1 cloud files (`filesDir/cloud_upload_queue`) and the v1 store file. Runs from `ForwarderApp.onCreate` on `appScope` before the service starts. Fresh install (`v1 == null`) → only default rules with no destinations.

- [ ] **Step 1: Write failing tests:** chats `"111, 222"` → two TELEGRAM destinations "Chat 1", "Chat 2"; telegram off + sms on → only the SMS destination assigned; `skipContacts` → `NOT_IN_CONTACTS`; blank alias → deviceName `""` (UI falls back to `Build.MODEL`); `null` → no destinations, master off.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement. **Step 4:** Gate → PASS.
- [ ] **Step 5:** Commit `feat: migrate v1 settings on first launch`.

### Task 12: UI shell, theme, navigation, Home

**Files:**
- Create: `ui/theme/{Color,Theme,Type}.kt` (replace old), `ui/nav/{Routes,AppNavHost}.kt`, `ui/common/{TypeBadge,TypeChip,StatusChip,SectionTitle}.kt`, `ui/home/{HomeScreen,HomeViewModel}.kt`
- Modify: `MainActivity.kt` (edge-to-edge, `AppNavHost`; permission prompting moves to onboarding in Task 15)
- Delete: `ui/PlaceholderScreen.kt`, old `ui/screen/*`, `UserPreferences.kt`

**Interfaces:**
- Consumes: repositories (Task 7), `HealthChecker` (Task 10).
- Produces: `Routes.HOME, RULES, RULE_EDIT("rules/{id}"), LOG, SETTINGS, ONBOARDING`; bottom bar with 4 tabs (Home ⌂, Rules, Log, Settings) as in the mock; `TypeBadge(type)`, `TypeChip(type)`, `StatusChip(state)` reused by later screens.

- [ ] **Step 1:** Implement Home per mock: gradient hero (master switch writes `SettingsStore.masterOn`; when turned on, set `catchUpFloor = now` and start the service; when any Health item is red show a "Needs attention" line linking to Settings), counts from `todayCounts`, rules card with switches (`setEnabled`), 3 recent log rows, "Set up Telegram or SMS" card when no destinations exist.
- [ ] **Step 2:** `./gradlew testDebugUnitTest assembleDebug` → PASS; install on a device/emulator and check Home renders in light and dark mode.
- [ ] **Step 3:** Commit `feat(ui): theme, navigation and Home`.

### Task 13: Rules list and rule editor

**Files:**
- Create: `ui/rules/{RulesScreen,RulesViewModel,RuleEditorScreen,RuleEditorViewModel}.kt`
- Test: `ui/rules/RuleEditorStateTest.kt` (pure state reducer)

**Interfaces:**
- Produces: `data class RuleEditorState(...)` with `fun toggleType(t: MessageType)` (selecting **All messages** clears types; selecting a type clears All), `fun toggleDestination(id)`, `val canSave: Boolean` (name non-blank, ≥1 type or All, ≥1 destination), and `fun preview(): List<String>` rendering up to two sample messages (OTP/Transaction/Missed call samples from the mock, through the real `MessageAnalyzer` + `MessageFormatter`, Telegram channel).

- [ ] **Step 1: Write failing reducer tests** (All ↔ types exclusivity; `canSave` false with no destination; preview for a Transaction rule starts with "💸").
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement screens per mock: list with switches + move up/down, FAB "New rule", fixed "Never forwarded" card; editor fields name, type chips (SPAM not shown), sender segmented control, destination checklist, style segmented + custom template field with token help `%s sender · %m message · %r device · %o OTP · %a amount · %t type`, preview bubbles, Save, Delete (confirm dialog). **Step 4:** Gate → PASS.
- [ ] **Step 5:** Commit `feat(ui): rules list and editor`.

### Task 14: Log screen

**Files:**
- Create: `ui/log/{LogScreen,LogViewModel,LogDetailSheet}.kt`

**Interfaces:**
- Consumes: `LogRepository.recent`, `OutboxRepository.resend`, `LogRepository.clear`.

- [ ] **Step 1:** Implement per mock: filter chips All / Forwarded / Skipped / Failed-retrying; rows with sender, time, body (masked when `masked`), type chip, status chip, note line ("OTPs → Me" or skip reason text: Paused / "Spam / promotion" / "No rule matched" / "Contacts permission missing"); detail bottom sheet with extracted line, per-destination status + error, **Resend** for FAILED; "Clear log" with confirm.
- [ ] **Step 2:** Gate → PASS; inject 3 debug SMS (OTP, debit, personal) and check rows/filters on device.
- [ ] **Step 3:** Commit `feat(ui): forwarding log`.

### Task 15: Settings, Test-a-message, onboarding, PIN, export, updater

**Files:**
- Create: `ui/settings/{SettingsScreen,SettingsViewModel,TelegramSection,SmsSection,TestMessageSection,HealthSection,DeviceSection,PinDialog,ExportImport}.kt`, `ui/onboarding/OnboardingScreen.kt`, `core/SettingsExport.kt`
- Test: `core/SettingsExportTest.kt`

**Interfaces:**
- Produces: `object SettingsExport { fun toJson(rules: List<Rule>, destinations: List<Destination>, deviceName: String, token: String?): String; fun fromJson(json: String): Imported }` (`token` included only when non-null; format `{"version":2,…}`; import replaces rules + destinations after confirm).

- [ ] **Step 1: Write failing `SettingsExportTest`:** round-trip equality; JSON without the token has no `"token"` key; unknown `version` → error.
- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3:** Implement Settings per mock: Telegram (token field saved encrypted, chats list with Test + delete, **Add chat** dialog with Detect (`detectChats`) or manual chat id + optional topic id; alerts destination picker), SMS numbers (label, number, SIM picker when >1 active subscription, Test), **Test a message** (sender + body → detected type, extracted line, sender kind, matched rules, destinations, Telegram preview; nothing sent), Health list (Fix opens each item's intent; OEM item uses `OemSettings` + hint + "Done" checkbox), Device name, optional PIN (4–6 digits, SHA-256 hash in `SettingsStore`; when set, master switch, rule edits and Settings require it), Export/Import via SAF with "include bot token" checkbox, app version + "Check for update" (`update.UpdateManager`). Onboarding: shown while any of SMS/contacts/notifications permissions are missing — the Health list full-screen with a "Grant" button requesting runtime permissions, then battery + OEM steps, then Home.
- [ ] **Step 4:** Gate → PASS.
- [ ] **Step 5:** Commit `feat(ui): settings, test tool, health, onboarding, PIN, export`.

### Task 16: Docs, brain vault, screenshots, version, device smoke test

**Files:**
- Modify: `CLAUDE.md` (new architecture, commands, debug inject hook, constraints above), `README.md` (features, permissions, setup steps incl. battery/auto-start, Telegram topics), `docs/wiki.html` changelog entry "v2.0.0", `app/build.gradle.kts` default version.

- [ ] **Step 1:** Update docs; complete every `docs/brain/` note (no stub sections left), refresh `00 Index.md`, add a `Log.md` entry for v2.0.0.
- [ ] **Step 2:** `./gradlew testDebugUnitTest assembleDebug assembleRelease` → BUILD SUCCESSFUL (release unsigned locally is fine).
- [ ] **Step 3:** Device smoke test (`./gradlew installDebug`, upgrading over an installed v1 if available): migration kept token/chats; inject OTP, debit, Airtel missed call, personal from a phone number, Myntra promo → first three reach Telegram with smart formatting, last two skipped with reasons; turn Wi-Fi + data off, inject OTP → Log shows Retrying; turn network on → delivered; swipe app from Recents → inject again → still forwarded; reboot → notification back and an "ℹ rebooted" alert in Telegram; revoke Contacts → "⚠ Contacts permission was removed" within ~15 min.
- [ ] **Step 4:** Screenshots on `127.0.0.1:6562` with realistic demo data (dummy bot token/chats, the mock's sample messages injected via the debug hook): `adb -s 127.0.0.1:6562 exec-out screencap -p > screenshots/v2/<name>.png` for home, rules, rule_editor, log, settings, test_message, health — light theme, plus home in dark. Delete the old `screenshots/*.png` + `screenshots/v1.0` references from README and replace the README screenshot table with the v2 set.
- [ ] **Step 5:** Commit `docs: v2.0.0 docs, brain vault, screenshots and changelog`.
