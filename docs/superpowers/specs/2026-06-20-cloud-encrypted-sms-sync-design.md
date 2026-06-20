# Cloud Encrypted SMS Sync — Design Spec

**Date:** 2026-06-20
**Status:** Approved design (pending user spec review)
**App:** `com.viswa2k.smsforwarder` (Android, Kotlin + Jetpack Compose, minSdk 29 / target 34)

## Goal

Add a third forwarding channel (alongside SMS and Telegram): a **family/team
fleet** of devices that upload incoming SMS to Supabase in **end-to-end
encrypted** form, and let authorized devices **read other devices' SMS** from
the cloud — live (Supabase Realtime) and/or via push notification (FCM). A
**super-admin** controls who may sign in and which device may read which.

## Cast & terminology

- **Super-admin (UserX)** — the single master account, identified by an email
  supplied by the user. Administers the email allow-list and the access matrix.
  Holds a key pair like any reader, and is **always** a recipient of every
  message (can decrypt everything).
- **Sender device** — a phone that receives SMS and uploads them encrypted. Holds
  **no decryption key**.
- **Reader device** — a phone authorized to view another device's SMS. Holds its
  own private key (hardware-sealed).
- A single device can be **both** a sender and a reader.

## Core security model — hybrid envelope encryption

Mental model: **public key = an open padlock** (safe to publish); **private key =
the only key that opens it** (never leaves the device).

### Keys
- Each **reader/admin device** generates an asymmetric **identity key pair** using
  **Google Tink hybrid encryption** (ECIES P-256 / HPKE). The **private keyset is
  sealed by an Android Keystore master key** (hardware-backed, non-exportable,
  `setUserAuthenticationRequired(true)` with a short validity window → biometric/PIN
  re-auth). The **public key** is published to Supabase.
- **Sender devices need no key pair** — they only download recipients' public keys.
- Plaintext SMS and unwrapped keys exist **only in memory**, never persisted.

### Encrypting (sender)
1. Generate a random one-time **Data Key (DEK)** (Tink AES-256-GCM AEAD).
2. Serialize the SMS as JSON `{ sender, body, originalTimestamp, deviceAlias }`
   and AEAD-encrypt it with the DEK → `ciphertext` (+ unique nonce). **All
   metadata is inside the blob.**
3. Fetch the **authorized recipients' public keys** for this source device
   (super-admin is always included) from `device_keys` filtered by `access_matrix`.
4. **Hybrid-wrap a copy of the DEK to each recipient's public key** (Tink
   `HybridEncrypt`) → one `wrapped_dek` per recipient.
5. Upload one `messages` row + N `message_keys` rows. **Discard the DEK.** The
   sender now holds nothing that can decrypt.

### Decrypting (reader/admin)
1. Sign in → unlock the private keyset from Keystore (biometric).
2. Fetch its own `message_keys` row → `HybridDecrypt` to recover the DEK.
3. AEAD-decrypt `ciphertext` → plaintext SMS **in memory only**.

### Access control = cryptographic + RLS (defense in depth)
- The super-admin's **access matrix** drives *whose public keys a sender wraps
  for*. An unauthorized device has **no `wrapped_dek`** → cannot decrypt even if
  it obtained the row. RLS additionally prevents it from reading the row at all.

### Key rotation / leak containment
- **Per-message DEKs** are single-use (inherently short-lived).
- **Device key pairs rotate every 30 days** (and on-demand). The newest public
  key is used for new wraps; older private key versions are retained in Keystore
  only to decrypt historical messages (non-exportable hardware-sealed, so leak
  risk is low). `device_keys.active` marks the current wrapping key.
- Super-admin can **revoke** a device → senders stop wrapping for it → it loses
  access to all future messages immediately; RLS blocks its reads.

### Consequence: notifications carry no server-side plaintext
Because this is true E2E, **Supabase and FCM never see plaintext**. Push is a
**silent "data" wake-up** with no message text; the app wakes, fetches, decrypts
locally, then shows a **local notification** with the real content.

## Authentication

- **Supabase Auth**: email/password **and** Google sign-in.
- **Email allow-list**: only emails present in `authorized_emails` may use the
  app. Enforced server-side via (a) RLS policies that deny all data access when
  `auth.email()` is not in `authorized_emails`, and (b) a Supabase **auth hook /
  trigger** that rejects sign-ups from non-listed emails. Client UI also gates,
  but the server is the source of truth.
- **Bootstrap**: a migration seeds `authorized_emails` with the super-admin email
  (role `admin`). The user will supply this email.

## Supabase schema

