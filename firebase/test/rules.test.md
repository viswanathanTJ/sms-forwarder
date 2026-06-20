# Firestore rules tests (@firebase/rules-unit-testing)

Run against the emulator (`firebase emulators:start --only firestore`). Assert:
1. A user whose email has no `authorized_emails` doc is DENIED reads on every collection.
2. An authorized member can read `devices`, `access_matrix`; cannot write `access_matrix`.
3. A member CANNOT read another device's `inbox/{otherDeviceId}/messages`.
4. A member CAN read `inbox/{ownDeviceId}/messages`.
5. A non-admin CANNOT delete an inbox message; an admin CAN.
6. Any authorized device CAN create a doc in another device's inbox (fan-out).

Skeleton:
```js
import { initializeTestEnvironment, assertFails, assertSucceeds } from "@firebase/rules-unit-testing";
// load firestore.rules, seed authorized_emails, run the assertions above.
```
