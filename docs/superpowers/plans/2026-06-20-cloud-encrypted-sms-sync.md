# Cloud Encrypted SMS Sync — Implementation Plan (Firebase)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an end-to-end-encrypted cloud channel so fleet devices upload incoming SMS to Firebase and authorized devices read any permitted device's SMS (live + push), with a super-admin controlling access.

**Architecture:** Senders encrypt each SMS on-device (random AES-256-GCM Data Key; body encrypted; DEK HPKE-sealed per recipient) and **fan out** one encrypted copy into each authorized recipient's Firestore inbox. Readers attach a snapshot listener to their own inbox, decrypt in memory, and view via a Compose screen. A Cloud Function pushes silent FCM on each inbox write. Firestore Security Rules + the crypto envelope both enforce a super-admin-managed access matrix.

**Tech Stack:** Kotlin, Jetpack Compose, Firebase (Auth, Firestore, Cloud Functions, FCM), Google Tink (HPKE RFC 9180 + AES-GCM), Android Keystore, DataStore, kotlinx-serialization, Credential Manager (Google sign-in). Backend functions in TypeScript.

**Spec:** `docs/superpowers/specs/2026-06-20-cloud-encrypted-sms-sync-design.md`

## Global Constraints

- Package: `com.viswa2k.smsforwarder`; new cloud code under `…/cloud/**` (sub-packages `crypto`, `data`, `ui`, `fcm`).
- minSdk 29, targetSdk/compileSdk 34. Bump Java/`jvmTarget` to **17**.
- Preserve `allowBackup=false`, `usesCleartextTraffic=false`. Firebase traffic is HTTPS.
- **Never persist plaintext SMS or unwrapped keys to disk.** Plaintext exists only in memory.
- Envelope crypto: HPKE template `DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_256_GCM`; body AES-256-GCM with a random 12-byte IV. Binary blobs stored as **base64 strings** in Firestore.
- **Fan-out model:** one logical SMS → one shared `messageId` → one encrypted copy per recipient at `inbox/{recipientDeviceId}/messages/{messageId}`. The super-admin is always a recipient.
- Filename↔class convention follows existing project style (e.g. `SMSReceiver.kt` defines `SmsReceiver`).
- `BuildConfig.GOOGLE_WEB_CLIENT_ID` is fed from `local.properties`/Gradle. Firebase config comes from `app/google-services.json` (git-ignored). No secrets committed.
- Firestore data classes use **camelCase fields stored verbatim** (Firebase POJO mapping); they need default values + a no-arg constructor (Kotlin data classes with all-defaults satisfy this).
- The **super-admin email** is supplied by the user and seeded into `authorized_emails` (role `admin`) via the bootstrap step.

## File Structure

**Backend (new dir `firebase/`):**
- `firebase/firestore.rules` — Security Rules.
- `firebase/firestore.indexes.json` — collection-group indexes.
- `firebase/firebase.json` — Firebase CLI config.
- `firebase/functions/src/index.ts` — `notifyReader` Cloud Function.
- `firebase/functions/package.json`, `firebase/functions/tsconfig.json`.
- `firebase/scripts/bootstrap-admin.md` — one-time super-admin seed instructions.
- `firebase/README.md` — deploy runbook.

**App — crypto (`…/cloud/crypto/`):** `HpkeCrypto.kt`, `CryptoManager.kt`, `CloudSmsPayload.kt`.

**App — data (`…/cloud/data/`):** `FirebaseProvider.kt`, `FirestoreModels.kt`, `RecipientSelector.kt`, `AuthRepository.kt`, `DeviceRepository.kt`, `AccessRepository.kt`, `CloudMessageRepository.kt`, `CloudUploadQueue.kt`, `SmsCloudUploader.kt`.

**App — fcm (`…/cloud/fcm/`):** `SmsForwarderFcmService.kt`.

**App — ui (`…/cloud/ui/`):** `CloudViewModel.kt`, `SignInScreen.kt`, `CloudSmsScreen.kt`, `WatchScreen.kt`, `AdminScreen.kt`, `CloudNav.kt`.

**Modified:** `app/build.gradle.kts`, `gradle/libs.versions.toml`, root `build.gradle.kts`, `AndroidManifest.xml`, `UserPreferences.kt`, `SMSReceiver.kt`, `MainActivity.kt`, `ui/screen/SettingsScreen.kt`, `ui/screen/SettingsViewModel.kt`.

---

## Phase A — Firebase backend

### Task 1: Firestore Security Rules + indexes + CLI config

**Files:**
- Create: `firebase/firestore.rules`
- Create: `firebase/firestore.indexes.json`
- Create: `firebase/firebase.json`
- Create: `firebase/README.md`

**Interfaces:**
- Produces: rules guaranteeing (a) only allow-listed emails access data; (b) a reader reads only its own `inbox`; (c) only admins delete inbox docs / write the access matrix; (d) any authorized device may create docs in any recipient's inbox (fan-out). Collection-group index on `messages.messageId` and `messages.sourceDeviceId` for admin delete.

- [ ] **Step 1: Write the rules**

Create `firebase/firestore.rules`:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    function authed() { return request.auth != null && request.auth.token.email != null; }
    function isAuthorized() {
      return authed() && exists(/databases/$(database)/documents/authorized_emails/$(request.auth.token.email));
    }
    function isAdmin() {
      return isAuthorized() &&
        get(/databases/$(database)/documents/authorized_emails/$(request.auth.token.email)).data.role == 'admin';
    }
    function ownsDevice(deviceId) {
      return isAuthorized() &&
        get(/databases/$(database)/documents/devices/$(deviceId)).data.ownerEmail == request.auth.token.email;
    }

    match /authorized_emails/{email} {
      allow read: if isAuthorized();
      allow write: if isAdmin();
    }

    match /devices/{deviceId} {
      allow read: if isAuthorized();
      allow create, update: if isAdmin()
        || request.resource.data.ownerEmail == request.auth.token.email;
      allow delete: if isAdmin();
    }

    match /access_matrix/{pair} {
      allow read: if isAuthorized();
      allow write: if isAdmin();
    }

    match /subscriptions/{pair} {
      allow read: if isAuthorized();
      allow write: if ownsDevice(request.resource.data.readerDeviceId)
        || ownsDevice(resource.data.readerDeviceId);
    }

    match /inbox/{deviceId}/messages/{messageId} {
      allow read: if ownsDevice(deviceId) || isAdmin();
      allow create: if isAuthorized();
      allow delete: if isAdmin();
      allow update: if false;
    }
  }
}
```

- [ ] **Step 2: Write the indexes**

Create `firebase/firestore.indexes.json`:

```json
{
  "indexes": [
    {
      "collectionGroup": "messages",
      "queryScope": "COLLECTION_GROUP",
      "fields": [{ "fieldPath": "messageId", "order": "ASCENDING" }]
    },
    {
      "collectionGroup": "messages",
      "queryScope": "COLLECTION_GROUP",
      "fields": [{ "fieldPath": "sourceDeviceId", "order": "ASCENDING" }]
    }
  ],
  "fieldOverrides": []
}
```

- [ ] **Step 3: Write the CLI config**

Create `firebase/firebase.json`:

```json
{
  "firestore": {
    "rules": "firestore.rules",
    "indexes": "firestore.indexes.json"
  },
  "functions": [
    { "source": "functions", "codebase": "default", "runtime": "nodejs20" }
  ]
}
```

- [ ] **Step 4: Write the runbook**

Create `firebase/README.md`:

```markdown
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
```

- [ ] **Step 5: Verify the rules parse**

Run: `cd firebase && firebase deploy --only firestore:rules --dry-run` (or `firebase emulators:start` and confirm rules load).
Expected: rules compile without errors.

> If the Firebase CLI is unavailable, verify-by-review: paste the rules into the console Rules editor (it validates syntax).

- [ ] **Step 6: Commit**

```bash
git add firebase/firestore.rules firebase/firestore.indexes.json firebase/firebase.json firebase/README.md
git commit -m "feat(backend): Firestore rules, indexes, CLI config"
```

### Task 2: notifyReader Cloud Function + admin bootstrap

**Files:**
- Create: `firebase/functions/package.json`
- Create: `firebase/functions/tsconfig.json`
- Create: `firebase/functions/src/index.ts`
- Create: `firebase/scripts/bootstrap-admin.md`

**Interfaces:**
- Consumes: Firestore `onCreate` of `inbox/{deviceId}/messages/{messageId}`; `devices/{deviceId}.fcmToken`; `subscriptions/{deviceId}__{sourceDeviceId}.notify`.
- Produces: a silent FCM data message `{ type:'new_sms', message_id, source_device_id, device_id }` to the recipient device when `notify != false` (default ON). Carries **no plaintext**.

- [ ] **Step 1: Write the functions package files**

Create `firebase/functions/package.json`:

```json
{
  "name": "cloud-sms-functions",
  "type": "module",
  "engines": { "node": "20" },
  "main": "lib/index.js",
  "scripts": { "build": "tsc" },
  "dependencies": {
    "firebase-admin": "^12.3.0",
    "firebase-functions": "^5.0.1"
  },
  "devDependencies": { "typescript": "^5.5.4" }
}
```

Create `firebase/functions/tsconfig.json`:

```json
{
  "compilerOptions": {
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "target": "ES2022",
    "outDir": "lib",
    "strict": true,
    "esModuleInterop": true
  },
  "include": ["src"]
}
```

- [ ] **Step 2: Write the function**

Create `firebase/functions/src/index.ts`:

```typescript
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";

