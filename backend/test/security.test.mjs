import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { after, before, test } from "node:test";
import { createZeroTrustApp } from "../dist/app.js";
import { assertProductionConfig, loadConfig } from "../dist/config.js";
import {
  MemoryRateLimitStore,
  RedisHttpRateLimitStore,
  resolveRateLimitStore,
} from "../dist/middleware/rateLimit.js";
import { validateOutboundHttpsUrl } from "../dist/security/ssrf.js";
import { redactSensitiveText } from "../dist/services/auditLogger.js";
import {
  buildSandboxIsolationProfile,
  hashIsolationProfile,
  hashSandboxPayload,
  signSandboxAttestation,
} from "../dist/services/codeSandbox.js";

let server;
let baseUrl;
let revokedUids = [];

const testConfig = {
  port: 0,
  nodeEnv: "production",
  firebaseProjectId: "gen-lang-client-0153630349",
  requireAppCheck: true,
  corsAllowedOrigins: ["https://ayra.ai.app"],
  rateLimitWindowMs: 60_000,
  rateLimitMax: 25,
  aiProviderRateLimitMax: 120,
  aiModelRateLimitMax: 60,
  toolRateLimitMax: 20,
  globalRateLimitMax: 1000,
  maxConcurrentRequestsPerUser: 4,
  dailyTokenQuotaPerUser: 250_000,
  dailySpendCapUsdPerUser: 5.0,
  codeSandboxMaxPerWindow: 4,
  redisRestUrl: "",
  redisRestToken: "",
  allowInMemoryRateLimit: true,
  sandboxExecutorUrl: "",
  sandboxExecutorToken: "",
  sandboxAttestationSecret: "",
  sandboxAllowedHosts: [],
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
    if (token === "sandbox_quota_user_token_12345") {
      return { uid: "uid_sandbox_quota", email: "sb@example.com", role: "user" };
    }
    if (token === "daily_quota_user_token_12345") {
      return { uid: "uid_daily_quota", email: "dq@example.com", role: "user" };
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
      if (host === "nat64-internal.test") return [{ address: "64:ff9b::169.254.169.254" }];
      if (host === "6to4-internal.test") return [{ address: "2002:0a00:0001::1" }];
      if (host === "teredo-internal.test") return [{ address: "2001:0000:4136:e378::1" }];
      if (host === "dns-fail.test") throw new Error("ENOTFOUND");
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

test("Firebase config refuses stale hardcoded project IDs and fails closed in production", () => {
  const unconfiguredProd = loadConfig({ NODE_ENV: "production" });
  assert.equal(unconfiguredProd.firebaseProjectId, "");
  assert.throws(() => assertProductionConfig(unconfiguredProd), /FIREBASE_PROJECT_ID/);

  const staleProd = loadConfig({
    NODE_ENV: "production",
    FIREBASE_PROJECT_ID: "gen-lang-client-0595455617",
    ALLOW_IN_MEMORY_RATE_LIMIT: "true",
  });
  assert.throws(() => assertProductionConfig(staleProd), /retired\/stale/);

  const validProdNoRedis = loadConfig({
    NODE_ENV: "production",
    FIREBASE_PROJECT_ID: "gen-lang-client-0153630349",
  });
  assert.throws(() => resolveRateLimitStore(validProdNoRedis), /REDIS_REST_URL/);
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
  assert.equal(body.sandboxConfigured, false);
  assert.equal(body.environment, "production");
  assert.equal(body.firebaseProjectId, "gen-lang-client-0153630349");
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
      model: "gemini-3.8-flash",
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
      model: "gemini-3.8-flash",
      messages: [{ role: "user", content: "Hello" }],
    }),
  });
  assert.equal(ok.status, 200);
  const json = await ok.json();
  assert.equal(json.content, "Proxied secure Gemini reply");
  assert.ok(json.estimatedTokens > 0);
});

