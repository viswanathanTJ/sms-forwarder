# Cloud Encrypted SMS Sync — Design Spec

**Date:** 2026-06-20
**Status:** Approved design (Firebase backend; pending user spec review)
**App:** `com.viswa2k.smsforwarder` (Android, Kotlin + Jetpack Compose, minSdk 29 / target 34)

## Goal

Add a third forwarding channel (alongside SMS and Telegram): a **family/team
fleet** of devices that upload incoming SMS to **Firebase** in **end-to-end
encrypted** form, and let authorized devices **read other devices' SMS** from
the cloud — live (Firestore snapshot listeners) and/or via push notification
(FCM). A **super-admin** controls who may sign in and which device may read which.

## Backend choice — Firebase (single vendor)

Since FCM (push) is required regardless, the whole backend is Firebase to keep one
vendor, one auth system, one SDK, and minimal setup: **Firestore** (storage +
realtime), **Firebase Auth** (email/password + Google), **Cloud Functions**
(`onCreate` → FCM), **FCM** (push). End-to-end encryption is orthogonal — the
backend only ever stores ciphertext.

## Cast & terminology

- **Super-admin (UserX)** — the single master account, identified by an email
  supplied by the user. Administers the email allow-list and the access matrix.
  Holds a key pair like any reader and is **always** a fan-out recipient (can
  decrypt everything via its own inbox).
- **Sender device** — receives SMS and uploads them encrypted. Holds **no
  decryption key**.
- **Reader device** — authorized to view another device's SMS. Holds its own
  private key (hardware-sealed).
- A single device can be **both** a sender and a reader.

## Core security model — hybrid envelope encryption

Mental model: **public key = an open padlock** (safe to publish); **private key =
the only key that opens it** (never leaves the device).

### Keys
- Each **reader/admin device** generates an asymmetric **identity key pair** using
  **HPKE (RFC 9180)** — `DHKEM(P-256, HKDF-SHA256)` + `HKDF-SHA256` +
  `AES-256-GCM`. This is a **portable, standards-based wire format** so a future
  web client (`hpke-js` / Web Crypto) interoperates with Android (Tink's RFC 9180
  HPKE primitive). The **private key is sealed by an Android Keystore master key**
  (hardware-backed, non-exportable). The **public key is published** to the
  device's Firestore doc.
- **Sender devices need no key pair** — they only download recipients' public keys.
- Plaintext SMS and unwrapped keys exist **only in memory**, never persisted.

### Encrypting an SMS — sender fan-out
1. Generate a random one-time **Data Key (DEK)** (AES-256-GCM).
2. Serialize the SMS as JSON `{ sender, body, originalTimestamp, deviceAlias }`
   and encrypt it with the DEK → `ciphertext` (+ unique nonce). **All metadata is
   inside the blob.**
3. Determine **authorized recipients** for this source device (super-admin always
   included) from the access matrix, and read their **public keys**.
4. For **each recipient**: **HPKE-seal** a copy of the DEK to that recipient's
   public key, and write one document into **that recipient's inbox**:
   `inbox/{recipientDeviceId}/messages/{messageId}` = `{ messageId, sourceDeviceId,
   ciphertext, nonce, wrappedDek, createdAt }`. The same `messageId` is shared
   across all copies of one logical SMS.
5. **Discard the DEK.** The sender retains nothing that can decrypt.

The ciphertext is duplicated per recipient — SMS are tiny and recipient counts
small, so this idiomatic Firestore **fan-out-on-write** is the right trade: it
makes reads, realtime, and security rules trivial.

### Decrypting — reader/admin
- Sign in → unlock the private key from Keystore (biometric gate on the screen) →
  read **own inbox** docs → for each, HPKE-open `wrappedDek` → AES-GCM-decrypt
  `ciphertext` → plaintext **in memory only**.
- The **admin** is always a recipient, so the admin's own inbox contains **every**
  message → "see any device's SMS" = read the admin inbox.

### Access control = cryptographic + Security Rules (defense in depth)
- The super-admin's **access matrix** drives *whose inbox a sender fans out to*.
  A non-authorized device gets **no inbox doc and no wrapped DEK** → cannot decrypt.
- Firestore **Security Rules** enforce: a reader reads only its own inbox; only
  admins delete. Because crypto is the primary gate, a rules slip can't leak
  plaintext (no wrapped DEK exists for an unauthorized device).

### Key rotation / leak containment
- **Per-message DEKs** are single-use.
- Device key pairs **rotate** (and on-demand); the newest public key is used for
  new fan-outs; old private key versions are retained in Keystore to read history.
- Super-admin can **revoke** a device → senders stop fanning out to it; rules deny
  its reads. Revocation is **forward-only** (admin re-fan-out is the backfill).

