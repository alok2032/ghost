import type { NextFunction, Request, Response } from "express";
import type { BackendConfig } from "../config.js";
import type { RequestWithAuth } from "./auth.js";

export interface RateLimitCounterResult {
  count: number;
  resetAtMs: number;
}

export interface RateLimitStore {
  readonly kind: "redis-rest" | "memory";
  increment(key: string, windowMs: number, delta?: number): Promise<RateLimitCounterResult>;
  get(key: string): Promise<number>;
  acquireConcurrency(key: string, ttlMs: number): Promise<number>;
  releaseConcurrency(key: string): Promise<void>;
}

interface MemoryBucket {
  value: number;
  resetAtMs: number;
}

export class MemoryRateLimitStore implements RateLimitStore {
  readonly kind = "memory" as const;
  private readonly buckets = new Map<string, MemoryBucket>();
  private readonly concurrency = new Map<string, { count: number; expiresAtMs: number }>();

  async increment(key: string, windowMs: number, delta = 1): Promise<RateLimitCounterResult> {
    const now = Date.now();
    const existing = this.buckets.get(key);
    if (!existing || existing.resetAtMs <= now) {
      const next: MemoryBucket = { value: delta, resetAtMs: now + windowMs };
      this.buckets.set(key, next);
      return { count: next.value, resetAtMs: next.resetAtMs };
    }
    existing.value += delta;
    return { count: existing.value, resetAtMs: existing.resetAtMs };
  }

  async get(key: string): Promise<number> {
    const now = Date.now();
    const existing = this.buckets.get(key);
    if (!existing || existing.resetAtMs <= now) return 0;
    return existing.value;
  }

  async acquireConcurrency(key: string, ttlMs: number): Promise<number> {
    const now = Date.now();
    const existing = this.concurrency.get(key);
    const active = existing && existing.expiresAtMs > now ? existing.count : 0;
    const next = active + 1;
    this.concurrency.set(key, { count: next, expiresAtMs: now + ttlMs });
    return next;
  }

  async releaseConcurrency(key: string): Promise<void> {
    const existing = this.concurrency.get(key);
    if (!existing) return;
    const next = Math.max(0, existing.count - 1);
    if (next === 0) {
      this.concurrency.delete(key);
    } else {
      existing.count = next;
    }
  }
}

export class RedisHttpRateLimitStore implements RateLimitStore {
  readonly kind = "redis-rest" as const;
  private readonly baseUrl: string;
  private readonly token: string;
  private readonly fetchFn: typeof fetch;

  constructor(baseUrl: string, token: string, fetchFn: typeof fetch = fetch) {
    this.baseUrl = baseUrl.replace(/\/+$/, "");
    this.token = token;
    this.fetchFn = fetchFn;
  }

