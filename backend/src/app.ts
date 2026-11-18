import cors from "cors";
import express, { type NextFunction, type Request, type Response } from "express";
import helmet from "helmet";
import type { BackendConfig } from "./config.js";
import {
  createFirebaseTokenVerifiers,
  requireRole,
  requireZeroTrustAuth,
  revokeUserRefreshTokens,
  type RequestWithAuth,
  type TokenVerifiers,
} from "./middleware/auth.js";
import {
  createCodeSandboxQuotaLimiter,
  createZeroTrustRateLimiter,
} from "./middleware/rateLimit.js";
import { validateOutboundHttpsUrl } from "./security/ssrf.js";
import { forwardSecureAiRequest, GatewayError } from "./services/aiGateway.js";
import { logSecurityEvent, redactSensitiveText } from "./services/auditLogger.js";
import { executeIsolatedSandbox } from "./services/codeSandbox.js";
import { buildCuratedModelCatalog } from "./services/modelCatalog.js";
import {
  AiChatRequestSchema,
  CodeRunRequestSchema,
  InspectUrlRequestSchema,
  RevokeDeviceRequestSchema,
} from "./validation/schemas.js";

export interface AppDependencies {
  config: BackendConfig;
  verifiers?: TokenVerifiers;
  revokeRefreshTokensFn?: (uid: string) => Promise<void>;
  fetchFn?: typeof fetch;
  ssrfResolver?: (hostname: string) => Promise<Array<{ address: string }>>;
}