### Consequence: notifications carry no server-side plaintext
True E2E means Firestore/Cloud Functions/FCM never see plaintext. Push is a
**silent "data" FCM message** with no text; the app wakes, reads its inbox doc,
decrypts locally, then shows a **local notification** with real content.

## Authentication

- **Firebase Auth**: email/password **and** Google sign-in (via Credential Manager →
  `GoogleAuthProvider`).
- **Email allow-list**: only emails present in the `authorized_emails` Firestore
  collection may use the app. Enforced by **Security Rules** (deny all data access
  unless the signed-in email has an `authorized_emails/{email}` doc). The in-app
  flow also checks this on sign-in and signs out unauthorized accounts.
- **Bootstrap**: a one-time admin setup writes the super-admin's
  `authorized_emails/{email}` doc with role `admin` (done via a setup script or the
  Firebase console). The user supplies this email.

## Firestore data model

```
authorized_emails/{email}
  { role: 'admin'|'member', addedBy: string, createdAt: ts }

devices/{deviceId}
  { ownerEmail: string, alias: string, fcmToken: string|null,
    publicKey: string(base64 Tink public keyset), keyVersion: int,
    revoked: bool, createdAt: ts }

access_matrix/{readerDeviceId}__{sourceDeviceId}     // admin-managed
  { readerDeviceId: string, sourceDeviceId: string, grantedBy: string }

subscriptions/{readerDeviceId}__{sourceDeviceId}     // reader-managed (notify pref)
  { readerDeviceId: string, sourceDeviceId: string, notify: bool }

inbox/{recipientDeviceId}/messages/{messageId}       // per-recipient encrypted copy
  { messageId: string, sourceDeviceId: string, sourceAlias: string,
    ciphertext: string(base64), nonce: string(base64),
    wrappedDek: string(base64), createdAt: ts }
```

Notes:
- Active public key lives on the `devices` doc (senders only need the current key).
- `messageId` is shared across all recipients' copies so the admin can delete every
  copy of one logical SMS (collection-group query on `messages` by `messageId`).
- `subscriptions` only controls the **notify** flag; fan-out to a reader happens
  whenever the access matrix grants it (so the reader can always see in-app).

## Security Rules summary

Helpers: `isAuthorized()` = signed-in email has an `authorized_emails` doc;
`isAdmin()` = that doc's `role == 'admin'`; `ownsDevice(id)` = `devices/{id}.ownerEmail == email`.

- `authorized_emails`: read if `isAuthorized()`; write if `isAdmin()`.
- `devices`: read if `isAuthorized()`; create/update if owner (`ownerEmail == email`) or admin.
- `access_matrix`: read if `isAuthorized()` (senders need it); write if `isAdmin()`.
- `subscriptions`: read/write if `ownsDevice(readerDeviceId)`; create requires a
  matching `access_matrix` doc to exist.
