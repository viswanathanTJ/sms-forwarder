---
tags: [feature]
updated: 2026-10-25
---
# Rules

> Rule model and rule engine: which message types go to which destination.

## What the user can do
Define rules: message types (or all) + sender condition (anyone / not in contacts / contacts only) -> destinations, with a style. Reorder rules; the first matching rule decides a shared destination's style.

## Screens & entry points
None yet (pure core). UI arrives in the rules screen task; default rules are created on first run / migration.

## Data
`core/Rule.kt`: `Rule`, `Destination`, enums `SenderCondition`/`Style`/`Channel` (stored codes, never renumber). `core/DefaultRules`: OTPs, Bank transactions, Missed calls (enabled only if destinations exist), Unknown senders (all messages, not in contacts, disabled).

## How it works
`RuleEngine.decide(...)`: master off -> PAUSED; SPAM -> SPAM. Rules sorted by `position`; enabled rules with at least one existing destination, matching type and sender, are matched. Union of destinations, one send each, style/template from the first (lowest position) rule. Sender match: NOT_IN_CONTACTS = BUSINESS or UNKNOWN_NUMBER; CONTACTS_ONLY = CONTACT; UNVERIFIED (no contacts permission) matches ANYONE only. Nothing matched -> NO_RULE, or CONTACTS_PERMISSION_MISSING only when the sender is UNVERIFIED and an enabled, type-matching rule with an existing destination was excluded solely by its sender condition. Loop guard: SMS destinations whose number `PhoneNumbers.same` the sender are dropped (Telegram kept); if that empties sends, matched ids stay and skip is null.

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- `PhoneNumbers.normalize`: last 10 digits; alphanumeric IDs uppercased, so `VM-HDFCBK` is not `HDFCBK`.
- A rule whose destination ids all dangle is ignored (-> NO_RULE).
- UNVERIFIED with no match reports NO_RULE unless a sender condition alone excluded a viable rule (then CONTACTS_PERMISSION_MISSING).
