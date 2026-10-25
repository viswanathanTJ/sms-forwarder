---
tags: [feature]
updated: 2026-10-25
---
# Rules

> Rule model and rule engine: which message types go to which destination.

## What the user can do
Define rules: message types (or all) + sender condition (anyone / not in contacts / contacts only) -> destinations, with a style. Reorder rules; the first matching rule decides a shared destination's style.

## Screens & entry points
Rules tab (`ui/rules/RulesScreen`): list with enable switch and move up/down per rule, FAB "New rule", fixed "Never forwarded" card. Editor (`rules/{id}`, 0 = new, `RuleEditorScreen`): name, type chips (All messages exclusive, SPAM never offered), sender segmented, destination checklist (empty -> "Add a Telegram chat or SMS number in Settings" + button, Save disabled), style segmented + custom template with token help, live Telegram preview from the real analyzer + formatter, Save, Delete with confirm. Save/delete/move/enable go through `requireUnlock`. A rule without destinations cannot be switched on (Rules list and Home). Pure reducer: `RuleEditorState` (tested). Default rules are created on first run / migration.

## Data
`core/Rule.kt`: `Rule`, `Destination`, enums `SenderCondition`/`Style`/`Channel` (stored codes, never renumber). `core/DefaultRules`: OTPs, Bank transactions, Missed calls (enabled only if destinations exist), Unknown senders (all messages, not in contacts, disabled). Fresh install: the defaults have no destination and are off; saving the FIRST destination attaches it to every default-named rule without destinations and switches on OTPs / Bank transactions / Missed calls (`DefaultRules.attachFirstDestination`, called by `DestinationRepository.save`). Renamed rules are not touched.

## How it works
`RuleEngine.decide(...)`: master off -> PAUSED; SPAM -> SPAM. Rules sorted by `position`; enabled rules with at least one existing destination, matching type and sender, are matched. Union of destinations, one send each, style/template from the first (lowest position) rule. Sender match: NOT_IN_CONTACTS = BUSINESS or UNKNOWN_NUMBER; CONTACTS_ONLY = CONTACT; UNVERIFIED (no contacts permission) matches ANYONE only. Nothing matched -> NO_RULE, or CONTACTS_PERMISSION_MISSING only when the sender is UNVERIFIED and an enabled, type-matching rule with an existing destination was excluded solely by its sender condition. Loop guard (spec 5.3): when the sender `PhoneNumbers.same` ANY configured SMS destination, every SMS send is dropped (Telegram kept); if that empties sends, matched ids stay and skip is null. `RuleEngine.canForward(rules, destinations)`: some enabled rule has an existing destination (Home warns when false).

## Connections
[[00 Index]] · [[Architecture Overview]] · [[Testing]]

## Gotchas
- `PhoneNumbers.normalize`: last 10 digits; alphanumeric IDs uppercased, so `VM-HDFCBK` is not `HDFCBK`.
- A rule whose destination ids all dangle is ignored (-> NO_RULE).
- UNVERIFIED with no match reports NO_RULE unless a sender condition alone excluded a viable rule (then CONTACTS_PERMISSION_MISSING).
