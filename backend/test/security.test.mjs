import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { createZeroTrustApp } from "../dist/app.js";
import { redactSensitiveText } from "../dist/services/auditLogger.js";
import { validateOutboundHttpsUrl } from "../dist/security/ssrf.js";

let server;
let baseUrl;
let revokedUids = [];

const testConfig = {
  port: 0,
  nodeEnv: "production",
  firebaseProjectId: "demo-ayra",
  requireAppCheck: true,
  corsAllowedOrigins: ["https://ayra.ai.app"],
  rateLimitWindowMs: 60_000,
  rateLimitMax: 25,
  geminiApiKey: "AIzaFakeServerKeyShouldNeverLeakToClient",
  openRouterApiKey: "",
  nvidiaApiKey: "",
  groqApiKey: "",
};

const fakeVerifiers = {
  verifyIdToken: async (token) => {
    if (token === "valid_user_id_token_12345") {
      return { uid: "uid_alice", email: "alice@example.com", role: "user" };
    }
    if (token === "valid_admin_id_token_12345") {
      return { uid: "uid_admin", email: "admin@example.com", role: "admin" };
    }
    if (token === "ratelimit_user_token_12345") {
      return { uid: "uid_ratelimit", email: "rate@example.com", role: "user" };
    }
    throw new Error("Invalid or revoked token");
  },
  verifyAppCheckToken: async (token) => {
    if (token !== "valid_app_check_token_xyz") {
      throw new Error("Invalid App Check token");
    }
  },
};

