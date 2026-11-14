import dns from "node:dns/promises";
import net from "node:net";

const BLOCKED_HOSTNAMES = new Set([
  "localhost",
  "localhost.localdomain",
  "metadata.google.internal",
  "metadata",
  "169.254.169.254",
  "100.100.100.200",
]);

function isPrivateOrReservedIpv4(ip: string): boolean {
  const parts = ip.split(".").map((p) => Number.parseInt(p, 10));
  if (parts.length !== 4 || parts.some((p) => !Number.isInteger(p) || p < 0 || p > 255)) {
    return true;
  }
  const [a, b] = parts;
  if (a === 0 || a === 10 || a === 127) return true;
  if (a === 169 && b === 254) return true; // Link-local / Cloud metadata
  if (a === 172 && b >= 16 && b <= 31) return true; // RFC1918
  if (a === 192 && b === 168) return true; // RFC1918
  if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT
  if (a === 198 && (b === 18 || b === 19)) return true; // Benchmark
  if (a >= 224) return true; // Multicast / Reserved
  return false;
}

export function isBlockedIpAddress(rawIp: string): boolean {
  const ip = rawIp.trim().toLowerCase().replace(/^\[|\]$/g, "").split("%")[0];
  const version = net.isIP(ip);
  if (version === 4) {
    return isPrivateOrReservedIpv4(ip);
  }
  if (version === 6) {
    if (ip === "::" || ip === "::1") return true;
    if (ip.startsWith("::ffff:")) {
      const mapped = ip.slice("::ffff:".length);
      return net.isIP(mapped) === 4 ? isPrivateOrReservedIpv4(mapped) : true;
    }
    if (ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd")) return true;
    if (ip.startsWith("ff")) return true; // Multicast
    return false;
  }
  return true;
}

export interface SsrfValidationResult {
  allowed: boolean;
  reason?: string;
  normalizedUrl?: string;
}

export async function validateOutboundHttpsUrl(
  rawUrl: string,
  allowedHosts?: ReadonlySet<string>,
  resolver: (hostname: string) => Promise<Array<{ address: string }>> = (h) =>
    dns.lookup(h, { all: true })
): Promise<SsrfValidationResult> {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl.trim());
  } catch {
    return { allowed: false, reason: "Malformed URL syntax." };
  }

  if (parsed.protocol !== "https:") {
    return { allowed: false, reason: "Only HTTPS outbound requests are permitted." };
  }
  if (parsed.username || parsed.password) {
    return { allowed: false, reason: "Embedded credentials in URLs are prohibited." };
  }
  const hostname = parsed.hostname.trim().toLowerCase();
  if (!hostname || BLOCKED_HOSTNAMES.has(hostname) || hostname.endsWith(".internal") || hostname.endsWith(".local")) {
    return { allowed: false, reason: "Target hostname resolves to an internal or metadata domain." };
  }
  if (net.isIP(hostname.replace(/^\[|\]$/g, "")) !== 0) {
    if (isBlockedIpAddress(hostname)) {
      return { allowed: false, reason: "Direct private, loopback, or link-local IP addresses are blocked." };
    }
  } else {
    try {
      const records = await resolver(hostname);
      if (records.length === 0) {
        return { allowed: false, reason: "DNS lookup returned no addresses." };
      }
      for (const rec of records) {
        if (isBlockedIpAddress(rec.address)) {
          return {
            allowed: false,
            reason: "DNS resolved to a private, loopback, or cloud-metadata IP range (DNS rebinding guard).",
          };
        }
      }
    } catch {
      return { allowed: false, reason: "DNS resolution failed." };
    }
  }

  if (allowedHosts && !allowedHosts.has(hostname)) {
    return { allowed: false, reason: `Host '${hostname}' is not in the outbound allowlist.` };
  }

  return { allowed: true, normalizedUrl: parsed.toString() };
}
