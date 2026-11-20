import type { NextFunction, Request, Response } from "express";
import type { BackendConfig } from "../config.js";
import type { RequestWithAuth } from "./auth.js";

export interface RateLimitCounterResult {
  count: number;
  resetAtMs: number;
}

export interface QuotaReservationResult {
  allowed: boolean;
  totalAfter: number;
}

export interface RateLimitStore {
  readonly kind: "redis-rest" | "memory";
  increment(key: string, windowMs: number, delta?: number): Promise<RateLimitCounterResult>;
  get(key: string): Promise<number>;
  acquireConcurrency(key: string, ttlMs: number): Promise<number>;
  releaseConcurrency(key: string): Promise<void>;
  reserveQuota(key: string, windowMs: number, delta: number, limit: number): Promise<QuotaReservationResult>;
  settleQuota(key: string, windowMs: number, delta: number): Promise<number>;
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
      const next: MemoryBucket = { value: Math.max(0, delta), resetAtMs: now + windowMs };
      this.buckets.set(key, next);
      return { count: next.value, resetAtMs: next.resetAtMs };
    }
    existing.value = Math.max(0, existing.value + delta);
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

  async reserveQuota(
    key: string,
    windowMs: number,
    delta: number,
    limit: number
  ): Promise<QuotaReservationResult> {
    const safeDelta = Math.max(0, Math.round(delta));
    const now = Date.now();
    const existing = this.buckets.get(key);
    const current = existing && existing.resetAtMs > now ? existing.value : 0;
    if (current + safeDelta > limit) {
      return { allowed: false, totalAfter: current };
    }
    const resetAtMs = existing && existing.resetAtMs > now ? existing.resetAtMs : now + windowMs;
    const nextTotal = current + safeDelta;
    this.buckets.set(key, { value: nextTotal, resetAtMs });
    return { allowed: true, totalAfter: nextTotal };
  }

  async settleQuota(key: string, windowMs: number, delta: number): Promise<number> {
    const safeDelta = Math.round(delta);
    const now = Date.now();
    const existing = this.buckets.get(key);
    const current = existing && existing.resetAtMs > now ? existing.value : 0;
    const nextTotal = Math.max(0, current + safeDelta);
    const resetAtMs = existing && existing.resetAtMs > now ? existing.resetAtMs : now + windowMs;
    this.buckets.set(key, { value: nextTotal, resetAtMs });
    return nextTotal;
  }
}

const LUA_RELEASE_CONCURRENCY = [
  "local curr = tonumber(redis.call('GET', KEYS[1]) or '0')",
  "if curr <= 1 then redis.call('DEL', KEYS[1]); return 0 end",
  "return redis.call('DECR', KEYS[1])",
].join(" ");

const LUA_RESERVE_QUOTA = [
  "local curr = tonumber(redis.call('GET', KEYS[1]) or '0')",
  "local delta = tonumber(ARGV[1])",
  "local ttl = tonumber(ARGV[2])",
  "local limit = tonumber(ARGV[3])",
  "if (curr + delta) > limit then return {0, curr} end",
  "local next = redis.call('INCRBY', KEYS[1], delta)",
  "redis.call('PEXPIRE', KEYS[1], ttl, 'NX')",
  "return {1, next}",
].join(" ");