export function createZeroTrustApp(deps: AppDependencies) {
  const { config } = deps;
  const verifiers = deps.verifiers ?? createFirebaseTokenVerifiers(config.firebaseProjectId);
  const revokeTokensFn =
    deps.revokeRefreshTokensFn ??
    ((uid: string) => revokeUserRefreshTokens(config.firebaseProjectId, uid));

  const app = express();
  app.disable("x-powered-by");
  app.set("trust proxy", 1);

  app.use(
    helmet({
      contentSecurityPolicy: {
        directives: {
          defaultSrc: ["'none'"],
          frameAncestors: ["'none'"],
        },
      },
      hsts: {
        maxAge: 31536000,
        includeSubDomains: true,
        preload: true,
      },
      referrerPolicy: { policy: "no-referrer" },
    })
  );

  app.use(
    cors({
      origin: (origin, callback) => {
        if (!origin) {
          callback(null, true);
          return;
        }
        if (config.corsAllowedOrigins.includes(origin)) {
          callback(null, true);
        } else {
          callback(new GatewayError(403, "Origin not permitted by CORS policy"));
        }
      },
      methods: ["GET", "POST"],
      allowedHeaders: ["Content-Type", "Authorization", "X-Firebase-AppCheck"],
      maxAge: 600,
    })
  );

  app.use(express.json({ limit: "256kb", strict: true }));

  app.get("/health", (_req: Request, res: Response) => {
    res.status(200).json({ status: "ok", service: "ayra-zero-trust-backend" });
  });

  const authMiddleware = requireZeroTrustAuth(config, verifiers);
  const limiter = createZeroTrustRateLimiter(config);
  const sandboxQuotaLimiter = createCodeSandboxQuotaLimiter(config);

  app.get(
    "/api/v1/security/posture",
    authMiddleware,
    limiter,
    (req: RequestWithAuth, res: Response) => {
      res.status(200).json({
        uid: req.principal?.uid,
        role: req.principal?.role ?? "user",
        environment: config.nodeEnv,
        appCheckVerified: req.principal?.appCheckVerified ?? false,
        requireAppCheck: config.requireAppCheck,
        sandboxConfigured:
          config.sandboxExecutorUrl.length > 0 && config.sandboxExecutorToken.length > 0,
        configuredProviders: {
          gemini: config.geminiApiKey.length > 0,
          openrouter: config.openRouterApiKey.length > 0,
          nvidia: config.nvidiaApiKey.length > 0,
          groq: config.groqApiKey.length > 0,
        },
      });
    }
  );

  app.post(
    "/api/v1/ai/chat",
    authMiddleware,
    limiter,
    async (req: RequestWithAuth, res: Response, next: NextFunction) => {
      const parsed = AiChatRequestSchema.safeParse(req.body);
      if (!parsed.success) {
        logSecurityEvent({
          severity: "WARN",
          category: "VALIDATION",
          uid: req.principal?.uid,
          message: "Rejected invalid AI chat request schema",
        });
        res.status(400).json({
          error: "Invalid request payload",
          details: parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`),
        });
        return;
      }

      try {
        const result = await forwardSecureAiRequest(config, parsed.data, deps.fetchFn);
        logSecurityEvent({
          severity: "INFO",
          category: "AI_GATEWAY",
          uid: req.principal?.uid,
          message: `Completed proxied AI inference (${parsed.data.provider}/${parsed.data.model})`,
        });
        res.status(200).json(result);
      } catch (err) {
        next(err);
      }
    }
  );

  app.post(
    "/api/v1/network/inspect-url",
    authMiddleware,
    limiter,
    async (req: RequestWithAuth, res: Response) => {
      const parsed = InspectUrlRequestSchema.safeParse(req.body);
      if (!parsed.success) {
        res.status(400).json({ error: "Invalid URL payload" });
        return;
      }
      const verdict = await validateOutboundHttpsUrl(
        parsed.data.url,
        undefined,
        deps.ssrfResolver
      );
      if (!verdict.allowed) {
        logSecurityEvent({
          severity: "WARN",
          category: "SSRF_GUARD",
          uid: req.principal?.uid,
          message: `Blocked unsafe outbound URL inspection: ${verdict.reason ?? "rejected"}`,
        });
        res.status(400).json({ allowed: false, reason: verdict.reason });
        return;
      }
      res.status(200).json({ allowed: true, normalizedUrl: verdict.normalizedUrl });
    }
  );

  app.post(
    "/api/v1/devices/revoke",
    authMiddleware,
    limiter,
    async (req: RequestWithAuth, res: Response, next: NextFunction) => {
      const parsed = RevokeDeviceRequestSchema.safeParse(req.body);
      if (!parsed.success || !req.principal) {
        res.status(400).json({ error: "Invalid deviceId payload" });
        return;
      }
      try {
        await revokeTokensFn(req.principal.uid);
        logSecurityEvent({
          severity: "WARN",
          category: "SESSION",
          uid: req.principal.uid,
          message: `Revoked refresh tokens after device revocation (${parsed.data.deviceId})`,
        });
        res.status(200).json({ revoked: true, deviceId: parsed.data.deviceId });
      } catch (err) {
        next(err);
      }
    }
  );

  app.get(
    "/api/v1/models/catalog",
    authMiddleware,
    limiter,
    (_req: RequestWithAuth, res: Response) => {
      res.status(200).json(buildCuratedModelCatalog(config));
    }
  );

  app.post(
    "/api/v1/code/run",
    authMiddleware,
    limiter,
    sandboxQuotaLimiter,
    async (req: RequestWithAuth, res: Response) => {
      const parsed = CodeRunRequestSchema.safeParse(req.body);
      if (!parsed.success) {
        logSecurityEvent({
          severity: "WARN",
          category: "VALIDATION",
          uid: req.principal?.uid,
          message: "Rejected invalid code sandbox payload",
        });
        res.status(400).json({
          error: "Invalid code run payload",
          details: parsed.error.issues.map((i) => `${i.path.join(".")}: ${i.message}`),
        });
        return;
      }

      const outcome = await executeIsolatedSandbox(
        config,
        parsed.data,
        req.principal?.uid ?? "anonymous",
        {
          fetchFn: deps.fetchFn,
          ssrfResolver: deps.ssrfResolver,
        }
      );

      logSecurityEvent({
        severity: outcome.ok ? "INFO" : outcome.statusCode === 503 ? "WARN" : "WARN",
        category: "CODE_SANDBOX",
        uid: req.principal?.uid,
        message: `Sandbox ${parsed.data.language} status=${outcome.statusCode} ok=${outcome.ok} isolated=${outcome.isolated} (${outcome.durationMs}ms)`,
      });

      const { statusCode, ...publicOutcome } = outcome;
      res.status(statusCode).json(publicOutcome);
    }
  );

  app.get(
    "/api/v1/admin/audit-health",
    authMiddleware,
    requireRole("admin"),
    (_req: RequestWithAuth, res: Response) => {
      res.status(200).json({ status: "admin-verified" });
    }
  );

  // Sanitized error handler preventing credential or stack trace leakage
  app.use((err: unknown, req: Request, res: Response, _next: NextFunction) => {
    const status = err instanceof GatewayError ? err.statusCode : 500;
    const rawMsg = err instanceof Error ? err.message : "Internal server error";
    const safeMsg = redactSensitiveText(rawMsg);

    logSecurityEvent({
      severity: status >= 500 ? "CRITICAL" : "WARN",
      category: "HTTP_ERROR",
      message: `${req.method} ${req.path} -> HTTP ${status}: ${safeMsg}`,
    });

    if (config.nodeEnv === "production" && status >= 500) {
      res.status(status).json({ error: "Internal server error" });
      return;
    }
    res.status(status).json({ error: safeMsg });
  });

  return app;
}