initializeApp();

// On each per-recipient inbox write, push a silent FCM data message to that
// recipient if they have a token and notify is not disabled (default ON).
export const notifyReader = onDocumentCreated(
  "inbox/{deviceId}/messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const data = snap.data();
    const deviceId = event.params.deviceId as string;
    const db = getFirestore();

    const deviceSnap = await db.collection("devices").doc(deviceId).get();
    const token = deviceSnap.get("fcmToken") as string | undefined;
    if (!token) return;

    const subId = `${deviceId}__${data.sourceDeviceId}`;
    const subSnap = await db.collection("subscriptions").doc(subId).get();
    const notify = subSnap.exists ? subSnap.get("notify") !== false : true;
    if (!notify) return;

    await getMessaging().send({
      token,
      data: {
        type: "new_sms",
        message_id: String(data.messageId),
        source_device_id: String(data.sourceDeviceId),
        device_id: deviceId,
      },
      android: { priority: "high" },
    });
  }
);
```

- [ ] **Step 3: Write the bootstrap instructions**

Create `firebase/scripts/bootstrap-admin.md`:

```markdown
# Seed the super-admin

Pick ONE:

**A. Firebase console** → Firestore → create collection `authorized_emails`,
document id = the super-admin email, fields: `role` = `"admin"`, `addedBy` = `"bootstrap"`.

**B. CLI (Node):**
```js
// node bootstrap.mjs  (run with GOOGLE_APPLICATION_CREDENTIALS set to a service account)
import { initializeApp, applicationDefault } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
initializeApp({ credential: applicationDefault() });
await getFirestore().collection("authorized_emails")
  .doc("REPLACE_WITH_SUPER_ADMIN_EMAIL")
  .set({ role: "admin", addedBy: "bootstrap" });
console.log("seeded");
```
```

- [ ] **Step 4: Verify it type-checks**

Run: `cd firebase/functions && npm install && npm run build`
Expected: `tsc` succeeds, emits `lib/index.js`.

> CLI/npm unavailable → verify-by-review: confirm imports + the v2 `onDocumentCreated` signature and the `messaging().send` payload shape.

- [ ] **Step 5: Commit**

```bash
git add firebase/functions firebase/scripts/bootstrap-admin.md
git commit -m "feat(backend): notifyReader Cloud Function + admin bootstrap"
```

---

## Phase B — App foundation & crypto

### Task 3: Dependencies, plugins, BuildConfig, Java 17

**Files:**
- Modify: `gradle/libs.versions.toml`, root `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `BuildConfig.GOOGLE_WEB_CLIENT_ID`; Firebase (Auth/Firestore/Messaging), Tink, serialization, biometric, credential-manager, navigation on the classpath.

- [ ] **Step 1: Add plugins to the version catalog**

In `gradle/libs.versions.toml` `[versions]` add (use the existing Kotlin version for `kotlin`):
```toml
googleServices = "4.4.2"
```
`[plugins]`:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
google-services = { id = "com.google.gms.google-services", version.ref = "googleServices" }
```

- [ ] **Step 2: Register plugins at the root**

In root `build.gradle.kts` top-level `plugins { }`:
```kotlin
alias(libs.plugins.kotlin.serialization) apply false
alias(libs.plugins.google.services) apply false
```

- [ ] **Step 3: Apply plugins, deps, BuildConfig, Java 17**

In `app/build.gradle.kts` `plugins { }`:
```kotlin
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
```

Near the top of `app/build.gradle.kts` (before `android {}`):
```kotlin
val localProps = java.util.Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String): String = (localProps.getProperty(name) ?: project.findProperty(name) as String?) ?: ""
```

In `android { defaultConfig { } }`:
```kotlin
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${prop("GOOGLE_WEB_CLIENT_ID")}\"")
```

Replace `compileOptions`/`kotlinOptions`/`buildFeatures`:
```kotlin
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
```

Add to `dependencies { }`:
```kotlin
    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Crypto
    implementation("com.google.crypto.tink:tink-android:1.13.0")
    implementation("androidx.biometric:biometric:1.1.0")

    // Serialization (payload), Navigation, Google sign-in
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // JVM crypto for unit tests
    testImplementation("com.google.crypto.tink:tink:1.13.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
```

In `AndroidManifest.xml` `<manifest>` (add if missing):
```xml
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 4: Add config placeholders so the build resolves**

Append to `local.properties` (git-ignored):
```properties
GOOGLE_WEB_CLIENT_ID=placeholder.apps.googleusercontent.com
```
Place `app/google-services.json` (from the Firebase console). Add `app/google-services.json` to `.gitignore`.

- [ ] **Step 5: Verify the project builds**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Requires `app/google-services.json`; the google-services plugin fails fast without it — a console-downloaded file is fine.)

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts app/build.gradle.kts app/src/main/AndroidManifest.xml .gitignore
git commit -m "build: add Firebase, Tink, serialization, nav, credential-manager deps"
```

### Task 4: HpkeCrypto — pure HPKE + AES-GCM (JVM-tested)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCrypto.kt`
- Test: `app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCryptoTest.kt`

