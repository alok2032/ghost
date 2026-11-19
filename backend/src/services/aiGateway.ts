import type { BackendConfig } from "../config.js";
import { createPinnedFetch, validateOutboundHttpsUrl } from "../security/ssrf.js";
import type { AiChatRequest } from "../validation/schemas.js";
import { redactSensitiveText } from "./auditLogger.js";

const ALLOWED_PROVIDER_HOSTS = new Set([
  "generativelanguage.googleapis.com",
  "openrouter.ai",
  "integrate.api.nvidia.com",
  "api.groq.com",
]);

export interface AiGatewayResponse {
  provider: AiChatRequest["provider"];
  model: string;
  content: string;
  estimatedTokens: number;
  estimatedSpendUsdMicros: number;
}

export class ProviderCircuitBreaker {
  private readonly failureThreshold: number;
  private readonly cooldownMs: number;
  private readonly state = new Map<
    string,
    { consecutiveFailures: number; openUntilMs: number; lastSuccessMs: number | null; lastError: string | null }
  >();

  constructor(failureThreshold = 3, cooldownMs = 30_000) {
    this.failureThreshold = failureThreshold;
    this.cooldownMs = cooldownMs;
  }

  assertClosed(provider: string, nowMs = Date.now()): void {
    const entry = this.state.get(provider);
    if (entry && entry.openUntilMs > nowMs) {
      const retrySec = Math.max(1, Math.ceil((entry.openUntilMs - nowMs) / 1000));
      throw new GatewayError(
        503,
        `Circuit breaker open for provider '${provider}'. Retry after ${retrySec}s.`
      );
    }
  }

  recordSuccess(provider: string, nowMs = Date.now()): void {
    this.state.set(provider, {
      consecutiveFailures: 0,
      openUntilMs: 0,
      lastSuccessMs: nowMs,
      lastError: null,
    });
  }

  recordFailure(provider: string, reason: string, nowMs = Date.now()): void {
    const prev = this.state.get(provider) ?? {
      consecutiveFailures: 0,
      openUntilMs: 0,
      lastSuccessMs: null,
      lastError: null,
    };
    const nextFailures = prev.consecutiveFailures + 1;
    this.state.set(provider, {
      consecutiveFailures: nextFailures,
      openUntilMs: nextFailures >= this.failureThreshold ? nowMs + this.cooldownMs : 0,
      lastSuccessMs: prev.lastSuccessMs,
      lastError: redactSensitiveText(reason).slice(0, 160),
    });
  }

  getSnapshot(): Record<
    string,
    { status: "LIVE_VERIFIED" | "UNAVAILABLE" | "STATIC_DEFAULT"; lastSuccessMs?: number; failureReason?: string }
  > {
    const now = Date.now();
    const out: Record<
      string,
      { status: "LIVE_VERIFIED" | "UNAVAILABLE" | "STATIC_DEFAULT"; lastSuccessMs?: number; failureReason?: string }
    > = {};
    for (const [provider, st] of this.state.entries()) {
      const mapped = provider === "nvidia" ? "nvidia_nim" : provider;
      if (st.openUntilMs > now) {
        out[mapped] = {
          status: "UNAVAILABLE",
          lastSuccessMs: st.lastSuccessMs ?? undefined,
          failureReason: st.lastError ?? "Circuit breaker open",
        };
      } else if (st.lastSuccessMs !== null) {
        out[mapped] = { status: "LIVE_VERIFIED", lastSuccessMs: st.lastSuccessMs };
      }
    }
    return out;
  }
}

export function estimateRequestUsage(payload: AiChatRequest): {
  estimatedTokens: number;
  estimatedSpendUsdMicros: number;
} {
  const inputChars = payload.messages.reduce((sum, m) => sum + m.content.length, 0);
  const estInputTokens = Math.max(16, Math.ceil(inputChars / 4));
  const estTotalTokens = estInputTokens + Math.min(payload.maxTokens, 1024);
  // Conservative ceiling cost estimate: $2.00 per 1M tokens -> 2 micro-USD per token
  return {
    estimatedTokens: estTotalTokens,
    estimatedSpendUsdMicros: estTotalTokens * 2,
  };
}

