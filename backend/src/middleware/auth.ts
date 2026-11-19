import type { NextFunction, Request, Response } from "express";
import admin from "firebase-admin";
import type { BackendConfig } from "../config.js";
import { logSecurityEvent } from "../services/auditLogger.js";

export interface AuthenticatedPrincipal {
  uid: string;
  email?: string;
  role: "user" | "admin";
  appCheckVerified: boolean;
}

export interface RequestWithAuth extends Request {
  principal?: AuthenticatedPrincipal;
}

export interface TokenVerifiers {
  verifyIdToken: (idToken: string) => Promise<{ uid: string; email?: string; role?: unknown }>;
  verifyAppCheckToken: (appCheckToken: string, options?: { consume?: boolean }) => Promise<void>;
}

function ensureFirebaseAdminInitialized(projectId: string): void {
  if (admin.apps.length === 0) {
    admin.initializeApp({ projectId });
  }
}

export function createFirebaseTokenVerifiers(projectId: string): TokenVerifiers {
  return {
    verifyIdToken: async (idToken: string) => {
      ensureFirebaseAdminInitialized(projectId);
      // checkRevoked = true rejects tokens revoked during sign-out or device revocation
      const decoded = await admin.auth().verifyIdToken(idToken, true);
      return {
        uid: decoded.uid,
        email: decoded.email,
        role: decoded.role,
      };
    },
    verifyAppCheckToken: async (appCheckToken: string, options?: { consume?: boolean }) => {
      ensureFirebaseAdminInitialized(projectId);
      await admin.appCheck().verifyToken(appCheckToken, options?.consume ? { consume: true } : undefined);
    },
  };
}

export async function revokeUserRefreshTokens(projectId: string, uid: string): Promise<void> {
  ensureFirebaseAdminInitialized(projectId);
  await admin.auth().revokeRefreshTokens(uid);
}

export function requireZeroTrustAuth(
  config: BackendConfig,
  verifiers: TokenVerifiers,
  options?: { consumeAppCheck?: boolean }
) {
  return async (req: RequestWithAuth, res: Response, next: NextFunction): Promise<void> => {
    const authHeader = req.header("authorization") ?? "";
    if (!authHeader.startsWith("Bearer ")) {
      logSecurityEvent({
        severity: "WARN",
        category: "AUTH",
        message: `Rejected request missing Bearer ID token (${req.method} ${req.path})`,
      });
      res.status(401).json({ error: "Authentication required" });
      return;
    }

    const idToken = authHeader.slice("Bearer ".length).trim();
    if (idToken.length < 16) {
      res.status(401).json({ error: "Invalid Bearer token" });
      return;
    }

    let decoded: { uid: string; email?: string; role?: unknown };
    try {
      decoded = await verifiers.verifyIdToken(idToken);
    } catch {
      logSecurityEvent({
        severity: "WARN",
        category: "AUTH",
        message: `Rejected invalid or revoked Firebase ID token on ${req.path}`,
      });
      res.status(401).json({ error: "Invalid or expired authentication token" });
      return;
    }

    const appCheckHeader = req.header("x-firebase-appcheck")?.trim() ?? "";
    let appCheckVerified = false;
    if (config.requireAppCheck || appCheckHeader.length > 0) {
      if (!appCheckHeader) {
        logSecurityEvent({
          severity: "WARN",
          category: "APP_CHECK",
          uid: decoded.uid,
          message: `Blocked request missing Firebase App Check token on ${req.path}`,
        });
        res.status(401).json({ error: "App Check attestation token required" });
        return;
      }
      try {
        await verifiers.verifyAppCheckToken(appCheckHeader, {
          consume: Boolean(options?.consumeAppCheck),
        });
        appCheckVerified = true;
      } catch {
        logSecurityEvent({
          severity: "CRITICAL",
          category: "APP_CHECK",
          uid: decoded.uid,
          message: `Invalid Firebase App Check token rejected on ${req.path}`,
        });
        res.status(401).json({ error: "Invalid App Check attestation token" });
        return;
      }
    }

    const role = decoded.role === "admin" ? "admin" : "user";
    req.principal = {
      uid: decoded.uid,
      email: decoded.email,
      role,
      appCheckVerified,
    };
    next();
  };
}

export function requireRole(requiredRole: "admin") {
  return (req: RequestWithAuth, res: Response, next: NextFunction): void => {
    if (!req.principal || req.principal.role !== requiredRole) {
      logSecurityEvent({
        severity: "WARN",
        category: "RBAC",
        uid: req.principal?.uid,
        message: `Denied access to privileged route ${req.path}`,
      });
      res.status(403).json({ error: "Insufficient permissions" });
      return;
    }
    next();
  };
}
