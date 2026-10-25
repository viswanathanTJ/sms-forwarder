---
tags: [feature]
updated: 2026-10-25
---
# Delivery & Retries

> Telegram client, SMS sender, outbox, retry policy and delivery worker.

## What the user can do
- Nothing directly: forwarded messages are sent, and retried while offline, without any action.
- Failed rows (rejected by Telegram, SMS error, or 24 h elapsed) show in the Log with the error and can be re-sent.
- Add-chat **Detect** and the per-destination **Test** button use `TelegramClient.detectChats()` / `test()`.

## Screens & entry points
- `Dispatcher.sendDue(now)`: called by the pipeline right after enqueueing and by `DeliveryWorker`.
- `Dispatcher.schedule(context)`: (re)queues the retry job; `AppGraph.dispatcher`, `.telegram`, `.smsSender`.

## Data
- Outbox row: state 0 PENDING / 1 SENT / 2 FAILED, `attempts`, `firstAttemptAt`, `nextAttemptAt`, `lastError` (see [[Data Layer]]).
- Token comes from `SettingsStore.encryptedToken` through `TokenCipher.decrypt` on every send (never cached).

## How it works
- A pass loads due rows oldest first and sends them one by one (a mutex allows one pass at a time).
- `RetryPolicy`: delay = `retry_after` or 30 s doubling, capped at 1 h; `null` (give up) once 24 h have passed since the first attempt, and the row becomes FAILED.
- `TelegramResponse.classify`: 200 + `ok` is Sent; 429 is Retry with `retry_after`; 408/5xx/IO errors are Retry; other 4xx (400/401/403/404) are Fatal with Telegram's `description`.
- `SmsSender`: `divideMessage` + `sendMultipartTextMessage`, one sent `PendingIntent` per part with a unique request code, one-shot receiver per send (unique action, unregistered in `finally`), 60 s timeout is Retry. Sent only when all parts are OK; radio off / no service are Retry; other codes are Fatal.
- Send-boundary rules: blank text is replaced by the original SMS body (escaped for Telegram); Telegram text is hard-capped at 4096. A row whose destination was deleted is marked FAILED "destination removed".
- `schedule`: unique one-time work `"delivery"`, `REPLACE`, network `CONNECTED`, initial delay = max(0, earliest pending `nextAttemptAt` - now). Called at the end of every pass that leaves PENDING rows.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- The token is redacted (`bot<redacted>`): network errors are rethrown as fresh `IOException`s without cause.
- The network constraint also applies to SMS rows (accepted trade-off: an SMS retry waits for connectivity).
- `REPLACE` called from inside the running worker cancels that worker; `schedule` is therefore the last step of a pass.
- An SMS sent-confirmation timeout (60 s) is retried, so a very slow radio can produce a duplicate SMS.
- OTP rows older than 1 h have a masked body; a blank-text fallback after that sends the masked body.
- Tests: `RetryPolicyTest`, `TelegramResponseTest`, `DispatcherTextTest`.