test("Anti-SSRF blocks metadata IPs, RFC1918, NAT64, 6to4, Teredo, non-443 ports, credentials, and DNS errors", async () => {
  const blockedUrls = [
    "http://public.example.com",
    "https://user:pass@public.example.com/",
    "https://public.example.com:8443/v1",
    "https://169.254.169.254/latest/meta-data",
    "https://metadata.google.internal/computeMetadata/v1/",
    "https://127.0.0.1:8080/admin",
    "https://10.0.0.1/internal",
    "https://192.168.1.1/",
    "https://[::1]/",
    "https://[::ffff:169.254.169.254]/latest/meta-data",
    "https://[0:0:0:0:0:ffff:7f00:1]/",
    "https://rebind.evil.test/",
    "https://ipv6-internal.test/",
    "https://nat64-internal.test/",
    "https://6to4-internal.test/",
    "https://teredo-internal.test/",
    "https://dns-fail.test/",
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
  assert.deepEqual(allowedDirect.resolvedAddresses, ["93.184.216.34"]);
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

test("Device revocation calls Firebase token revocation, rate limit sets Retry-After 429, and quota status returns usage", async () => {
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

  let saw429 = false;
  let retryAfterHeader = null;
  for (let i = 0; i < 27; i++) {
    const r = await fetch(`${baseUrl}/api/v1/security/posture`, {
      headers: {
        Authorization: "Bearer ratelimit_user_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
    });
    if (r.status === 429) {
      saw429 = true;
      retryAfterHeader = r.headers.get("retry-after");
      break;
    }
  }
  assert.equal(saw429, true);
  assert.ok(Number(retryAfterHeader) >= 1);

  const statusRes = await fetch(`${baseUrl}/api/v1/quotas/status`, {
    headers: {
      Authorization: "Bearer valid_user_id_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
  });
  assert.equal(statusRes.status, 200);
  const statusJson = await statusRes.json();
  assert.equal(statusJson.uid, "uid_alice");
  assert.equal(statusJson.storeKind, "memory");
  assert.ok(statusJson.dailyTokensUsed > 0);
});

test("Distributed RedisHttpRateLimitStore sends atomic pipeline commands", async () => {
  const calls = [];
  const redisStore = new RedisHttpRateLimitStore(
    "https://redis.upstash.example",
    "token_123",
    async (url, init) => {
      calls.push({ url, body: JSON.parse(String(init?.body ?? "[]")) });
      return new Response(
        JSON.stringify([{ result: 3 }, { result: 1 }, { result: 42000 }]),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    }
  );
  const res = await redisStore.increment("rl:user:uid_alice", 60_000, 1);
  assert.equal(res.count, 3);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "https://redis.upstash.example/pipeline");
  assert.equal(calls[0].body[0][0], "INCRBY");
});

test("Model catalog exposes honest verification status, provenance, and unavailability", async () => {
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
  const flash = catalog.models.find((m) => m.id === "gemini-3.8-flash");
  const groq = catalog.models.find((m) => m.id === "llama-3.3-70b-versatile");
  assert.ok(flash);
  assert.equal(flash.verificationStatus, "LIVE_VERIFIED"); // earlier chat succeeded & closed circuit breaker
  assert.equal(flash.pricingVerified, false);
  assert.ok(groq);
  assert.equal(groq.verificationStatus, "UNAVAILABLE"); // groqApiKey empty
  assert.equal(JSON.stringify(catalog).includes("AIzaFakeServerKey"), false);
});

test("In-process node:vm is permanently eliminated from backend source", () => {
  const sandboxSrc = fs.readFileSync(path.resolve("src/services/codeSandbox.ts"), "utf8");
  assert.equal(/from\s+["']node:vm["']|require\(["']node:vm["']\)/.test(sandboxSrc), false);
  assert.equal(sandboxSrc.includes("createContext("), false);
  assert.equal(sandboxSrc.includes("runInContext("), false);
});

test("/api/v1/code/run fails closed when unconfigured and enforces per-user quota", async () => {
  const unauth = await fetch(`${baseUrl}/api/v1/code/run`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ language: "math", code: "2 + 2" }),
  });
  assert.equal(unauth.status, 401);

  const disabledRes = await fetch(`${baseUrl}/api/v1/code/run`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer valid_user_id_token_12345",
      "X-Firebase-AppCheck": "valid_app_check_token_xyz",
    },
    body: JSON.stringify({
      language: "javascript",
      code: "return process.env;",
      timeoutMs: 250,
    }),
  });
  assert.equal(disabledRes.status, 503);
  const disabledBody = await disabledRes.json();
  assert.equal(disabledBody.ok, false);
  assert.equal(disabledBody.isolated, false);
  assert.equal(disabledBody.errorCode, "SANDBOX_DISABLED");

  let sawSandbox429 = false;
  for (let i = 0; i < 6; i++) {
    const r = await fetch(`${baseUrl}/api/v1/code/run`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer sandbox_quota_user_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
      body: JSON.stringify({ language: "math", code: "1+1" }),
    });
    if (r.status === 429) {
      sawSandbox429 = true;
      break;
    }
  }
  assert.equal(sawSandbox429, true);
});

test("Isolated sandbox verifies HMAC attestation receipt and never sets isolated=true on errors or tampered attestation", async () => {
  const attSecret = "super_strong_sandbox_attestation_secret_2026";
  let mode = "valid";
  let capturedPayload = null;
  let capturedAuth = null;

  const isolatedApp = createZeroTrustApp({
    config: {
      ...testConfig,
      nodeEnv: "staging",
      codeSandboxMaxPerWindow: 10,
      sandboxExecutorUrl: "https://sandbox-runner.example.com/execute",
      sandboxExecutorToken: "sandbox_runner_secret_token_xyz",
      sandboxAttestationSecret: attSecret,
      sandboxAllowedHosts: ["sandbox-runner.example.com"],
    },
    verifiers: fakeVerifiers,
    rateLimitStore: new MemoryRateLimitStore(),
    ssrfResolver: async (host) => {
      if (host === "metadata-runner.evil.test") return [{ address: "169.254.169.254" }];
      return [{ address: "93.184.216.34" }];
    },
    fetchFn: async (_url, init) => {
      capturedPayload = JSON.parse(String(init?.body ?? "{}"));
      capturedAuth = init?.headers;

      if (mode === "http500") {
        return new Response(JSON.stringify({ error: "Runner crash" }), { status: 500 });
      }
      if (mode === "network_error") {
        throw new Error("ECONNREFUSED");
      }

      const resultText = "42";
      const stdout = "sum: 13";
      const profileHash = hashIsolationProfile(capturedPayload.isolation);
      const payloadSha = hashSandboxPayload(true, resultText, stdout);
      const now = Date.now();
      const attestation = signSandboxAttestation(attSecret, {
        executionId: capturedPayload.executionId,
        nonce: capturedPayload.nonce,
        runnerId: "gvisor-node-01",
        isolationProfileHash: profileHash,
        issuedAtMs: now - 5,
        expiresAtMs: mode === "expired_att" ? now - 10 : now + 30_000,
        payloadSha256: payloadSha,
      });
      if (mode === "tampered_att") {
        attestation.signature = "0".repeat(64);
      }

      return new Response(
        JSON.stringify({
          ok: true,
          resultText,
          stdout,
          truncated: false,
          attestation: mode === "missing_att" ? undefined : attestation,
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      );
    },
  });

  const isolatedServer = await new Promise((resolve) => {
    const s = isolatedApp.listen(0, "127.0.0.1", () => resolve(s));
  });
  try {
    const addr = isolatedServer.address();
    const url = `http://127.0.0.1:${addr.port}`;

    // 1. Valid signed attestation -> ok=true, isolated=true, attestationVerified=true
    const okRes = await fetch(`${url}/api/v1/code/run`, {
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
    assert.equal(okRes.status, 200);
    const body = await okRes.json();
    assert.equal(body.ok, true);
    assert.equal(body.isolated, true);
    assert.equal(body.attestationVerified, true);
    assert.equal(body.resultText, "42");
    assert.equal(capturedPayload?.isolation?.nonRoot, true);
    assert.equal(capturedAuth?.["X-Ayra-Principal"], "uid_alice");

    // 2. HTTP 500 -> NEVER reports isolated=true
    mode = "http500";
    const errRes = await fetch(`${url}/api/v1/code/run`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer valid_user_id_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
      body: JSON.stringify({ language: "math", code: "2+2" }),
    });
    assert.equal(errRes.status, 502);
    const errJson = await errRes.json();
    assert.equal(errJson.isolated, false);
    assert.equal(errJson.attestationVerified, false);

    // 3. Tampered attestation -> 502 ATTESTATION_FAILED, isolated=false
    mode = "tampered_att";
    const tampRes = await fetch(`${url}/api/v1/code/run`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer valid_user_id_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
      body: JSON.stringify({ language: "math", code: "2+2" }),
    });
    assert.equal(tampRes.status, 502);
    const tampJson = await tampRes.json();
    assert.equal(tampJson.errorCode, "ATTESTATION_FAILED");
    assert.equal(tampJson.isolated, false);

    // 4. Missing attestation -> 502 ATTESTATION_FAILED, isolated=false
    mode = "missing_att";
    const missRes = await fetch(`${url}/api/v1/code/run`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer valid_user_id_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
      body: JSON.stringify({ language: "math", code: "2+2" }),
    });
    assert.equal(missRes.status, 502);
    assert.equal((await missRes.json()).isolated, false);

    // 5. Network failure -> 502 EXECUTION_FAILED, isolated=false
    mode = "network_error";
    const netRes = await fetch(`${url}/api/v1/code/run`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer valid_user_id_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
      body: JSON.stringify({ language: "math", code: "2+2" }),
    });
    assert.equal(netRes.status, 502);
    assert.equal((await netRes.json()).isolated, false);
  } finally {
    await new Promise((resolve) => isolatedServer.close(() => resolve()));
  }

  // Host allowlist enforcement check
  const disallowedHostApp = createZeroTrustApp({
    config: {
      ...testConfig,
      sandboxExecutorUrl: "https://unapproved-runner.example.com/execute",
      sandboxExecutorToken: "sandbox_runner_secret_token_xyz",
      sandboxAttestationSecret: attSecret,
      sandboxAllowedHosts: ["sandbox-runner.example.com"],
    },
    verifiers: fakeVerifiers,
    rateLimitStore: new MemoryRateLimitStore(),
    ssrfResolver: async () => [{ address: "93.184.216.34" }],
  });
  const sServer = await new Promise((resolve) => {
    const s = disallowedHostApp.listen(0, "127.0.0.1", () => resolve(s));
  });
  try {
    const addr = sServer.address();
    const res = await fetch(`http://127.0.0.1:${addr.port}/api/v1/code/run`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer valid_user_id_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
      body: JSON.stringify({ language: "math", code: "2+2" }),
    });
    assert.equal(res.status, 503);
    assert.equal((await res.json()).errorCode, "SANDBOX_MISCONFIGURED");
  } finally {
    await new Promise((resolve) => sServer.close(() => resolve()));
  }
});

