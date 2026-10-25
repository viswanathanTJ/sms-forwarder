# SMS Forwarder v2

Android app (`com.viswa2k.smsforwarder`) that reads each incoming SMS, works out what it is (OTP, bank transaction, missed call, ...) and forwards it by **rules** to **Telegram chats** and/or **SMS numbers**. Built for phones that sit unattended 24x7 (for example a parent's phone) while the owner watches the messages remotely.

No cloud, no accounts, no Firebase. The app talks only to `api.telegram.org` (sending) and the GitHub releases API (update check).

## Features

- **Smart classification**: OTP, Transaction, Balance alert, Bills & dues, Delivery, Missed call, Other important, Personal. Spam, promotions and scams are never forwarded.
- **Rules**: pick message types, sender (Anyone / Not in contacts / Contacts only), destinations and a style. All matching rules are combined and each destination gets the message once. Rules can be reordered.
- **Destinations**: one Telegram bot with many chats (optional forum topic per chat, so each phone gets its own topic) and many SMS numbers (SIM choice on dual-SIM phones). Each has a Test button.
- **Message styles**: Smart (for example `OTP 482913`, `Debited ₹1,250 / To Swiggy · UPI / A/c XX4521 · HDFC Bank`), Original, or Custom with tokens `%s` sender, `%m` message, `%r` device, `%o` OTP, `%a` amount, `%t` type.
- **Delivery queue**: every send is stored and retried (30 s, doubling to 1 h) until delivered, up to 24 h, then shown as Failed. Long multi-part SMS are joined and forwarded once.
- **Log**: last 48 hours with filters, details and Resend. OTP codes are masked one hour after receipt.
- **Home**: master switch, today's Forwarded / Skipped / Not sent counts, rules, recent messages.
- **Health**: checklist for SMS, contacts, notifications, battery optimisation and OEM auto-start, each with a Fix button.
- **Telegram problem alerts** so nobody has to look at the phone (see below).
- **Test a message**: paste any text and see its type, the matching rules and the exact message that would be sent.
- **Optional PIN** that locks the master switch, rules and Settings, with back-off after wrong tries.
- **Export / Import** of settings as JSON (bot token excluded unless you tick the box).
- Light and dark themes, in-app update from GitHub releases.

## How it works

```
incoming SMS -> classify -> rules -> destinations (Telegram / SMS) -> delivery queue -> Log
```

1. A manifest-registered receiver wakes the app for every SMS, even after a swipe from Recents or a reboot. Nothing polls between messages.
2. The message is classified once. A missed-call alert from the carrier wins first, then spam is dropped.
3. Every enabled rule that matches adds its destinations; the first matching rule decides the style for a destination.
4. Each (message x destination) is queued, sent right away, and retried in the background if the network or Telegram fails.
5. A foreground service, a 15-minute watchdog and a 24 h inbox catch-up scan make sure nothing is missed after a reboot or while the app process was dead. Catch-up never forwards old inbox history or messages received while forwarding was paused.

A loop guard stops an SMS from one of your own SMS destination numbers being forwarded by SMS again.

## Setup (once per phone)

1. Install the APK and open the app. The first-run checklist asks for permissions: SMS (receive/read/send), Contacts, Notifications.
2. **Battery**: Settings > Health > Battery > Fix, and choose **Unrestricted** (no optimisation).
3. **Auto-start / do not sleep** (Health opens the right screen where it can):
   - Samsung: Battery > Background usage limits > Never sleeping apps > add SMS Forwarder.
   - Realme / Oppo / OnePlus: App info > Battery > allow background activity, and enable Auto-launch.
   - Xiaomi / Redmi / Poco: Security > Autostart > enable, and Battery saver > No restrictions.
   - Vivo / iQOO: Settings > Battery > Background power consumption > allow, and enable Autostart.
4. **Telegram**: create a bot with @BotFather and copy the token. Add the bot to your chat or group (for a forum group, add it as admin and note the topic). In Settings > Telegram paste the token, then Add chat > **Detect** (send the bot a message first) or enter the chat id (and topic id) by hand. Press Test.
5. **SMS number** (optional): Settings > SMS numbers > Add, and choose the SIM if the phone has two.
6. Review the rules on the Rules tab (OTPs, Bank transactions, Missed calls are on by default; Unknown senders is off) and attach destinations.
7. Set the **Alerts destination** and device name in Settings, optionally set a **PIN**, then tap **Start forwarding** on the last onboarding step.

Opening the app always restarts the service and runs the catch-up scan, so "please open the app" is a safe recovery step over the phone.

## Telegram problem alerts

Sent to the Alerts destination (default: first Telegram chat), once per problem, repeated daily while unresolved, with a "resolved" message when it clears. No heartbeat messages.

- SMS permission removed
- Contacts permission removed
- Notification permission removed
- Battery optimisation turned back on
- Message classifier unavailable
- A destination failing for more than an hour (with the last error)
- Forwarding is switched off (master switch paused)
- Phone rebooted (info)

## Upgrading from v1

v2 installs over v1 (same package and signing). On first launch it migrates the Telegram token and chat ids, SMS number, device alias and master switch, creates the default rules (only for channels that were enabled; "Not in contacts" if Skip Contacts was on), and deletes the old cloud settings. v1 message formats are replaced by the Smart style. The cloud inbox and Firebase features are gone in v2.

## Permissions

- `RECEIVE_SMS`, `READ_SMS`: receive messages and run the catch-up scan.
- `SEND_SMS`: forward by SMS.
- `READ_CONTACTS`: tell contacts from unknown senders for rules.
- `READ_PHONE_STATE`: SIM picker on dual-SIM phones (asked only when needed).
- `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`: the quiet "Forwarding SMS" notification.
- `RECEIVE_BOOT_COMPLETED`: restart after reboot.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: ask for Unrestricted battery.
- `INTERNET`, `ACCESS_NETWORK_STATE`: Telegram and update check.
- `REQUEST_INSTALL_PACKAGES`: install updates downloaded from GitHub releases.

## Troubleshooting

- **Nothing arrives in Telegram**: open Settings > Health and fix every red item; press Test on the Telegram chat. A 401 means a wrong token; "chat not found" means the bot has not been added or messaged.
- **Log says "Contacts permission missing"**: grant Contacts, or make the rule's sender "Anyone".
- **Messages stop after a while**: battery must be Unrestricted and auto-start allowed for the brand (see Setup).
- **Log says "No rule matched"**: add a rule for that type, or use the "All messages" chip.
- **Not sent tile is above zero**: those messages are retrying or failed; open the Log entry for the error and use Resend.
- **Forgot the PIN**: clear the app's data (settings are lost; export a backup beforehand).
- **Missed-call text not recognised**: paste it into Test a message and report the wording.

## Screenshots

| Home | Rules | Rule editor |
|---|---|---|
| ![Home](screenshots/v2/home.png) | ![Rules](screenshots/v2/rules.png) | ![Rule editor](screenshots/v2/rule_editor.png) |

| Log | Settings | Test a message |
|---|---|---|
| ![Log](screenshots/v2/log.png) | ![Settings](screenshots/v2/settings.png) | ![Test a message](screenshots/v2/test_message.png) |

| Health | Home (dark) |
|---|---|
| ![Health](screenshots/v2/health.png) | ![Home dark](screenshots/v2/home_dark.png) |

## Build

```bash
./gradlew testDebugUnitTest assembleDebug
./gradlew assembleRelease
```

Requires JDK 17 (Android Studio JBR), Android SDK 36.

## Changelog

### v2.0.0
- Rebuilt around rules and destinations, smart classification, delivery queue with retries, Log, Health and Telegram problem alerts, PIN, export/import.
- Removed Firebase and the cloud inbox.

### v1.1.0
- Optimized message receiver, message configuration, device alias.

## Contributing

Contributions are welcome. Open an issue or submit a pull request.

## Acknowledgments

- Jetpack Compose, Telegram Bot API.