```sql
-- who may use the app
authorized_emails (
  email        text primary key,
  role         text not null check (role in ('admin','member')) default 'member',
  added_by     text,
  created_at   timestamptz default now()
)

-- a registered fleet device (owned by an authorized email)
devices (
  id           uuid primary key default gen_random_uuid(),
  owner_email  text not null references authorized_emails(email),
  alias        text not null,
  fcm_token    text,
  revoked      boolean not null default false,
  last_seen    timestamptz,
  created_at   timestamptz default now()
)

-- published public keys (no private material ever)
device_keys (
  id           uuid primary key default gen_random_uuid(),
  device_id    uuid not null references devices(id) on delete cascade,
  public_key   bytea not null,         -- Tink public keyset (serialized)
  alg          text not null default 'TINK_HYBRID_ECIES_P256',
  version      int  not null,
  active       boolean not null default true,
  created_at   timestamptz default now()
)

-- ADMIN-set: reader_device may read source_device's SMS
access_matrix (
  id               uuid primary key default gen_random_uuid(),
  reader_device_id uuid not null references devices(id) on delete cascade,
  source_device_id uuid not null references devices(id) on delete cascade,
  granted_by       text not null,
  created_at       timestamptz default now(),
  unique (reader_device_id, source_device_id)
)

-- READER-set: which allowed sources this reader actually watches + notify pref
subscriptions (
  id               uuid primary key default gen_random_uuid(),
  reader_device_id uuid not null references devices(id) on delete cascade,
  source_device_id uuid not null references devices(id) on delete cascade,
  notify           boolean not null default true,   -- ON by default
  created_at       timestamptz default now(),
  unique (reader_device_id, source_device_id)
)

-- the encrypted SMS (sender/body/timestamp are INSIDE ciphertext)
messages (
  id               uuid primary key default gen_random_uuid(),
  source_device_id uuid not null references devices(id) on delete cascade,
  ciphertext       bytea not null,
  nonce            bytea not null,
  created_at       timestamptz default now()      -- upload time only
)

-- per-recipient wrapped DEK
message_keys (
  id                  uuid primary key default gen_random_uuid(),
  message_id          uuid not null references messages(id) on delete cascade,
  recipient_device_id uuid not null references devices(id) on delete cascade,
  wrapped_dek         bytea not null,
  unique (message_id, recipient_device_id)
)
```

### RLS policy summary
- **Global gate**: every policy requires `auth.email() in (select email from authorized_emails)`.
- `authorized_emails`, `access_matrix`: **write = admin only**; readable by authorized users (senders need the matrix to compute recipients).
- `devices`: a user inserts/updates **their own** devices (`owner_email = auth.email()`); admin can do all.
- `device_keys`: a device publishes **its own** public keys; everyone authorized can read public keys.
- `subscriptions`: a reader manages **its own** subscriptions (device owned by `auth.email()`), limited to sources allowed by `access_matrix`.
- `messages`: **insert** by any authorized non-revoked device; **select** only where a `message_keys` row exists for a device owned by `auth.email()`; **delete = admin only**.
- `message_keys`: **insert** by the uploading sender; **select** only rows whose `recipient_device_id` is owned by `auth.email()`.

## Delivery / receive paths

1. **Live (Realtime)**: reader subscribes to `messages` (Realtime), filtered by
   its watched sources; new rows appear in the cloud screen and are decrypted
   on-device.
2. **Push (FCM, ON by default)**: a Supabase **Database Webhook** on
   `messages` INSERT calls an **Edge Function**, which looks up `subscriptions`
   (notify = true) for that `source_device_id`, then sends a **silent FCM data
   message** to each reader's `fcm_token`. The app wakes, fetches + decrypts, and
   posts a **local notification** with the decrypted content.
3. **Manual**: if a reader turns notify off, it simply sees messages when it
   opens the cloud screen.

## Android app changes

### New components
- **`SupabaseClient`** (singleton) — configures supabase-kt (Auth, Postgrest,
  Realtime, Functions) with project URL + anon key (build config).
- **`AuthRepository`** — email/password + Google sign-in, session persistence,
  sign-out; exposes auth state as `Flow`.
- **`CryptoManager`** — Tink-based: keyset generation sealed by Keystore master
  key (biometric-gated), publish public key, `wrapDek(publicKey)`,
  `unwrapDek()`, AEAD encrypt/decrypt, rotation.
- **`DeviceRepository`** — register/update this device (alias, fcm_token),
  publish/rotate public keys, fetch fleet devices.