- `inbox/{deviceId}/messages/{messageId}`:
  - **read** if `ownsDevice(deviceId)` or `isAdmin()`;
  - **create** if `isAuthorized()` (a sender fanning out into recipients' inboxes);
  - **delete** if `isAdmin()`.

## Delivery / receive paths

1. **Live (Realtime):** a reader attaches a Firestore **snapshot listener** to its
   own `inbox/{deviceId}/messages` → new docs appear and are decrypted on-device.
2. **Push (FCM, ON by default):** a Cloud Function `onCreate` of
   `inbox/{deviceId}/messages/{messageId}` reads that device's `fcmToken` and the
   `subscriptions/{deviceId}__{sourceDeviceId}.notify` flag; if notify (default true)
   and a token exists, it sends a **silent FCM data message**. The app wakes, reads
   the inbox doc, decrypts, and posts a **local notification**.
3. **Manual:** with notify off, the reader simply sees messages when it opens the
   cloud screen (the snapshot listener / one-shot read).

## Android app changes

### New components
- **`FirebaseProvider`** — exposes `FirebaseAuth`, `FirebaseFirestore`, `FirebaseMessaging`.
- **`AuthRepository`** — email/password + Google sign-in (Firebase Auth), allow-list
  check, sign-out, auth-state `Flow`.
- **`CryptoManager`** / **`HpkeCrypto`** / **`CloudSmsPayload`** — Tink HPKE + AES-GCM,
  Keystore-sealed keyset (unchanged from the crypto design).
- **`DeviceRepository`** — register/update this device (alias, fcmToken, publicKey,
  keyVersion), fetch fleet devices, admin device ids, recipients' public keys.
- **`AccessRepository`** — admin: `authorized_emails` + `access_matrix` + revoke;
  reader: allowed sources + `subscriptions` (notify).
- **`CloudMessageRepository`** — sender: build + fan-out encrypted copies to each
  recipient inbox; reader: list/stream + decrypt own inbox; admin: delete (single
  by `messageId` across inboxes / all for a source).
- **`RecipientSelector`** — pure recipient-selection logic (JVM-testable).
- **`CloudUploadQueue`** — offline retry queue (encrypted artifacts only).
- **`SmsCloudUploader`** — orchestrates encrypt + fan-out from the receiver.
- **`SmsForwarderFcmService`** — silent data push → read inbox doc + decrypt → local
  notification; `onNewToken` updates the device's `fcmToken`.

### New screens (Compose)
- **Sign-in** (email/password + Google; "not authorized" message).
- **Cloud SMS** — lists own-inbox messages (grouped by source device), decrypted
  on-device; snapshot-listener live updates; per-message + bulk **delete** (admin only).
- **Watch / subscriptions** — reader picks which allowed sources to notify on.
- **Admin** (super-admin only) — manage `authorized_emails`, the access matrix,
  device revoke.
- Settings additions: enable **cloud channel** (sender), enable **receive** (reader),
  account/sign-in status.

### Existing code touch points
- `SmsReceiver` — after SMS/Telegram forwarding, call `SmsCloudUploader` when the
  cloud channel is enabled.
- `UserPreferences` — new keys: `isCloudChannelEnabled`, `isReceiveEnabled`,
  `cloudDeviceId`.
- `MainActivity` — route to sign-in when no session; init Firebase, FCM token,
  device registration; flush offline queue.

## Dependencies to add
- Firebase: `firebase-bom`, `firebase-auth-ktx`, `firebase-firestore-ktx`,
  `firebase-messaging-ktx`; `google-services` Gradle plugin.
- Google Tink (`com.google.crypto.tink:tink-android`) — RFC 9180 HPKE + AES-GCM.
- AndroidX Biometric; Credential Manager + Google Identity (`googleid`) for Google sign-in.
- `kotlinx-serialization-json` (payload), `kotlinx-coroutines-play-services` (await Tasks).
- Compose Navigation.
- **No** supabase-kt / Ktor.

## Firebase project assets (out of the app)
- `firestore.rules` — the rules above.
- `firestore.indexes.json` — composite indexes (e.g., collection-group `messages`
  by `messageId`; `subscriptions` lookups).
- Cloud Function `notifyReader` (TypeScript, `firebase-functions` v2) on
  `inbox/{deviceId}/messages/{messageId}` create → FCM via `firebase-admin`.
- One-time admin bootstrap (script/console) to seed the super-admin
  `authorized_emails` doc.

## Error handling
- **Offline / upload failure** (sender): queue the per-recipient encrypted artifacts
  locally and retry with backoff; never store plaintext.
- **Not signed in / expired**: reader screens prompt re-auth; sender defers upload.
- **Biometric declined**: decryption skipped; UI shows a locked/retry state.
- **Revoked / unauthorized**: rules deny; UI shows "access revoked / not authorized."
- **Key mismatch** (reader rotated before sender fanned out): that inbox doc shows
  "cannot decrypt" instead of crashing; admin re-fan-out fixes it.

## Testing strategy
- **Unit (JVM):** `HpkeCrypto` round-trips, payload serialization, `RecipientSelector`,
  `CloudUploadQueue`.
- **Repository:** fake Firestore (or emulator) — sender fans out to exactly the
  authorized recipient inboxes (+ admin); reader decrypts only its own inbox.
- **Rules tests:** `@firebase/rules-unit-testing` — unauthorized email blocked; a
  non-authorized reader can't read another device's inbox; non-admin can't delete.
- **Instrumented:** Keystore-sealed keyset seal/open; FCM data message → local
  notification.

## Out of scope (YAGNI for v1)
- Cryptographic revocation of historical messages (forward-only; admin re-fan-out).
- Multi-tenant separation between unrelated owners (single trusted fleet only).
- Web cloud viewer / admin console — **deferred to a later sub-project**. The crypto
  wire format (RFC 9180 HPKE), Firestore model, and rules are portable so a web
  reader/admin can be added later with no crypto migration.
- Attachments/MMS.

## Web enrollment (future sub-project) — QR pairing driven by Android

Identity stays **per-device** (keys never leave the device). A new web client gets
full history via **Android-authorized QR pairing**, WhatsApp-Web style:

1. **Web** generates its own HPKE key pair (private key non-extractable in the
   browser), shows a QR of `{ webPublicKey, nonce }` (public key + nonce only — no secret).
2. **Android** (signed in, holds the key) scans → explicit **"Approve web login?"**.
3. On approval Android: registers the web as a device + publishes `webPublicKey`;
   authorizes it in the access matrix; **re-fans-out / re-wraps** historical DEKs to
   the web's key (Android decrypts each from its own inbox and re-seals into the web's
   inbox); and mints a web session (Firebase custom token via a Cloud Function).
4. **Web** signs in and sees **all history**.

Security: QR is single-use/short-lived; scanning requires explicit on-device
confirmation; the web private key never leaves the browser; Android never transmits
a key — it only re-seals DEKs to the web's public key. No data-model migration needed.
