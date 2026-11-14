import rateLimit from "express-rate-limit";
import type { BackendConfig } from "../config.js";
import type { RequestWithAuth } from "./auth.js";

export function createZeroTrustRateLimiter(config: BackendConfig) {
  return rateLimit({
    windowMs: config.rateLimitWindowMs,
    limit: config.rateLimitMax,
    standardHeaders: "draft-7",
    legacyHeaders: false,
    keyGenerator: (req) => {
      const authReq = req as RequestWithAuth;
      return authReq.principal?.uid ? `uid:${authReq.principal.uid}` : `ip:${req.ip ?? "unknown"}`;
    },
    handler: (_req, res) => {
      res.status(429).json({
        error: "Rate limit exceeded. Retry later.",
      });
    },
  });
}
