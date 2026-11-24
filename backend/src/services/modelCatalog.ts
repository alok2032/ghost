import type { BackendConfig } from "../config.js";

export type CatalogVerificationStatus =
  | "STATIC_DEFAULT"
  | "LIVE_VERIFIED"
  | "UNAVAILABLE"
  | "RETIRED"
  | "RATE_LIMITED";

export interface CatalogModelEntry {
  id: string;
  provider: string;
  displayName: string;
  capabilities: Array<"TEXT" | "VISION" | "TOOLS" | "IMAGE_GEN" | "AUDIO" | "CODE" | "REASONING">;
  contextTokens: number;
  costTier: "FREE_PLATFORM" | "FREE_TIER" | "LOW" | "MEDIUM" | "HIGH" | "LOCAL";
  isDefault: boolean;
  fallbackModelId?: string;
  availableViaGateway: boolean;
  verificationStatus: CatalogVerificationStatus;
  verifiedAtMs: number;
  verifiedByProvider: boolean;
  verificationMethod: "STATIC_BASELINE" | "PROVIDER_API_DISCOVERY" | "GATEWAY_PROBE";
  lastSuccessMs: number | null;
  expiresAtMs: number | null;
  failureReason: string | null;
  pricingVerified: boolean;
}

export interface ModelCatalogPayload {
  version: string;
  updatedAt: string;
  models: CatalogModelEntry[];
}

export function buildCuratedModelCatalog(
  config: BackendConfig,
  providerProbeStatus?: Record<
    string,
    { status: CatalogVerificationStatus; lastSuccessMs?: number; failureReason?: string }
  >
): ModelCatalogPayload {
  const hasKey: Record<string, boolean> = {
    gemini: config.geminiApiKey.length > 0,
    openrouter: config.openRouterApiKey.length > 0,
    nvidia_nim: config.nvidiaApiKey.length > 0,
    groq: config.groqApiKey.length > 0,
  };

  const now = Date.now();
  const baseEntries: Array<
    Omit<
      CatalogModelEntry,
      | "availableViaGateway"
      | "verificationStatus"
      | "verifiedAtMs"
      | "verifiedByProvider"
      | "verificationMethod"
      | "lastSuccessMs"
      | "expiresAtMs"
      | "failureReason"
      | "pricingVerified"
    >
  > = [
    {
      id: "gemini-3.6-flash",
      provider: "gemini",
      displayName: "Gemini 3.6 Flash",
      capabilities: ["TEXT", "VISION", "TOOLS", "AUDIO", "CODE"],
      contextTokens: 1_000_000,
      costTier: "FREE_PLATFORM",
      isDefault: true,
      fallbackModelId: "gemini-3.5-flash-lite",
    },
    {
      id: "gemini-3.5-flash-lite",
      provider: "gemini",
      displayName: "Gemini 3.5 Flash-Lite",
      capabilities: ["TEXT", "VISION", "TOOLS"],
      contextTokens: 1_000_000,
      costTier: "FREE_PLATFORM",
      isDefault: true,
      fallbackModelId: "gemini-3.6-flash",
    },
    {
      id: "gemini-3.1-pro-preview",
      provider: "gemini",
      displayName: "Gemini 3.1 Pro",
      capabilities: ["TEXT", "VISION", "TOOLS", "CODE", "REASONING"],
      contextTokens: 2_000_000,
      costTier: "MEDIUM",
      isDefault: true,
      fallbackModelId: "gemini-3.6-flash",
    },
    {
      id: "gemini-3.1-flash-image",
      provider: "gemini",
      displayName: "Gemini 3.1 Flash Image",
      capabilities: ["IMAGE_GEN", "VISION"],
      contextTokens: 128_000,
      costTier: "MEDIUM",
      isDefault: true,
      fallbackModelId: "gemini-3-pro-image",
    },
    {
      id: "qwen/qwen3-32b",
      provider: "groq",
      displayName: "Groq Qwen3 32B",
      capabilities: ["TEXT", "TOOLS", "CODE", "REASONING"],
      contextTokens: 131_072,
      costTier: "FREE_TIER",
      isDefault: true,
      fallbackModelId: "openai/gpt-oss-120b",
    },
    {
      id: "openai/gpt-oss-120b",
      provider: "groq",
      displayName: "Groq GPT OSS 120B",
      capabilities: ["TEXT", "TOOLS", "CODE", "REASONING"],
      contextTokens: 131_072,
      costTier: "FREE_TIER",
      isDefault: true,
      fallbackModelId: "qwen/qwen3-32b",
    },
    {
      id: "openrouter/free",
      provider: "openrouter",
      displayName: "OpenRouter Free Router",
      capabilities: ["TEXT", "TOOLS", "CODE"],
      contextTokens: 128_000,
      costTier: "FREE_TIER",
      isDefault: true,
      fallbackModelId: "qwen/qwen3.8-27b:free",
    },
    {
      id: "qwen/qwen3.8-27b:free",
      provider: "openrouter",
      displayName: "Qwen 3.8 27B (Free)",
      capabilities: ["TEXT", "CODE", "REASONING"],
      contextTokens: 128_000,
      costTier: "FREE_TIER",
      isDefault: false,
      fallbackModelId: "openrouter/auto",
    },
    {
      id: "openrouter/auto",
      provider: "openrouter",
      displayName: "OpenRouter Auto",
      capabilities: ["TEXT", "VISION", "TOOLS", "CODE"],
      contextTokens: 128_000,
      costTier: "LOW",
      isDefault: true,
      fallbackModelId: "openrouter/free",
    },
    {
      id: "meta/llama-3.3-70b-instruct",
      provider: "nvidia_nim",
      displayName: "NVIDIA NIM Llama 3.3 70B",
      capabilities: ["TEXT", "CODE"],
      contextTokens: 128_000,
      costTier: "FREE_TIER",
      isDefault: true,
    },
  ];

  return {
    version: "2026.11.20",
    updatedAt: new Date(now).toISOString(),
    models: baseEntries.map((entry) => {
      const configured = Boolean(hasKey[entry.provider]);
      const probe = providerProbeStatus?.[entry.provider];
      const verificationStatus: CatalogVerificationStatus = !configured
        ? "UNAVAILABLE"
        : probe?.status ?? "STATIC_DEFAULT";
      return {
        ...entry,
        availableViaGateway:
          configured && verificationStatus !== "RETIRED" && verificationStatus !== "UNAVAILABLE",
        verificationStatus,
        verifiedAtMs: now,
        verifiedByProvider: verificationStatus === "LIVE_VERIFIED",
        verificationMethod: verificationStatus === "LIVE_VERIFIED" ? "GATEWAY_PROBE" : "STATIC_BASELINE",
        lastSuccessMs: probe?.lastSuccessMs ?? null,
        expiresAtMs: verificationStatus === "LIVE_VERIFIED" ? now + 3_600_000 : null,
        failureReason: !configured
          ? `Provider credential missing for '${entry.provider}'.`
          : probe?.failureReason ?? null,
        pricingVerified: false,
      };
    }),
  };
}
