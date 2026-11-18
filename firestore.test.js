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
    content: "AYRA_E2EE_V1:encryptedCipherEnvelopePayload",
    modelUsed: "gemini-3.8-flash",
    craftType: "",
    craftPayload: "",
    e2ee: true,
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
    prompt: "AYRA_E2EE_V1:encryptedPromptEnvelope",
    summary: "AYRA_E2EE_V1:encryptedSummaryEnvelope",
    modelUsed: "gemini-nano-banana-2.1",
    e2ee: true,
    createdAt: tsNow(),
    updatedAt: tsNow(),
  };
}

function validDevice(uid, deviceId = "dev_pixel_01") {
  return {
    userId: uid,
    deviceId,
    deviceName: "Pixel 9 Pro",
    identityPublicKeyB64: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE1234567890",
    ecdhPublicKeyB64: "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0987654321",
    fingerprintSha256: "a1b2c3d4e5f60708090a0b0c0d0e0f10",
    epochAdded: 1,
    isRevoked: false,
    platform: "Android 15",
    createdAt: tsNow(),
    updatedAt: tsNow(),
  };
}

function validSession(uid, sessionId = "sess_dev_pixel_01", deviceId = "dev_pixel_01") {
  return {
    userId: uid,
    sessionId,
    deviceId,
    deviceName: "Pixel 9 Pro",
    status: "ACTIVE",
    createdAtMs: 1700000000000,
    lastActiveMs: 1700000001000,
    createdAt: tsNow(),
    updatedAt: tsNow(),
  };
}

function validBackup(uid, backupId = "bak_001") {
  return {
    userId: uid,
    backupId,
    format: "AYRA_ENCRYPTED_BACKUP_V1",
    encryptedPayloadJson: "{\"v\":1,\"ciphertext\":\"abcdef123456\"}",
    payloadSha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    createdAtMs: 1700000000000,
    createdAt: tsNow(),
    updatedAt: tsNow(),
  };
}

