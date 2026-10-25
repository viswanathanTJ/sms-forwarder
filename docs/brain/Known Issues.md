---
tags: [issues]
updated: 2026-10-25
---
# Known Issues

> Bugs and spec drift found while building v2. None yet.

- Settings import keeps each SMS destination's `simSubscriptionId` from the file; on another phone that id may not exist (send then fails until the number is re-added). Task 15.
- PIN entry has no attempt limit / back-off (4-digit PIN can be brute-forced by someone holding the phone). Task 15.
- The legacy privacy-policy `AlertDialog` in `MainActivity` still shows over onboarding on first launch. Task 15.
- v1 `SMSReceiver`, `SMSForwarderService` and `MainActivity` are temporary and are replaced by [[Incoming Pipeline]], [[Reliability]] and [[UI Screens]].

Back to [[00 Index]].
