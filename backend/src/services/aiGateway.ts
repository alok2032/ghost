import type { BackendConfig } from "../config.js";
import { validateOutboundHttpsUrl } from "../security/ssrf.js";
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
}

export async function forwardSecureAiRequest(
  config: BackendConfig,
  payload: AiChatRequest,
  fetchFn: typeof fetch = fetch
): Promise<AiGatewayResponse> {
  if (payload.provider === "gemini") {
    if (!config.geminiApiKey) {
      throw new GatewayError(503, "GEMINI_API_KEY is not configured on backend.");
    }
    const target = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(payload.model)}:generateContent`;
    const check = await validateOutboundHttpsUrl(target, ALLOWED_PROVIDER_HOSTS);
    if (!check.allowed || !check.normalizedUrl) {
      throw new GatewayError(400, check.reason ?? "Blocked AI upstream URL.");
    }
    const upstreamRes = await fetchFn(check.normalizedUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": config.geminiApiKey,
      },
      body: JSON.stringify({
        contents: payload.messages
          .filter((m) => m.role !== "system")
          .map((m) => ({
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
      throw new GatewayError(502, `Upstream provider returned HTTP ${upstreamRes.status}`);
    }
    const data = (await upstreamRes.json()) as {
      candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
    };
    const text =
      data.candidates?.[0]?.content?.parts?.map((p) => p.text ?? "").join("").trim() ?? "";
    if (!text) {
      throw new GatewayError(502, "Upstream provider returned empty content.");
    }
    return { provider: payload.provider, model: payload.model, content: text };
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
  const check = await validateOutboundHttpsUrl(targetSpec.url, ALLOWED_PROVIDER_HOSTS);
  if (!check.allowed || !check.normalizedUrl) {
    throw new GatewayError(400, check.reason ?? "Blocked AI upstream URL.");
  }

  const upstreamRes = await fetchFn(check.normalizedUrl, {
    method: "POST",
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
    throw new GatewayError(502, `Upstream provider returned HTTP ${upstreamRes.status}`);
  }

  const data = (await upstreamRes.json()) as {
    choices?: Array<{ message?: { content?: string } }>;
  };
  const text = data.choices?.[0]?.message?.content?.trim() ?? "";
  if (!text) {
    throw new GatewayError(502, "Upstream provider returned empty completion.");
  }
  return { provider: payload.provider, model: payload.model, content: text };
}

export class GatewayError extends Error {
  readonly statusCode: number;

  constructor(statusCode: number, message: string) {
    super(redactSensitiveText(message));
    this.statusCode = statusCode;
  }
}
