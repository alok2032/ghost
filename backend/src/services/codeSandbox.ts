import crypto from "node:crypto";
import type { BackendConfig } from "../config.js";
import { createPinnedFetch, validateOutboundHttpsUrl } from "../security/ssrf.js";
import type { CodeRunRequest } from "../validation/schemas.js";
import { redactSensitiveText } from "./auditLogger.js";

const MAX_STDOUT_CHARS = 16_384;
const MAX_SANDBOX_RESPONSE_BYTES = 128 * 1024;
const SANDBOX_MEMORY_MB = 128;
const SANDBOX_CPU_QUOTA = 0.5;
const SANDBOX_PIDS_LIMIT = 16;

export interface SandboxIsolationProfile {
  nonRoot: true;
  readOnlyRootFs: true;
  networkDisabled: true;
  memoryMb: number;
  cpuQuota: number;
  pidsLimit: number;
  timeoutMs: number;
  maxOutputBytes: number;
}

export interface SandboxAttestationReceipt {
  executionId: string;
  nonce: string;
  runnerId: string;
  isolationProfileHash: string;
  issuedAtMs: number;
  expiresAtMs: number;
  payloadSha256: string;
  signature: string;
}

export interface SandboxExecutionResult {
  statusCode: number;
  ok: boolean;
  isolated: boolean;
  attestationVerified: boolean;
  executionId?: string;
  language: CodeRunRequest["language"];
  resultText: string;
  stdout: string;
  durationMs: number;
  truncated: boolean;
  errorCode?: "SANDBOX_DISABLED" | "SANDBOX_MISCONFIGURED" | "INVALID_INPUT" | "ATTESTATION_FAILED" | "EXECUTION_FAILED";
  error?: string;
}

export function buildSandboxIsolationProfile(timeoutMs: number): SandboxIsolationProfile {
  return {
    nonRoot: true,
    readOnlyRootFs: true,
    networkDisabled: true,
    memoryMb: SANDBOX_MEMORY_MB,
    cpuQuota: SANDBOX_CPU_QUOTA,
    pidsLimit: SANDBOX_PIDS_LIMIT,
    timeoutMs: Math.max(50, Math.min(timeoutMs, 1500)),
    maxOutputBytes: MAX_STDOUT_CHARS,
  };
}

export function hashIsolationProfile(profile: SandboxIsolationProfile): string {
  const canonical = JSON.stringify({
    cpuQuota: profile.cpuQuota,
    maxOutputBytes: profile.maxOutputBytes,
    memoryMb: profile.memoryMb,
    networkDisabled: profile.networkDisabled,
    nonRoot: profile.nonRoot,
    pidsLimit: profile.pidsLimit,
    readOnlyRootFs: profile.readOnlyRootFs,
    timeoutMs: profile.timeoutMs,
  });
  return crypto.createHash("sha256").update(canonical, "utf8").digest("hex");
}

export function hashSandboxPayload(ok: boolean, resultText: string, stdout: string): string {
  return crypto
    .createHash("sha256")
    .update(`${ok ? "1" : "0"}|${resultText}|${stdout}`, "utf8")
    .digest("hex");
}

export function signSandboxAttestation(
  secret: string,
  fields: Omit<SandboxAttestationReceipt, "signature">
): SandboxAttestationReceipt {
  const material = [
    fields.executionId,
    fields.nonce,
    fields.runnerId,
    fields.isolationProfileHash,
    String(fields.issuedAtMs),
    String(fields.expiresAtMs),
    fields.payloadSha256,
  ].join("|");
  const signature = crypto.createHmac("sha256", secret).update(material, "utf8").digest("hex");
  return { ...fields, signature };
}

