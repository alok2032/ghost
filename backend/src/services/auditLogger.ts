const SECRET_PATTERNS: Array<[RegExp, string]> = [
  [/Bearer\s+[A-Za-z0-9\-._~+/]+=*/gi, "Bearer [REDACTED]"],
  [/AIza[0-9A-Za-z\-_]{20,}/g, "[REDACTED_GOOGLE_KEY]"],
  [/sk-(?:or-v1-|proj-)?[A-Za-z0-9\-_]{16,}/g, "[REDACTED_SK_KEY]"],
  [/nvapi-[A-Za-z0-9\-_]{16,}/g, "[REDACTED_NVAPI_KEY]"],
  [/gsk_[A-Za-z0-9]{16,}/g, "[REDACTED_GROQ_KEY]"],
  [/gh[pousr]_[A-Za-z0-9_]{16,}/g, "[REDACTED_GH_TOKEN]"],
  [/hf_[A-Za-z0-9]{16,}/g, "[REDACTED_HF_TOKEN]"],
  [/(passphrase|password|secret|token|authorization)\s*[:=]\s*["']?[^\s"',}]+["']?/gi, "$1=[REDACTED]"],
];

export function redactSensitiveText(input: string): string {
  let out = input;
  for (const [pattern, replacement] of SECRET_PATTERNS) {
    out = out.replace(pattern, replacement);
  }
  return out;
}

export type AuditSeverity = "INFO" | "WARN" | "CRITICAL";

export interface SecurityAuditEntry {
  timestamp: string;
  severity: AuditSeverity;
  category: string;
  uid?: string;
  requestId?: string;
  message: string;
}

export function logSecurityEvent(entry: Omit<SecurityAuditEntry, "timestamp">): SecurityAuditEntry {
  const sanitized: SecurityAuditEntry = {
    timestamp: new Date().toISOString(),
    severity: entry.severity,
    category: entry.category,
    uid: entry.uid,
    requestId: entry.requestId,
    message: redactSensitiveText(entry.message).slice(0, 600),
  };
  if (process.env.NODE_ENV !== "test") {
    const line = JSON.stringify(sanitized);
    if (entry.severity === "CRITICAL") {
      console.error(line);
    } else {
      console.log(line);
    }
  }
  return sanitized;
}
