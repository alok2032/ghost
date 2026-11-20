import "dotenv/config";

export type NodeEnvProfile = "development" | "staging" | "test" | "production";

export interface BackendConfig {
  port: number;
  nodeEnv: NodeEnvProfile;
  firebaseProjectId: string;
  requireAppCheck: boolean;
  corsAllowedOrigins: string[];
  rateLimitWindowMs: number;
  rateLimitMax: number;
  ipPreAuthRateLimitMax: number;
  aiProviderRateLimitMax: number;
  aiModelRateLimitMax: number;
  toolRateLimitMax: number;
  globalRateLimitMax: number;
  maxConcurrentRequestsPerUser: number;
  dailyTokenQuotaPerUser: number;
  dailySpendCapUsdPerUser: number;
  codeSandboxMaxPerWindow: number;
  redisRestUrl: string;
  redisRestToken: string;
  allowInMemoryRateLimit: boolean;
  sandboxExecutorUrl: string;
  sandboxExecutorToken: string;
  sandboxAttestationSecret: string;
  sandboxAllowedHosts: string[];
  geminiApiKey: string;
  openRouterApiKey: string;
  nvidiaApiKey: string;
  groqApiKey: string;
}

function parsePositiveInt(raw: string | undefined, fallback: number): number {
  const parsed = Number.parseInt(raw ?? "", 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function parsePositiveFloat(raw: string | undefined, fallback: number): number {
  const parsed = Number.parseFloat(raw ?? "");
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BackendConfig {
  const rawEnv = env.NODE_ENV;
  const nodeEnv: NodeEnvProfile =
    rawEnv === "development" || rawEnv === "staging" || rawEnv === "test"
      ? rawEnv
      : "production";

  const allowedOrigins = (env.CORS_ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((o) => o.trim())
    .filter((o) => o.length > 0 && o !== "*");

  const sandboxAllowedHosts = (env.SANDBOX_ALLOWED_HOSTS ?? "")
    .split(",")
    .map((h) => h.trim().toLowerCase())
    .filter((h) => h.length > 0 && h !== "*");

  const rawProjectId = (env.FIREBASE_PROJECT_ID ?? "").trim();
  const firebaseProjectId = rawProjectId || (nodeEnv === "test" ? "demo-ayra" : "");

  return {
    port: parsePositiveInt(env.PORT, 8443),
    nodeEnv,
    firebaseProjectId,
    requireAppCheck: (env.REQUIRE_APP_CHECK ?? "true").toLowerCase() === "true",
    corsAllowedOrigins: allowedOrigins,
    rateLimitWindowMs: parsePositiveInt(env.RATE_LIMIT_WINDOW_MS, 60_000),
    rateLimitMax: parsePositiveInt(env.RATE_LIMIT_MAX, 30),
    ipPreAuthRateLimitMax: parsePositiveInt(env.IP_PRE_AUTH_RATE_LIMIT_MAX, 240),
    aiProviderRateLimitMax: parsePositiveInt(env.AI_PROVIDER_RATE_LIMIT_MAX, 120),
    aiModelRateLimitMax: parsePositiveInt(env.AI_MODEL_RATE_LIMIT_MAX, 60),
    toolRateLimitMax: parsePositiveInt(env.TOOL_RATE_LIMIT_MAX, 20),
    globalRateLimitMax: parsePositiveInt(env.GLOBAL_RATE_LIMIT_MAX, 1000),
    maxConcurrentRequestsPerUser: parsePositiveInt(env.MAX_CONCURRENT_REQUESTS_PER_USER, 4),
    dailyTokenQuotaPerUser: parsePositiveInt(env.DAILY_TOKEN_QUOTA_PER_USER, 250_000),
    dailySpendCapUsdPerUser: parsePositiveFloat(env.DAILY_SPEND_CAP_USD_PER_USER, 5.0),
    codeSandboxMaxPerWindow: parsePositiveInt(env.CODE_SANDBOX_MAX_PER_WINDOW, 10),
    redisRestUrl: (env.REDIS_REST_URL ?? "").trim(),
    redisRestToken: (env.REDIS_REST_TOKEN ?? "").trim(),
    allowInMemoryRateLimit:
      (env.ALLOW_IN_MEMORY_RATE_LIMIT ?? (nodeEnv === "production" ? "false" : "true")).toLowerCase() ===
      "true",
    sandboxExecutorUrl: (env.SANDBOX_EXECUTOR_URL ?? "").trim(),
    sandboxExecutorToken: (env.SANDBOX_EXECUTOR_TOKEN ?? "").trim(),
    sandboxAttestationSecret: (env.SANDBOX_ATTESTATION_SECRET ?? "").trim(),
    sandboxAllowedHosts,
    geminiApiKey: (env.GEMINI_API_KEY ?? "").trim(),
    openRouterApiKey: (env.OPENROUTER_API_KEY ?? "").trim(),
    nvidiaApiKey: (env.NVIDIA_API_KEY ?? "").trim(),
    groqApiKey: (env.GROQ_API_KEY ?? "").trim(),
  };
}

export function assertProductionConfig(config: BackendConfig): void {
  if (!config.firebaseProjectId || config.firebaseProjectId === "remixed-project-id") {
    throw new Error(
      "FIREBASE_PROJECT_ID must be configured explicitly (failing closed rather than falling back to a hardcoded ID)."
    );
  }
  if (config.nodeEnv !== "test") {
    console.info(`[ayra-backend] Initialized FIREBASE_PROJECT_ID=${config.firebaseProjectId} env=${config.nodeEnv}`);
  }
  if (config.nodeEnv === "production") {
    const hasRedis = config.redisRestUrl.length > 0 && config.redisRestToken.length > 0;
    if (!hasRedis && !config.allowInMemoryRateLimit) {
      throw new Error(
        "Production requires REDIS_REST_URL + REDIS_REST_TOKEN for distributed rate limiting (or explicit ALLOW_IN_MEMORY_RATE_LIMIT=true)."
      );
    }
    if (
      config.sandboxExecutorUrl.length > 0 &&
      (config.sandboxExecutorToken.length < 16 || config.sandboxAttestationSecret.length < 16)
    ) {
      throw new Error(
        "SANDBOX_EXECUTOR_TOKEN and SANDBOX_ATTESTATION_SECRET (>=16 chars) are required when SANDBOX_EXECUTOR_URL is set."
      );
    }
  }
}