export async function forwardSecureAiRequest(
  config: BackendConfig,
  payload: AiChatRequest,
  fetchFn?: typeof fetch,
  circuitBreaker?: ProviderCircuitBreaker,
  ssrfResolver?: (hostname: string) => Promise<Array<{ address: string }>>
): Promise<AiGatewayResponse> {
  circuitBreaker?.assertClosed(payload.provider);
  const usage = estimateRequestUsage(payload);

  try {
    if (payload.provider === "gemini") {
      if (!config.geminiApiKey) {
        throw new GatewayError(503, "GEMINI_API_KEY is not configured on backend.");
      }
      const target = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(payload.model)}:generateContent`;
      const check = await validateOutboundHttpsUrl(target, ALLOWED_PROVIDER_HOSTS, ssrfResolver);
      if (!check.allowed || !check.normalizedUrl || !check.resolvedAddresses?.length) {
        throw new GatewayError(400, check.reason ?? "Blocked AI upstream URL.");
      }
      const systemText = payload.messages
        .filter((m) => m.role === "system")
        .map((m) => m.content.trim())
        .filter((t) => t.length > 0)
        .join("\n\n");
      const nonSystemMessages = payload.messages.filter((m) => m.role !== "system");
      if (nonSystemMessages.length === 0) {
        throw new GatewayError(400, "At least one user or assistant message is required.");
      }
      const activeFetch = fetchFn ?? createPinnedFetch(check.resolvedAddresses);
      const upstreamRes = await activeFetch(check.normalizedUrl, {
        method: "POST",
        redirect: "error",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": config.geminiApiKey,
        },
        body: JSON.stringify({
          ...(systemText ? { systemInstruction: { parts: [{ text: systemText }] } } : {}),
          contents: nonSystemMessages.map((m) => ({
            role: m.role === "assistant" ? "model" : "user",
            parts: [{ text: m.content }],
          })),
          generationConfig: {
            temperature: payload.temperature,
            maxOutputTokens: payload.maxTokens,
          },
        }),
        signal: AbortSignal.timeout(30_000),
      });
      if (!upstreamRes.ok) {
        throw new GatewayError(
          upstreamRes.status === 429 ? 429 : 502,
          `Upstream provider returned HTTP ${upstreamRes.status}`
        );
      }
      const data = (await upstreamRes.json()) as {
        candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
      };
      const text =
        data.candidates?.[0]?.content?.parts?.map((p) => p.text ?? "").join("").trim() ?? "";
      if (!text) {
        throw new GatewayError(502, "Upstream provider returned empty content.");
      }
      circuitBreaker?.recordSuccess(payload.provider);
      const actualTokens = Math.max(usage.estimatedTokens, Math.ceil((text.length + 64) / 4));
      return {
        provider: payload.provider,
        model: payload.model,
        content: text,
        estimatedTokens: actualTokens,
        estimatedSpendUsdMicros: actualTokens * 2,
      };
    }

    const endpointByProvider: Record<
      Exclude<AiChatRequest["provider"], "gemini">,
      { url: string; apiKey: string }
    > = {
      openrouter: {
        url: "https://openrouter.ai/api/v1/chat/completions",
        apiKey: config.openRouterApiKey,
      },
      nvidia: {
        url: "https://integrate.api.nvidia.com/v1/chat/completions",
        apiKey: config.nvidiaApiKey,
      },
      groq: {
        url: "https://api.groq.com/openai/v1/chat/completions",
        apiKey: config.groqApiKey,
      },
    };

    const targetSpec = endpointByProvider[payload.provider];
    if (!targetSpec.apiKey) {
      throw new GatewayError(
        503,
        `API credential for provider '${payload.provider}' is not configured on backend.`
      );
    }
    const check = await validateOutboundHttpsUrl(targetSpec.url, ALLOWED_PROVIDER_HOSTS, ssrfResolver);
    if (!check.allowed || !check.normalizedUrl || !check.resolvedAddresses?.length) {
      throw new GatewayError(400, check.reason ?? "Blocked AI upstream URL.");
    }

    const activeFetch = fetchFn ?? createPinnedFetch(check.resolvedAddresses);
    const upstreamRes = await activeFetch(check.normalizedUrl, {
      method: "POST",
      redirect: "error",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${targetSpec.apiKey}`,
      },
      body: JSON.stringify({
        model: payload.model,
        messages: payload.messages,
        temperature: payload.temperature,
        max_tokens: payload.maxTokens,
      }),
      signal: AbortSignal.timeout(30_000),
    });

    if (!upstreamRes.ok) {
      throw new GatewayError(
        upstreamRes.status === 429 ? 429 : 502,
        `Upstream provider returned HTTP ${upstreamRes.status}`
      );
    }

    const data = (await upstreamRes.json()) as {
      choices?: Array<{ message?: { content?: string } }>;
    };
    const text = data.choices?.[0]?.message?.content?.trim() ?? "";
    if (!text) {
      throw new GatewayError(502, "Upstream provider returned empty completion.");
    }
    circuitBreaker?.recordSuccess(payload.provider);
    const actualTokens = Math.max(usage.estimatedTokens, Math.ceil((text.length + 64) / 4));
    return {
      provider: payload.provider,
      model: payload.model,
      content: text,
      estimatedTokens: actualTokens,
      estimatedSpendUsdMicros: actualTokens * 2,
    };
  } catch (err) {
    if (err instanceof GatewayError && err.statusCode >= 500) {
      circuitBreaker?.recordFailure(payload.provider, err.message);
    } else if (!(err instanceof GatewayError)) {
      circuitBreaker?.recordFailure(
        payload.provider,
        err instanceof Error ? err.message : "Upstream failure"
      );
    }
    throw err;
  }
}

export class GatewayError extends Error {
  readonly statusCode: number;

  constructor(statusCode: number, message: string) {
    super(redactSensitiveText(message));
    this.statusCode = statusCode;
  }
}
