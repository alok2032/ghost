const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_123";
const BOB_UID = "bob_uid_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function tsNow() {
  return new Date(Date.now() - 1000);
}

function validProfile(uid) {
  return {
    userId: uid,
    displayName: "Alice",
    email: "alice@example.com",
    photoUrl: "https://example.com/alice.png",
    preferredPersona: "GF_COMPANION",
    preferredModelTier: "auto",
    createdAt: tsNow(),
    updatedAt: tsNow(),
  };
}

function validMessage(uid) {
  return {
    userId: uid,
    localMessageId: 1,
    role: "user",
    content: "Hello Ayra",
    modelUsed: "gemini-3.8-flash",
    craftType: "",
    craftPayload: "",
    timestampMs: 1700000000000,
    createdAt: tsNow(),
    updatedAt: tsNow(),
  };
}

function validPlugin(uid) {
  return {
    userId: uid,
    repoFullName: "obra/superpowers",
    repoUrl: "https://github.com/obra/superpowers",
    name: "superpowers",
    owner: "obra",
    description: "Agent skills plugin",
    defaultBranch: "main",
    stars: 500,
    primaryLanguage: "Markdown",
    pluginType: "SKILL_PACK",
    capabilitiesSummary: "Code review and TDD workflows",
    skillPromptContext: "Follow TDD first",
    openApiSpecJson: "[]",
    commandsJson: "[]",
    fileTreeJson: "[]",
    isEnabled: true,
    createdAt: tsNow(),
    updatedAt: tsNow(),
  };
}

function validCreation(uid) {
  return {
    userId: uid,
    creationType: "IMAGE",
    title: "Cyberpunk Sunset",
    prompt: "Draw a futuristic neon sunset city",
    summary: "Generated via Nano Banana 2.1",
    modelUsed: "gemini-nano-banana-2.1",
    createdAt: tsNow(),
    updatedAt: tsNow(),
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated caller cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("messages").get());
  await assertFails(unauthDb.collection("other_root").doc("doc1").set({ hello: "world" }));
});

test("Owner can create, read, and validly update their user profile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(profileRef.set(validProfile(ALICE_UID)));
  await assertSucceeds(profileRef.get());
  await assertSucceeds(
    profileRef.update({
      displayName: "Alice Updated",
      preferredModelTier: "omniroute",
      updatedAt: tsNow(),
    })
  );
});

test("Profile rejects forged userId, extra fields, and immutable field mutation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertFails(profileRef.set({ ...validProfile(BOB_UID) }));
  await assertFails(profileRef.set({ ...validProfile(ALICE_UID), role: "admin" }));

  await assertSucceeds(profileRef.set(validProfile(ALICE_UID)));
  await assertFails(
    profileRef.update({
      userId: BOB_UID,
      updatedAt: tsNow(),
    })
  );
});

test("Cross-user access to profiles, messages, plugins, and creations is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("messages").doc("m1").set(validMessage(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("plugins").doc("p1").set(validPlugin(ALICE_UID))
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("creations").doc("c1").set(validCreation(ALICE_UID))
  );

  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("messages").doc("m1").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("messages").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("plugins").doc("p1").delete());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("creations").doc("c2").set(validCreation(BOB_UID))
  );
});

test("Messages, plugins, and creations validate schema enums and reject invalid payloads", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const aliceRoot = aliceDb.collection("users").doc(ALICE_UID);

  // Invalid role enum
  await assertFails(aliceRoot.collection("messages").doc("bad_role").set({ ...validMessage(ALICE_UID), role: "system" }));
  await assertSucceeds(aliceRoot.collection("messages").doc("ok_msg").set(validMessage(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("messages").get());

  // Invalid pluginType enum
  await assertFails(
    aliceRoot.collection("plugins").doc("bad_plug").set({ ...validPlugin(ALICE_UID), pluginType: "ROOTKIT" })
  );
  await assertSucceeds(aliceRoot.collection("plugins").doc("ok_plug").set(validPlugin(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("plugins").get());

  // Invalid creationType enum or blank title
  await assertFails(
    aliceRoot.collection("creations").doc("bad_cr").set({ ...validCreation(ALICE_UID), creationType: "EXECUTABLE" })
  );
  await assertFails(
    aliceRoot.collection("creations").doc("blank_cr").set({ ...validCreation(ALICE_UID), title: "" })
  );
  await assertSucceeds(aliceRoot.collection("creations").doc("ok_cr").set(validCreation(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("creations").get());
});
