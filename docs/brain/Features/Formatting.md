---
tags: [feature]
updated: 2026-10-25
---
# Formatting

> Message formatter: Telegram HTML and SMS text, escaping, truncation.

## What the user can do
Pick a style per rule: **Smart** (type-aware layout), **Original** (sender + raw text) or **Custom** (template with tokens `%s %m %r %o %a %t`, literal `\n` = newline, unavailable tokens render empty).

## Screens & entry points
No screen of its own: the rule editor's style picker (later task) and the dispatcher call `MessageFormatter.format(...)`.

## Data
`IncomingSms(sender, body, sentAt, receivedAt, subscriptionId)` in, `Analysis` + `Style` + `Channel` + device name in, one string out. Code: `core/MessageFormatter.kt`, tests `core/MessageFormatterTest.kt`.

## How it works
- Telegram is HTML: all SMS/user text goes through `escapeHtml` (`&`, `<`, `>`); SMS channel is plain text.
- Smart layouts: OTP (code in `<code>`), transaction (amount, optional `To merchant · mode`, `A/c XX1234 · Bank`, balance, device · sender), missed call (`caller at time`, `device · via <raw sender>`), other types (`emoji Type`, `sender · device`, `<i>original</i>`). A transaction without a parsed amount/direction uses the generic layout. Fraud level adds a `⚠ Suspicious` style line.
- Missed-call time is the text from the SMS, else the received time in the given zone.
- Truncation (4096): only the original-text part is shortened and `…` appended; the cut never splits an `&amp;` entity and closing tags (`</i>`) are always kept. Custom shortens only the `%m` value.
- Money: `Money.format` with `₹` glued to the number on Telegram, `Rs` on SMS.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- `.00` is stripped from amounts (`₹1,250`, not `₹1,250.00`).
- Token replacement is single-pass, so an SMS containing `%s` is not re-expanded.
- `Analysis.senderName` is the bank/brand display name, else the title-cased header (`AMAZON` -> `Amazon`).