before(async () => {
  const app = createZeroTrustApp({
    config: testConfig,
    verifiers: fakeVerifiers,
    revokeRefreshTokensFn: async (uid) => {
      revokedUids.push(uid);
    },
    ssrfResolver: async (host) => {
      if (host === "public.example.com") return [{ address: "93.184.216.34" }];
      if (host === "rebind.evil.test") return [{ address: "169.254.169.254" }];
      if (host === "ipv6-internal.test") return [{ address: "fd12:3456:789a::1" }];
      return [{ address: "93.184.216.34" }];
    },
    fetchFn: async () =>
      new Response(
        JSON.stringify({
          candidates: [{ content: { parts: [{ text: "Proxied secure Gemini reply" }] } }],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      ),
  });
  await new Promise((resolve) => {
    server = app.listen(0, "127.0.0.1", () => {
      const addr = server.address();
      baseUrl = `http://127.0.0.1:${addr.port}`;
      resolve();
    });
  });
});

after(async () => {
  await new Promise((resolve) => server.close(() => resolve()));
});

test("Security headers and minimal health endpoint", async () => {
  const res = await fetch(`${baseUrl}/health`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("x-content-type-options"), "nosniff");
  assert.equal(res.headers.get("x-powered-by"), null);
  assert.ok(res.headers.get("strict-transport-security")?.includes("max-age=31536000"));
});

test("Rejects unauthenticated, invalid Bearer, and missing/invalid App Check requests", async () => {
  const noAuth = await fetch(`${baseUrl}/api/v1/security/posture`);
  assert.equal(noAuth.status, 401);

  const badBearer = await fetch(`${baseUrl}/api/v1/security/posture`, {
    headers: { Authorization: "Bearer forged_token_1234567890" },
  });
  assert.equal(badBearer.status, 401);

  const missingAppCheck = await fetch(`${baseUrl}/api/v1/security/posture`, {
    headers: { Authorization: "Bearer valid_user_id_token_12345" },
  });
  assert.equal(missingAppCheck.status, 401);

  const valid = await fetch(`${baseUrl}/api/v1/security/posture`, {
    headers: {
      Authorization: "Bearer valid_user_id_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
  });
  assert.equal(valid.status, 200);
  const body = await valid.json();
  assert.equal(body.uid, "uid_alice");
  assert.equal(body.appCheckVerified, true);
  assert.equal(JSON.stringify(body).includes("AIzaFakeServerKey"), false);
});

test("RBAC rejects non-admin caller and permits admin caller", async () => {
  const nonAdmin = await fetch(`${baseUrl}/api/v1/admin/audit-health`, {
    headers: {
      Authorization: "Bearer valid_user_id_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
  });
  assert.equal(nonAdmin.status, 403);

  const admin = await fetch(`${baseUrl}/api/v1/admin/audit-health`, {
    headers: {
      Authorization: "Bearer valid_admin_id_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
  });
  assert.equal(admin.status, 200);
});

test("Strict Zod validation rejects unknown mass-assignment fields and forwards valid AI proxy", async () => {
  const massAssign = await fetch(`${baseUrl}/api/v1/ai/chat`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer valid_user_id_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
    body: JSON.stringify({
      provider: "gemini",
      model: "gemini-2.5-flash",
      messages: [{ role: "user", content: "Hello" }],
      roleOverride: "admin",
    }),
  });
  assert.equal(massAssign.status, 400);

  const ok = await fetch(`${baseUrl}/api/v1/ai/chat`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer valid_user_id_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
    body: JSON.stringify({
      provider: "gemini",
      model: "gemini-2.5-flash",
      messages: [{ role: "user", content: "Hello" }],
    }),
  });
  assert.equal(ok.status, 200);
  const json = await ok.json();
  assert.equal(json.content, "Proxied secure Gemini reply");
});

test("Anti-SSRF blocks metadata IPs, RFC1918, HTTP, loopback, DNS rebinding, and IPv6 ULA", async () => {
  const blockedUrls = [
    "http://public.example.com",
    "https://169.254.169.254/latest/meta-data",
    "https://metadata.google.internal/computeMetadata/v1/",
    "https://127.0.0.1:8080/admin",
    "https://10.0.0.1/internal",
    "https://192.168.1.1/",
    "https://[::1]/",
    "https://rebind.evil.test/",
    "https://ipv6-internal.test/",
  ];

  for (const url of blockedUrls) {
    const res = await fetch(`${baseUrl}/api/v1/network/inspect-url`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer valid_user_id_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
      body: JSON.stringify({ url }),
    });
    assert.equal(res.status, 400, `Expected SSRF block for ${url}`);
  }

  const allowedDirect = await validateOutboundHttpsUrl(
    "https://public.example.com/v1",
    undefined,
    async () => [{ address: "93.184.216.34" }]
  );
  assert.equal(allowedDirect.allowed, true);
});

test("CORS blocks untrusted origins", async () => {
  const blockedCors = await fetch(`${baseUrl}/health`, {
    headers: { Origin: "https://evil-attacker.example" },
  });
  assert.equal(blockedCors.status, 403);
});

test("Audit logger redacts API keys, Bearer tokens, and passphrases", () => {
  const dirty =
    "Failed auth Bearer eyJhbGciOi.abc.xyz with AIzaSyDummySecret123456789012345 and sk-proj-secret123456789012345 passphrase=myStrongSecret";
  const clean = redactSensitiveText(dirty);
  assert.equal(clean.includes("eyJhbGciOi"), false);
  assert.equal(clean.includes("AIzaSyDummySecret"), false);
  assert.equal(clean.includes("sk-proj-secret"), false);
  assert.equal(clean.includes("myStrongSecret"), false);
});

test("Device revocation calls Firebase token revocation and rate limit enforces 429", async () => {
  const revRes = await fetch(`${baseUrl}/api/v1/devices/revoke`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer ratelimit_user_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
    body: JSON.stringify({ deviceId: "dev_old_phone_01" }),
  });
  assert.equal(revRes.status, 200);
  assert.ok(revokedUids.includes("uid_ratelimit"));

  // Exhaust 25 requests/window limit
  let saw429 = false;
  for (let i = 0; i < 27; i++) {
    const r = await fetch(`${baseUrl}/api/v1/security/posture`, {
      headers: {
        Authorization: "Bearer ratelimit_user_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
    });
    if (r.status === 429) {
      saw429 = true;
      break;
    }
  }
  assert.equal(saw429, true);
});

test("Model catalog requires auth + App Check and returns curated catalog without key leakage", async () => {
  const unauth = await fetch(`${baseUrl}/api/v1/models/catalog`);
  assert.equal(unauth.status, 401);

  const ok = await fetch(`${baseUrl}/api/v1/models/catalog`, {
    headers: {
      Authorization: "Bearer valid_user_id_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
  });
  assert.equal(ok.status, 200);
  const catalog = await ok.json();
  assert.ok(Array.isArray(catalog.models) && catalog.models.length >= 5);
  assert.ok(catalog.models.some((m) => m.id === "gemini-3.8-flash"));
  assert.equal(JSON.stringify(catalog).includes("AIzaFakeServerKey"), false);
});

test("Sandboxed /api/v1/code/run evaluates math/JS cleanly and blocks fs/process/fetch/infinite loops", async () => {
  const unauth = await fetch(`${baseUrl}/api/v1/code/run`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ language: "math", code: "2 + 2" }),
  });
  assert.equal(unauth.status, 401);

  const evalOk = await fetch(`${baseUrl}/api/v1/code/run`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer valid_user_id_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
    body: JSON.stringify({
      language: "javascript",
      code: "console.log('sum:', input.a + input.b); return input.a * input.b;",
      inputJson: JSON.stringify({ a: 6, b: 7 }),
      timeoutMs: 300,
    }),
  });
  assert.equal(evalOk.status, 200);
  const okBody = await evalOk.json();
  assert.equal(okBody.ok, true);
  assert.equal(okBody.resultText, "42");
  assert.ok(okBody.stdout.includes("sum: 13"));

  const escapes = [
    "return process.env;",
    "const fs = require('fs'); return fs.readdirSync('/');",
    "return typeof fetch;",
    "while (true) {}",
  ];
  for (const snippet of escapes) {
    const res = await fetch(`${baseUrl}/api/v1/code/run`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer valid_user_id_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
      body: JSON.stringify({
        language: "javascript",
        code: snippet,
        timeoutMs: 120,
      }),
    });
    const body = await res.json();
    if (snippet.includes("typeof fetch")) {
      assert.equal(body.resultText, "undefined");
    } else {
      assert.equal(res.status, 400, `Expected sandbox rejection/timeout for: ${snippet}`);
      assert.equal(body.ok, false);
    }
  }
});
