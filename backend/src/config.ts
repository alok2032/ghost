import "dotenv/config";

export interface BackendConfig {
  port: number;
  nodeEnv: "development" | "test" | "production";
  firebaseProjectId: string;
  requireAppCheck: boolean;
  corsAllowedOrigins: string[];
  rateLimitWindowMs: number;
  rateLimitMax: number;
  geminiApiKey: string;
  openRouterApiKey: string;
  nvidiaApiKey: string;
  groqApiKey: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): BackendConfig {
  const rawEnv = env.NODE_ENV;
  const nodeEnv =
    rawEnv === "development" || rawEnv === "test" ? rawEnv : "production";

  const allowedOrigins = (env.CORS_ALLOWED_ORIGINS ?? "")
    .split(",")
    .map((o) => o.trim())
    .filter((o) => o.length > 0 && o !== "*");

  const parsedPort = Number.parseInt(env.PORT ?? "8443", 10);
  const parsedWindow = Number.parseInt(env.RATE_LIMIT_WINDOW_MS ?? "60000", 10);
  const parsedMax = Number.parseInt(env.RATE_LIMIT_MAX ?? "30", 10);

  return {
    port: Number.isFinite(parsedPort) && parsedPort > 0 ? parsedPort : 8443,
    nodeEnv,
    firebaseProjectId: env.FIREBASE_PROJECT_ID ?? "gen-lang-client-0153630349",
    requireAppCheck: (env.REQUIRE_APP_CHECK ?? "true").toLowerCase() === "true",
    corsAllowedOrigins: allowedOrigins,
    rateLimitWindowMs: Number.isFinite(parsedWindow) && parsedWindow > 0 ? parsedWindow : 60_000,
    rateLimitMax: Number.isFinite(parsedMax) && parsedMax > 0 ? parsedMax : 30,
    geminiApiKey: env.GEMINI_API_KEY ?? "",
    openRouterApiKey: env.OPENROUTER_API_KEY ?? "",
    nvidiaApiKey: env.NVIDIA_API_KEY ?? "",
    groqApiKey: env.GROQ_API_KEY ?? "",
  };
}