const LUA_SETTLE_QUOTA = [
  "local curr = tonumber(redis.call('GET', KEYS[1]) or '0')",
  "local delta = tonumber(ARGV[1])",
  "local ttl = tonumber(ARGV[2])",
  "local next = curr + delta",
  "if next < 0 then next = 0 end",
  "redis.call('SET', KEYS[1], next, 'PX', ttl)",
  "return next",
].join(" ");

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
    return Number.isFinite(raw) ? Math.max(0, raw) : 0;
  }

  async acquireConcurrency(key: string, ttlMs: number): Promise<number> {
    const results = await this.pipeline([
      ["INCR", key],
      ["PEXPIRE", key, ttlMs],
    ]);
    return Number(results[0]?.result ?? 1);
  }

  async releaseConcurrency(key: string): Promise<void> {
    // Requires Redis >= 7 Lua EVAL support; clamps at 0 / deletes expired keys atomically
    await this.pipeline([["EVAL", LUA_RELEASE_CONCURRENCY, 1, key]]).catch(() => undefined);
  }

  async reserveQuota(
    key: string,
    windowMs: number,
    delta: number,
    limit: number
  ): Promise<QuotaReservationResult> {
    const safeDelta = Math.max(0, Math.round(delta));
    const results = await this.pipeline([
      ["EVAL", LUA_RESERVE_QUOTA, 1, key, safeDelta, windowMs, limit],
    ]);
    const tuple = results[0]?.result;
    if (Array.isArray(tuple)) {
      return { allowed: Number(tuple[0]) === 1, totalAfter: Number(tuple[1] ?? 0) };
    }
    return { allowed: false, totalAfter: 0 };
  }

  async settleQuota(key: string, windowMs: number, delta: number): Promise<number> {
    const safeDelta = Math.round(delta);
    if (safeDelta === 0) {
      return this.get(key);
    }
    const results = await this.pipeline([
      ["EVAL", LUA_SETTLE_QUOTA, 1, key, safeDelta, windowMs],
    ]);
    const val = Number(results[0]?.result ?? 0);
    return Number.isFinite(val) ? Math.max(0, val) : 0;
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

export interface AiQuotaReservation {
  uid: string;
  dayBucket: string;
  reservedTokens: number;
  reservedSpendUsdMicros: number;
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

  /**
   * Pre-authentication IP limiter applied before Firebase ID token / App Check verification
   * to prevent unauthenticated traffic floods from exhausting verification resources.
   */
  createIpPreAuthLimiter(maxPerWindow?: number) {
    return async (req: Request, res: Response, next: NextFunction) => {
      try {
        const limit =
          maxPerWindow ??
          this.config.ipPreAuthRateLimitMax ??
          Math.max((this.config.rateLimitMax ?? 30) * 8, 240);
        const key = `rl:preauth:ip:${req.ip ?? "unknown"}`;
        const ipRes = await this.store.increment(key, this.config.rateLimitWindowMs, 1);
        if (ipRes.count > limit) {
          this.setRateHeaders(res, limit, ipRes.count, ipRes.resetAtMs);
          res.status(429).json({
            errorCode: "IP_RATE_LIMIT_EXCEEDED",
            error: "Too many requests from this IP. Retry later.",
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

  /**
   * Checks per-user window BEFORE touching global counter so one abusive user sending 5x limit
   * gets 429 locally and never exhausts global capacity for other users.
   */
  createUserWindowLimiter() {
    return async (req: Request, res: Response, next: NextFunction) => {
      try {
        const key = `rl:user:${this.principalKey(req)}`;
        const userRes = await this.store.increment(key, this.config.rateLimitWindowMs, 1);
        this.setRateHeaders(res, this.config.rateLimitMax, userRes.count, userRes.resetAtMs);
        if (userRes.count > this.config.rateLimitMax) {
          res.status(429).json({
            errorCode: "RATE_LIMIT_EXCEEDED",
            error: "Rate limit exceeded. Retry later.",
          });
          return;
        }

        const globalKey = "rl:global:all";
        const globalRes = await this.store.increment(globalKey, this.config.rateLimitWindowMs, 1);
        const globalLimit = this.config.globalRateLimitMax ?? 1000;
        if (globalRes.count > globalLimit) {
          this.setRateHeaders(res, globalLimit, globalRes.count, globalRes.resetAtMs);
          res.status(429).json({
            errorCode: "GLOBAL_RATE_LIMIT_EXCEEDED",
            error: "Global gateway capacity reached. Retry later.",
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

  async reserveAiQuotas(params: {
    uid: string;
    provider: string;
    model: string;
    reservedTokens: number;
    reservedUsdMicros: number;
  }): Promise<AiQuotaReservation> {
    const day = currentUtcDayBucket();
    const dayTtlMs = 26 * 60 * 60 * 1000;
    const providerLimit = this.config.aiProviderRateLimitMax ?? 120;
    const modelLimit = this.config.aiModelRateLimitMax ?? 60;
    const dailyTokensLimit = this.config.dailyTokenQuotaPerUser ?? 250_000;
    const dailySpendMicrosLimit = Math.round((this.config.dailySpendCapUsdPerUser ?? 5.0) * 1_000_000);

    const [provRes, modelRes] = await Promise.all([
      this.store.increment(`rl:provider:${params.provider}:${params.uid}`, this.config.rateLimitWindowMs, 1),
      this.store.increment(`rl:model:${params.provider}:${params.model}:${params.uid}`, this.config.rateLimitWindowMs, 1),
    ]);

    if (provRes.count > providerLimit) {
      const retrySec = Math.max(1, Math.ceil((provRes.resetAtMs - Date.now()) / 1000));
      throw new QuotaExceededError("PROVIDER_QUOTA_EXCEEDED", "Provider request quota exceeded.", retrySec);
    }
    if (modelRes.count > modelLimit) {
      const retrySec = Math.max(1, Math.ceil((modelRes.resetAtMs - Date.now()) / 1000));
      throw new QuotaExceededError("MODEL_QUOTA_EXCEEDED", "Model request quota exceeded.", retrySec);
    }

    const safeTokens = Math.max(0, Math.round(params.reservedTokens));
    const safeMicros = Math.max(0, Math.round(params.reservedUsdMicros));

    const tokenRes = await this.store.reserveQuota(
      `quota:tokens:${day}:${params.uid}`,
      dayTtlMs,
      safeTokens,
      dailyTokensLimit
    );
    if (!tokenRes.allowed) {
      throw new QuotaExceededError("DAILY_TOKEN_QUOTA_EXCEEDED", "Daily token quota exceeded.", 3600);
    }

    const spendRes = await this.store.reserveQuota(
      `quota:spend:${day}:${params.uid}`,
      dayTtlMs,
      safeMicros,
      dailySpendMicrosLimit
    );
    if (!spendRes.allowed) {
      await this.store.settleQuota(`quota:tokens:${day}:${params.uid}`, dayTtlMs, -safeTokens).catch(() => undefined);
      throw new QuotaExceededError("DAILY_SPEND_CAP_EXCEEDED", "Daily spend cap exceeded.", 3600);
    }

    return {
      uid: params.uid,
      dayBucket: day,
      reservedTokens: safeTokens,
      reservedSpendUsdMicros: safeMicros,
    };
  }

  async settleAiQuotas(
    reservation: AiQuotaReservation,
    actualTokensUsed: number,
    actualSpendUsdMicros: number
  ): Promise<void> {
    const dayTtlMs = 26 * 60 * 60 * 1000;
    const tokenDelta = Math.max(0, Math.round(actualTokensUsed)) - reservation.reservedTokens;
    const spendDelta = Math.max(0, Math.round(actualSpendUsdMicros)) - reservation.reservedSpendUsdMicros;
    await Promise.all([
      this.store.settleQuota(`quota:tokens:${reservation.dayBucket}:${reservation.uid}`, dayTtlMs, tokenDelta),
      this.store.settleQuota(`quota:spend:${reservation.dayBucket}:${reservation.uid}`, dayTtlMs, spendDelta),
    ]);
  }

  async refundAiReservation(reservation: AiQuotaReservation): Promise<void> {
    const dayTtlMs = 26 * 60 * 60 * 1000;
    await Promise.all([
      this.store
        .settleQuota(`quota:tokens:${reservation.dayBucket}:${reservation.uid}`, dayTtlMs, -reservation.reservedTokens)
        .catch(() => undefined),
      this.store
        .settleQuota(
          `quota:spend:${reservation.dayBucket}:${reservation.uid}`,
          dayTtlMs,
          -reservation.reservedSpendUsdMicros
        )
        .catch(() => undefined),
    ]);
  }

  async enforceAiQuotas(params: {
    uid: string;
    provider: string;
    model: string;
    estimatedTokens: number;
    estimatedUsdMicros: number;
  }): Promise<AiQuotaReservation> {
    return this.reserveAiQuotas({
      uid: params.uid,
      provider: params.provider,
      model: params.model,
      reservedTokens: params.estimatedTokens,
      reservedUsdMicros: params.estimatedUsdMicros,
    });
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