**Interfaces:**
- Produces: `HpkeCrypto.HPKE_TEMPLATE`; `generatePrivateKeyset(): KeysetHandle`; `serializePublicKeyset(handle): ByteArray`; `serializePrivateKeyset(handle): ByteArray`; `deserializePrivateKeyset(bytes): KeysetHandle`; `seal(recipientPublicKeyset, plaintext, contextInfo): ByteArray`; `open(privateKeyset, ciphertext, contextInfo): ByteArray`; `newDek(): ByteArray`; `EncryptedBody(ciphertext, nonce)`; `encryptBody(dek, plaintext): EncryptedBody`; `decryptBody(dek, ciphertext, nonce): ByteArray`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCryptoTest.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HpkeCryptoTest {

    @Test
    fun sealThenOpen_roundTrips() {
        val recipient = HpkeCrypto.generatePrivateKeyset()
        val pub = HpkeCrypto.serializePublicKeyset(recipient)
        val msg = "Your OTP is 4471".toByteArray()
        val sealed = HpkeCrypto.seal(pub, msg, contextInfo = ByteArray(0))
        assertArrayEquals(msg, HpkeCrypto.open(recipient, sealed, ByteArray(0)))
    }

    @Test
    fun wrongRecipient_cannotOpen() {
        val a = HpkeCrypto.generatePrivateKeyset()
        val b = HpkeCrypto.generatePrivateKeyset()
        val sealed = HpkeCrypto.seal(HpkeCrypto.serializePublicKeyset(a), "secret".toByteArray(), ByteArray(0))
        var failed = false
        try { HpkeCrypto.open(b, sealed, ByteArray(0)) } catch (e: Exception) { failed = true }
        assertTrue("device B must not decrypt A's envelope", failed)
    }

    @Test
    fun body_encryptDecrypt_roundTrips() {
        val dek = HpkeCrypto.newDek()
        val plain = "sender=+100;body=hello".toByteArray()
        val enc = HpkeCrypto.encryptBody(dek, plain)
        assertArrayEquals(plain, HpkeCrypto.decryptBody(dek, enc.ciphertext, enc.nonce))
    }

    @Test
    fun privateKeyset_serializeRoundTrips() {
        val handle = HpkeCrypto.generatePrivateKeyset()
        val restored = HpkeCrypto.deserializePrivateKeyset(HpkeCrypto.serializePrivateKeyset(handle))
        val sealed = HpkeCrypto.seal(HpkeCrypto.serializePublicKeyset(handle), "x".toByteArray(), ByteArray(0))
        assertArrayEquals("x".toByteArray(), HpkeCrypto.open(restored, sealed, ByteArray(0)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.crypto.HpkeCryptoTest"`
Expected: FAIL — `HpkeCrypto` unresolved.

- [ ] **Step 3: Implement HpkeCrypto**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCrypto.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.crypto

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Pure Tink HPKE (RFC 9180) envelope + AES-256-GCM body crypto. JVM-testable. */
object HpkeCrypto {
    const val HPKE_TEMPLATE = "DHKEM_P256_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private val rng = SecureRandom()

    init { HybridConfig.register() }

    fun generatePrivateKeyset(): KeysetHandle = KeysetHandle.generateNew(KeyTemplates.get(HPKE_TEMPLATE))

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
        return pub.getPrimitive(HybridEncrypt::class.java).encrypt(plaintext, contextInfo)
    }

    fun open(privateKeyset: KeysetHandle, ciphertext: ByteArray, contextInfo: ByteArray): ByteArray =
        privateKeyset.getPrimitive(HybridDecrypt::class.java).decrypt(ciphertext, contextInfo)

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

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.crypto.HpkeCryptoTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCrypto.kt app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/HpkeCryptoTest.kt
git commit -m "feat(crypto): HPKE envelope + AES-GCM body crypto with JVM tests"
```

### Task 5: CloudSmsPayload model (JVM-tested)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CloudSmsPayload.kt`
- Test: `app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/CloudSmsPayloadTest.kt`

**Interfaces:**
- Produces: `@Serializable data class CloudSmsPayload(sender, body, originalTimestamp, deviceAlias)` with `toJsonBytes(): ByteArray` and `fromJsonBytes(ByteArray): CloudSmsPayload`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/viswa2k/smsforwarder/cloud/crypto/CloudSmsPayloadTest.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudSmsPayloadTest {
    @Test
    fun jsonRoundTrips() {
        val p = CloudSmsPayload("+100", "héllo, world", 123L, "Pixel")
        assertEquals(p, CloudSmsPayload.fromJsonBytes(p.toJsonBytes()))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.crypto.CloudSmsPayloadTest"`
Expected: FAIL — unresolved `CloudSmsPayload`.

- [ ] **Step 3: Implement**

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

### Task 6: CryptoManager — Keystore-sealed keyset

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CryptoManager.kt`

**Interfaces:**
- Consumes: `HpkeCrypto`.
- Produces: `class CryptoManager(context)` with `ensureIdentityKey()`, `publicKeyset(): ByteArray`, `currentVersion(): Int`, `rotateIdentityKey(): Int`, `newDek(): ByteArray`, `sealDekTo(recipientPublicKeyset, dek): ByteArray`, `openWrappedDek(wrappedDek): ByteArray`, `encryptBody(dek, plaintext): HpkeCrypto.EncryptedBody`, `decryptBody(dek, ciphertext, nonce): ByteArray`.

Notes: Tink `AndroidKeysetManager` seals the keyset at rest with a Keystore AES-GCM master key (`android-keystore://sms_forwarder_identity`) in private prefs `cloud_identity_prefs`. Biometric gating is at the **screen level** (`BiometricPrompt`) before decrypt screens. Rotation keeps old key versions in the keyset for reading history. `contextInfo` is `ByteArray(0)` in v1.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CryptoManager.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.crypto

import android.content.Context
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

class CryptoManager(context: Context) {
    private val appContext = context.applicationContext
    init { HybridConfig.register() }

    private fun manager(): AndroidKeysetManager =
        AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get(HpkeCrypto.HPKE_TEMPLATE))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()

    private fun handle(): KeysetHandle = manager().keysetHandle

    fun ensureIdentityKey() { manager() }

    fun publicKeyset(): ByteArray = HpkeCrypto.serializePublicKeyset(handle())

    fun currentVersion(): Int =
        appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getInt(VERSION_KEY, 1)

    fun rotateIdentityKey(): Int {
        val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val next = currentVersion() + 1
        manager().add(KeyTemplates.get(HpkeCrypto.HPKE_TEMPLATE))
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
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/crypto/CryptoManager.kt
git commit -m "feat(crypto): Keystore-sealed CryptoManager identity keyset"
```

---

## Phase C — Data layer (Firebase)

### Task 7: FirebaseProvider + Firestore models

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/FirebaseProvider.kt`
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/FirestoreModels.kt`

**Interfaces:**
- Produces: `FirebaseProvider.auth: FirebaseAuth`, `FirebaseProvider.db: FirebaseFirestore`; `pairId(reader, source): String`; domain models `Device(id, ownerEmail, alias, publicKey, revoked, fcmToken)`, `AuthorizedEmail(email, role)`, `AccessGrant(readerDeviceId, sourceDeviceId)`, `Subscription(readerDeviceId, sourceDeviceId, notify)`.

- [ ] **Step 1: Implement the provider**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/FirebaseProvider.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object FirebaseProvider {
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
}
```

- [ ] **Step 2: Implement the models**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/FirestoreModels.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

/** Firestore document-id for the reader→source pair collections. */
fun pairId(readerDeviceId: String, sourceDeviceId: String): String = "${readerDeviceId}__${sourceDeviceId}"

data class Device(
    val id: String,
    val ownerEmail: String,
    val alias: String,
    val publicKey: String,
    val revoked: Boolean,
    val fcmToken: String?,
)

data class AuthorizedEmail(val email: String, val role: String)
data class AccessGrant(val readerDeviceId: String, val sourceDeviceId: String)
data class Subscription(val readerDeviceId: String, val sourceDeviceId: String, val notify: Boolean)
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/FirebaseProvider.kt app/src/main/java/com/viswa2k/smsforwarder/cloud/data/FirestoreModels.kt
git commit -m "feat(data): Firebase provider and Firestore domain models"
```

### Task 8: RecipientSelector (pure, JVM-tested)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/RecipientSelector.kt`
- Test: `app/src/test/java/com/viswa2k/smsforwarder/cloud/data/RecipientSelectorTest.kt`

**Interfaces:**
- Produces: `RecipientSelector.recipientDeviceIds(sourceDeviceId: String, matrix: List<AccessGrant>, adminDeviceIds: Set<String>): Set<String>` — super-admin devices always included, plus every reader granted access to the source.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/viswa2k/smsforwarder/cloud/data/RecipientSelectorTest.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecipientSelectorTest {
    private fun g(reader: String, source: String) = AccessGrant(reader, source)

    @Test
    fun includesAdminPlusGrantedReaders_forThatSourceOnly() {
        val matrix = listOf(g("B", "A"), g("C", "A"), g("B", "X"))
        assertEquals(setOf("B", "C", "ADMIN"), RecipientSelector.recipientDeviceIds("A", matrix, setOf("ADMIN")))
    }

    @Test
    fun adminAlwaysIncluded_evenWithNoGrants() {
        assertEquals(setOf("ADMIN"), RecipientSelector.recipientDeviceIds("A", emptyList(), setOf("ADMIN")))
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
        matrix: List<AccessGrant>,
        adminDeviceIds: Set<String>,
    ): Set<String> =
        matrix.filter { it.sourceDeviceId == sourceDeviceId }.map { it.readerDeviceId }.toSet() + adminDeviceIds
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

### Task 9: AuthRepository (Firebase Auth)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AuthRepository.kt`

**Interfaces:**
- Produces: `class AuthRepository(auth = FirebaseProvider.auth, db = FirebaseProvider.db)` with `currentEmail(): String?`, `authState: Flow<Boolean>`, `suspend signInEmail(email, password)`, `suspend signUpEmail(email, password)`, `suspend signInGoogle(idToken: String)`, `suspend signOut()`, `suspend isAuthorized(): Boolean`, `suspend isAdmin(): Boolean`.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AuthRepository.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseProvider.auth,
    private val db: FirebaseFirestore = FirebaseProvider.db,
) {
    fun currentEmail(): String? = auth.currentUser?.email

    val authState: Flow<Boolean> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser != null) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signInEmail(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
    }

    suspend fun signUpEmail(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password).await()
    }

    suspend fun signInGoogle(idToken: String) {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
    }

    suspend fun signOut() = auth.signOut()

    suspend fun isAuthorized(): Boolean {
        val email = currentEmail() ?: return false
        return db.collection("authorized_emails").document(email).get().await().exists()
    }

    suspend fun isAdmin(): Boolean {
        val email = currentEmail() ?: return false
        val doc = db.collection("authorized_emails").document(email).get().await()
        return doc.exists() && doc.getString("role") == "admin"
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AuthRepository.kt
git commit -m "feat(data): AuthRepository (Firebase email/Google + allow-list check)"
```

### Task 10: DeviceRepository (Firestore)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/DeviceRepository.kt`

**Interfaces:**
- Consumes: `FirebaseProvider.db`, `CryptoManager`, `UserPreferences`.
- Produces: `class DeviceRepository(db = FirebaseProvider.db, crypto, prefs)` with `suspend registerThisDevice(ownerEmail, alias): String`, `suspend updateFcmToken(token)`, `suspend fetchFleetDevices(): List<Device>`, `suspend adminDeviceIds(): Set<String>`.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/DeviceRepository.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.Base64
import java.util.UUID

class DeviceRepository(
    private val db: FirebaseFirestore = FirebaseProvider.db,
    private val crypto: CryptoManager,
    private val prefs: UserPreferences,
) {
    suspend fun registerThisDevice(ownerEmail: String, alias: String): String {
        crypto.ensureIdentityKey()
        var id = prefs.cloudDeviceId.first()
        if (id.isBlank()) { id = UUID.randomUUID().toString(); prefs.saveCloudDeviceId(id) }
        val pub = Base64.getEncoder().encodeToString(crypto.publicKeyset())
        db.collection("devices").document(id).set(
            mapOf(
                "ownerEmail" to ownerEmail,
                "alias" to alias,
                "publicKey" to pub,
                "keyVersion" to crypto.currentVersion(),
                "revoked" to false,
            ),
            SetOptions.merge(),
        ).await()
        return id
    }

    suspend fun updateFcmToken(token: String) {
        val id = prefs.cloudDeviceId.first()
        if (id.isBlank()) return
        db.collection("devices").document(id).set(mapOf("fcmToken" to token), SetOptions.merge()).await()
    }

    suspend fun fetchFleetDevices(): List<Device> =
        db.collection("devices").get().await().documents.map { d ->
            Device(
                id = d.id,
                ownerEmail = d.getString("ownerEmail") ?: "",
                alias = d.getString("alias") ?: "",
                publicKey = d.getString("publicKey") ?: "",
                revoked = d.getBoolean("revoked") ?: false,
                fcmToken = d.getString("fcmToken"),
            )
        }

    suspend fun adminDeviceIds(): Set<String> {
        val adminEmails = db.collection("authorized_emails").whereEqualTo("role", "admin")
            .get().await().documents.map { it.id }.toSet()
        if (adminEmails.isEmpty()) return emptySet()
        return fetchFleetDevices().filter { it.ownerEmail in adminEmails }.map { it.id }.toSet()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (depends on `UserPreferences.cloudDeviceId`/`saveCloudDeviceId` from Task 13 — do Task 13 first if compile fails on those).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/DeviceRepository.kt
git commit -m "feat(data): DeviceRepository (register, fcm token, fleet, admin ids)"
```

### Task 11: AccessRepository (Firestore)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AccessRepository.kt`

**Interfaces:**
- Produces: `class AccessRepository(db = FirebaseProvider.db)` with admin ops `listAuthorizedEmails(): List<AuthorizedEmail>`, `addAuthorizedEmail(email, role, addedBy)`, `removeAuthorizedEmail(email)`, `listAccessMatrix(): List<AccessGrant>`, `grantAccess(reader, source, grantedBy)`, `revokeAccess(reader, source)`, `setDeviceRevoked(deviceId, revoked)`; reader ops `allowedSources(reader): List<String>`, `listSubscriptions(reader): List<Subscription>`, `subscribe(reader, source, notify)`, `setNotify(reader, source, notify)`, `unsubscribe(reader, source)`.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/AccessRepository.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

class AccessRepository(private val db: FirebaseFirestore = FirebaseProvider.db) {

    // --- admin ---
    suspend fun listAuthorizedEmails(): List<AuthorizedEmail> =
        db.collection("authorized_emails").get().await().documents
            .map { AuthorizedEmail(it.id, it.getString("role") ?: "member") }

    suspend fun addAuthorizedEmail(email: String, role: String, addedBy: String) {
        db.collection("authorized_emails").document(email)
            .set(mapOf("role" to role, "addedBy" to addedBy)).await()
    }

    suspend fun removeAuthorizedEmail(email: String) {
        db.collection("authorized_emails").document(email).delete().await()
    }

    suspend fun listAccessMatrix(): List<AccessGrant> =
        db.collection("access_matrix").get().await().documents
            .map { AccessGrant(it.getString("readerDeviceId") ?: "", it.getString("sourceDeviceId") ?: "") }

    suspend fun grantAccess(readerDeviceId: String, sourceDeviceId: String, grantedBy: String) {
        db.collection("access_matrix").document(pairId(readerDeviceId, sourceDeviceId))
            .set(mapOf("readerDeviceId" to readerDeviceId, "sourceDeviceId" to sourceDeviceId, "grantedBy" to grantedBy)).await()
    }

    suspend fun revokeAccess(readerDeviceId: String, sourceDeviceId: String) {
        db.collection("access_matrix").document(pairId(readerDeviceId, sourceDeviceId)).delete().await()
    }

    suspend fun setDeviceRevoked(deviceId: String, revoked: Boolean) {
        db.collection("devices").document(deviceId).set(mapOf("revoked" to revoked), SetOptions.merge()).await()
    }

    // --- reader ---
    suspend fun allowedSources(readerDeviceId: String): List<String> =
        db.collection("access_matrix").whereEqualTo("readerDeviceId", readerDeviceId)
            .get().await().documents.map { it.getString("sourceDeviceId") ?: "" }

    suspend fun listSubscriptions(readerDeviceId: String): List<Subscription> =
        db.collection("subscriptions").whereEqualTo("readerDeviceId", readerDeviceId)
            .get().await().documents.map {
                Subscription(it.getString("readerDeviceId") ?: "", it.getString("sourceDeviceId") ?: "", it.getBoolean("notify") ?: true)
            }

    suspend fun subscribe(readerDeviceId: String, sourceDeviceId: String, notify: Boolean) {
        db.collection("subscriptions").document(pairId(readerDeviceId, sourceDeviceId))
            .set(mapOf("readerDeviceId" to readerDeviceId, "sourceDeviceId" to sourceDeviceId, "notify" to notify)).await()
    }

    suspend fun setNotify(readerDeviceId: String, sourceDeviceId: String, notify: Boolean) =
        subscribe(readerDeviceId, sourceDeviceId, notify)

    suspend fun unsubscribe(readerDeviceId: String, sourceDeviceId: String) {
        db.collection("subscriptions").document(pairId(readerDeviceId, sourceDeviceId)).delete().await()
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

### Task 12: CloudMessageRepository (fan-out + inbox read + delete)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudMessageRepository.kt`

**Interfaces:**
- Consumes: `FirebaseProvider.db`, `CryptoManager`, `DeviceRepository`, `AccessRepository`, `RecipientSelector`, `CloudSmsPayload`.
- Produces:
  - `@Serializable data class RecipientCopy(recipientDeviceId, wrappedDekB64)`
  - `@Serializable data class FanOut(messageId, sourceDeviceId, sourceAlias, ciphertextB64, nonceB64, copies: List<RecipientCopy>)`
  - `data class DecryptedMessage(id, sourceDeviceId, sourceAlias, sender, body, originalTimestamp, uploadedAt)`
  - `suspend fun buildFanOut(sourceDeviceId, sourceAlias, payload): FanOut`
  - `suspend fun pushFanOut(fanOut: FanOut)`
  - `suspend fun uploadEncrypted(sourceDeviceId, sourceAlias, payload)` = build+push
  - `suspend fun listForReader(readerDeviceId, aliases: Map<String,String>): List<DecryptedMessage>`
  - `suspend fun decryptOne(readerDeviceId, messageId, aliases): DecryptedMessage?`
  - `suspend fun deleteMessage(messageId)` (all copies, admin), `suspend fun deleteAllForSource(sourceDeviceId)`

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudMessageRepository.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.viswa2k.smsforwarder.cloud.crypto.CloudSmsPayload
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.UUID

class CloudMessageRepository(
    private val db: FirebaseFirestore = FirebaseProvider.db,
    private val crypto: CryptoManager,
    private val deviceRepo: DeviceRepository,
    private val accessRepo: AccessRepository,
) {
    @Serializable data class RecipientCopy(val recipientDeviceId: String, val wrappedDekB64: String)

    @Serializable data class FanOut(
        val messageId: String,
        val sourceDeviceId: String,
        val sourceAlias: String,
        val ciphertextB64: String,
        val nonceB64: String,
        val copies: List<RecipientCopy>,
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

    private val enc = Base64.getEncoder()
    private val dec = Base64.getDecoder()

    suspend fun buildFanOut(sourceDeviceId: String, sourceAlias: String, payload: CloudSmsPayload): FanOut {
        val matrix = accessRepo.listAccessMatrix()
        val adminIds = deviceRepo.adminDeviceIds()
        val recipientIds = RecipientSelector.recipientDeviceIds(sourceDeviceId, matrix, adminIds)
        val keyByDevice = deviceRepo.fetchFleetDevices()
            .filter { !it.revoked && it.publicKey.isNotBlank() }
            .associate { it.id to it.publicKey }

        val dek = crypto.newDek()
        val body = crypto.encryptBody(dek, payload.toJsonBytes())
        val copies = recipientIds.mapNotNull { id ->
            keyByDevice[id]?.let { pub ->
                RecipientCopy(id, enc.encodeToString(crypto.sealDekTo(dec.decode(pub), dek)))
            }
        }
        return FanOut(
            messageId = UUID.randomUUID().toString(),
            sourceDeviceId = sourceDeviceId,
            sourceAlias = sourceAlias,
            ciphertextB64 = enc.encodeToString(body.ciphertext),
            nonceB64 = enc.encodeToString(body.nonce),
            copies = copies,
        )
    }

    suspend fun pushFanOut(fanOut: FanOut) {
        if (fanOut.copies.isEmpty()) return
        val batch = db.batch()
        for (c in fanOut.copies) {
            val ref = db.collection("inbox").document(c.recipientDeviceId)
                .collection("messages").document(fanOut.messageId)
            batch.set(ref, mapOf(
                "messageId" to fanOut.messageId,
                "sourceDeviceId" to fanOut.sourceDeviceId,
                "sourceAlias" to fanOut.sourceAlias,
                "ciphertext" to fanOut.ciphertextB64,
                "nonce" to fanOut.nonceB64,
                "wrappedDek" to c.wrappedDekB64,
                "createdAt" to FieldValue.serverTimestamp(),
            ))
        }
        batch.commit().await()
    }

    suspend fun uploadEncrypted(sourceDeviceId: String, sourceAlias: String, payload: CloudSmsPayload) =
        pushFanOut(buildFanOut(sourceDeviceId, sourceAlias, payload))

    suspend fun listForReader(readerDeviceId: String, aliases: Map<String, String>): List<DecryptedMessage> =
        db.collection("inbox").document(readerDeviceId).collection("messages")
            .get().await().documents.mapNotNull { decrypt(it, aliases) }
            .sortedByDescending { it.originalTimestamp }

    suspend fun decryptOne(readerDeviceId: String, messageId: String, aliases: Map<String, String>): DecryptedMessage? {
        val doc = db.collection("inbox").document(readerDeviceId).collection("messages")
            .document(messageId).get().await()
        if (!doc.exists()) return null
        return decrypt(doc, aliases)
    }

    private fun decrypt(doc: DocumentSnapshot, aliases: Map<String, String>): DecryptedMessage? = try {
        val dek = crypto.openWrappedDek(dec.decode(doc.getString("wrappedDek")!!))
        val plain = crypto.decryptBody(dek, dec.decode(doc.getString("ciphertext")!!), dec.decode(doc.getString("nonce")!!))
        val payload = CloudSmsPayload.fromJsonBytes(plain)
        val source = doc.getString("sourceDeviceId") ?: ""
        DecryptedMessage(
            id = doc.getString("messageId") ?: doc.id,
            sourceDeviceId = source,
            sourceAlias = aliases[source] ?: doc.getString("sourceAlias") ?: payload.deviceAlias,
            sender = payload.sender,
            body = payload.body,
            originalTimestamp = payload.originalTimestamp,
            uploadedAt = doc.getTimestamp("createdAt")?.toDate()?.toString() ?: "",
        )
    } catch (e: Exception) {
        null // cannot decrypt (e.g., sealed before this device's key) — skip, don't crash
    }

    suspend fun deleteMessage(messageId: String) {
        val docs = db.collectionGroup("messages").whereEqualTo("messageId", messageId).get().await().documents
        val batch = db.batch()
        docs.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }

    suspend fun deleteAllForSource(sourceDeviceId: String) {
        val docs = db.collectionGroup("messages").whereEqualTo("sourceDeviceId", sourceDeviceId).get().await().documents
        val batch = db.batch()
        docs.forEach { batch.delete(it.reference) }
        batch.commit().await()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudMessageRepository.kt
git commit -m "feat(data): CloudMessageRepository (fan-out, inbox decrypt, admin delete)"
```

### Task 13: UserPreferences cloud keys + offline queue

**Files:**
- Modify: `app/src/main/java/com/viswa2k/smsforwarder/UserPreferences.kt`
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueue.kt`
- Test: `app/src/test/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueueTest.kt`

**Interfaces:**
- Produces (UserPreferences): `isCloudChannelEnabled: Flow<Boolean>` + `saveCloudChannelEnabled(Boolean)`; `isReceiveEnabled: Flow<Boolean>` + `saveReceiveEnabled(Boolean)`; `cloudDeviceId: Flow<String>` + `saveCloudDeviceId(String)`; defaults seeded in `initializeDefaults()`.
- Produces (queue): `class CloudUploadQueue(dir: File)` with `enqueue(FanOut)`, `pending(): List<FanOut>`, `remove(FanOut)`. Persists ONLY ciphertext + wrapped DEKs.

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

    private fun fanOut(id: String) = CloudMessageRepository.FanOut(
        messageId = id, sourceDeviceId = "S", sourceAlias = "Phone",
        ciphertextB64 = "Yw==", nonceB64 = "bg==",
        copies = listOf(CloudMessageRepository.RecipientCopy("R", "dw==")),
    )

    @Test
    fun enqueue_pending_remove_roundTrips() {
        val q = CloudUploadQueue(tmp.newFolder("queue"))
        val a = fanOut("a"); val b = fanOut("b")
        q.enqueue(a); q.enqueue(b)
        assertEquals(2, q.pending().size)
        q.remove(a)
        assertEquals(listOf("b"), q.pending().map { it.messageId })
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

/** File-backed retry queue for encrypted fan-outs. Persists ONLY ciphertext + wrapped DEKs. */
class CloudUploadQueue(private val dir: File) {
    init { if (!dir.exists()) dir.mkdirs() }
    private val json = Json { encodeDefaults = true }

    fun enqueue(f: CloudMessageRepository.FanOut) {
        File(dir, "${f.messageId}.json").writeText(json.encodeToString(CloudMessageRepository.FanOut.serializer(), f))
    }

    fun pending(): List<CloudMessageRepository.FanOut> =
        (dir.listFiles { file -> file.extension == "json" } ?: emptyArray())
            .sortedBy { it.lastModified() }
            .mapNotNull { runCatching { json.decodeFromString(CloudMessageRepository.FanOut.serializer(), it.readText()) }.getOrNull() }

    fun remove(f: CloudMessageRepository.FanOut) { File(dir, "${f.messageId}.json").delete() }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.data.CloudUploadQueueTest"`
Expected: PASS.

- [ ] **Step 5: Add the preference keys**

In `UserPreferences.kt`, add keys:
```kotlin
    private val IS_CLOUD_CHANNEL_ENABLED = booleanPreferencesKey("IS_CLOUD_CHANNEL_ENABLED")
    private val IS_RECEIVE_ENABLED = booleanPreferencesKey("IS_RECEIVE_ENABLED")
    private val CLOUD_DEVICE_ID = stringPreferencesKey("CLOUD_DEVICE_ID")
```
In `initializeDefaults()`:
```kotlin
            if (preferences[IS_CLOUD_CHANNEL_ENABLED] == null) preferences[IS_CLOUD_CHANNEL_ENABLED] = false
            if (preferences[IS_RECEIVE_ENABLED] == null) preferences[IS_RECEIVE_ENABLED] = false
            if (preferences[CLOUD_DEVICE_ID] == null) preferences[CLOUD_DEVICE_ID] = ""
```
Getters + savers:
```kotlin
    val isCloudChannelEnabled: Flow<Boolean> = dataStore.data.map { it[IS_CLOUD_CHANNEL_ENABLED] ?: false }
    val isReceiveEnabled: Flow<Boolean> = dataStore.data.map { it[IS_RECEIVE_ENABLED] ?: false }
    val cloudDeviceId: Flow<String> = dataStore.data.map { it[CLOUD_DEVICE_ID] ?: "" }

    suspend fun saveCloudChannelEnabled(enabled: Boolean) { dataStore.edit { it[IS_CLOUD_CHANNEL_ENABLED] = enabled } }
    suspend fun saveReceiveEnabled(enabled: Boolean) { dataStore.edit { it[IS_RECEIVE_ENABLED] = enabled } }
    suspend fun saveCloudDeviceId(id: String) { dataStore.edit { it[CLOUD_DEVICE_ID] = id } }
```

- [ ] **Step 6: Verify build + tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.viswa2k.smsforwarder.cloud.data.*" && ./gradlew :app:compileDebugKotlin`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/UserPreferences.kt app/src/main/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueue.kt app/src/test/java/com/viswa2k/smsforwarder/cloud/data/CloudUploadQueueTest.kt
git commit -m "feat(data): cloud preference keys and offline fan-out queue"
```

### Task 14: SmsCloudUploader + SmsReceiver hook

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/data/SmsCloudUploader.kt`
- Modify: `app/src/main/java/com/viswa2k/smsforwarder/SMSReceiver.kt`

**Interfaces:**
- Produces: `class SmsCloudUploader(context)` with `suspend fun upload(senderNumber, body, timestamp)` and `suspend fun flushQueue()`.

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

class SmsCloudUploader(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = UserPreferences(appContext.dataStore)
    private val crypto = CryptoManager(appContext)
    private val deviceRepo = DeviceRepository(crypto = crypto, prefs = prefs)
    private val accessRepo = AccessRepository()
    private val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = accessRepo)
    private val queue = CloudUploadQueue(File(appContext.filesDir, "cloud_upload_queue"))

    suspend fun upload(senderNumber: String, body: String, timestamp: Long) {
        if (!prefs.isCloudChannelEnabled.first()) return
        val deviceId = prefs.cloudDeviceId.first()
        if (deviceId.isBlank()) {
            Log.w("SmsCloudUploader", "Cloud enabled but device not registered; skipping")
            return
        }
        var alias = prefs.deviceAlias.first()
        if (alias.isBlank()) alias = "${Build.MANUFACTURER} ${Build.MODEL}"
        val payload = CloudSmsPayload(senderNumber, body, timestamp, alias)
        val fanOut = try {
            messageRepo.buildFanOut(deviceId, alias, payload)
        } catch (e: Exception) {
            Log.e("SmsCloudUploader", "Encrypt failed; cannot queue without recipients")
            return
        }
        try {
            messageRepo.pushFanOut(fanOut)
        } catch (e: Exception) {
            Log.w("SmsCloudUploader", "Upload failed; queued for retry")
            queue.enqueue(fanOut)
        }
    }

    suspend fun flushQueue() {
        if (!prefs.isCloudChannelEnabled.first()) return
        for (f in queue.pending()) {
            try { messageRepo.pushFanOut(f); queue.remove(f) }
            catch (e: Exception) { Log.w("SmsCloudUploader", "Retry failed; will try later"); break }
        }
    }
}
```

- [ ] **Step 2: Hook into SmsReceiver**

In `SMSReceiver.kt`, at the end of `forwardMessage(...)` (after the Telegram block, before the method closes ~line 125), add:
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
(`forwardMessage` is already `suspend` and runs inside the `Dispatchers.IO` coroutine, so the `suspend` call compiles directly.)

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/data/SmsCloudUploader.kt app/src/main/java/com/viswa2k/smsforwarder/SMSReceiver.kt
git commit -m "feat: encrypt-and-fan-out incoming SMS to cloud from receiver"
```

---

## Phase D — Notifications, UI, navigation

### Task 15: FCM service (silent push → read inbox doc → decrypt → notify)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/fcm/SmsForwarderFcmService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: FCM data `{ type:'new_sms', message_id, source_device_id, device_id }`; `CloudMessageRepository.decryptOne(...)`.
- Produces: a local notification with decrypted content; `onNewToken` updates the device's `fcmToken`.

- [ ] **Step 1: Implement**

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
        val prefs = UserPreferences(applicationContext.dataStore)
        val deviceRepo = DeviceRepository(crypto = CryptoManager(applicationContext), prefs = prefs)
        CoroutineScope(Dispatchers.IO).launch { runCatching { deviceRepo.updateFcmToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "new_sms") return
        val messageId = data["message_id"] ?: return
        val ctx = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = UserPreferences(ctx.dataStore)
            if (!prefs.isReceiveEnabled.first()) return@launch
            val readerDeviceId = prefs.cloudDeviceId.first()
            if (readerDeviceId.isBlank()) return@launch
            val crypto = CryptoManager(ctx)
            val deviceRepo = DeviceRepository(crypto = crypto, prefs = prefs)
            val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = AccessRepository())
            val aliases = runCatching { deviceRepo.fetchFleetDevices().associate { it.id to it.alias } }.getOrDefault(emptyMap())
            val decrypted = runCatching { messageRepo.decryptOne(readerDeviceId, messageId, aliases) }.getOrNull() ?: return@launch
            showNotification(ctx, decrypted.sourceAlias, "${decrypted.sender}: ${decrypted.body}")
        }
    }

    private fun showNotification(context: Context, title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Cloud SMS", NotificationManager.IMPORTANCE_HIGH))
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationManagerCompat.from(context).notify(body.hashCode(), n)
        }
    }

    companion object { private const val CHANNEL_ID = "cloud_sms" }
}
```

- [ ] **Step 2: Register the service**

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
git commit -m "feat(fcm): silent-push handler decrypts inbox doc and notifies"
```

### Task 16: CloudViewModel

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudViewModel.kt`

**Interfaces:**
- Produces: `class CloudViewModel(app: Application) : AndroidViewModel(app)` exposing `signedIn/isAdmin/email/messages: StateFlow`, `signInEmail/signInGoogle/signOut`, `onAuthenticated`, `refreshMessages/startRealtime/stopRealtime`, `deleteMessage/deleteAllForSource`, `fleetDevices()/myDeviceId()/accessRepository()`.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudViewModel.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.messaging.FirebaseMessaging
import com.viswa2k.smsforwarder.UserPreferences
import com.viswa2k.smsforwarder.cloud.crypto.CryptoManager
import com.viswa2k.smsforwarder.cloud.data.AccessRepository
import com.viswa2k.smsforwarder.cloud.data.AuthRepository
import com.viswa2k.smsforwarder.cloud.data.CloudMessageRepository
import com.viswa2k.smsforwarder.cloud.data.Device
import com.viswa2k.smsforwarder.cloud.data.DeviceRepository
import com.viswa2k.smsforwarder.cloud.data.FirebaseProvider
import com.viswa2k.smsforwarder.cloud.data.SmsCloudUploader
import com.viswa2k.smsforwarder.dataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CloudViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = UserPreferences(app.dataStore)
    private val crypto = CryptoManager(app)
    private val auth = AuthRepository()
    private val deviceRepo = DeviceRepository(crypto = crypto, prefs = prefs)
    private val accessRepo = AccessRepository()
    private val messageRepo = CloudMessageRepository(crypto = crypto, deviceRepo = deviceRepo, accessRepo = accessRepo)

    private val _signedIn = MutableStateFlow(auth.currentEmail() != null)
    val signedIn: StateFlow<Boolean> = _signedIn
    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin
    private val _email = MutableStateFlow(auth.currentEmail())
    val email: StateFlow<String?> = _email
    private val _messages = MutableStateFlow<List<CloudMessageRepository.DecryptedMessage>>(emptyList())
    val messages: StateFlow<List<CloudMessageRepository.DecryptedMessage>> = _messages

    private var registration: ListenerRegistration? = null
    private var aliasCache: Map<String, String> = emptyMap()

    init { viewModelScope.launch { auth.authState.collect { _signedIn.value = it; _email.value = auth.currentEmail() } } }

    fun signInEmail(email: String, password: String, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { auth.signInEmail(email, password) }
            .onSuccess { onAuthenticatedInternal(onError) }
            .onFailure { onError(it.message ?: "Sign-in failed") }
    }

    fun signInGoogle(idToken: String, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { auth.signInGoogle(idToken) }
            .onSuccess { onAuthenticatedInternal(onError) }
            .onFailure { onError(it.message ?: "Google sign-in failed") }
    }

    fun onAuthenticated(onError: (String) -> Unit = {}) = viewModelScope.launch { onAuthenticatedInternal(onError) }

    private suspend fun onAuthenticatedInternal(onError: (String) -> Unit) {
        if (!auth.isAuthorized()) { auth.signOut(); _signedIn.value = false; onError("This email is not authorized."); return }
        val email = auth.currentEmail() ?: return
        var alias = prefs.deviceAlias.first(); if (alias.isBlank()) alias = android.os.Build.MODEL
        runCatching { deviceRepo.registerThisDevice(email, alias) }
        _isAdmin.value = runCatching { auth.isAdmin() }.getOrDefault(false)
        runCatching { deviceRepo.updateFcmToken(FirebaseMessaging.getInstance().token.await()) }
        runCatching { SmsCloudUploader(getApplication()).flushQueue() }
        refreshMessages()
    }

    fun signOut() = viewModelScope.launch { runCatching { auth.signOut() }; _signedIn.value = false }

    fun refreshMessages() = viewModelScope.launch {
        val readerId = prefs.cloudDeviceId.first(); if (readerId.isBlank()) return@launch
        aliasCache = runCatching { deviceRepo.fetchFleetDevices().associate { it.id to it.alias } }.getOrDefault(emptyMap())
        _messages.value = runCatching { messageRepo.listForReader(readerId, aliasCache) }.getOrDefault(emptyList())
    }

    fun startRealtime() = viewModelScope.launch {
        if (registration != null) return@launch
        val readerId = prefs.cloudDeviceId.first(); if (readerId.isBlank()) return@launch
        registration = FirebaseProvider.db.collection("inbox").document(readerId).collection("messages")
            .addSnapshotListener { _, _ -> refreshMessages() }
    }

    fun stopRealtime() { registration?.remove(); registration = null }

    fun deleteMessage(id: String) = viewModelScope.launch { runCatching { messageRepo.deleteMessage(id) }; refreshMessages() }
    fun deleteAllForSource(sourceDeviceId: String) = viewModelScope.launch { runCatching { messageRepo.deleteAllForSource(sourceDeviceId) }; refreshMessages() }

    suspend fun fleetDevices(): List<Device> = runCatching { deviceRepo.fetchFleetDevices() }.getOrDefault(emptyList())
    suspend fun myDeviceId(): String = prefs.cloudDeviceId.first()
    fun accessRepository() = accessRepo

    override fun onCleared() { stopRealtime() }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudViewModel.kt
git commit -m "feat(ui): CloudViewModel (Firebase auth, messages, snapshot realtime)"
```

### Task 17: SignInScreen (email/password + Google)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/SignInScreen.kt`

**Interfaces:**
- Consumes: `CloudViewModel.signInEmail/signInGoogle/signedIn`; `BuildConfig.GOOGLE_WEB_CLIENT_ID`.
- Produces: `@Composable fun SignInScreen(vm: CloudViewModel, onSignedIn: () -> Unit)`.

- [ ] **Step 1: Implement**

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
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
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
            onClick = { busy = true; error = null; vm.signInEmail(email.trim(), password) { busy = false; error = it } },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val nonce = sha256(randomNonce())
                        val option = GetGoogleIdOption.Builder()
                            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                            .setFilterByAuthorizedAccounts(false)
                            .setNonce(nonce)
                            .build()
                        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                        val result = CredentialManager.create(context).getCredential(context, request)
                        val cred = GoogleIdTokenCredential.createFrom(result.credential.data)
                        vm.signInGoogle(cred.idToken) { busy = false; error = it }
                    } catch (e: Exception) { busy = false; error = e.message ?: "Google sign-in cancelled" }
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in with Google") }
    }

    val signedIn by vm.signedIn.collectAsState()
    LaunchedEffect(signedIn) { if (signedIn) onSignedIn() }
}

private fun randomNonce(): String {
    val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
private fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/SignInScreen.kt
git commit -m "feat(ui): sign-in screen (email/password + Google credential manager)"
```

### Task 18: CloudSmsScreen (list + realtime + admin delete)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudSmsScreen.kt`

**Interfaces:**
- Produces: `@Composable fun CloudSmsScreen(vm: CloudViewModel, onOpenWatch: () -> Unit, onOpenAdmin: () -> Unit)`.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudSmsScreen.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("No messages yet") }
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

### Task 19: WatchScreen (subscriptions)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/WatchScreen.kt`

**Interfaces:**
- Produces: `@Composable fun WatchScreen(vm: CloudViewModel, onBack: () -> Unit)`.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/WatchScreen.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.cloud.data.Device
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(vm: CloudViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var myDeviceId by remember { mutableStateOf("") }
    var allowed by remember { mutableStateOf<List<Device>>(emptyList()) }
    var watchedNotify by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    LaunchedEffect(Unit) {
        myDeviceId = vm.myDeviceId()
        val sources = vm.accessRepository().allowedSources(myDeviceId).toSet()
        allowed = vm.fleetDevices().filter { it.id in sources }
        watchedNotify = vm.accessRepository().listSubscriptions(myDeviceId).associate { it.sourceDeviceId to it.notify }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Watch devices") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            items(allowed, key = { it.id }) { dev ->
                val sourceId = dev.id
                val watched = watchedNotify.containsKey(sourceId)
                val notify = watchedNotify[sourceId] ?: true
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
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

### Task 20: AdminScreen (allow-list + access matrix + revoke)

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/AdminScreen.kt`

**Interfaces:**
- Produces: `@Composable fun AdminScreen(vm: CloudViewModel, onBack: () -> Unit)`.

- [ ] **Step 1: Implement**

Create `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/AdminScreen.kt`:

```kotlin
package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.cloud.data.AuthorizedEmail
import com.viswa2k.smsforwarder.cloud.data.Device
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(vm: CloudViewModel, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val access = vm.accessRepository()
    val adminEmail = vm.email.collectAsState().value ?: ""
    var emails by remember { mutableStateOf<List<AuthorizedEmail>>(emptyList()) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var newEmail by remember { mutableStateOf("") }
    var readerSel by remember { mutableStateOf<String?>(null) }
    var sourceSel by remember { mutableStateOf<String?>(null) }

    suspend fun reload() { emails = access.listAuthorizedEmails(); devices = vm.fleetDevices() }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Admin") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            item { Text("Authorized emails", style = MaterialTheme.typography.titleMedium) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newEmail, { newEmail = it }, label = { Text("email") }, modifier = Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { access.addAuthorizedEmail(newEmail.trim(), "member", adminEmail); newEmail = ""; reload() } }) { Text("Add") }
                }
            }
            items(emails, key = { it.email }) { e ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${e.email} (${e.role})", Modifier.weight(1f))
                    if (e.role != "admin") TextButton(onClick = { scope.launch { access.removeAuthorizedEmail(e.email); reload() } }) { Text("Remove") }
                }
            }

            item { Spacer(Modifier.height(16.dp)); Text("Grant access (reader → source)", style = MaterialTheme.typography.titleMedium) }
            item {
                Column {
                    Text("Reader device:")
                    devices.forEach { d -> FilterChip(selected = readerSel == d.id, onClick = { readerSel = d.id }, label = { Text(d.alias) }) }
                    Spacer(Modifier.height(8.dp))
                    Text("Source device:")
                    devices.forEach { d -> FilterChip(selected = sourceSel == d.id, onClick = { sourceSel = d.id }, label = { Text(d.alias) }) }
                    Button(
                        enabled = readerSel != null && sourceSel != null && readerSel != sourceSel,
                        onClick = { scope.launch { access.grantAccess(readerSel!!, sourceSel!!, adminEmail) } },
                    ) { Text("Grant") }
                }
            }

            item { Spacer(Modifier.height(16.dp)); Text("Devices", style = MaterialTheme.typography.titleMedium) }
            items(devices, key = { it.id }) { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.alias}${if (d.revoked) " (revoked)" else ""}", Modifier.weight(1f))
                    TextButton(onClick = { scope.launch { access.setDeviceRevoked(d.id, !d.revoked); reload() } }) { Text(if (d.revoked) "Un-revoke" else "Revoke") }
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

### Task 21: Navigation + MainActivity + Settings toggles

**Files:**
- Create: `app/src/main/java/com/viswa2k/smsforwarder/cloud/ui/CloudNav.kt`
- Modify: `MainActivity.kt`, `ui/screen/SettingsScreen.kt`, `ui/screen/SettingsViewModel.kt`

**Interfaces:**
- Produces: `@Composable fun CloudNav(vm: CloudViewModel)` hosting sign-in → cloud → watch/admin; a cloud entry from settings; cloud-channel + receive toggles wired to `UserPreferences`.

- [ ] **Step 1: Implement the cloud nav host**

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
        composable("signin") { SignInScreen(vm) { nav.navigate("cloud") { popUpTo("signin") { inclusive = true } } } }
        composable("cloud") { CloudSmsScreen(vm, onOpenWatch = { nav.navigate("watch") }, onOpenAdmin = { nav.navigate("admin") }) }
        composable("watch") { WatchScreen(vm) { nav.popBackStack() } }
        composable("admin") { AdminScreen(vm) { nav.popBackStack() } }
    }
}
```

- [ ] **Step 2: Add a Cloud entry from MainActivity**

In `MainActivity.kt`, wrap the existing settings content in a top-level NavHost. Replace the `setContent { }` body's root with:
```kotlin
        setContent {
            MaterialTheme {
                Surface {
                    val cloudVm: com.viswa2k.smsforwarder.cloud.ui.CloudViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel()
                    val nav = androidx.navigation.compose.rememberNavController()
                    androidx.navigation.compose.NavHost(nav, startDestination = "settings") {
                        androidx.navigation.compose.composable("settings") {
                            SettingsScreen(/* keep existing args */ onOpenCloud = { nav.navigate("cloud") })
                        }
                        androidx.navigation.compose.composable("cloud") {
                            com.viswa2k.smsforwarder.cloud.ui.CloudNav(cloudVm)
                        }
                    }
                }
            }
        }
```
Keep the current `SettingsScreen(...)` arguments exactly; just add the new `onOpenCloud` lambda (defined in Step 4).

After `userPreferences.initializeDefaults()` in the existing `lifecycleScope.launch { }`, add a one-time queue flush:
```kotlin
            try {
                if (userPreferences.isCloudChannelEnabled.first()) {
                    com.viswa2k.smsforwarder.cloud.data.SmsCloudUploader(applicationContext).flushQueue()
                }
            } catch (e: Exception) { Log.e("MainActivity", "queue flush failed") }
```

- [ ] **Step 3: Wire cloud toggles into SettingsViewModel**

In `SettingsViewModel.kt`, mirror the existing per-preference pattern. Add:
```kotlin
    val isCloudChannelEnabled = MutableStateFlow(false)
    val isReceiveEnabled = MutableStateFlow(false)
```
In the existing init/load block (where other prefs are collected), collect `userPreferences.isCloudChannelEnabled` and `userPreferences.isReceiveEnabled` into those flows. Add setters (using the existing `userPreferences` field name):
```kotlin
    fun setCloudChannelEnabled(v: Boolean) { isCloudChannelEnabled.value = v; viewModelScope.launch { userPreferences.saveCloudChannelEnabled(v) } }
    fun setReceiveEnabled(v: Boolean) { isReceiveEnabled.value = v; viewModelScope.launch { userPreferences.saveReceiveEnabled(v) } }
```

- [ ] **Step 4: Add toggles + cloud entry to SettingsScreen**

In `SettingsScreen.kt`, add `onOpenCloud: () -> Unit = {}` to the composable signature. In the settings list (matching the existing "Forward by SMS"/"Forward by Telegram" toggle row style), add two switches bound to `viewModel.isCloudChannelEnabled`/`setCloudChannelEnabled` ("Upload to cloud") and `viewModel.isReceiveEnabled`/`setReceiveEnabled` ("Receive cloud messages"), and a button row "Open Cloud SMS" calling `onOpenCloud()`.

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

### Task 22: Instrumented crypto test + rules test + smoke checklist

**Files:**
- Create: `app/src/androidTest/java/com/viswa2k/smsforwarder/cloud/CryptoManagerInstrumentedTest.kt`
- Create: `firebase/test/rules.test.md`
- Create: `docs/superpowers/plans/2026-06-20-cloud-sms-smoke-checklist.md`

- [ ] **Step 1: Write the instrumented crypto test**

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
        assertArrayEquals(dek, cm.openWrappedDek(cm.sealDekTo(pub, dek)))
        val body = cm.encryptBody(dek, "hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), cm.decryptBody(dek, body.ciphertext, body.nonce))
    }
}
```

- [ ] **Step 2: Run it on a device/emulator**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.viswa2k.smsforwarder.cloud.CryptoManagerInstrumentedTest"`
Expected: PASS (Keystore-backed seal/open round-trips).

- [ ] **Step 3: Write the rules-test guidance**

Create `firebase/test/rules.test.md`:

```markdown
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
```

- [ ] **Step 4: Write the e2e smoke checklist**

Create `docs/superpowers/plans/2026-06-20-cloud-sms-smoke-checklist.md`:

```markdown
# Cloud SMS — manual smoke test (two devices)

Pre-req: rules + indexes deployed, Cloud Function deployed, super-admin seeded,
`google-services.json` + `GOOGLE_WEB_CLIENT_ID` set, Auth providers enabled.

1. Device A (admin): sign in → device doc created with `publicKey`.
2. Device B (member): sign in with an allow-listed email → registered. A non-listed email is rejected ("not authorized").
3. Admin → Admin screen: Grant access (reader=B, source=A).
4. Device B → Watch: enable A, notify ON.
5. Device A: enable "Upload to cloud"; send an SMS to A.
6. Expect `inbox/{A-admin}/messages/{id}` and `inbox/{B}/messages/{id}` created; fields are ciphertext (unreadable in console).
7. Device B: gets a push with decrypted "sender: body"; Cloud SMS screen lists it.
8. Toggle notify OFF on B; send another SMS; B gets no push but sees it on opening the screen (snapshot listener).
9. Admin deletes a message → removed from all inboxes; a member has no Delete button and rules deny delete.
10. Admin revokes Device B → new SMS no longer fans out to B; B stops seeing new messages.
```

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/com/viswa2k/smsforwarder/cloud/CryptoManagerInstrumentedTest.kt firebase/test/rules.test.md docs/superpowers/plans/2026-06-20-cloud-sms-smoke-checklist.md
git commit -m "test: instrumented crypto, rules-test guidance, e2e smoke checklist"
```

---

## Appendix — known follow-ups (not in this plan)

- **Web reader/admin sub-project** (own spec/plan): QR pairing, Tink↔raw-HPKE public-key bridging for browsers, Web Push, Android-side re-fan-out backfill + Firebase custom-token minting via a Cloud Function.
- **Hardware per-key biometric binding**: current build seals the keyset with a Keystore master key and gates decrypt screens with `BiometricPrompt`; binding the keyset to `setUserAuthenticationRequired` is a hardening follow-up.
- **Key rotation scheduler**: `CryptoManager.rotateIdentityKey()` exists; a 30-day trigger + re-publish + re-fan-out is a follow-up.
