/**
 * Security-rules unit tests for ../firestore.rules.
 *
 * Run against the Firestore emulator:
 *   cd firebase/test && npm install
 *   cd .. && firebase emulators:exec --only firestore --project demo-smsforwarder \
 *       "cd test && npx jest --runInBand"
 *
 * Or, with the emulator already running and FIRESTORE_EMULATOR_HOST set:
 *   cd firebase/test && npm test
 */
const fs = require("fs");
const path = require("path");
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { setDoc, getDoc, deleteDoc, updateDoc, doc } = require("firebase/firestore");

const PROJECT_ID = "demo-smsforwarder";
const ADMIN = "admin@x.com";
const MEMBER = "member@x.com";
const STRANGER = "stranger@x.com";

let testEnv;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: fs.readFileSync(path.resolve(__dirname, "../firestore.rules"), "utf8"),
    },
  });
});

afterAll(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  // Seed the world with rules disabled.
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, "authorized_emails", ADMIN), { role: "admin", addedBy: ADMIN });
    await setDoc(doc(db, "authorized_emails", MEMBER), { role: "member", addedBy: ADMIN });
    // devA owned by member, devB owned by admin.
    await setDoc(doc(db, "devices", "devA"), { ownerEmail: MEMBER, alias: "A" });
    await setDoc(doc(db, "devices", "devB"), { ownerEmail: ADMIN, alias: "B" });
    // a grant so devA (member) is allowed to receive from devB
    await setDoc(doc(db, "access_matrix", "devA__devB"), {
      readerDeviceId: "devA", sourceDeviceId: "devB", grantedBy: ADMIN,
    });
    // one message in each inbox
    await setDoc(doc(db, "inbox", "devA", "messages", "m1"), { sourceDeviceId: "devA", ciphertext: "x" });
    await setDoc(doc(db, "inbox", "devB", "messages", "m2"), { sourceDeviceId: "devB", ciphertext: "y" });
  });
});

const ctxFor = (email) =>
  email ? testEnv.authenticatedContext(email.replace(/\W/g, ""), { email }).firestore()
        : testEnv.unauthenticatedContext().firestore();

describe("authorization gate", () => {
  test("unauthenticated is denied reads everywhere", async () => {
    const db = ctxFor(null);
    await assertFails(getDoc(doc(db, "devices", "devA")));
    await assertFails(getDoc(doc(db, "authorized_emails", MEMBER)));
  });

  test("signed-in but not allow-listed is denied reads", async () => {
    const db = ctxFor(STRANGER);
    await assertFails(getDoc(doc(db, "devices", "devA")));
    await assertFails(getDoc(doc(db, "access_matrix", "devA__devB")));
  });
});

describe("member capabilities", () => {
  test("member can read devices and access_matrix", async () => {
    const db = ctxFor(MEMBER);
    await assertSucceeds(getDoc(doc(db, "devices", "devA")));
    await assertSucceeds(getDoc(doc(db, "access_matrix", "devA__devB")));
  });

  test("member cannot write access_matrix (admin-only)", async () => {
    const db = ctxFor(MEMBER);
    await assertFails(setDoc(doc(db, "access_matrix", "devA__devX"), {
      readerDeviceId: "devA", sourceDeviceId: "devX", grantedBy: MEMBER,
    }));
  });

  test("admin can write access_matrix", async () => {
    const db = ctxFor(ADMIN);
    await assertSucceeds(setDoc(doc(db, "access_matrix", "devA__devX"), {
      readerDeviceId: "devA", sourceDeviceId: "devX", grantedBy: ADMIN,
    }));
  });
});

describe("inbox isolation", () => {
  test("member can read own device inbox", async () => {
    const db = ctxFor(MEMBER);
    await assertSucceeds(getDoc(doc(db, "inbox", "devA", "messages", "m1")));
  });

  test("member cannot read another device's inbox", async () => {
    const db = ctxFor(MEMBER);
    await assertFails(getDoc(doc(db, "inbox", "devB", "messages", "m2")));
  });

  test("non-admin cannot delete an inbox message; admin can", async () => {
    await assertFails(deleteDoc(doc(ctxFor(MEMBER), "inbox", "devA", "messages", "m1")));
    await assertSucceeds(deleteDoc(doc(ctxFor(ADMIN), "inbox", "devA", "messages", "m1")));
  });

  test("inbox messages are immutable (no update)", async () => {
    await assertFails(updateDoc(doc(ctxFor(ADMIN), "inbox", "devA", "messages", "m1"), { ciphertext: "z" }));
  });
});

describe("fan-out create", () => {
  test("owner can write into its own inbox (source == recipient)", async () => {
    const db = ctxFor(MEMBER);
    await assertSucceeds(setDoc(doc(db, "inbox", "devA", "messages", "f1"), { sourceDeviceId: "devA" }));
  });

  test("owner can fan-out to an admin device (isAdminDevice)", async () => {
    // member owns devA; devB is an admin device → allowed even without a matrix grant.
    const db = ctxFor(MEMBER);
    await assertSucceeds(setDoc(doc(db, "inbox", "devB", "messages", "f2"), { sourceDeviceId: "devA" }));
  });

  test("cannot fan-out claiming a source device you do not own", async () => {
    const db = ctxFor(MEMBER);
    await assertFails(setDoc(doc(db, "inbox", "devA", "messages", "f3"), { sourceDeviceId: "devB" }));
  });
});

describe("device takeover prevention", () => {
  test("owner can update alias but not reassign ownerEmail", async () => {
    const db = ctxFor(MEMBER);
    await assertSucceeds(updateDoc(doc(db, "devices", "devA"), { alias: "A2" }));
    await assertFails(updateDoc(doc(db, "devices", "devA"), { ownerEmail: STRANGER }));
  });

  test("authorized user can create only a device they own", async () => {
    const db = ctxFor(MEMBER);
    await assertSucceeds(setDoc(doc(db, "devices", "devY"), { ownerEmail: MEMBER, alias: "Y" }));
    await assertFails(setDoc(doc(db, "devices", "devZ"), { ownerEmail: ADMIN, alias: "Z" }));
  });
});

describe("access_requests self-service", () => {
  test("a signed-in user can create only their own pending request", async () => {
    const db = ctxFor(STRANGER);
    await assertSucceeds(setDoc(doc(db, "access_requests", STRANGER), { status: "pending", displayName: "S" }));
    await assertFails(setDoc(doc(db, "access_requests", "someone@else.com"), { status: "pending" }));
    await assertFails(setDoc(doc(db, "access_requests", STRANGER + ".x"), { status: "approved" }));
  });

  test("a user can read only their own request; admin can read and delete", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "access_requests", STRANGER), { status: "pending" });
    });
    await assertSucceeds(getDoc(doc(ctxFor(STRANGER), "access_requests", STRANGER)));
    await assertFails(getDoc(doc(ctxFor(MEMBER), "access_requests", STRANGER)));
    await assertSucceeds(getDoc(doc(ctxFor(ADMIN), "access_requests", STRANGER)));
    await assertSucceeds(deleteDoc(doc(ctxFor(ADMIN), "access_requests", STRANGER)));
  });
});
