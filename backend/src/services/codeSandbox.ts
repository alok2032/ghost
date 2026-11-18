import type { BackendConfig } from "../config.js";
import { validateOutboundHttpsUrl } from "../security/ssrf.js";
import type { CodeRunRequest } from "../validation/schemas.js";
import { redactSensitiveText } from "./auditLogger.js";

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

export interface SandboxExecutionResult {
  statusCode: number;
  ok: boolean;
  isolated: boolean;
  language: CodeRunRequest["language"];
  resultText: string;
  stdout: string;
  durationMs: number;
  truncated: boolean;
  errorCode?: "SANDBOX_DISABLED" | "SANDBOX_MISCONFIGURED" | "INVALID_INPUT" | "EXECUTION_FAILED";
  error?: string;
}

const MAX_STDOUT_CHARS = 16_384;
const SANDBOX_MEMORY_MB = 128;
const SANDBOX_CPU_QUOTA = 0.5;
const SANDBOX_PIDS_LIMIT = 16;

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

/**
 * Zero-Trust Remote Code Sandbox Client.
 *
 * Security Invariant: In-process `node:vm` / `eval` execution is permanently disabled because
 * `node:vm` is NOT a security boundary against arbitrary JS escapes or prototype pollution.
 * Code execution strictly requires a hardened out-of-process isolated container runner configured
 * via SANDBOX_EXECUTOR_URL + SANDBOX_EXECUTOR_TOKEN, and fails CLOSED with HTTP 503 otherwise.
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
      language: req.language,
      resultText: "",
      stdout: "",
      durationMs: Date.now() - started,
      truncated: false,
      errorCode: "SANDBOX_DISABLED",
      error:
        "Isolated remote code sandbox is not configured. In-process VM execution is disabled.",
    };
  }

  const urlVerdict = await validateOutboundHttpsUrl(
    config.sandboxExecutorUrl,
    undefined,
    deps?.ssrfResolver
  );
  if (!urlVerdict.allowed || !urlVerdict.normalizedUrl) {
    return {
      statusCode: 503,
      ok: false,
      isolated: false,
      language: req.language,
      resultText: "",
      stdout: "",
      durationMs: Date.now() - started,
      truncated: false,
      errorCode: "SANDBOX_MISCONFIGURED",
      error: `Sandbox executor endpoint rejected by SSRF policy: ${urlVerdict.reason ?? "invalid URL"}`,
    };
  }

  const profile = buildSandboxIsolationProfile(req.timeoutMs);
  const fetchFn = deps?.fetchFn ?? fetch;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), profile.timeoutMs + 1500);

  try {
    const response = await fetchFn(urlVerdict.normalizedUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${config.sandboxExecutorToken}`,
        "X-Ayra-Principal": principalUid,
      },
      body: JSON.stringify({
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
        isolated: true,
        language: req.language,
        resultText: "",
        stdout: "",
        durationMs: Date.now() - started,
        truncated: false,
        errorCode: "EXECUTION_FAILED",
        error: `Isolated sandbox returned HTTP ${response.status}`,
      };
    }

    const body = (await response.json()) as {
      ok?: unknown;
      resultText?: unknown;
      stdout?: unknown;
      truncated?: unknown;
      error?: unknown;
    };

    const rawResult = typeof body.resultText === "string" ? body.resultText : "";
    const rawStdout = typeof body.stdout === "string" ? body.stdout : "";
    const truncated =
      Boolean(body.truncated) ||
      rawResult.length > MAX_STDOUT_CHARS ||
      rawStdout.length > MAX_STDOUT_CHARS;
    const isOk = body.ok === true;

    return {
      statusCode: isOk ? 200 : 400,
      ok: isOk,
      isolated: true,
      language: req.language,
      resultText: rawResult.slice(0, MAX_STDOUT_CHARS),
      stdout: rawStdout.slice(0, MAX_STDOUT_CHARS),
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
      isolated: true,
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
