import type { BackendConfig } from "../config.js";

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
}

export interface ModelCatalogPayload {
  version: string;
  updatedAt: string;
  models: CatalogModelEntry[];
}

export function buildCuratedModelCatalog(config: BackendConfig): ModelCatalogPayload {
  const hasGemini = config.geminiApiKey.length > 0;
  const hasOpenRouter = config.openRouterApiKey.length > 0;
  const hasNvidia = config.nvidiaApiKey.length > 0;
  const hasGroq = config.groqApiKey.length > 0;

  return {
    version: "2026.11.17",
    updatedAt: new Date().toISOString(),
    models: [
      {
        id: "gemini-3.8-flash",
        provider: "gemini",
        displayName: "Gemini 3.8 Flash",
        capabilities: ["TEXT", "VISION", "TOOLS", "AUDIO", "CODE"],
        contextTokens: 1_000_000,
        costTier: "FREE_PLATFORM",
        isDefault: true,
        fallbackModelId: "gemini-3.5-flash-lite",
        availableViaGateway: hasGemini,
      },
      {
        id: "gemini-3.5-flash-lite",
        provider: "gemini",
        displayName: "Gemini 3.5 Flash-Lite",
        capabilities: ["TEXT", "VISION", "TOOLS"],
        contextTokens: 1_000_000,
        costTier: "FREE_PLATFORM",
        isDefault: true,
        fallbackModelId: "gemini-3.8-flash",
        availableViaGateway: hasGemini,
      },
      {
        id: "gemini-4-argon",
        provider: "gemini",
        displayName: "Gemini 4 Argon",
        capabilities: ["TEXT", "VISION", "TOOLS", "CODE", "REASONING"],
        contextTokens: 2_000_000,
        costTier: "MEDIUM",
        isDefault: true,
        fallbackModelId: "gemini-3.8-flash",
        availableViaGateway: hasGemini,
      },
      {
        id: "gemini-nano-banana-2.1",
        provider: "gemini",
        displayName: "Gemini Nano Banana 2.1",
        capabilities: ["IMAGE_GEN", "VISION"],
        contextTokens: 128_000,
        costTier: "MEDIUM",
        isDefault: true,
        fallbackModelId: "gemini-3-pro-image",
        availableViaGateway: hasGemini,
      },
      {
        id: "llama-3.3-70b-versatile",
        provider: "groq",
        displayName: "Groq Llama 3.3 70B Versatile",
        capabilities: ["TEXT", "TOOLS", "CODE"],
        contextTokens: 128_000,
        costTier: "FREE_TIER",
        isDefault: true,
        fallbackModelId: "qwen/qwen3-32b",
        availableViaGateway: hasGroq,
      },
      {
        id: "qwen/qwen3-32b",
        provider: "groq",
        displayName: "Groq Qwen3 32B",
        capabilities: ["TEXT", "TOOLS", "CODE", "REASONING"],
        contextTokens: 128_000,
        costTier: "FREE_TIER",
        isDefault: true,
        fallbackModelId: "llama-3.3-70b-versatile",
        availableViaGateway: hasGroq,
      },
      {
        id: "openrouter/auto",
        provider: "openrouter",
        displayName: "OpenRouter Auto",
        capabilities: ["TEXT", "VISION", "TOOLS", "CODE"],
        contextTokens: 128_000,
        costTier: "LOW",
        isDefault: true,
        fallbackModelId: "qwen/qwen-2.5-72b-instruct",
        availableViaGateway: hasOpenRouter,
      },
      {
        id: "qwen/qwen-2.5-72b-instruct",
        provider: "openrouter",
        displayName: "Qwen 2.5 72B Instruct",
        capabilities: ["TEXT", "CODE", "REASONING"],
        contextTokens: 128_000,
        costTier: "LOW",
        isDefault: true,
        fallbackModelId: "openrouter/auto",
        availableViaGateway: hasOpenRouter,
      },
      {
        id: "meta/llama-3.3-70b-instruct",
        provider: "nvidia_nim",
        displayName: "NVIDIA NIM Llama 3.3 70B",
        capabilities: ["TEXT", "CODE"],
        contextTokens: 128_000,
        costTier: "FREE_TIER",
        isDefault: true,
        availableViaGateway: hasNvidia,
      },
    ],
  };
}