function validSecurityEvent(uid) {
  return {
    userId: uid,
    category: "BIOMETRIC",
    severity: "INFO",
    message: "Vault unlocked with cryptographic proof",
    hmacHex: "0123456789abcdef0123456789abcdef",
    timestampMs: 1700000000000,
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
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("devices").get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("backups").get());
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

test("Cross-user access to profiles, messages, plugins, creations, devices, sessions, backups, and securityEvents is denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceRoot = aliceDb.collection("users").doc(ALICE_UID);
  const bobViewOfAlice = bobDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(aliceRoot.set(validProfile(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("messages").doc("m1").set(validMessage(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("plugins").doc("p1").set(validPlugin(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("creations").doc("c1").set(validCreation(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("devices").doc("dev_pixel_01").set(validDevice(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("sessions").doc("sess_dev_pixel_01").set(validSession(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("backups").doc("bak_001").set(validBackup(ALICE_UID)));
  await assertSucceeds(aliceRoot.collection("securityEvents").doc("ev_1").set(validSecurityEvent(ALICE_UID)));

  await assertFails(bobViewOfAlice.get());
  await assertFails(bobViewOfAlice.collection("messages").doc("m1").get());
  await assertFails(bobViewOfAlice.collection("devices").get());
  await assertFails(bobViewOfAlice.collection("sessions").get());
  await assertFails(bobViewOfAlice.collection("backups").doc("bak_001").get());
  await assertFails(bobViewOfAlice.collection("securityEvents").get());
  await assertFails(bobViewOfAlice.collection("plugins").doc("p1").delete());
  await assertFails(
    bobViewOfAlice.collection("creations").doc("c2").set(validCreation(BOB_UID))
  );
});

test("Devices, sessions, backups, and securityEvents enforce immutability, status enums, and hash lengths", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const aliceRoot = aliceDb.collection("users").doc(ALICE_UID);

  // Device create & revocation update succeed; changing deviceId fails
  const devRef = aliceRoot.collection("devices").doc("dev_pixel_01");
  await assertSucceeds(devRef.set(validDevice(ALICE_UID)));
  await assertSucceeds(devRef.update({ isRevoked: true, updatedAt: tsNow() }));
  await assertFails(devRef.update({ deviceId: "dev_other", updatedAt: tsNow() }));

  // Session rejects unknown status enum
  const sessRef = aliceRoot.collection("sessions").doc("sess_dev_pixel_01");
  await assertFails(
    sessRef.set({ ...validSession(ALICE_UID), status: "ROOT_OVERRIDE" })
  );
  await assertSucceeds(sessRef.set(validSession(ALICE_UID)));
  await assertSucceeds(sessRef.update({ status: "REVOKED", updatedAt: tsNow() }));

  // Backups enforce 64-char sha256 & prohibit mutation (create/delete only)
  const bakRef = aliceRoot.collection("backups").doc("bak_001");
  await assertFails(bakRef.set({ ...validBackup(ALICE_UID), payloadSha256: "short" }));
  await assertSucceeds(bakRef.set(validBackup(ALICE_UID)));
  await assertFails(bakRef.update({ format: "TAMPERED", updatedAt: tsNow() }));
  await assertSucceeds(bakRef.delete());

  // SecurityEvents are append-only (no update), owner can delete on erasure
  const evRef = aliceRoot.collection("securityEvents").doc("ev_1");
  await assertFails(evRef.set({ ...validSecurityEvent(ALICE_UID), severity: "DEBUG" }));
  await assertSucceeds(evRef.set(validSecurityEvent(ALICE_UID)));
  await assertFails(evRef.update({ message: "Rewritten history", updatedAt: tsNow() }));
  await assertSucceeds(evRef.delete());
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

test("Shared system and administrative resources strictly deny client reads and writes", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();

  const restrictedPaths = [
    ["admins", ALICE_UID],
    ["system", "runtime_flags"],
    ["config", "providers"],
    ["shared", "global_memory"],
    ["auditLogs", "event_01"],
    ["modelCatalog", "curated_models"],
    ["workspaces", "shared_ws"],
  ];

  for (const [col, docId] of restrictedPaths) {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await ctx.firestore().collection(col).doc(docId).set({
        userId: ALICE_UID,
        enabled: true,
        updatedAt: tsNow(),
      });
    });

    await assertFails(unauthDb.collection(col).doc(docId).get());
    await assertFails(unauthDb.collection(col).get());
    await assertFails(aliceDb.collection(col).doc(docId).get());
    await assertFails(aliceDb.collection(col).get());
    await assertFails(aliceDb.collection(col).doc(docId).set({ userId: ALICE_UID, role: "admin" }));
    await assertFails(aliceDb.collection(col).doc(docId).update({ enabled: false }));
    await assertFails(aliceDb.collection(col).doc(docId).delete());
  }
});

test("Root /users collection enumeration, unmodeled subcollections, anonymous auth, future timestamps, and revocation un-revoking are blocked", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const anonDb = testEnv
    .authenticatedContext("anon_user_99", {
      firebase: { sign_in_provider: "anonymous" },
    })
    .firestore();

  // Root /users listing is denied even for signed-in users
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).set(validProfile(ALICE_UID)));
  await assertFails(aliceDb.collection("users").get());

  // Unmodeled subcollection under /users/{uid} fails closed
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("unmodeledVault").doc("doc1").set({ secret: "x" })
  );

  // Anonymous auth token rejected even on matching uid path
  await assertFails(
    anonDb.collection("users").doc("anon_user_99").set(validProfile("anon_user_99"))
  );

  // Future timestamp manipulation rejected
  const futureTs = new Date(Date.now() + 120000);
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("messages").doc("m_future").set({
      ...validMessage(ALICE_UID),
      createdAt: futureTs,
      updatedAt: futureTs,
    })
  );

  // Poisoned doc ID rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).collection("messages").doc("bad id$").set(validMessage(ALICE_UID))
  );

  // Once device is revoked, client update cannot flip isRevoked back to false or lower epochAdded
  const devRef = aliceDb.collection("users").doc(ALICE_UID).collection("devices").doc("dev_pixel_01");
  await assertSucceeds(devRef.set({ ...validDevice(ALICE_UID), epochAdded: 2 }));
  await assertSucceeds(devRef.update({ isRevoked: true, updatedAt: tsNow() }));
  await assertFails(devRef.update({ isRevoked: false, updatedAt: tsNow() }));
  await assertFails(devRef.update({ epochAdded: 1, updatedAt: tsNow() }));
});
