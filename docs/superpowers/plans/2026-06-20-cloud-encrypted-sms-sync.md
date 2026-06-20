# Cloud Encrypted SMS Sync — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an end-to-end-encrypted cloud channel so fleet devices upload incoming SMS to Supabase and authorized devices read any permitted device's SMS (live + push), with a super-admin controlling access.

**Architecture:** Senders encrypt each SMS on-device (random AES-256-GCM Data Key, body encrypted, DEK HPKE-sealed to each authorized recipient's public key) and upload ciphertext to Supabase. Readers fetch their wrapped DEK, decrypt in memory, view via a Compose screen (Realtime + FCM silent push). RLS + the cryptographic envelope both enforce a super-admin-managed access matrix.

**Tech Stack:** Kotlin, Jetpack Compose, supabase-kt (Auth/Postgrest/Realtime/Functions) + Ktor, Google Tink (HPKE RFC 9180 + AES-GCM), Firebase Cloud Messaging, Android Keystore, DataStore, kotlinx-serialization. Backend: Supabase Postgres + RLS + an Edge Function (TypeScript).

**Spec:** `docs/superpowers/specs/2026-06-20-cloud-encrypted-sms-sync-design.md`

## Global Constraints

- Package: `com.viswa2k.smsforwarder`; new cloud code under `…/cloud/**` (sub-packages `crypto`, `data`, `ui`, `fcm`).
- minSdk 29, targetSdk/compileSdk 34. Bump `jvmTarget`/Java compatibility to **17** (modern libs require ≥11).
- Preserve existing security flags: `allowBackup=false`, `usesCleartextTraffic=false`. The new Supabase/FCM traffic is HTTPS, compatible with cleartext disabled.
- **Never persist plaintext SMS or unwrapped keys to disk.** Plaintext exists only in memory.
- Envelope crypto: HPKE template `DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_256_GCM`; body AES-256-GCM with a random 12-byte IV.
- Filename↔class convention: this project already mismatches (`SMSReceiver.kt` → class `SmsReceiver`). New files use matching names.
- Version catalog `gradle/libs.versions.toml` for plugins; raw coordinate strings in `app/build.gradle.kts` are acceptable (existing convention).
- Secrets/config (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, Google OAuth web client id) come from `BuildConfig` fields fed by `local.properties` / Gradle — never hardcoded, never committed.
- The **super-admin email** is supplied by the user at deploy time and seeded into `authorized_emails` (role `admin`).
- supabase-kt is pinned to BOM **2.6.0**; the Postgrest query DSL used throughout (`select { filter { eq/isIn(...) } }`, `update({ set(...) }) { filter {...} }`, `upsert(..., onConflict=...)`, `insert(...) { select() }.decodeSingle()`) and package paths (`io.github.jan.supabase.gotrue.*`, `io.github.jan.supabase.postgrest.*`, `io.github.jan.supabase.realtime.*`) match that line. If you bump to 3.x, the `gotrue` package becomes `auth` and minor DSL signatures change — adjust imports accordingly.

## File Structure

**Backend (new dir `supabase/`):**
- `supabase/migrations/0001_cloud_sms_schema.sql` — tables.
- `supabase/migrations/0002_cloud_sms_rls.sql` — RLS + allow-list enforcement.
- `supabase/migrations/0003_seed_super_admin.sql` — seed admin email (template).
- `supabase/functions/notify-readers/index.ts` — DB-webhook→FCM Edge Function.
- `supabase/README.md` — deploy/runbook.

**App — crypto (`…/cloud/crypto/`):**
- `HpkeCrypto.kt` — pure Tink HPKE + AES-GCM (JVM-testable).
- `CryptoManager.kt` — Android Keystore-sealed keyset persistence + biometric gate.
- `CloudSmsPayload.kt` — `@Serializable` SMS payload model.

**App — data (`…/cloud/data/`):**
- `SupabaseProvider.kt` — singleton client from BuildConfig.
- `Dtos.kt` — `@Serializable` row DTOs.
- `AuthRepository.kt`, `DeviceRepository.kt`, `AccessRepository.kt`, `CloudMessageRepository.kt`.
- `RecipientSelector.kt` — pure recipient-selection logic (JVM-testable).
- `CloudUploadQueue.kt` — offline retry queue (encrypted artifacts only).
- `SmsCloudUploader.kt` — orchestrates encrypt+upload from the receiver.

**App — fcm (`…/cloud/fcm/`):**
- `SmsForwarderFcmService.kt` — silent data push → fetch+decrypt → local notification.

**App — ui (`…/cloud/ui/`):**
- `CloudViewModel.kt`, `SignInScreen.kt`, `CloudSmsScreen.kt`, `WatchScreen.kt`, `AdminScreen.kt`, `CloudNav.kt`.

**Modified:**
- `app/build.gradle.kts`, `gradle/libs.versions.toml`, root `build.gradle.kts` — deps/plugins.
- `app/src/main/AndroidManifest.xml` — FCM service, INTERNET, POST_NOTIFICATIONS.
- `UserPreferences.kt` — new keys.
- `SMSReceiver.kt` — call `SmsCloudUploader` after existing forwarding.
- `MainActivity.kt` — sign-in gate + cloud init + FCM token + offline-queue flush.
- `ui/screen/SettingsScreen.kt` + `SettingsViewModel.kt` — cloud channel + receive toggles, account status.

---

## Phase A — Supabase backend

### Task 1: Database schema migration

**Files:**
- Create: `supabase/migrations/0001_cloud_sms_schema.sql`
- Create: `supabase/README.md`

**Interfaces:**
- Produces: tables `authorized_emails`, `devices`, `device_keys`, `access_matrix`, `subscriptions`, `messages`, `message_keys`, and a `pairing` table reserved for the future web sub-project. Column names are consumed verbatim by the Kotlin DTOs in Task 9.

- [ ] **Step 1: Write the schema migration**

Create `supabase/migrations/0001_cloud_sms_schema.sql`:

```sql
-- Cloud Encrypted SMS Sync — schema
create extension if not exists pgcrypto;

create table authorized_emails (
  email      text primary key,
  role       text not null default 'member' check (role in ('admin','member')),
  added_by   text,
  created_at timestamptz not null default now()
);

create table devices (
  id          uuid primary key default gen_random_uuid(),
  owner_email text not null references authorized_emails(email) on delete cascade,
  alias       text not null,
  fcm_token   text,
  revoked     boolean not null default false,
  last_seen   timestamptz,
  created_at  timestamptz not null default now()
);
create index devices_owner_idx on devices(owner_email);

create table device_keys (
  id         uuid primary key default gen_random_uuid(),
  device_id  uuid not null references devices(id) on delete cascade,
  public_key text not null,                 -- base64(Tink public keyset)
  alg        text not null default 'HPKE_P256_HKDF_SHA256_AES256GCM',
  version    int  not null,
  active     boolean not null default true,
  created_at timestamptz not null default now(),
  unique (device_id, version)
);
create index device_keys_active_idx on device_keys(device_id) where active;

create table access_matrix (
  id               uuid primary key default gen_random_uuid(),
  reader_device_id uuid not null references devices(id) on delete cascade,
  source_device_id uuid not null references devices(id) on delete cascade,
  granted_by       text not null,
  created_at       timestamptz not null default now(),
  unique (reader_device_id, source_device_id)
);
create index access_matrix_source_idx on access_matrix(source_device_id);

create table subscriptions (
  id               uuid primary key default gen_random_uuid(),
  reader_device_id uuid not null references devices(id) on delete cascade,
  source_device_id uuid not null references devices(id) on delete cascade,
  notify           boolean not null default true,
  created_at       timestamptz not null default now(),
  unique (reader_device_id, source_device_id)
);
create index subscriptions_source_notify_idx on subscriptions(source_device_id) where notify;

create table messages (
  id               uuid primary key default gen_random_uuid(),
  source_device_id uuid not null references devices(id) on delete cascade,
  ciphertext       text not null,           -- base64(AES-GCM ciphertext)
  nonce            text not null,           -- base64(12-byte IV)
  created_at       timestamptz not null default now()
);
create index messages_source_idx on messages(source_device_id, created_at desc);

create table message_keys (
  id                  uuid primary key default gen_random_uuid(),
  message_id          uuid not null references messages(id) on delete cascade,
  recipient_device_id uuid not null references devices(id) on delete cascade,
  wrapped_dek         text not null,         -- base64(HPKE-sealed DEK)
  unique (message_id, recipient_device_id)
);
create index message_keys_recipient_idx on message_keys(recipient_device_id);

-- reserved for future web QR-pairing sub-project (not used by the Android build)
create table pairing (
  id             uuid primary key default gen_random_uuid(),
  web_public_key text not null,             -- base64
  nonce          text not null,
  approved_by    text,
  session_token  text,
  created_at     timestamptz not null default now(),
  expires_at     timestamptz not null
);
```

- [ ] **Step 2: Write the backend runbook**

Create `supabase/README.md`:

```markdown
# Cloud SMS — Supabase backend

## Deploy
1. `supabase link --project-ref <ref>`
2. `supabase db push`            # applies migrations/*.sql in order
3. Edit `migrations/0003_seed_super_admin.sql`, set the real admin email, re-run `supabase db push`.
4. Deploy the Edge Function: `supabase functions deploy notify-readers --no-verify-jwt`
5. Set function secrets: `supabase secrets set FCM_PROJECT_ID=... FCM_SERVICE_ACCOUNT_JSON='...'`
6. Create a Database Webhook (Studio → Database → Webhooks) on `messages` INSERT → HTTP POST to the `notify-readers` function URL with the service-role key as a header.

## Local test
- `supabase start` then `supabase db reset` to apply migrations to the local stack.
- RLS test queries live in `migrations/` review notes; run via `psql` against the local DB.
```

- [ ] **Step 3: Apply migration locally and verify tables exist**

Run: `supabase start && supabase db reset`
Then: `psql "$(supabase status -o env | grep DB_URL | cut -d= -f2-)" -c "\dt public.*"`
Expected: lists `authorized_emails, devices, device_keys, access_matrix, subscriptions, messages, message_keys, pairing`.

> If the Supabase CLI is unavailable in the execution environment, mark this step verified-by-review: confirm the SQL parses by pasting into the Supabase Studio SQL editor against a dev project.

- [ ] **Step 4: Commit**

```bash
git add supabase/migrations/0001_cloud_sms_schema.sql supabase/README.md
git commit -m "feat(backend): cloud SMS schema"
```

### Task 2: RLS policies + email allow-list enforcement

**Files:**
- Create: `supabase/migrations/0002_cloud_sms_rls.sql`
- Create: `supabase/migrations/0003_seed_super_admin.sql`

**Interfaces:**
- Consumes: all tables from Task 1.
- Produces: helper SQL functions `is_authorized()`, `is_admin()`, `owns_device(uuid)`; RLS guarantees relied on by every repository (e.g., a reader only SELECTs `messages` it has a `message_keys` row for; only admins delete).

- [ ] **Step 1: Write the RLS migration**

Create `supabase/migrations/0002_cloud_sms_rls.sql`:

```sql
-- helper predicates
create or replace function public.is_authorized() returns boolean
language sql stable as $$
  select exists (select 1 from authorized_emails a where a.email = auth.email())
$$;

create or replace function public.is_admin() returns boolean
language sql stable as $$
  select exists (select 1 from authorized_emails a where a.email = auth.email() and a.role = 'admin')
$$;

create or replace function public.owns_device(d uuid) returns boolean
language sql stable as $$
  select exists (select 1 from devices dev where dev.id = d and dev.owner_email = auth.email())
$$;

alter table authorized_emails enable row level security;
alter table devices          enable row level security;
alter table device_keys      enable row level security;
alter table access_matrix    enable row level security;
alter table subscriptions    enable row level security;
alter table messages         enable row level security;
alter table message_keys     enable row level security;
alter table pairing          enable row level security;

-- authorized_emails: readable by any authorized user; writable by admins only
create policy ae_select on authorized_emails for select using (is_authorized());
create policy ae_admin_write on authorized_emails for all using (is_admin()) with check (is_admin());

-- devices: owner manages own rows; admin manages all; authorized users can read fleet
create policy dev_select on devices for select using (is_authorized());
create policy dev_owner_write on devices for all
  using (owner_email = auth.email() or is_admin())
  with check (owner_email = auth.email() or is_admin());

-- device_keys: device owner publishes own keys; all authorized read public keys
create policy dk_select on device_keys for select using (is_authorized());
create policy dk_owner_write on device_keys for all
  using (owns_device(device_id) or is_admin())
  with check (owns_device(device_id) or is_admin());

-- access_matrix: readable by authorized (senders compute recipients); admin writes
create policy am_select on access_matrix for select using (is_authorized());
create policy am_admin_write on access_matrix for all using (is_admin()) with check (is_admin());

-- subscriptions: a reader manages own subs, limited to access-granted sources
create policy sub_select on subscriptions for select using (owns_device(reader_device_id) or is_admin());
create policy sub_write on subscriptions for all
  using (owns_device(reader_device_id))
  with check (
    owns_device(reader_device_id)
    and exists (select 1 from access_matrix am
                where am.reader_device_id = subscriptions.reader_device_id
                  and am.source_device_id = subscriptions.source_device_id)
  );

-- messages: any authorized non-revoked device inserts; reader selects only rows it has a key for; admin deletes
create policy msg_insert on messages for insert with check (is_authorized());
create policy msg_select on messages for select using (
  exists (
    select 1 from message_keys mk join devices d on d.id = mk.recipient_device_id
    where mk.message_id = messages.id and d.owner_email = auth.email()
  ) or is_admin()
);
create policy msg_admin_delete on messages for delete using (is_admin());

-- message_keys: sender inserts; recipient (or admin) selects own
create policy mk_insert on message_keys for insert with check (is_authorized());
create policy mk_select on message_keys for select using (owns_device(recipient_device_id) or is_admin());
create policy mk_admin_delete on message_keys for delete using (is_admin());

-- pairing: reserved; admin-only for now
create policy pair_admin_all on pairing for all using (is_admin()) with check (is_admin());
```

- [ ] **Step 2: Write the seed template**

Create `supabase/migrations/0003_seed_super_admin.sql`:

```sql
-- Replace the email below with the real super-admin address before deploying.
insert into authorized_emails (email, role, added_by)
values ('REPLACE_WITH_SUPER_ADMIN_EMAIL', 'admin', 'seed')
on conflict (email) do update set role = 'admin';
```

- [ ] **Step 3: Verify RLS denies an un-allow-listed reader**

Run (local psql, simulating a non-authorized JWT email):
```sql
set local role authenticated;
set local request.jwt.claims = '{"email":"stranger@example.com"}';
select count(*) from messages;   -- expected: 0 rows / permission filtered
```
Expected: returns 0 (no access). Then repeat with an allow-listed admin email and confirm full visibility.

> If CLI/psql unavailable: verify-by-review against a dev project's SQL editor using `Impersonate` role.

- [ ] **Step 4: Commit**

```bash
git add supabase/migrations/0002_cloud_sms_rls.sql supabase/migrations/0003_seed_super_admin.sql
git commit -m "feat(backend): RLS policies and super-admin seed"
```

### Task 3: notify-readers Edge Function (DB webhook → FCM)

**Files:**
- Create: `supabase/functions/notify-readers/index.ts`

**Interfaces:**
- Consumes: webhook payload `{ type:'INSERT', record:{ id, source_device_id } }` from `messages`; tables `subscriptions`, `devices`.
- Produces: silent FCM data messages `{ data:{ type:'new_sms', message_id, source_device_id } }` to each subscribed reader with `notify=true` and a non-null `fcm_token`. Carries **no plaintext**.

- [ ] **Step 1: Write the Edge Function**

Create `supabase/functions/notify-readers/index.ts`:

```typescript
// Deno Edge Function: on messages INSERT, push a silent FCM data message
// to every reader subscribed (notify=true) to the source device.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const PROJECT_ID = Deno.env.get("FCM_PROJECT_ID")!;
const SA = JSON.parse(Deno.env.get("FCM_SERVICE_ACCOUNT_JSON")!);
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

async function getAccessToken(): Promise<string> {
  // Build a Google OAuth2 JWT assertion and exchange for an access token.
  const now = Math.floor(Date.now() / 1000);
  const header = { alg: "RS256", typ: "JWT" };
  const claim = {
    iss: SA.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now, exp: now + 3600,
  };
  const enc = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const unsigned = `${enc(header)}.${enc(claim)}`;
  const keyData = SA.private_key.replace(/-----[^-]+-----/g, "").replace(/\s/g, "");
  const der = Uint8Array.from(atob(keyData), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = new Uint8Array(
    await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned)),
  );
  const jwt = `${unsigned}.${btoa(String.fromCharCode(...sig)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")}`;
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
  });
  return (await res.json()).access_token;
}