test("Daily token quota enforces 429 with DAILY_TOKEN_QUOTA_EXCEEDED", async () => {
  const lowQuotaApp = createZeroTrustApp({
    config: {
      ...testConfig,
      dailyTokenQuotaPerUser: 100,
    },
    verifiers: fakeVerifiers,
    rateLimitStore: new MemoryRateLimitStore(),
    fetchFn: async () =>
      new Response(
        JSON.stringify({
          candidates: [{ content: { parts: [{ text: "ok" }] } }],
        }),
        { status: 200, headers: { "Content-Type": "application/json" } }
      ),
  });
  const qServer = await new Promise((resolve) => {
    const s = lowQuotaApp.listen(0, "127.0.0.1", () => resolve(s));
  });
  try {
    const addr = qServer.address();
    const res = await fetch(`http://127.0.0.1:${addr.port}/api/v1/ai/chat`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: "Bearer daily_quota_user_token_12345",
        "X-Firebase-AppCheck": "valid_app_check_token_xyz",
      },
      body: JSON.stringify({
        provider: "gemini",
        model: "gemini-3.8-flash",
        messages: [{ role: "user", content: "Hello" }],
        maxTokens: 512,
      }),
    });
    assert.equal(res.status, 429);
    const body = await res.json();
    assert.equal(body.errorCode, "DAILY_TOKEN_QUOTA_EXCEEDED");
    assert.ok(Number(res.headers.get("retry-after")) >= 1);
  } finally {
    await new Promise((resolve) => qServer.close(() => resolve()));
  }
});
