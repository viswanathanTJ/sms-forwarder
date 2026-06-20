# Cloud SMS — Firebase backend

## One-time setup
1. `firebase login` and `firebase use <project-id>`.
2. Add Android apps in the Firebase console for `com.viswa2k.smsforwarder` and
   `com.viswa2k.smsforwarder.debug`; download `google-services.json` to `app/`.
3. Enable Auth providers: Email/Password and Google.
4. Seed the super-admin (see `scripts/bootstrap-admin.md`).

## Deploy
- Rules + indexes: `firebase deploy --only firestore` (run inside `firebase/`).
- Functions: `cd functions && npm install && npm run build && firebase deploy --only functions`.

## Local test
- `firebase emulators:start` for Firestore + Functions + Auth emulators.
- Rules unit tests: see Task 22.