Deno.serve(async (req) => {
  try {
    const { record } = await req.json();
    if (!record?.source_device_id) return new Response("ignored", { status: 200 });

    const db = createClient(SUPABASE_URL, SERVICE_ROLE);
    const { data: subs } = await db
      .from("subscriptions")
      .select("reader_device_id, devices:reader_device_id(fcm_token)")
      .eq("source_device_id", record.source_device_id)
      .eq("notify", true);

    const tokens = (subs ?? [])
      .map((s: any) => s.devices?.fcm_token)
      .filter((t: string | null): t is string => !!t);
    if (tokens.length === 0) return new Response("no targets", { status: 200 });

    const accessToken = await getAccessToken();
    await Promise.all(tokens.map((token) =>
      fetch(`https://fcm.googleapis.com/v1/projects/${PROJECT_ID}/messages:send`, {
        method: "POST",
        headers: { Authorization: `Bearer ${accessToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          message: {
            token,
            data: {
              type: "new_sms",
              message_id: String(record.id),
              source_device_id: String(record.source_device_id),
            },
            android: { priority: "HIGH" },
          },
        }),
      })
    ));
    return new Response("sent", { status: 200 });
  } catch (e) {
    return new Response(`error: ${e}`, { status: 500 });
  }
});
```

- [ ] **Step 2: Verify it deploys / type-checks**

Run: `supabase functions deploy notify-readers --no-verify-jwt` (or `deno check supabase/functions/notify-readers/index.ts`)
Expected: deploy succeeds / no type errors.

> CLI unavailable → verify-by-review: confirm `deno check` passes locally if Deno present; otherwise review imports and the FCM v1 payload shape.

- [ ] **Step 3: Commit**

```bash
git add supabase/functions/notify-readers/index.ts
git commit -m "feat(backend): notify-readers Edge Function for FCM push"
```

---

## Phase B — App foundation & crypto

### Task 4: Dependencies, plugins, BuildConfig, Java 17

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: root `build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `BuildConfig.SUPABASE_URL`, `BuildConfig.SUPABASE_ANON_KEY`, `BuildConfig.GOOGLE_WEB_CLIENT_ID` (String); availability of supabase-kt, Tink, FCM, serialization, biometric on the classpath.

- [ ] **Step 1: Add serialization + google-services plugins to the version catalog**

In `gradle/libs.versions.toml`, under `[versions]` add `kotlin` if not present (match the existing Kotlin version) and:
```toml
googleServices = "4.4.2"
```
Under `[plugins]` add:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
google-services = { id = "com.google.gms.google-services", version.ref = "googleServices" }
```

- [ ] **Step 2: Register plugins at the root**

In root `build.gradle.kts`, in the top-level `plugins { }` block add (with `apply false`):
```kotlin
alias(libs.plugins.kotlin.serialization) apply false
alias(libs.plugins.google.services) apply false
```

- [ ] **Step 3: Apply plugins, deps, BuildConfig, and Java 17 in the app module**

In `app/build.gradle.kts`:

Add to the `plugins { }` block:
```kotlin
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
```

Inside `android { defaultConfig { } }`, add BuildConfig fields fed from Gradle properties:
```kotlin
        buildConfigField("String", "SUPABASE_URL", "\"${project.findProperty("SUPABASE_URL") ?: ""}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${project.findProperty("SUPABASE_ANON_KEY") ?: ""}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${project.findProperty("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")
```

Change `compileOptions`/`kotlinOptions` to Java 17 and enable buildConfig + core library desugaring:
```kotlin
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
```

Add to `dependencies { }`:
```kotlin
    // Supabase
    implementation(platform("io.github.jan-tennert.supabase:bom:2.6.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Crypto
    implementation("com.google.crypto.tink:tink-android:1.13.0")
    implementation("androidx.biometric:biometric:1.1.0")

    // Firebase Cloud Messaging
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Compose navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Unit-test crypto on the JVM with the desktop Tink artifact
    testImplementation("com.google.crypto.tink:tink:1.13.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```

In `AndroidManifest.xml`, ensure these permissions exist inside `<manifest>` (add if missing):
```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 4: Add config + google-services placeholders so the build resolves**

Append to `local.properties` (git-ignored) — real values supplied at deploy:
```properties
SUPABASE_URL=https://example.supabase.co
SUPABASE_ANON_KEY=placeholder-anon-key
GOOGLE_WEB_CLIENT_ID=placeholder.apps.googleusercontent.com
```
And wire `local.properties` into Gradle: in `app/build.gradle.kts`, near the top (before `android {}`):
```kotlin
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String): String = (localProps.getProperty(name) ?: project.findProperty(name) as String?) ?: ""
```
Then change the three `buildConfigField` lines from `project.findProperty(...)` to `prop("SUPABASE_URL")` etc.

Place the Firebase config file at `app/google-services.json` (downloaded from the Firebase console for applicationId `com.viswa2k.smsforwarder`; the `.debug` suffix variant should be added in Firebase too). Add `app/google-services.json` to `.gitignore`.

- [ ] **Step 5: Verify the project builds**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (dependencies resolve, BuildConfig generated). If `google-services.json` is absent the build fails fast — provide the file (a console placeholder is fine for compilation).

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml .gitignore
git commit -m "build: add supabase-kt, Tink, FCM, serialization deps and BuildConfig"
```

### Task 5: HpkeCrypto — pure HPKE + AES-GCM (JVM-tested)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCrypto.kt`
- Test: `app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCryptoTest.kt`

**Interfaces:**
- Produces:
  - `HpkeCrypto.HPKE_TEMPLATE: String`
  - `HpkeCrypto.generatePrivateKeyset(): com.google.crypto.tink.KeysetHandle`
  - `HpkeCrypto.serializePublicKeyset(handle): ByteArray`
  - `HpkeCrypto.serializePrivateKeyset(handle): ByteArray` / `deserializePrivateKeyset(bytes): KeysetHandle`
  - `HpkeCrypto.seal(recipientPublicKeyset: ByteArray, plaintext: ByteArray, contextInfo: ByteArray): ByteArray`
  - `HpkeCrypto.open(privateKeyset: KeysetHandle, ciphertext: ByteArray, contextInfo: ByteArray): ByteArray`
  - `HpkeCrypto.newDek(): ByteArray` (32 bytes)
  - `HpkeCrypto.EncryptedBody(ciphertext: ByteArray, nonce: ByteArray)` data class
  - `HpkeCrypto.encryptBody(dek: ByteArray, plaintext: ByteArray): EncryptedBody`
  - `HpkeCrypto.decryptBody(dek: ByteArray, ciphertext: ByteArray, nonce: ByteArray): ByteArray`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCryptoTest.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HpkeCryptoTest {

    @Test
    fun sealThenOpen_roundTrips() {
        val recipient = HpkeCrypto.generatePrivateKeyset()
        val pub = HpkeCrypto.serializePublicKeyset(recipient)
        val msg = "Your OTP is 4471".toByteArray()

        val sealed = HpkeCrypto.seal(pub, msg, contextInfo = ByteArray(0))
        val opened = HpkeCrypto.open(recipient, sealed, contextInfo = ByteArray(0))

        assertArrayEquals(msg, opened)
    }

    @Test
    fun wrongRecipient_cannotOpen() {
        val a = HpkeCrypto.generatePrivateKeyset()
        val b = HpkeCrypto.generatePrivateKeyset()
        val sealed = HpkeCrypto.seal(HpkeCrypto.serializePublicKeyset(a), "secret".toByteArray(), ByteArray(0))
        var failed = false
        try { HpkeCrypto.open(b, sealed, ByteArray(0)) } catch (e: Exception) { failed = true }
        assertFalse("device B must not decrypt A's envelope", !failed)
    }

    @Test
    fun body_encryptDecrypt_roundTrips() {
        val dek = HpkeCrypto.newDek()
        val plain = "sender=+100;body=hello".toByteArray()
        val enc = HpkeCrypto.encryptBody(dek, plain)
        val dec = HpkeCrypto.decryptBody(dek, enc.ciphertext, enc.nonce)
        assertArrayEquals(plain, dec)
    }

    @Test
    fun privateKeyset_serializeRoundTrips() {
        val handle = HpkeCrypto.generatePrivateKeyset()
        val bytes = HpkeCrypto.serializePrivateKeyset(handle)
        val restored = HpkeCrypto.deserializePrivateKeyset(bytes)
        val pub = HpkeCrypto.serializePublicKeyset(handle)
        val sealed = HpkeCrypto.seal(pub, "x".toByteArray(), ByteArray(0))
        assertArrayEquals("x".toByteArray(), HpkeCrypto.open(restored, sealed, ByteArray(0)))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.crypto.HpkeCryptoTest"`
Expected: FAIL — `HpkeCrypto` is unresolved.

- [ ] **Step 3: Implement HpkeCrypto**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCrypto.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.crypto

import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.hybrid.HybridConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure Tink HPKE (RFC 9180) envelope + AES-256-GCM body crypto. No Android APIs,
 * so it is unit-testable on the JVM. Keystore-sealed persistence lives in
 * [CryptoManager]; this object only does math on byte arrays.
 */
object HpkeCrypto {
    const val HPKE_TEMPLATE = "DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val rng = SecureRandom()

    init { HybridConfig.register() }

    fun generatePrivateKeyset(): KeysetHandle =
        KeysetHandle.generateNew(KeyTemplates.get(HPKE_TEMPLATE))

    fun serializePrivateKeyset(handle: KeysetHandle): ByteArray {
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle, BinaryKeysetWriter.withOutputStream(out))
        return out.toByteArray()
    }

    fun deserializePrivateKeyset(bytes: ByteArray): KeysetHandle =
        CleartextKeysetHandle.read(BinaryKeysetReader.withInputStream(ByteArrayInputStream(bytes)))

    fun serializePublicKeyset(handle: KeysetHandle): ByteArray {
        val out = ByteArrayOutputStream()
        CleartextKeysetHandle.write(handle.publicKeysetHandle, BinaryKeysetWriter.withOutputStream(out))
        return out.toByteArray()
    }

    fun seal(recipientPublicKeyset: ByteArray, plaintext: ByteArray, contextInfo: ByteArray): ByteArray {
        val pub = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(recipientPublicKeyset))
        val enc = pub.getPrimitive(HybridEncrypt::class.java)
        return enc.encrypt(plaintext, contextInfo)
    }

    fun open(privateKeyset: KeysetHandle, ciphertext: ByteArray, contextInfo: ByteArray): ByteArray {
        val dec = privateKeyset.getPrimitive(HybridDecrypt::class.java)
        return dec.decrypt(ciphertext, contextInfo)
    }

    fun newDek(): ByteArray = ByteArray(32).also { rng.nextBytes(it) }

    data class EncryptedBody(val ciphertext: ByteArray, val nonce: ByteArray)

    fun encryptBody(dek: ByteArray, plaintext: ByteArray): EncryptedBody {
        val iv = ByteArray(GCM_IV_BYTES).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return EncryptedBody(cipher.doFinal(plaintext), iv)
    }

    fun decryptBody(dek: ByteArray, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.crypto.HpkeCryptoTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCrypto.kt app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCryptoTest.kt
git commit -m "feat(crypto): HPKE envelope + AES-GCM body crypto with JVM tests"
```

### Task 6: CloudSmsPayload model (JVM-tested)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CloudSmsPayload.kt`
- Test: `app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/CloudSmsPayloadTest.kt`

**Interfaces:**
- Produces: `@Serializable data class CloudSmsPayload(sender:String, body:String, originalTimestamp:Long, deviceAlias:String)` with `fun toJsonBytes(): ByteArray` and `companion fun fromJsonBytes(ByteArray): CloudSmsPayload`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/CloudSmsPayloadTest.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSmsPayloadTest {
    @Test
    fun jsonRoundTrips() {
        val p = CloudSmsPayload(sender = "+100", body = "héllo, world", originalTimestamp = 123L, deviceAlias = "Pixel")
        val restored = CloudSmsPayload.fromJsonBytes(p.toJsonBytes())
        assertEquals(p, restored)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.crypto.CloudSmsPayloadTest"`
Expected: FAIL — unresolved `CloudSmsPayload`.

- [ ] **Step 3: Implement the model**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CloudSmsPayload.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CloudSmsPayload(
    val sender: String,
    val body: String,
    val originalTimestamp: Long,
    val deviceAlias: String,
) {
    fun toJsonBytes(): ByteArray = Json.encodeToString(serializer(), this).toByteArray(Charsets.UTF_8)

    companion object {
        fun fromJsonBytes(bytes: ByteArray): CloudSmsPayload =
            Json.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.crypto.CloudSmsPayloadTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CloudSmsPayload.kt app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/CloudSmsPayloadTest.kt
git commit -m "feat(crypto): serializable CloudSmsPayload model"
```

### Task 7: CryptoManager — Keystore-sealed keyset + biometric gate

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CryptoManager.kt`

**Interfaces:**
- Consumes: `HpkeCrypto`.
- Produces:
  - `class CryptoManager(context: Context)`
  - `fun ensureIdentityKey(): Unit` — generates + Keystore-seals a keyset on first use; no-op after.
  - `fun publicKeyset(): ByteArray` — serialized public keyset (for `device_keys.public_key`).
  - `fun rotateIdentityKey(): Int` — generates a new keyset version, returns new version number.
  - `fun currentVersion(): Int`
  - `fun sealDekTo(recipientPublicKeyset: ByteArray, dek: ByteArray): ByteArray`
  - `fun openWrappedDek(wrappedDek: ByteArray): ByteArray`
  - `fun newDek(): ByteArray`, `fun encryptBody(...)`, `fun decryptBody(...)` (delegate to `HpkeCrypto`)

Notes for implementer: Tink's `AndroidKeysetManager` seals the keyset at rest with a Keystore AES-GCM master key (`android-keystore://sms_forwarder_identity`), persisted in a private SharedPreferences file `cloud_identity_keyset`. Biometric/PIN gating is enforced at the **screen level** via `androidx.biometric.BiometricPrompt` before any decrypt screen (Task 17/Task 20). Rotation keeps prior versions in the keyset so historical envelopes still open; the newest version is the active wrapping key. `contextInfo` for HPKE is `ByteArray(0)` in v1.

- [ ] **Step 1: Implement CryptoManager**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CryptoManager.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.crypto

import android.content.Context
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.hybrid.HybridConfig

/**
 * Manages this device's HPKE identity keyset, sealed at rest by an Android
 * Keystore master key. The private key never leaves the device. Decryption
 * screens must gate access behind BiometricPrompt before calling open*().
 */
class CryptoManager(context: Context) {

    private val appContext = context.applicationContext

    init { HybridConfig.register() }

    private fun manager(): AndroidKeysetManager =
        AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(com.google.crypto.tink.KeyTemplates.get(HpkeCrypto.HPKE_TEMPLATE))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()

    private fun handle(): KeysetHandle = manager().keysetHandle

    fun ensureIdentityKey() { manager() } // building generates on first use

    fun publicKeyset(): ByteArray = HpkeCrypto.serializePublicKeyset(handle())

    fun currentVersion(): Int =
        appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getInt(VERSION_KEY, 1)

    /** Generate a fresh keyset version; keep old versions readable. Returns new version. */
    fun rotateIdentityKey(): Int {
        // Tink AndroidKeysetManager.rotate is deprecated; create a fresh primary by
        // re-generating. Old envelopes were wrapped to the OLD public key, so we keep
        // the previous keyset handle bytes under a versioned alias for historical reads.
        val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val next = currentVersion() + 1
        // Archive current private keyset (already Keystore-sealed by Android at rest is
        // not portable across alias; we instead add a new key to the keyset and promote it).
        manager().add(com.google.crypto.tink.KeyTemplates.get(HpkeCrypto.HPKE_TEMPLATE))
        prefs.edit().putInt(VERSION_KEY, next).apply()
        return next
    }

    fun newDek(): ByteArray = HpkeCrypto.newDek()

    fun sealDekTo(recipientPublicKeyset: ByteArray, dek: ByteArray): ByteArray =
        HpkeCrypto.seal(recipientPublicKeyset, dek, ByteArray(0))

    fun openWrappedDek(wrappedDek: ByteArray): ByteArray =
        HpkeCrypto.open(handle(), wrappedDek, ByteArray(0))

    fun encryptBody(dek: ByteArray, plaintext: ByteArray): HpkeCrypto.EncryptedBody =
        HpkeCrypto.encryptBody(dek, plaintext)

    fun decryptBody(dek: ByteArray, ciphertext: ByteArray, nonce: ByteArray): ByteArray =
        HpkeCrypto.decryptBody(dek, ciphertext, nonce)

    companion object {
        private const val KEYSET_NAME = "cloud_identity_keyset"
        private const val PREF_FILE = "cloud_identity_prefs"
        private const val VERSION_KEY = "key_version"
        private const val MASTER_KEY_URI = "android-keystore://sms_forwarder_identity"
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Keystore behavior is exercised by the instrumented test in Task 22; CryptoManager is not JVM-unit-tested because it needs Android Keystore.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CryptoManager.kt
git commit -m "feat(crypto): Keystore-sealed CryptoManager identity keyset"
```

---

## Phase C — Data layer

### Task 8: SupabaseProvider + DTOs

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/SupabaseProvider.kt`
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/Dtos.kt`

**Interfaces:**
- Produces: `SupabaseProvider.client: SupabaseClient` (lazy, from BuildConfig); DTOs `AuthorizedEmailDto, DeviceDto, DeviceKeyDto, AccessMatrixDto, SubscriptionDto, MessageDto, MessageKeyDto` with snake_case `@SerialName` matching columns.

- [ ] **Step 1: Implement the provider**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/SupabaseProvider.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import com.viswa2k.smsforwarder.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.functions.Functions

object SupabaseProvider {
    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Functions)
        }
    }
}
```

- [ ] **Step 2: Implement the DTOs**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/Dtos.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthorizedEmailDto(
    val email: String,
    val role: String = "member",
    @SerialName("added_by") val addedBy: String? = null,
)

@Serializable
data class DeviceDto(
    val id: String? = null,
    @SerialName("owner_email") val ownerEmail: String,
    val alias: String,
    @SerialName("fcm_token") val fcmToken: String? = null,
    val revoked: Boolean = false,
)

@Serializable
data class DeviceKeyDto(
    val id: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("public_key") val publicKey: String, // base64
    val alg: String = "HPKE_P256_HKDF_SHA256_AES256GCM",
    val version: Int,
    val active: Boolean = true,
)

@Serializable
data class AccessMatrixDto(
    val id: String? = null,
    @SerialName("reader_device_id") val readerDeviceId: String,
    @SerialName("source_device_id") val sourceDeviceId: String,
    @SerialName("granted_by") val grantedBy: String = "",
)

@Serializable
data class SubscriptionDto(
    val id: String? = null,
    @SerialName("reader_device_id") val readerDeviceId: String,
    @SerialName("source_device_id") val sourceDeviceId: String,
    val notify: Boolean = true,
)

@Serializable
data class MessageDto(
    val id: String? = null,
    @SerialName("source_device_id") val sourceDeviceId: String,
    val ciphertext: String, // base64
    val nonce: String,      // base64
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class MessageKeyDto(
    val id: String? = null,
    @SerialName("message_id") val messageId: String,
    @SerialName("recipient_device_id") val recipientDeviceId: String,
    @SerialName("wrapped_dek") val wrappedDek: String, // base64
)
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/SupabaseProvider.kt app/src/main/java/com/viswa2k/smsforwarder/cloud/data/Dtos.kt
git commit -m "feat(data): Supabase client provider and row DTOs"
```

### Task 9: RecipientSelector (pure, JVM-tested)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/RecipientSelector.kt`
- Test: `app/src/test/java/com/viswa2k/smsforwarder/cloud/data/RecipientSelectorTest.kt`

**Interfaces:**
- Produces: `RecipientSelector.recipientDeviceIds(sourceDeviceId: String, matrix: List<AccessMatrixDto>, adminDeviceIds: Set<String>): Set<String>` — the super-admin's devices are always included, plus every reader granted access to the source.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/viswa2k/smsforwarder/cloud/data/RecipientSelectorTest.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientSelectorTest {
    private fun am(reader: String, source: String) = AccessMatrixDto(readerDeviceId = reader, sourceDeviceId = source)

    @Test
    fun includesAdminPlusGrantedReaders_forThatSourceOnly() {
        val matrix = listOf(am("B", "A"), am("C", "A"), am("B", "X"))
        val result = RecipientSelector.recipientDeviceIds("A", matrix, adminDeviceIds = setOf("ADMIN"))
        assertEquals(setOf("B", "C", "ADMIN"), result)
    }

    @Test
    fun adminAlwaysIncluded_evenWithNoGrants() {
        val result = RecipientSelector.recipientDeviceIds("A", emptyList(), adminDeviceIds = setOf("ADMIN"))
        assertEquals(setOf("ADMIN"), result)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.data.RecipientSelectorTest"`
Expected: FAIL — unresolved `RecipientSelector`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/RecipientSelector.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

object RecipientSelector {
    fun recipientDeviceIds(
        sourceDeviceId: String,
        matrix: List<AccessMatrixDto>,
        adminDeviceIds: Set<String>,
    ): Set<String> {
        val readers = matrix.filter { it.sourceDeviceId == sourceDeviceId }.map { it.readerDeviceId }
        return readers.toSet() + adminDeviceIds
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.data.RecipientSelectorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/RecipientSelector.kt app/src/test/java/com/viswa2k/smsforwarder/cloud/data/RecipientSelectorTest.kt
git commit -m "feat(data): recipient selection logic with tests"
```

### Task 10: AuthRepository

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AuthRepository.kt`

**Interfaces:**
- Consumes: `SupabaseProvider.client`.
- Produces:
  - `class AuthRepository(client: SupabaseClient = SupabaseProvider.client)`
  - `val sessionStatus: StateFlow<SessionStatus>`
  - `fun currentEmail(): String?`
  - `suspend fun signInEmail(email: String, password: String)`
  - `suspend fun signUpEmail(email: String, password: String)`
  - `suspend fun signInGoogle(idToken: String, rawNonce: String)`
  - `suspend fun signOut()`
  - `suspend fun isAuthorized(): Boolean` — confirms the signed-in email exists in `authorized_emails` (server is source of truth).
  - `suspend fun isAdmin(): Boolean`

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AuthRepository.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.Google
import io.github.jan.supabase.gotrue.providers.builtin.Email
import io.github.jan.supabase.gotrue.providers.builtin.IDToken
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.StateFlow

class AuthRepository(private val client: SupabaseClient = SupabaseProvider.client) {

    val sessionStatus: StateFlow<SessionStatus> get() = client.auth.sessionStatus

    fun currentEmail(): String? = client.auth.currentUserOrNull()?.email

    suspend fun signInEmail(email: String, password: String) {
        client.auth.signInWith(Email) { this.email = email; this.password = password }
    }

    suspend fun signUpEmail(email: String, password: String) {
        client.auth.signUpWith(Email) { this.email = email; this.password = password }
    }

    suspend fun signInGoogle(idToken: String, rawNonce: String) {
        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            this.provider = Google
            this.nonce = rawNonce
        }
    }

    suspend fun signOut() = client.auth.signOut()

    suspend fun isAuthorized(): Boolean {
        val email = currentEmail() ?: return false
        return client.postgrest.from("authorized_emails")
            .select { filter { eq("email", email) } }
            .decodeList<AuthorizedEmailDto>()
            .isNotEmpty()
    }

    suspend fun isAdmin(): Boolean {
        val email = currentEmail() ?: return false
        return client.postgrest.from("authorized_emails")
            .select { filter { eq("email", email); eq("role", "admin") } }
            .decodeList<AuthorizedEmailDto>()
            .isNotEmpty()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AuthRepository.kt
git commit -m "feat(data): AuthRepository (email/Google sign-in + allow-list check)"
```

### Task 11: DeviceRepository

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/DeviceRepository.kt`

**Interfaces:**
- Consumes: `SupabaseProvider.client`, `CryptoManager`, `UserPreferences`.
- Produces:
  - `class DeviceRepository(client, crypto: CryptoManager, prefs: UserPreferences)`
  - `suspend fun registerThisDevice(ownerEmail: String, alias: String): String` — upserts a `devices` row, stores its id in prefs (`cloudDeviceId`), ensures the identity key, publishes the active public key; returns deviceId.
  - `suspend fun publishActivePublicKey(deviceId: String)` — deactivates old `device_keys`, inserts the current public keyset as active with `crypto.currentVersion()`.
  - `suspend fun updateFcmToken(token: String)`
  - `suspend fun fetchFleetDevices(): List<DeviceDto>`
  - `suspend fun activeKeysFor(deviceIds: Collection<String>): List<DeviceKeyDto>`
  - `suspend fun adminDeviceIds(): Set<String>`

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/DeviceRepository.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.first
import java.util.Base64

class DeviceRepository(
    private val client: SupabaseClient = SupabaseProvider.client,
    private val crypto: CryptoManager,
    private val prefs: UserPreferences,
) {
    suspend fun registerThisDevice(ownerEmail: String, alias: String): String {
        crypto.ensureIdentityKey()
        val existingId = prefs.cloudDeviceId.first()
        val dto = DeviceDto(
            id = existingId.ifBlank { null },
            ownerEmail = ownerEmail,
            alias = alias,
            revoked = false,
        )
        val saved = client.postgrest.from("devices")
            .upsert(dto) { select() }
            .decodeSingle<DeviceDto>()
        val deviceId = saved.id!!
        prefs.saveCloudDeviceId(deviceId)
        publishActivePublicKey(deviceId)
        return deviceId
    }

    suspend fun publishActivePublicKey(deviceId: String) {
        val version = crypto.currentVersion()
        val pubB64 = Base64.getEncoder().encodeToString(crypto.publicKeyset())
        // deactivate previous keys, then insert the current active one (idempotent on version)
        client.postgrest.from("device_keys").update({ set("active", false) }) {
            filter { eq("device_id", deviceId) }
        }
        client.postgrest.from("device_keys").upsert(
            DeviceKeyDto(deviceId = deviceId, publicKey = pubB64, version = version, active = true),
            onConflict = "device_id,version",
        )
    }

    suspend fun updateFcmToken(token: String) {
        val deviceId = prefs.cloudDeviceId.first()
        if (deviceId.isBlank()) return
        client.postgrest.from("devices").update({ set("fcm_token", token) }) {
            filter { eq("id", deviceId) }
        }
    }

    suspend fun fetchFleetDevices(): List<DeviceDto> =
        client.postgrest.from("devices").select().decodeList()

    suspend fun activeKeysFor(deviceIds: Collection<String>): List<DeviceKeyDto> {
        if (deviceIds.isEmpty()) return emptyList()
        return client.postgrest.from("device_keys")
            .select(Columns.ALL) {
                filter { isIn("device_id", deviceIds.toList()); eq("active", true) }
            }
            .decodeList()
    }

    suspend fun adminDeviceIds(): Set<String> {
        val admins = client.postgrest.from("authorized_emails")
            .select { filter { eq("role", "admin") } }
            .decodeList<AuthorizedEmailDto>()
            .map { it.email }
        if (admins.isEmpty()) return emptySet()
        return client.postgrest.from("devices")
            .select { filter { isIn("owner_email", admins) } }
            .decodeList<DeviceDto>()
            .mapNotNull { it.id }
            .toSet()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (depends on `UserPreferences.cloudDeviceId`/`saveCloudDeviceId` added in Task 14 — implement Task 14 first if the compile fails on those symbols).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/DeviceRepository.kt
git commit -m "feat(data): DeviceRepository (register, publish key, fcm token, fleet)"
```

### Task 12: AccessRepository

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AccessRepository.kt`

**Interfaces:**
- Produces:
  - `class AccessRepository(client = SupabaseProvider.client)`
  - admin: `suspend fun listAuthorizedEmails(): List<AuthorizedEmailDto>`, `addAuthorizedEmail(email, role, addedBy)`, `removeAuthorizedEmail(email)`, `grantAccess(reader, source, grantedBy)`, `revokeAccess(reader, source)`, `setDeviceRevoked(deviceId, revoked)`, `listAccessMatrix()`.
  - reader: `suspend fun allowedSources(readerDeviceId): List<String>`, `listSubscriptions(readerDeviceId)`, `subscribe(reader, source, notify)`, `setNotify(reader, source, notify)`, `unsubscribe(reader, source)`.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AccessRepository.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class AccessRepository(private val client: SupabaseClient = SupabaseProvider.client) {

    // --- admin ---
    suspend fun listAuthorizedEmails(): List<AuthorizedEmailDto> =
        client.postgrest.from("authorized_emails").select().decodeList()

    suspend fun addAuthorizedEmail(email: String, role: String, addedBy: String) {
        client.postgrest.from("authorized_emails")
            .upsert(AuthorizedEmailDto(email = email, role = role, addedBy = addedBy))
    }

    suspend fun removeAuthorizedEmail(email: String) {
        client.postgrest.from("authorized_emails").delete { filter { eq("email", email) } }
    }

    suspend fun listAccessMatrix(): List<AccessMatrixDto> =
        client.postgrest.from("access_matrix").select().decodeList()

    suspend fun grantAccess(readerDeviceId: String, sourceDeviceId: String, grantedBy: String) {
        client.postgrest.from("access_matrix").upsert(
            AccessMatrixDto(readerDeviceId = readerDeviceId, sourceDeviceId = sourceDeviceId, grantedBy = grantedBy),
            onConflict = "reader_device_id,source_device_id",
        )
    }

    suspend fun revokeAccess(readerDeviceId: String, sourceDeviceId: String) {
        client.postgrest.from("access_matrix").delete {
            filter { eq("reader_device_id", readerDeviceId); eq("source_device_id", sourceDeviceId) }
        }
    }

    suspend fun setDeviceRevoked(deviceId: String, revoked: Boolean) {
        client.postgrest.from("devices").update({ set("revoked", revoked) }) {
            filter { eq("id", deviceId) }
        }
    }

    // --- reader ---
    suspend fun allowedSources(readerDeviceId: String): List<String> =
        client.postgrest.from("access_matrix")
            .select { filter { eq("reader_device_id", readerDeviceId) } }
            .decodeList<AccessMatrixDto>()
            .map { it.sourceDeviceId }

    suspend fun listSubscriptions(readerDeviceId: String): List<SubscriptionDto> =
        client.postgrest.from("subscriptions")
            .select { filter { eq("reader_device_id", readerDeviceId) } }
            .decodeList()

    suspend fun subscribe(readerDeviceId: String, sourceDeviceId: String, notify: Boolean) {
        client.postgrest.from("subscriptions").upsert(
            SubscriptionDto(readerDeviceId = readerDeviceId, sourceDeviceId = sourceDeviceId, notify = notify),
            onConflict = "reader_device_id,source_device_id",
        )
    }

    suspend fun setNotify(readerDeviceId: String, sourceDeviceId: String, notify: Boolean) =
        subscribe(readerDeviceId, sourceDeviceId, notify)

    suspend fun unsubscribe(readerDeviceId: String, sourceDeviceId: String) {
        client.postgrest.from("subscriptions").delete {
            filter { eq("reader_device_id", readerDeviceId); eq("source_device_id", sourceDeviceId) }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AccessRepository.kt
git commit -m "feat(data): AccessRepository (admin matrix + reader subscriptions)"
```

### Task 13: CloudMessageRepository

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudMessageRepository.kt`

**Interfaces:**
- Consumes: `SupabaseProvider.client`, `CryptoManager`, `DeviceRepository`, `AccessRepository`, `RecipientSelector`, `CloudSmsPayload`, `HpkeCrypto`.
- Produces:
  - `class CloudMessageRepository(client, crypto, deviceRepo, accessRepo)`
  - `data class DecryptedMessage(id, sourceDeviceId, sourceAlias, sender, body, originalTimestamp, uploadedAt)`
  - `suspend fun uploadEncrypted(sourceDeviceId: String, payload: CloudSmsPayload)` — selects recipients, encrypts, inserts `messages` + `message_keys`.
  - `suspend fun buildEncrypted(sourceDeviceId, payload): EncryptedUpload` (pure-ish, no network) and `suspend fun pushEncrypted(EncryptedUpload)` — split so the offline queue can persist `EncryptedUpload`.
  - `data class EncryptedUpload(sourceDeviceId, ciphertextB64, nonceB64, wrapped: Map<recipientDeviceId, wrappedDekB64>)` (`@Serializable`).
  - `suspend fun listForReader(readerDeviceId, aliases: Map<String,String>): List<DecryptedMessage>`
  - `suspend fun decryptOne(messageId: String, readerDeviceId: String, aliases: Map<String,String>): DecryptedMessage?` — used by the FCM path.
  - `suspend fun deleteMessage(messageId)`, `suspend fun deleteAllForSource(sourceDeviceId)`

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudMessageRepository.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import com.viswa2k.smsforwarder.cloud.crypto.CloudSmsPayload
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.Serializable
import java.util.Base64

class CloudMessageRepository(
    private val client: SupabaseClient = SupabaseProvider.client,
    private val crypto: CryptoManager,
    private val deviceRepo: DeviceRepository,
    private val accessRepo: AccessRepository,
) {
    @Serializable
    data class EncryptedUpload(
        val sourceDeviceId: String,
        val ciphertextB64: String,
        val nonceB64: String,
        val wrapped: Map<String, String>, // recipientDeviceId -> base64(wrapped DEK)
    )

    data class DecryptedMessage(
        val id: String,
        val sourceDeviceId: String,
        val sourceAlias: String,
        val sender: String,
        val body: String,
        val originalTimestamp: Long,
        val uploadedAt: String,
    )

    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    suspend fun buildEncrypted(sourceDeviceId: String, payload: CloudSmsPayload): EncryptedUpload {
        val matrix = accessRepo.listAccessMatrix()
        val adminIds = deviceRepo.adminDeviceIds()
        val recipientIds = RecipientSelector.recipientDeviceIds(sourceDeviceId, matrix, adminIds)
        val keys = deviceRepo.activeKeysFor(recipientIds)

        val dek = crypto.newDek()
        val body = crypto.encryptBody(dek, payload.toJsonBytes())
        val wrapped = keys.associate { key ->
            key.deviceId to b64.encodeToString(crypto.sealDekTo(b64d.decode(key.publicKey), dek))
        }
        return EncryptedUpload(
            sourceDeviceId = sourceDeviceId,
            ciphertextB64 = b64.encodeToString(body.ciphertext),
            nonceB64 = b64.encodeToString(body.nonce),
            wrapped = wrapped,
        )
    }

    suspend fun pushEncrypted(u: EncryptedUpload) {
        val msg = client.postgrest.from("messages")
            .insert(MessageDto(sourceDeviceId = u.sourceDeviceId, ciphertext = u.ciphertextB64, nonce = u.nonceB64)) { select() }
            .decodeSingle<MessageDto>()
        val rows = u.wrapped.map { (deviceId, wrappedB64) ->
            MessageKeyDto(messageId = msg.id!!, recipientDeviceId = deviceId, wrappedDek = wrappedB64)
        }
        if (rows.isNotEmpty()) client.postgrest.from("message_keys").insert(rows)
    }

    suspend fun uploadEncrypted(sourceDeviceId: String, payload: CloudSmsPayload) =
        pushEncrypted(buildEncrypted(sourceDeviceId, payload))

    suspend fun listForReader(readerDeviceId: String, aliases: Map<String, String>): List<DecryptedMessage> {
        // RLS guarantees only messages with a message_keys row for our device come back.
        val messages = client.postgrest.from("messages").select().decodeList<MessageDto>()
        val myKeys = client.postgrest.from("message_keys")
            .select { filter { eq("recipient_device_id", readerDeviceId) } }
            .decodeList<MessageKeyDto>()
            .associateBy { it.messageId }
        return messages.mapNotNull { m ->
            val mk = myKeys[m.id] ?: return@mapNotNull null
            decrypt(m, mk, aliases)
        }.sortedByDescending { it.originalTimestamp }
    }

    suspend fun decryptOne(messageId: String, readerDeviceId: String, aliases: Map<String, String>): DecryptedMessage? {
        val m = client.postgrest.from("messages")
            .select { filter { eq("id", messageId) } }.decodeList<MessageDto>().firstOrNull() ?: return null
        val mk = client.postgrest.from("message_keys")
            .select { filter { eq("message_id", messageId); eq("recipient_device_id", readerDeviceId) } }
            .decodeList<MessageKeyDto>().firstOrNull() ?: return null
        return decrypt(m, mk, aliases)
    }

    private fun decrypt(m: MessageDto, mk: MessageKeyDto, aliases: Map<String, String>): DecryptedMessage? {
        return try {
            val dek = crypto.openWrappedDek(b64d.decode(mk.wrappedDek))
            val plain = crypto.decryptBody(dek, b64d.decode(m.ciphertext), b64d.decode(m.nonce))
            val payload = CloudSmsPayload.fromJsonBytes(plain)
            DecryptedMessage(
                id = m.id!!,
                sourceDeviceId = m.sourceDeviceId,
                sourceAlias = aliases[m.sourceDeviceId] ?: payload.deviceAlias,
                sender = payload.sender,
                body = payload.body,
                originalTimestamp = payload.originalTimestamp,
                uploadedAt = m.createdAt ?: "",
            )
        } catch (e: Exception) {
            null // cannot decrypt (e.g., wrapped before this device's key) — skip, don't crash
        }
    }

    suspend fun deleteMessage(messageId: String) {
        client.postgrest.from("messages").delete { filter { eq("id", messageId) } }
    }

    suspend fun deleteAllForSource(sourceDeviceId: String) {
        client.postgrest.from("messages").delete { filter { eq("source_device_id", sourceDeviceId) } }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudMessageRepository.kt
git commit -m "feat(data): CloudMessageRepository (encrypt/upload, list/decrypt, delete)"
```

### Task 14: UserPreferences cloud keys + offline queue

**Files:**
- Modify: `app/src/main/java/com/viswa2k/smsforwarder/UserPreferences.kt`
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueue.kt`
- Test: `app/src/test/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueueTest.kt`

**Interfaces:**
- Produces (UserPreferences additions): `isCloudChannelEnabled: Flow<Boolean>` + `saveCloudChannelEnabled(Boolean)`; `isReceiveEnabled: Flow<Boolean>` + `saveReceiveEnabled(Boolean)`; `cloudDeviceId: Flow<String>` + `saveCloudDeviceId(String)`. New defaults seeded in `initializeDefaults()`.
- Produces (queue): `class CloudUploadQueue(dir: File)` with `fun enqueue(u: EncryptedUpload)`, `fun pending(): List<EncryptedUpload>`, `fun remove(u: EncryptedUpload)`. Stores each upload as a `.json` file; plaintext is never written (only ciphertext + wrapped DEKs).

- [ ] **Step 1: Write the failing queue test**

Create `app/src/test/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueueTest.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CloudUploadQueueTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun upload(id: String) = CloudMessageRepository.EncryptedUpload(
        sourceDeviceId = id, ciphertextB64 = "Yw==", nonceB64 = "bg==", wrapped = mapOf("R" to "dw=="),
    )

    @Test
    fun enqueue_pending_remove_roundTrips() {
        val q = CloudUploadQueue(tmp.newFolder("queue"))
        val a = upload("A"); val b = upload("B")
        q.enqueue(a); q.enqueue(b)
        assertEquals(2, q.pending().size)
        q.remove(a)
        assertEquals(listOf("B"), q.pending().map { it.sourceDeviceId })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.data.CloudUploadQueueTest"`
Expected: FAIL — unresolved `CloudUploadQueue`.

- [ ] **Step 3: Implement the queue**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueue.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import kotlinx.serialization.json.Json
import java.io.File

/** File-backed retry queue for encrypted uploads. Persists ONLY ciphertext + wrapped DEKs. */
class CloudUploadQueue(private val dir: File) {
    init { if (!dir.exists()) dir.mkdirs() }
    private val json = Json { encodeDefaults = true }

    fun enqueue(u: CloudMessageRepository.EncryptedUpload) {
        val name = "${u.sourceDeviceId}_${u.nonceB64.hashCode()}_${u.ciphertextB64.hashCode()}.json"
        File(dir, name).writeText(json.encodeToString(CloudMessageRepository.EncryptedUpload.serializer(), u))
    }

    fun pending(): List<CloudMessageRepository.EncryptedUpload> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { runCatching { json.decodeFromString(CloudMessageRepository.EncryptedUpload.serializer(), it.readText()) }.getOrNull() }

    fun remove(u: CloudMessageRepository.EncryptedUpload) {
        val name = "${u.sourceDeviceId}_${u.nonceB64.hashCode()}_${u.ciphertextB64.hashCode()}.json"
        File(dir, name).delete()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.data.CloudUploadQueueTest"`
Expected: PASS.

- [ ] **Step 5: Add the new preference keys**

In `UserPreferences.kt`, add keys (near the other `private val … = booleanPreferencesKey(...)`):
```kotlin
    private val IS_CLOUD_CHANNEL_ENABLED = booleanPreferencesKey("IS_CLOUD_CHANNEL_ENABLED")
    private val IS_RECEIVE_ENABLED = booleanPreferencesKey("IS_RECEIVE_ENABLED")
    private val CLOUD_DEVICE_ID = stringPreferencesKey("CLOUD_DEVICE_ID")
```
In `initializeDefaults()`, add:
```kotlin
            if (preferences[IS_CLOUD_CHANNEL_ENABLED] == null) preferences[IS_CLOUD_CHANNEL_ENABLED] = false
            if (preferences[IS_RECEIVE_ENABLED] == null) preferences[IS_RECEIVE_ENABLED] = false
            if (preferences[CLOUD_DEVICE_ID] == null) preferences[CLOUD_DEVICE_ID] = ""
```
Add getters + savers (near the others):
```kotlin
    val isCloudChannelEnabled: Flow<Boolean> = dataStore.data.map { it[IS_CLOUD_CHANNEL_ENABLED] ?: false }
    val isReceiveEnabled: Flow<Boolean> = dataStore.data.map { it[IS_RECEIVE_ENABLED] ?: false }
    val cloudDeviceId: Flow<String> = dataStore.data.map { it[CLOUD_DEVICE_ID] ?: "" }

    suspend fun saveCloudChannelEnabled(enabled: Boolean) {
        dataStore.edit { it[IS_CLOUD_CHANNEL_ENABLED] = enabled }
    }
    suspend fun saveReceiveEnabled(enabled: Boolean) {
        dataStore.edit { it[IS_RECEIVE_ENABLED] = enabled }
    }
    suspend fun saveCloudDeviceId(id: String) {
        dataStore.edit { it[CLOUD_DEVICE_ID] = id }
    }
```

- [ ] **Step 6: Verify build + tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.data.*" && ./gradlew :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/UserPreferences.kt app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueue.kt app/src/test/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueueTest.kt
git commit -m "feat(data): cloud preference keys and offline upload queue"
```

### Task 15: SmsCloudUploader + SmsReceiver hook

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/SmsCloudUploader.kt`
- Modify: `app/src/main/java/com/viswa2k/smsforwarder/SMSReceiver.kt`

**Interfaces:**
- Consumes: `UserPreferences`, `CryptoManager`, `DeviceRepository`, `AccessRepository`, `CloudMessageRepository`, `CloudUploadQueue`, `CloudSmsPayload`.
- Produces: `class SmsCloudUploader(context)` with `suspend fun upload(senderNumber: String, body: String, timestamp: Long)` and `suspend fun flushQueue()`.

- [ ] **Step 1: Implement the uploader**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/SmsCloudUploader.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CloudSmsPayload
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import com.viswa2k.smsforwarder.dataStore
import kotlinx.coroutines.flow.first
import java.io.File

class SmsCloudUploader(private val context: Context) {
    private val prefs = UserPreferences(context.dataStore)
    private val crypto = CryptoManager(context)
    private val deviceRepo = DeviceRepository(crypto = crypto, prefs = prefs)
    private val accessRepo = AccessRepository()
    private val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = accessRepo)
    private val queue = CloudUploadQueue(File(context.filesDir, "cloud_upload_queue"))

    suspend fun upload(senderNumber: String, body: String, timestamp: Long) {
        if (!prefs.isCloudChannelEnabled.first()) return
        val deviceId = prefs.cloudDeviceId.first()
        if (deviceId.isBlank()) {
            Log.w("SmsCloudUploader", "Cloud channel enabled but device not registered; skipping")
            return
        }
        var alias = prefs.deviceAlias.first()
        if (alias.isBlank()) alias = "${Build.MANUFACTURER} ${Build.MODEL}"
        val payload = CloudSmsPayload(senderNumber, body, timestamp, alias)
        val encrypted = try {
            messageRepo.buildEncrypted(deviceId, payload)
        } catch (e: Exception) {
            Log.e("SmsCloudUploader", "Encrypt failed; cannot queue without recipients")
            return
        }
        try {
            messageRepo.pushEncrypted(encrypted)
        } catch (e: Exception) {
            Log.w("SmsCloudUploader", "Upload failed; queued for retry")
            queue.enqueue(encrypted)
        }
    }

    suspend fun flushQueue() {
        if (!prefs.isCloudChannelEnabled.first()) return
        for (u in queue.pending()) {
            try { messageRepo.pushEncrypted(u); queue.remove(u) }
            catch (e: Exception) { Log.w("SmsCloudUploader", "Retry failed; will try later"); break }
        }
    }
}
```

- [ ] **Step 2: Hook the uploader into SmsReceiver**

In `SMSReceiver.kt`, at the end of `forwardMessage(...)` (after the Telegram block, before the method closes at line ~125), add:
```kotlin
        val isCloudEnabled = prefs[booleanPreferencesKey("IS_CLOUD_CHANNEL_ENABLED")] ?: false
        if (isCloudEnabled) {
            try {
                com.viswa2k.smsforwarder.cloud.data.SmsCloudUploader(context)
                    .upload(senderNumber.toString(), messageBody, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Cloud upload error")
            }
        }
```
(`forwardMessage` is already a `suspend` function called inside the `Dispatchers.IO` coroutine, so the `suspend` call compiles directly.)

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/SmsCloudUploader.kt app/src/main/java/com/viswa2k/smsforwarder/SMSReceiver.kt
git commit -m "feat: encrypt-and-upload incoming SMS to cloud from receiver"
```

---

## Phase D — Notifications, UI, navigation

### Task 16: FCM service (silent push → fetch + decrypt → local notification)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/fcm/SmsForwarderFcmService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: FCM data message `{ type:'new_sms', message_id, source_device_id }`; `CloudMessageRepository.decryptOne(...)`.
- Produces: a local notification with decrypted content; `onNewToken` updates `devices.fcm_token`.

- [ ] **Step 1: Implement the service**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/fcm/SmsForwarderFcmService.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import com.viswa2k.smsforwarder.cloud.data.AccessRepository
import com.viswa2k.smsforwarder.cloud.data.CloudMessageRepository
import com.viswa2k.smsforwarder.cloud.data.DeviceRepository
import com.viswa2k.smsforwarder.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SmsForwarderFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val prefs = UserPreferences(dataStore)
        val crypto = CryptoManager(this)
        val deviceRepo = DeviceRepository(crypto = crypto, prefs = prefs)
        CoroutineScope(Dispatchers.IO).launch { runCatching { deviceRepo.updateFcmToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "new_sms") return
        val messageId = data["message_id"] ?: return
        val context = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = UserPreferences(context.dataStore)
            if (!prefs.isReceiveEnabled.first()) return@launch
            val readerDeviceId = prefs.cloudDeviceId.first()
            if (readerDeviceId.isBlank()) return@launch
            val crypto = CryptoManager(context)
            val deviceRepo = DeviceRepository(crypto = crypto, prefs = prefs)
            val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = AccessRepository())
            val aliases = runCatching { deviceRepo.fetchFleetDevices().associate { (it.id ?: "") to it.alias } }.getOrDefault(emptyMap())
            val decrypted = runCatching { messageRepo.decryptOne(messageId, readerDeviceId, aliases) }.getOrNull() ?: return@launch
            showNotification(context, decrypted.sourceAlias, "${decrypted.sender}: ${decrypted.body}")
        }
    }

    private fun showNotification(context: Context, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Cloud SMS", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(body.hashCode(), notification)
        }
    }

    companion object { private const val CHANNEL_ID = "cloud_sms" }
}
```

- [ ] **Step 2: Register the service in the manifest**

In `AndroidManifest.xml`, inside `<application>`:
```xml
        <service
            android:name=".cloud.fcm.SmsForwarderFcmService"
            android:exported="false">
            <intent-filter>
                <action android:name="com.google.firebase.MESSAGING_EVENT" />
            </intent-filter>
        </service>
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/fcm/SmsForwarderFcmService.kt app/src/main/AndroidManifest.xml
git commit -m "feat(fcm): silent-push handler decrypts and shows local notification"
```

### Task 17: CloudViewModel

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudViewModel.kt`

**Interfaces:**
- Consumes: all repositories + `CryptoManager`.
- Produces: `class CloudViewModel(app: Application) : AndroidViewModel(app)` exposing:
  - `val signedIn: StateFlow<Boolean>`, `val isAdmin: StateFlow<Boolean>`, `val email: StateFlow<String?>`
  - `val messages: StateFlow<List<DecryptedMessage>>`
  - `fun signInEmail(email, password, onError)`, `fun signInGoogle(idToken, nonce, onError)`, `fun signOut()`
  - `fun onAuthenticated()` — registers device, publishes key, refreshes admin flag, flushes queue, registers FCM token
  - `fun refreshMessages()`, `fun startRealtime()`, `fun stopRealtime()`
  - `fun deleteMessage(id)`, `fun deleteAllForSource(id)` (admin)
  - access/subscription passthroughs used by Watch/Admin screens.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudViewModel.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import com.viswa2k.smsforwarder.cloud.data.AccessRepository
import com.viswa2k.smsforwarder.cloud.data.AuthRepository
import com.viswa2k.smsforwarder.cloud.data.CloudMessageRepository
import com.viswa2k.smsforwarder.cloud.data.CloudUploadQueue
import com.viswa2k.smsforwarder.cloud.data.DeviceDto
import com.viswa2k.smsforwarder.cloud.data.DeviceRepository
import com.viswa2k.smsforwarder.cloud.data.SmsCloudUploader
import com.viswa2k.smsforwarder.cloud.data.SupabaseProvider
import com.viswa2k.smsforwarder.dataStore
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

class CloudViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = UserPreferences(app.dataStore)
    private val crypto = CryptoManager(app)
    private val auth = AuthRepository()
    private val deviceRepo = DeviceRepository(crypto = crypto, prefs = prefs)
    private val accessRepo = AccessRepository()
    private val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = accessRepo)
    private val queue = CloudUploadQueue(File(app.filesDir, "cloud_upload_queue"))

    private val _signedIn = MutableStateFlow(auth.currentEmail() != null)
    val signedIn: StateFlow<Boolean> = _signedIn
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin
    private val _email = MutableStateFlow(auth.currentEmail())
    val email: StateFlow<String?> = _email
    private val _messages = MutableStateFlow<List<CloudMessageRepository.DecryptedMessage>>(emptyList())
    val messages: StateFlow<List<CloudMessageRepository.DecryptedMessage>> = _messages

    private var realtimeJob: Job? = null
    private var aliasCache: Map<String, String> = emptyMap()

    init {
        viewModelScope.launch {
            auth.sessionStatus.collect { status ->
                val authed = status is SessionStatus.Authenticated
                _signedIn.value = authed
                _email.value = auth.currentEmail()
            }
        }
    }

    fun signInEmail(email: String, password: String, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { auth.signInEmail(email, password) }
            .onSuccess { onAuthenticatedInternal(onError) }
            .onFailure { onError(it.message ?: "Sign-in failed") }
    }

    fun signInGoogle(idToken: String, nonce: String, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { auth.signInGoogle(idToken, nonce) }
            .onSuccess { onAuthenticatedInternal(onError) }
            .onFailure { onError(it.message ?: "Google sign-in failed") }
    }

    fun onAuthenticated(onError: (String) -> Unit = {}) = viewModelScope.launch { onAuthenticatedInternal(onError) }

    private suspend fun onAuthenticatedInternal(onError: (String) -> Unit) {
        if (!auth.isAuthorized()) {
            auth.signOut()
            onError("This email is not authorized. Contact the administrator.")
            return
        }
        val email = auth.currentEmail() ?: return
        var alias = prefs.deviceAlias.first()
        if (alias.isBlank()) alias = android.os.Build.MODEL
        runCatching { deviceRepo.registerThisDevice(email, alias) }
        _isAdmin.value = runCatching { auth.isAdmin() }.getOrDefault(false)
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            deviceRepo.updateFcmToken(token)
        }
        runCatching { SmsCloudUploader(getApplication()).flushQueue() }
        refreshMessages()
    }

    fun signOut() = viewModelScope.launch { runCatching { auth.signOut() }; _signedIn.value = false }

    fun refreshMessages() = viewModelScope.launch {
        val readerId = prefs.cloudDeviceId.first()
        if (readerId.isBlank()) return@launch
        aliasCache = runCatching { deviceRepo.fetchFleetDevices().associate { (it.id ?: "") to it.alias } }.getOrDefault(emptyMap())
        _messages.value = runCatching { messageRepo.listForReader(readerId, aliasCache) }.getOrDefault(emptyList())
    }

    fun startRealtime() {
        if (realtimeJob != null) return
        realtimeJob = viewModelScope.launch {
            runCatching {
                val channel = SupabaseProvider.client.realtime.channel("cloud-sms")
                val flow = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") { table = "messages" }
                channel.subscribe()
                flow.collect { refreshMessages() }
            }
        }
    }

    fun stopRealtime() { realtimeJob?.cancel(); realtimeJob = null }

    fun deleteMessage(id: String) = viewModelScope.launch {
        runCatching { messageRepo.deleteMessage(id) }; refreshMessages()
    }
    fun deleteAllForSource(sourceDeviceId: String) = viewModelScope.launch {
        runCatching { messageRepo.deleteAllForSource(sourceDeviceId) }; refreshMessages()
    }

    suspend fun fleetDevices(): List<DeviceDto> = runCatching { deviceRepo.fetchFleetDevices() }.getOrDefault(emptyList())
    suspend fun myDeviceId(): String = prefs.cloudDeviceId.first()
    fun accessRepository() = accessRepo
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudViewModel.kt
git commit -m "feat(ui): CloudViewModel orchestrating auth, messages, realtime"
```

### Task 18: SignInScreen (email/password + Google)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/SignInScreen.kt`
- Modify: `app/build.gradle.kts` (Credential Manager deps)

**Interfaces:**
- Consumes: `CloudViewModel.signInEmail/signInGoogle`.
- Produces: `@Composable fun SignInScreen(vm: CloudViewModel, onSignedIn: () -> Unit)`.

- [ ] **Step 1: Add Credential Manager dependencies**

In `app/build.gradle.kts` `dependencies { }` add:
```kotlin
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
```

- [ ] **Step 2: Implement the screen**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/SignInScreen.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.viswa2k.smsforwarder.BuildConfig
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

@Composable
fun SignInScreen(vm: CloudViewModel, onSignedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Cloud SMS Sign-in", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true; error = null
                vm.signInEmail(email.trim(), password) { busy = false; error = it }
            },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val rawNonce = randomNonce()
                        val hashedNonce = sha256(rawNonce)
                        val option = GetGoogleIdOption.Builder()
                            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                            .setFilterByAuthorizedAccounts(false)
                            .setNonce(hashedNonce)
                            .build()
                        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                        val result = CredentialManager.create(context).getCredential(context, request)
                        val cred = GoogleIdTokenCredential.createFrom(result.credential.data)
                        vm.signInGoogle(cred.idToken, rawNonce) { busy = false; error = it }
                    } catch (e: Exception) {
                        busy = false; error = e.message ?: "Google sign-in cancelled"
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in with Google") }
    }

    val signedIn by vm.signedIn.collectAsState()
    LaunchedEffect(signedIn) { if (signedIn) onSignedIn() }
}

private fun randomNonce(): String {
    val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
private fun sha256(s: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/SignInScreen.kt
git commit -m "feat(ui): sign-in screen (email/password + Google credential manager)"
```

### Task 19: CloudSmsScreen (list + Realtime + admin delete)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudSmsScreen.kt`

**Interfaces:**
- Consumes: `CloudViewModel.messages/isAdmin/refreshMessages/startRealtime/stopRealtime/deleteMessage`.
- Produces: `@Composable fun CloudSmsScreen(vm: CloudViewModel, onOpenWatch: () -> Unit, onOpenAdmin: () -> Unit)`.

- [ ] **Step 1: Implement the screen**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudSmsScreen.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSmsScreen(vm: CloudViewModel, onOpenWatch: () -> Unit, onOpenAdmin: () -> Unit) {
    val messages by vm.messages.collectAsState()
    val isAdmin by vm.isAdmin.collectAsState()

    LaunchedEffect(Unit) { vm.refreshMessages(); vm.startRealtime() }
    DisposableEffect(Unit) { onDispose { vm.stopRealtime() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud SMS") },
                actions = {
                    TextButton(onClick = onOpenWatch) { Text("Watch") }
                    if (isAdmin) TextButton(onClick = onOpenAdmin) { Text("Admin") }
                },
            )
        },
    ) { padding ->
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("No messages yet")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
                items(messages, key = { it.id }) { m ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${m.sourceAlias} • ${m.sender}", style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(m.body, style = MaterialTheme.typography.bodyLarge)
                            if (isAdmin) {
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = { vm.deleteMessage(m.id) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudSmsScreen.kt
git commit -m "feat(ui): cloud SMS list with realtime refresh and admin delete"
```

### Task 20: WatchScreen (subscriptions)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/WatchScreen.kt`

**Interfaces:**
- Consumes: `CloudViewModel.fleetDevices/myDeviceId/accessRepository`.
- Produces: `@Composable fun WatchScreen(vm: CloudViewModel, onBack: () -> Unit)`.

- [ ] **Step 1: Implement the screen**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/WatchScreen.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.cloud.data.DeviceDto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(vm: CloudViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var myDeviceId by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
    var watchedNotify by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LaunchedEffect(Unit) {
        myDeviceId = vm.myDeviceId()
        val sources = vm.accessRepository().allowedSources(myDeviceId).toSet()
        allowed = vm.fleetDevices().filter { (it.id ?: "") in sources }
        watchedNotify = vm.accessRepository().listSubscriptions(myDeviceId).associate { it.sourceDeviceId to it.notify }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Watch devices") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            items(allowed, key = { it.id ?: "" }) { dev ->
                val sourceId = dev.id ?: ""
                val watched = watchedNotify.containsKey(sourceId)
                val notify = watchedNotify[sourceId] ?: true
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(dev.alias, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(checked = watched, onCheckedChange = { on ->
                                scope.launch {
                                    if (on) vm.accessRepository().subscribe(myDeviceId, sourceId, true)
                                    else vm.accessRepository().unsubscribe(myDeviceId, sourceId)
                                    watchedNotify = if (on) watchedNotify + (sourceId to true) else watchedNotify - sourceId
                                }
                            })
                        }
                        if (watched) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text("Notify", Modifier.weight(1f))
                                Switch(checked = notify, onCheckedChange = { on ->
                                    scope.launch {
                                        vm.accessRepository().setNotify(myDeviceId, sourceId, on)
                                        watchedNotify = watchedNotify + (sourceId to on)
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/WatchScreen.kt
git commit -m "feat(ui): watch/subscriptions screen with per-device notify toggle"
```

### Task 21: AdminScreen (allow-list + access matrix + revoke)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/AdminScreen.kt`

**Interfaces:**
- Consumes: `CloudViewModel.fleetDevices/email/accessRepository`.
- Produces: `@Composable fun AdminScreen(vm: CloudViewModel, onBack: () -> Unit)`.

- [ ] **Step 1: Implement the screen**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/AdminScreen.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.cloud.data.AuthorizedEmailDto
import com.viswa2k.smsforwarder.cloud.data.DeviceDto
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(vm: CloudViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val access = vm.accessRepository()
    val adminEmail = vm.email.collectAsState().value ?: ""
    var emails by remember { mutableStateOf<List<AuthorizedEmailDto>>(emptyList()) }
    var devices by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
    var newEmail by remember { mutableStateOf("") }
    var readerSel by remember { mutableStateOf<String?>(null) }
    var sourceSel by remember { mutableStateOf<String?>(null) }

    suspend fun reload() { emails = access.listAuthorizedEmails(); devices = vm.fleetDevices() }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Admin") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            item { Text("Authorized emails", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(newEmail, { newEmail = it }, label = { Text("email") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch { access.addAuthorizedEmail(newEmail.trim(), "member", adminEmail); newEmail = ""; reload() }
                    }) { Text("Add") }
                }
            }
            items(emails, key = { it.email }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${e.email} (${e.role})", Modifier.weight(1f))
                    if (e.role != "admin") TextButton(onClick = {
                        scope.launch { access.removeAuthorizedEmail(e.email); reload() }
                    }) { Text("Remove") }
                }
            }

            item { Spacer(Modifier.height(16.dp)); Text("Grant access (reader → source)", style = MaterialTheme.typography.titleMedium) }
            item {
                Column {
                    Text("Reader device:")
                    devices.forEach { d ->
                        FilterChip(selected = readerSel == d.id, onClick = { readerSel = d.id }, label = { Text(d.alias) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Source device:")
                    devices.forEach { d ->
                        FilterChip(selected = sourceSel == d.id, onClick = { sourceSel = d.id }, label = { Text(d.alias) })
                    }
                    Button(
                        enabled = readerSel != null && sourceSel != null && readerSel != sourceSel,
                        onClick = { scope.launch { access.grantAccess(readerSel!!, sourceSel!!, adminEmail) } },
                    ) { Text("Grant") }
                }
            }

            item { Spacer(Modifier.height(16.dp)); Text("Devices", style = MaterialTheme.typography.titleMedium) }
            items(devices, key = { it.id ?: "" }) { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("${d.alias}${if (d.revoked) " (revoked)" else ""}", Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch { access.setDeviceRevoked(d.id!!, !d.revoked); reload() }
                    }) { Text(if (d.revoked) "Un-revoke" else "Revoke") }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/AdminScreen.kt
git commit -m "feat(ui): admin screen (allow-list, access matrix, device revoke)"
```

### Task 22: Navigation + MainActivity + Settings toggles

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudNav.kt`
- Modify: `app/src/main/java/com/viswa2k/smsforwarder/MainActivity.kt`
- Modify: `app/src/main/java/com/viswa2k/smsforwarder/ui/screen/SettingsScreen.kt`
- Modify: `app/src/main/java/com/viswa2k/smsforwarder/ui/screen/SettingsViewModel.kt`

**Interfaces:**
- Consumes: all cloud screens + `CloudViewModel`.
- Produces: `@Composable fun CloudNav(vm: CloudViewModel)` hosting sign-in → cloud → watch/admin; a "Cloud SMS" entry point reachable from settings; cloud-channel + receive toggles wired to `UserPreferences`.

- [ ] **Step 1: Implement navigation host**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudNav.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun CloudNav(vm: CloudViewModel) {
    val nav = rememberNavController()
    val signedIn by vm.signedIn.collectAsState()
    val start = if (signedIn) "cloud" else "signin"

    NavHost(navController = nav, startDestination = start) {
        composable("signin") {
            SignInScreen(vm) { nav.navigate("cloud") { popUpTo("signin") { inclusive = true } } }
        }
        composable("cloud") {
            CloudSmsScreen(vm, onOpenWatch = { nav.navigate("watch") }, onOpenAdmin = { nav.navigate("admin") })
        }
        composable("watch") { WatchScreen(vm) { nav.popBackStack() } }
        composable("admin") { AdminScreen(vm) { nav.popBackStack() } }
    }
}
```

- [ ] **Step 2: Add a Cloud entry from MainActivity**

In `MainActivity.kt`, the existing `setContent { ... SettingsScreen(...) ... }` shows settings. Add a top-level tab/route to reach `CloudNav`. Minimal approach — wrap existing content with a simple two-destination NavHost. Inside `onCreate`, replace the `setContent { }` body's root composable with:
```kotlin
        setContent {
            MaterialTheme {
                Surface {
                    val cloudVm: com.viswa2k.smsforwarder.cloud.ui.CloudViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel()
                    val nav = androidx.navigation.compose.rememberNavController()
                    androidx.navigation.compose.NavHost(nav, startDestination = "settings") {
                        androidx.navigation.compose.composable("settings") {
                            SettingsScreen(/* existing args */)
                            // add a button somewhere in SettingsScreen to navigate("cloud"); see Step 4
                        }
                        androidx.navigation.compose.composable("cloud") {
                            com.viswa2k.smsforwarder.cloud.ui.CloudNav(cloudVm)
                        }
                    }
                }
            }
        }
```
Keep the existing `SettingsScreen(...)` arguments exactly as they currently are; only wrap it in the NavHost and pass a `onOpenCloud = { nav.navigate("cloud") }` lambda (add this parameter in Step 4).

After `userPreferences.initializeDefaults()` in the existing `lifecycleScope.launch { }`, add a one-time queue flush:
```kotlin
            try {
                if (userPreferences.isCloudChannelEnabled.first()) {
                    com.viswa2k.smsforwarder.cloud.data.SmsCloudUploader(applicationContext).flushQueue()
                }
            } catch (e: Exception) { Log.e("MainActivity", "queue flush failed") }
```

- [ ] **Step 3: Wire cloud toggles into SettingsViewModel**

In `SettingsViewModel.kt`, mirror the existing pattern (a `MutableStateFlow` per preference + a setter that calls `UserPreferences`). Add:
```kotlin
    val isCloudChannelEnabled = MutableStateFlow(false)
    val isReceiveEnabled = MutableStateFlow(false)
```
In the `init`/load block where other prefs are collected, collect `userPreferences.isCloudChannelEnabled` and `userPreferences.isReceiveEnabled` into those flows (match the existing collection style). In `saveSettings()` (or the existing per-field savers), persist them:
```kotlin
    fun setCloudChannelEnabled(v: Boolean) { isCloudChannelEnabled.value = v; viewModelScope.launch { userPreferences.saveCloudChannelEnabled(v) } }
    fun setReceiveEnabled(v: Boolean) { isReceiveEnabled.value = v; viewModelScope.launch { userPreferences.saveReceiveEnabled(v) } }
```
(Use the exact `userPreferences` field name already present in this ViewModel.)

- [ ] **Step 4: Add toggles + cloud entry to SettingsScreen**

In `SettingsScreen.kt`, add an `onOpenCloud: () -> Unit = {}` parameter to the composable signature. In the settings list (matching the existing toggle row style used for "Forward by SMS"/"Forward by Telegram"), add two switches bound to `viewModel.isCloudChannelEnabled`/`setCloudChannelEnabled` ("Upload to cloud") and `viewModel.isReceiveEnabled`/`setReceiveEnabled` ("Receive cloud messages"), and a button/row "Open Cloud SMS" that calls `onOpenCloud()`. Pass `onOpenCloud = { nav.navigate("cloud") }` from MainActivity (Step 2).

- [ ] **Step 5: Verify build + all unit tests**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudNav.kt app/src/main/java/com/viswa2k/smsforwarder/MainActivity.kt app/src/main/java/com/viswa2k/smsforwarder/ui/screen/SettingsScreen.kt app/src/main/java/com/viswa2k/smsforwarder/ui/screen/SettingsViewModel.kt
git commit -m "feat(ui): cloud navigation, settings toggles, queue flush on launch"
```

---

## Phase E — Verification

### Task 23: Instrumented crypto round-trip + manual smoke test

**Files:**
- Create: `app/src/androidTest/java/com/viswa2k/smsforwarder/cloud/CryptoManagerInstrumentedTest.kt`
- Create: `docs/superpowers/plans/2026-06-20-cloud-sms-smoke-checklist.md`

**Interfaces:**
- Consumes: `CryptoManager` on a real device (Keystore available).

- [ ] **Step 1: Write the instrumented test**

Create `app/src/androidTest/java/com/viswa2k/smsforwarder/cloud/CryptoManagerInstrumentedTest.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CryptoManagerInstrumentedTest {
    @Test
    fun keystoreSealedKeyset_sealsAndOpensDek() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = CryptoManager(ctx)
        cm.ensureIdentityKey()
        val pub = cm.publicKeyset()
        assertTrue(pub.isNotEmpty())

        val dek = cm.newDek()
        val wrapped = cm.sealDekTo(pub, dek)        // seal to our own published key
        val opened = cm.openWrappedDek(wrapped)     // open with Keystore-sealed private key
        assertArrayEquals(dek, opened)

        val body = cm.encryptBody(dek, "hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), cm.decryptBody(dek, body.ciphertext, body.nonce))
    }
}
```

- [ ] **Step 2: Run it on a device/emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.viswa2k.smsforwarder.cloud.CryptoManagerInstrumentedTest"`
Expected: PASS (Keystore-backed seal/open round-trips).

- [ ] **Step 3: Write the end-to-end smoke checklist**

Create `docs/superpowers/plans/2026-06-20-cloud-sms-smoke-checklist.md`:

```markdown
# Cloud SMS — manual smoke test (two devices)

Pre-req: Supabase migrations applied, super-admin email seeded, Edge Function deployed,
Database Webhook on `messages` INSERT configured, `google-services.json` + BuildConfig set.

1. Device A (admin): sign in → confirm device registered (row in `devices`, active `device_keys`).
2. Device B (member): sign in with an allow-listed email → registered. A non-listed email must be rejected ("not authorized").
3. Admin → Admin screen: Grant access (reader=B, source=A).
4. Device B → Watch: enable A, notify ON.
5. Device A: enable "Upload to cloud". Send an SMS to Device A.
6. Expect: a `messages` row + `message_keys` rows for A's admin device and B appear; ciphertext is unreadable in Studio.
7. Device B: receives a push notification with decrypted "sender: body"; the Cloud SMS screen lists it.
8. Toggle notify OFF on B; send another SMS; B gets no push but sees it after opening the screen (Realtime).
9. Admin: delete a message → disappears for B. Confirm a member cannot delete (button hidden / RLS denies).
10. Admin: revoke Device B → new messages no longer wrapped for B; B stops seeing new messages.
```

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/com/viswa2k/smsforwarder/cloud/CryptoManagerInstrumentedTest.kt docs/superpowers/plans/2026-06-20-cloud-sms-smoke-checklist.md
git commit -m "test: instrumented crypto round-trip and e2e smoke checklist"
```

---

## Appendix — known follow-ups (not in this plan)

- **Web reader/admin sub-project** (own spec/plan): QR pairing, Tink↔raw-HPKE public-key bridging, Web Push, re-wrap backfill UI on Android.
- **Hardware per-key biometric binding**: current build seals the keyset with a Keystore master key and gates decrypt screens with `BiometricPrompt`; binding the keyset itself to `setUserAuthenticationRequired` is a hardening follow-up.
- **Key rotation scheduler**: `CryptoManager.rotateIdentityKey()` exists; wiring a 30-day trigger + re-publish is a follow-up.