export function verifySandboxAttestation(params: {
  secret: string;
  receipt: unknown;
  expectedExecutionId: string;
  expectedNonce: string;
  expectedProfileHash: string;
  expectedPayloadSha256: string;
  nowMs?: number;
}): { valid: boolean; reason?: string } {
  const {
    secret,
    receipt,
    expectedExecutionId,
    expectedNonce,
    expectedProfileHash,
    expectedPayloadSha256,
    nowMs = Date.now(),
  } = params;

  if (!receipt || typeof receipt !== "object") {
    return { valid: false, reason: "Missing sandbox attestation receipt." };
  }
  const r = receipt as Record<string, unknown>;
  const executionId = typeof r.executionId === "string" ? r.executionId : "";
  const nonce = typeof r.nonce === "string" ? r.nonce : "";
  const runnerId = typeof r.runnerId === "string" ? r.runnerId : "";
  const isolationProfileHash = typeof r.isolationProfileHash === "string" ? r.isolationProfileHash : "";
  const payloadSha256 = typeof r.payloadSha256 === "string" ? r.payloadSha256 : "";
  const signature = typeof r.signature === "string" ? r.signature : "";
  const issuedAtMs = typeof r.issuedAtMs === "number" ? r.issuedAtMs : Number.NaN;
  const expiresAtMs = typeof r.expiresAtMs === "number" ? r.expiresAtMs : Number.NaN;

  if (
    !executionId ||
    !nonce ||
    !runnerId ||
    !isolationProfileHash ||
    !payloadSha256 ||
    !signature ||
    !Number.isFinite(issuedAtMs) ||
    !Number.isFinite(expiresAtMs)
  ) {
    return { valid: false, reason: "Malformed sandbox attestation fields." };
  }
  if (executionId !== expectedExecutionId || nonce !== expectedNonce) {
    return { valid: false, reason: "Sandbox attestation executionId or nonce mismatch." };
  }
  if (isolationProfileHash !== expectedProfileHash) {
    return { valid: false, reason: "Sandbox attestation isolation profile mismatch." };
  }
  if (payloadSha256 !== expectedPayloadSha256) {
    return { valid: false, reason: "Sandbox attestation payload digest mismatch." };
  }
  if (issuedAtMs > nowMs + 5_000 || expiresAtMs <= nowMs) {
    return { valid: false, reason: "Sandbox attestation timestamp expired or invalid." };
  }

  const expected = signSandboxAttestation(secret, {
    executionId,
    nonce,
    runnerId,
    isolationProfileHash,
    issuedAtMs,
    expiresAtMs,
    payloadSha256,
  });

  const gotBuf = Buffer.from(signature, "hex");
  const expBuf = Buffer.from(expected.signature, "hex");
  if (gotBuf.length === 0 || gotBuf.length !== expBuf.length) {
    return { valid: false, reason: "Sandbox attestation signature length mismatch." };
  }
  if (!crypto.timingSafeEqual(gotBuf, expBuf)) {
    return { valid: false, reason: "Sandbox attestation HMAC signature verification failed." };
  }
  return { valid: true };
}

async function readBoundedText(response: Response, maxBytes: number): Promise<string> {
  if (!response.body) {
    const text = await response.text();
    if (Buffer.byteLength(text, "utf8") > maxBytes) {
      throw new Error(`Sandbox response exceeded ${maxBytes} bytes limit.`);
    }
    return text;
  }
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) {
        total += value.byteLength;
        if (total > maxBytes) {
          await reader.cancel("Response limit exceeded").catch(() => undefined);
          throw new Error(`Sandbox response exceeded ${maxBytes} bytes limit.`);
        }
        chunks.push(value);
      }
    }
  } finally {
    reader.releaseLock();
  }
  return Buffer.concat(chunks.map((c) => Buffer.from(c))).toString("utf8");
}

/**
 * Zero-Trust Remote Code Sandbox Client.
 *
 * Security Invariants:
 * 1. In-process `node:vm` / `eval` execution is permanently disabled.
 * 2. Fails CLOSED unless SANDBOX_EXECUTOR_URL + SANDBOX_EXECUTOR_TOKEN + SANDBOX_ATTESTATION_SECRET are valid.
 * 3. `isolated: true` is NEVER reported unless HTTP 2xx, bounded payload parsing, AND HMAC-SHA256
 *    attestation receipt verification succeed.
 */