- **`AccessRepository`** — admin: read/write `authorized_emails` + `access_matrix`;
  reader: read allowed sources, manage `subscriptions`.
- **`CloudMessageRepository`** — sender: build + upload encrypted message + wrapped
  DEKs; reader: list/stream + decrypt; admin: delete (single/bulk).
- **`SmsCloudUploader`** — invoked from `SmsReceiver` to encrypt-and-upload (only
  when cloud channel enabled), reusing the existing skip-contacts/format logic
  where relevant.
- **`SmsForwarderFcmService`** (`FirebaseMessagingService`) — handles silent data
  pushes (fetch + decrypt + local notification) and `onNewToken` (update
  `devices.fcm_token`).

### New screens (Compose)
- **Sign-in screen** — email/password + Google button; shows "not authorized"
  on rejected emails.
- **Cloud SMS screen** — lists messages from watched devices (grouped by source
  device), decrypted on-device; pull-to-refresh + Realtime; per-message and bulk
  **delete** (visible to super-admin only).
- **Watch / subscriptions screen** — reader picks which allowed source devices to
  watch and toggles notify per device ("Which devices do you want me to notify?").
- **Admin screen** (super-admin only) — manage `authorized_emails`, fleet
  devices, and the access matrix (which reader may read which source); revoke
  devices.
- Settings additions: enable/disable the **cloud channel** (sender), enable
  **receive messages** (reader), and account/sign-in status.

### Existing code touch points
- `SmsReceiver` — after existing SMS/Telegram forwarding, call `SmsCloudUploader`
  when the cloud channel is enabled.
- `UserPreferences` — new keys: `isCloudChannelEnabled`, `isReceiveEnabled`,
  `deviceId`, `deviceAlias` (reuse existing), `isSuperAdmin` (derived from role),
  last key-rotation timestamp.
- `MainActivity` — route to sign-in when no session; initialize Supabase, FCM
  token, device registration on launch.

## Dependencies to add
- supabase-kt: `gotrue-kt`, `postgrest-kt`, `realtime-kt`, `functions-kt`
- Ktor client engine (`ktor-client-okhttp`) + `kotlinx-serialization-json`
- Google Tink (`com.google.crypto.tink:tink-android`)
- Firebase: `firebase-bom`, `firebase-messaging`; `google-services` Gradle plugin
- AndroidX Biometric (`androidx.biometric`) for Keystore auth prompts
- Credential Manager / Google Identity for Google sign-in

(Declared in `gradle/libs.versions.toml` where practical; follow existing
convention of raw coordinate strings in `app/build.gradle.kts` for new ones.)

## Supabase project assets (out of the app)
- SQL migrations: tables + RLS policies above; seed super-admin email.
- Edge Function `notify-readers` (TypeScript) invoked by the Database Webhook on
  `messages` INSERT → sends FCM data messages via the FCM HTTP v1 API using a
  service account secret.
- Database Webhook config on `messages` INSERT → `notify-readers`.

## Error handling
- **Offline / upload failure** (sender): queue encrypted messages locally
  (DataStore/file) and retry with backoff; never store plaintext on disk.
- **Not signed in / session expired**: reader screens prompt re-auth; sender
  upload defers until session restored.
- **Biometric unlock declined**: decryption is skipped; UI shows a locked state
  with a retry action.
- **Revoked / unauthorized device**: server returns no rows; UI shows "access
  revoked / not authorized."
- **Key mismatch** (e.g., reader rotated before sender wrapped): message shows
  "cannot decrypt" rather than crashing; admin can re-wrap.

## Testing strategy
- **Unit (JVM)**: `CryptoManager` round-trip (wrap/unwrap, AEAD encrypt/decrypt),
  message serialization, recipient-selection logic from the access matrix,
  rotation picking the active key. Tink runs on JVM for these.
- **Repository tests**: mock supabase-kt responses; verify a sender wraps for
  exactly the authorized recipient set (+ admin), and a reader decrypts only its
  own `message_keys`.
- **RLS tests** (SQL / Supabase local): unauthorized email blocked; non-authorized
  reader cannot select another device's messages; non-admin cannot delete.
- **Instrumented**: Keystore-sealed keyset generation + biometric-gated unlock on
  device; FCM data message → local notification path.

## Out of scope (YAGNI for v1)
- Per-pair re-encryption / cryptographic access revocation of *historical*
  messages (revocation is forward-only; admin re-wrap is the escape hatch).
- Multi-tenant separation between unrelated owners (single trusted fleet only).
- Web cloud viewer (cloud screen is in the Android app, where Keystore exists).
- Attachments/MMS.
```
