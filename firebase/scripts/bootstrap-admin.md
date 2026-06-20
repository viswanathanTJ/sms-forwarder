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