export async function executeIsolatedSandbox(
  config: BackendConfig,
  req: CodeRunRequest,
  principalUid: string,
  deps?: {
    fetchFn?: typeof fetch;
    ssrfResolver?: (hostname: string) => Promise<Array<{ address: string }>>;
  }
): Promise<SandboxExecutionResult> {
  const started = Date.now();

  if (req.inputJson && req.inputJson.trim().length > 0) {
    try {
      JSON.parse(req.inputJson);
    } catch {
      return {
        statusCode: 400,
        ok: false,
        isolated: false,
        attestationVerified: false,
        language: req.language,
        resultText: "",
        stdout: "",
        durationMs: Date.now() - started,
        truncated: false,
        errorCode: "INVALID_INPUT",
        error: "Invalid inputJson payload",
      };
    }
  }

  if (!config.sandboxExecutorUrl || !config.sandboxExecutorToken) {
    return {
      statusCode: 503,
      ok: false,
      isolated: false,
      attestationVerified: false,
      language: req.language,
      resultText: "",
      stdout: "",
      durationMs: Date.now() - started,
      truncated: false,
      errorCode: "SANDBOX_DISABLED",
      error: "Isolated remote code sandbox is not configured. In-process VM execution is disabled.",
    };
  }

  if (!config.sandboxAttestationSecret || config.sandboxAttestationSecret.length < 16) {
    return {
      statusCode: 503,
      ok: false,
      isolated: false,
      attestationVerified: false,
      language: req.language,
      resultText: "",
      stdout: "",
      durationMs: Date.now() - started,
      truncated: false,
      errorCode: "SANDBOX_MISCONFIGURED",
      error: "SANDBOX_ATTESTATION_SECRET (>=16 chars) is required to verify isolated execution.",
    };
  }

  const allowedHosts =
    config.sandboxAllowedHosts && config.sandboxAllowedHosts.length > 0
      ? new Set(config.sandboxAllowedHosts)
      : undefined;

  const urlVerdict = await validateOutboundHttpsUrl(
    config.sandboxExecutorUrl,
    allowedHosts,
    deps?.ssrfResolver
  );
  if (!urlVerdict.allowed || !urlVerdict.normalizedUrl || !urlVerdict.resolvedAddresses?.length) {
    return {
      statusCode: 503,
      ok: false,
      isolated: false,
      attestationVerified: false,
      language: req.language,
      resultText: "",
      stdout: "",
      durationMs: Date.now() - started,
      truncated: false,
      errorCode: "SANDBOX_MISCONFIGURED",
      error: `Sandbox executor endpoint rejected by SSRF/allowlist policy: ${urlVerdict.reason ?? "invalid URL"}`,
    };
  }

  const profile = buildSandboxIsolationProfile(req.timeoutMs);
  const profileHash = hashIsolationProfile(profile);
  const executionId = crypto.randomUUID();
  const nonce = crypto.randomBytes(16).toString("hex");
  const fetchFn = deps?.fetchFn ?? createPinnedFetch(urlVerdict.resolvedAddresses);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), profile.timeoutMs + 1500);

  try {
    const response = await fetchFn(urlVerdict.normalizedUrl, {
      method: "POST",
      redirect: "error",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${config.sandboxExecutorToken}`,
        "X-Ayra-Principal": principalUid,
        "X-Ayra-Execution-Id": executionId,
        "X-Ayra-Nonce": nonce,
      },
      body: JSON.stringify({
        executionId,
        nonce,
        language: req.language,
        code: req.code,
        inputJson: req.inputJson ?? null,
        isolation: profile,
      }),
      signal: controller.signal,
    });

    if (!response.ok) {
      return {
        statusCode: response.status >= 500 ? 502 : 400,
        ok: false,
        isolated: false,
        attestationVerified: false,
        executionId,
        language: req.language,
        resultText: "",
        stdout: "",
        durationMs: Date.now() - started,
        truncated: false,
        errorCode: "EXECUTION_FAILED",
        error: `Isolated sandbox returned HTTP ${response.status}`,
      };
    }

    const rawText = await readBoundedText(response, MAX_SANDBOX_RESPONSE_BYTES);
    const body = JSON.parse(rawText) as {
      ok?: unknown;
      resultText?: unknown;
      stdout?: unknown;
      truncated?: unknown;
      error?: unknown;
      attestation?: unknown;
    };

    const rawResult = typeof body.resultText === "string" ? body.resultText : "";
    const rawStdout = typeof body.stdout === "string" ? body.stdout : "";
    const isOk = body.ok === true;
    const boundedResult = rawResult.slice(0, MAX_STDOUT_CHARS);
    const boundedStdout = rawStdout.slice(0, MAX_STDOUT_CHARS);
    const expectedPayloadSha = hashSandboxPayload(isOk, rawResult, rawStdout);

    const attCheck = verifySandboxAttestation({
      secret: config.sandboxAttestationSecret,
      receipt: body.attestation,
      expectedExecutionId: executionId,
      expectedNonce: nonce,
      expectedProfileHash: profileHash,
      expectedPayloadSha256: expectedPayloadSha,
    });

    if (!attCheck.valid) {
      return {
        statusCode: 502,
        ok: false,
        isolated: false,
        attestationVerified: false,
        executionId,
        language: req.language,
        resultText: "",
        stdout: "",
        durationMs: Date.now() - started,
        truncated: false,
        errorCode: "ATTESTATION_FAILED",
        error: attCheck.reason ?? "Sandbox attestation verification failed.",
      };
    }

    const truncated =
      Boolean(body.truncated) ||
      rawResult.length > MAX_STDOUT_CHARS ||
      rawStdout.length > MAX_STDOUT_CHARS;

    return {
      statusCode: isOk ? 200 : 400,
      ok: isOk,
      isolated: true,
      attestationVerified: true,
      executionId,
      language: req.language,
      resultText: boundedResult,
      stdout: boundedStdout,
      durationMs: Date.now() - started,
      truncated,
      errorCode: isOk ? undefined : "EXECUTION_FAILED",
      error:
        isOk || typeof body.error !== "string"
          ? undefined
          : redactSensitiveText(body.error).slice(0, 300),
    };
  } catch (err) {
    const safeErr = redactSensitiveText(
      err instanceof Error ? err.message : "Remote sandbox unavailable"
    ).slice(0, 300);
    return {
      statusCode: 502,
      ok: false,
      isolated: false,
      attestationVerified: false,
      executionId,
      language: req.language,
      resultText: "",
      stdout: "",
      durationMs: Date.now() - started,
      truncated: false,
      errorCode: "EXECUTION_FAILED",
      error: safeErr,
    };
  } finally {
    clearTimeout(timer);
  }
}