  private async pipeline(commands: Array<Array<string | number>>): Promise<Array<{ result?: unknown }>> {
    const res = await this.fetchFn(`${this.baseUrl}/pipeline`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${this.token}`,
      },
      body: JSON.stringify(commands),
      signal: AbortSignal.timeout(4000),
    });
    if (!res.ok) {
      throw new Error(`Distributed rate limit store error HTTP ${res.status}`);
    }
    const json = (await res.json()) as Array<{ result?: unknown; error?: string }>;
    if (!Array.isArray(json)) {
      throw new Error("Unexpected response from distributed Redis rate limit store");
    }
    for (const item of json) {
      if (item?.error) {
        throw new Error(`Redis error: ${item.error}`);
      }
    }
    return json;
  }

  async increment(key: string, windowMs: number, delta = 1): Promise<RateLimitCounterResult> {
    const results = await this.pipeline([
      ["INCRBY", key, delta],
      ["PEXPIRE", key, windowMs, "NX"],
      ["PTTL", key],
    ]);
    const count = Number(results[0]?.result ?? delta);
    const pttlRaw = Number(results[2]?.result ?? windowMs);
    const remainingMs = pttlRaw > 0 ? pttlRaw : windowMs;
    return { count, resetAtMs: Date.now() + remainingMs };
  }

  async get(key: string): Promise<number> {
    const results = await this.pipeline([["GET", key]]);
    const raw = Number(results[0]?.result ?? 0);
    return Number.isFinite(raw) ? raw : 0;
  }

  async acquireConcurrency(key: string, ttlMs: number): Promise<number> {
    const results = await this.pipeline([
      ["INCR", key],
      ["PEXPIRE", key, ttlMs],
    ]);
    return Number(results[0]?.result ?? 1);
  }

  async releaseConcurrency(key: string): Promise<void> {
    await this.pipeline([["DECR", key]]).catch(() => undefined);
  }
}

export function resolveRateLimitStore(
  config: BackendConfig,
  overrideStore?: RateLimitStore
): RateLimitStore {
  if (overrideStore) return overrideStore;
  if (config.redisRestUrl && config.redisRestToken) {
    return new RedisHttpRateLimitStore(config.redisRestUrl, config.redisRestToken);
  }
  if (config.nodeEnv === "production" && !config.allowInMemoryRateLimit) {
    throw new Error(
      "Distributed Redis rate limit store is required in production (set REDIS_REST_URL + REDIS_REST_TOKEN or ALLOW_IN_MEMORY_RATE_LIMIT=true)."
    );
  }
  return new MemoryRateLimitStore();
}

export interface UserQuotaStatus {
  uid: string;
  storeKind: "redis-rest" | "memory";
  windowRequestsUsed: number;
  windowRequestsLimit: number;
  dailyTokensUsed: number;
  dailyTokensLimit: number;
  dailySpendUsd: number;
  dailySpendCapUsd: number;
  dayBucket: string;
}

function currentUtcDayBucket(): string {
  return new Date().toISOString().slice(0, 10);
}

export class QuotaCoordinator {
  readonly store: RateLimitStore;
  private readonly config: BackendConfig;

  constructor(config: BackendConfig, overrideStore?: RateLimitStore) {
    this.config = config;
    this.store = resolveRateLimitStore(config, overrideStore);
  }

  private principalKey(req: Request): string {
    const authReq = req as RequestWithAuth;
    return authReq.principal?.uid ? `uid:${authReq.principal.uid}` : `ip:${req.ip ?? "unknown"}`;
  }

  private setRateHeaders(res: Response, limit: number, count: number, resetAtMs: number): void {
    const remaining = Math.max(0, limit - count);
    const retryAfterSec = Math.max(1, Math.ceil((resetAtMs - Date.now()) / 1000));
    res.setHeader("X-RateLimit-Limit", String(limit));
    res.setHeader("X-RateLimit-Remaining", String(remaining));
    res.setHeader("X-RateLimit-Reset", String(Math.ceil(resetAtMs / 1000)));
    if (count > limit) {
      res.setHeader("Retry-After", String(retryAfterSec));
    }
  }

  createUserWindowLimiter() {
    return async (req: Request, res: Response, next: NextFunction) => {
      try {
        const key = `rl:user:${this.principalKey(req)}`;
        const globalKey = "rl:global:all";
        const [userRes, globalRes] = await Promise.all([
          this.store.increment(key, this.config.rateLimitWindowMs, 1),
          this.store.increment(globalKey, this.config.rateLimitWindowMs, 1),
        ]);

        const globalLimit = this.config.globalRateLimitMax ?? 1000;
        if (globalRes.count > globalLimit) {
          this.setRateHeaders(res, globalLimit, globalRes.count, globalRes.resetAtMs);
          res.status(429).json({
            errorCode: "GLOBAL_RATE_LIMIT_EXCEEDED",
            error: "Global gateway capacity reached. Retry later.",
          });
          return;
        }

        this.setRateHeaders(res, this.config.rateLimitMax, userRes.count, userRes.resetAtMs);
        if (userRes.count > this.config.rateLimitMax) {
          res.status(429).json({
            errorCode: "RATE_LIMIT_EXCEEDED",
            error: "Rate limit exceeded. Retry later.",
          });
          return;
        }
        next();
      } catch (err) {
        res.status(503).json({
          errorCode: "RATE_LIMIT_STORE_UNAVAILABLE",
          error: err instanceof Error ? err.message : "Rate limit store unavailable.",
        });
      }
    };
  }

  createToolQuotaLimiter(bucketName = "tool", maxPerWindow?: number) {
    return async (req: Request, res: Response, next: NextFunction) => {
      try {
        const limit = maxPerWindow ?? this.config.toolRateLimitMax ?? this.config.codeSandboxMaxPerWindow;
        const key = `rl:${bucketName}:${this.principalKey(req)}`;
        const result = await this.store.increment(key, this.config.rateLimitWindowMs, 1);
        this.setRateHeaders(res, limit, result.count, result.resetAtMs);
        if (result.count > limit) {
          res.status(429).json({
            errorCode: "TOOL_QUOTA_EXCEEDED",
            error: "Tool or sandbox execution quota exceeded. Retry later.",
          });
          return;
        }
        next();
      } catch (err) {
        res.status(503).json({
          errorCode: "RATE_LIMIT_STORE_UNAVAILABLE",
          error: err instanceof Error ? err.message : "Quota store unavailable.",
        });
      }
    };
  }

  createConcurrencyGuard() {
    return async (req: Request, res: Response, next: NextFunction) => {
      const limit = this.config.maxConcurrentRequestsPerUser ?? 4;
      const key = `cc:user:${this.principalKey(req)}`;
      let active = 0;
      let released = false;
      try {
        active = await this.store.acquireConcurrency(key, 60_000);
      } catch (err) {
        res.status(503).json({
          errorCode: "CONCURRENCY_STORE_UNAVAILABLE",
          error: err instanceof Error ? err.message : "Concurrency store unavailable.",
        });
        return;
      }

      const release = () => {
        if (released) return;
        released = true;
        void this.store.releaseConcurrency(key);
      };

      res.once("finish", release);
      res.once("close", release);

      if (active > limit) {
        release();
        res.setHeader("Retry-After", "2");
        res.status(429).json({
          errorCode: "CONCURRENCY_LIMIT_EXCEEDED",
          error: `Maximum ${limit} concurrent in-flight requests per user exceeded.`,
        });
        return;
      }

      next();
    };
  }

  async enforceAiQuotas(params: {
    uid: string;
    provider: string;
    model: string;
    estimatedTokens: number;
    estimatedUsdMicros: number;
  }): Promise<void> {
    const day = currentUtcDayBucket();
    const providerLimit = this.config.aiProviderRateLimitMax ?? 120;
    const modelLimit = this.config.aiModelRateLimitMax ?? 60;
    const dailyTokensLimit = this.config.dailyTokenQuotaPerUser ?? 250_000;
    const dailySpendMicrosLimit = Math.round((this.config.dailySpendCapUsdPerUser ?? 5.0) * 1_000_000);

    const [provRes, modelRes, currentTokens, currentMicros] = await Promise.all([
      this.store.increment(`rl:provider:${params.provider}:${params.uid}`, this.config.rateLimitWindowMs, 1),
      this.store.increment(`rl:model:${params.provider}:${params.model}:${params.uid}`, this.config.rateLimitWindowMs, 1),
      this.store.get(`quota:tokens:${day}:${params.uid}`),
      this.store.get(`quota:spend:${day}:${params.uid}`),
    ]);

    if (provRes.count > providerLimit) {
      const retrySec = Math.max(1, Math.ceil((provRes.resetAtMs - Date.now()) / 1000));
      throw new QuotaExceededError("PROVIDER_QUOTA_EXCEEDED", "Provider request quota exceeded.", retrySec);
    }
    if (modelRes.count > modelLimit) {
      const retrySec = Math.max(1, Math.ceil((modelRes.resetAtMs - Date.now()) / 1000));
      throw new QuotaExceededError("MODEL_QUOTA_EXCEEDED", "Model request quota exceeded.", retrySec);
    }
    if (currentTokens + params.estimatedTokens > dailyTokensLimit) {
      throw new QuotaExceededError("DAILY_TOKEN_QUOTA_EXCEEDED", "Daily token quota exceeded.", 3600);
    }
    if (currentMicros + params.estimatedUsdMicros > dailySpendMicrosLimit) {
      throw new QuotaExceededError("DAILY_SPEND_CAP_EXCEEDED", "Daily spend cap exceeded.", 3600);
    }
  }

  async recordAiUsage(params: {
    uid: string;
    tokensUsed: number;
    spendUsdMicros: number;
  }): Promise<void> {
    const day = currentUtcDayBucket();
    const dayTtlMs = 26 * 60 * 60 * 1000;
    await Promise.all([
      this.store.increment(`quota:tokens:${day}:${params.uid}`, dayTtlMs, Math.max(0, Math.round(params.tokensUsed))),
      this.store.increment(`quota:spend:${day}:${params.uid}`, dayTtlMs, Math.max(0, Math.round(params.spendUsdMicros))),
    ]);
  }

  async getQuotaStatus(uid: string): Promise<UserQuotaStatus> {
    const day = currentUtcDayBucket();
    const [windowReq, dailyTokens, dailyMicros] = await Promise.all([
      this.store.get(`rl:user:uid:${uid}`),
      this.store.get(`quota:tokens:${day}:${uid}`),
      this.store.get(`quota:spend:${day}:${uid}`),
    ]);
    return {
      uid,
      storeKind: this.store.kind,
      windowRequestsUsed: windowReq,
      windowRequestsLimit: this.config.rateLimitMax,
      dailyTokensUsed: dailyTokens,
      dailyTokensLimit: this.config.dailyTokenQuotaPerUser ?? 250_000,
      dailySpendUsd: Number((dailyMicros / 1_000_000).toFixed(6)),
      dailySpendCapUsd: this.config.dailySpendCapUsdPerUser ?? 5.0,
      dayBucket: day,
    };
  }
}

export class QuotaExceededError extends Error {
  readonly errorCode: string;
  readonly retryAfterSeconds: number;

  constructor(errorCode: string, message: string, retryAfterSeconds: number) {
    super(message);
    this.errorCode = errorCode;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

export function createZeroTrustRateLimiter(config: BackendConfig, store?: RateLimitStore) {
  return new QuotaCoordinator(config, store).createUserWindowLimiter();
}

export function createCodeSandboxQuotaLimiter(config: BackendConfig, store?: RateLimitStore) {
  return new QuotaCoordinator(config, store).createToolQuotaLimiter(
    "sandbox",
    config.codeSandboxMaxPerWindow
  );
}
