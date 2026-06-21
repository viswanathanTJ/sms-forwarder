# Firestore rules tests

Runnable suite: [`firestore.rules.test.js`](./firestore.rules.test.js) (Jest + `@firebase/rules-unit-testing`).

## Run

```bash
cd firebase/test && npm install
cd .. && firebase emulators:exec --only firestore --project demo-smsforwarder \
    "cd test && npx jest --runInBand"
```

(Or, with the Firestore emulator already running and `FIRESTORE_EMULATOR_HOST` exported, just `npm test`.)

## Coverage

- Unauthenticated and non-allow-listed users are denied reads on every collection.
- Members can read `devices` / `access_matrix` but cannot write `access_matrix` (admin-only).
- Inbox isolation: a member reads only its own device's inbox, never another's.
- Inbox messages are delete-admin-only and update-never (immutable).
- Fan-out create: an owner may write into its own inbox, or into an admin device's inbox, but cannot claim a `sourceDeviceId` it does not own.
- Device-takeover prevention: an owner may rename but cannot reassign `ownerEmail`, and may create only devices it owns.
- `access_requests` self-service: a signed-in user may create/read only their own `pending` request; the admin reviews and deletes.
