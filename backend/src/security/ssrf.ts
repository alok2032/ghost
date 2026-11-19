import dns from "node:dns/promises";
import https from "node:https";
import net from "node:net";

const BLOCKED_HOSTNAMES = new Set([
  "localhost",
  "localhost.localdomain",
  "metadata.google.internal",
  "metadata",
  "metadata.goog",
  "169.254.169.254",
  "100.100.100.200",
  "fd00:ec2::254",
]);

function isPrivateOrReservedIpv4Bytes(b0: number, b1: number, b2: number, b3: number): boolean {
  if (b0 === 0 || b0 === 10 || b0 === 127) return true; // Current network, RFC1918, Loopback
  if (b0 === 169 && b1 === 254) return true; // Link-local / Cloud metadata
  if (b0 === 172 && b1 >= 16 && b1 <= 31) return true; // RFC1918
  if (b0 === 192 && b1 === 168) return true; // RFC1918
  if (b0 === 100 && b1 >= 64 && b1 <= 127) return true; // CGNAT
  if (b0 === 192 && b1 === 0 && b2 === 0) return true; // IETF protocol assignments
  if (b0 === 192 && b1 === 0 && b2 === 2) return true; // TEST-NET-1
  if (b0 === 198 && (b1 === 18 || b1 === 19)) return true; // Benchmarking
  if (b0 === 198 && b1 === 51 && b2 === 100) return true; // TEST-NET-2
  if (b0 === 203 && b1 === 0 && b2 === 113) return true; // TEST-NET-3
  if (b0 >= 224) return true; // Multicast & reserved (includes 255.255.255.255)
  void b3;
  return false;
}

function isPrivateOrReservedIpv4(ip: string): boolean {
  const parts = ip.split(".").map((p) => Number.parseInt(p, 10));
  if (parts.length !== 4 || parts.some((p) => !Number.isInteger(p) || p < 0 || p > 255)) {
    return true;
  }
  return isPrivateOrReservedIpv4Bytes(parts[0]!, parts[1]!, parts[2]!, parts[3]!);
}

/**
 * Expands any valid IPv6 literal (including :: compression and embedded IPv4 tail)
 * into exact 16-byte representation, failing closed (null) on malformed input.
 */
export function parseIpv6Bytes(rawIp: string): Uint8Array | null {
  const clean = rawIp.trim().toLowerCase().replace(/^\[|\]$/g, "").split("%")[0] ?? "";
  if (!clean || net.isIP(clean) !== 6) return null;

  let working = clean;
  // Convert trailing IPv4 notation (e.g. ::ffff:192.168.1.1) into two hex hextets
  const lastColon = working.lastIndexOf(":");
  if (lastColon !== -1 && working.slice(lastColon + 1).includes(".")) {
    const ipv4Part = working.slice(lastColon + 1);
    const octets = ipv4Part.split(".").map((s) => Number.parseInt(s, 10));
    if (octets.length !== 4 || octets.some((o) => !Number.isInteger(o) || o < 0 || o > 255)) {
      return null;
    }
    const highHex = ((octets[0]! << 8) | octets[1]!).toString(16);
    const lowHex = ((octets[2]! << 8) | octets[3]!).toString(16);
    working = `${working.slice(0, lastColon)}:${highHex}:${lowHex}`;
  }

  const dblIdx = working.indexOf("::");
  let hextets: string[];
  if (dblIdx !== -1) {
    if (working.indexOf("::", dblIdx + 2) !== -1) return null;
    const left = working.slice(0, dblIdx).split(":").filter(Boolean);
    const right = working.slice(dblIdx + 2).split(":").filter(Boolean);
    const missing = 8 - (left.length + right.length);
    if (missing < 1) return null;
    hextets = [...left, ...Array<string>(missing).fill("0"), ...right];
  } else {
    hextets = working.split(":");
  }

  if (hextets.length !== 8) return null;
  const out = new Uint8Array(16);
  for (let i = 0; i < 8; i++) {
    const val = Number.parseInt(hextets[i]!, 16);
    if (!Number.isInteger(val) || val < 0 || val > 0xffff) return null;
    out[i * 2] = (val >> 8) & 0xff;
    out[i * 2 + 1] = val & 0xff;
  }
  return out;
}

function isBlockedIpv6Bytes(b: Uint8Array): boolean {
  // All-zero (::/128) or loopback (::1/128)
  let nonZeroFirst15 = false;
  for (let i = 0; i < 15; i++) {
    if (b[i] !== 0) {
      nonZeroFirst15 = true;
      break;
    }
  }
  if (!nonZeroFirst15 && (b[15] === 0 || b[15] === 1)) return true;

  // IPv4-compatible IPv6 ::/96
  if (!nonZeroFirst15) return true;
  let prefix12Zero = true;
  for (let i = 0; i < 12; i++) {
    if (b[i] !== 0) {
      prefix12Zero = false;
      break;
    }
  }
  if (prefix12Zero) {
    return isPrivateOrReservedIpv4Bytes(b[12]!, b[13]!, b[14]!, b[15]!);
  }

  // IPv4-mapped IPv6 ::ffff:0:0/96 (bytes 0..9 = 0, 10..11 = 0xff)
  let zero10 = true;
  for (let i = 0; i < 10; i++) {
    if (b[i] !== 0) {
      zero10 = false;
      break;
    }
  }
  if (zero10 && b[10] === 0xff && b[11] === 0xff) {
    return isPrivateOrReservedIpv4Bytes(b[12]!, b[13]!, b[14]!, b[15]!);
  }

  // IPv4-translated / NAT64 well-known 64:ff9b::/96 and local-use 64:ff9b:1::/48
  if (b[0] === 0x00 && b[1] === 0x64 && b[2] === 0xff && b[3] === 0x9b) {
    if (b[4] === 0x00 && b[5] === 0x01) {
      return true; // Local-use NAT64 64:ff9b:1::/48
    }
    return isPrivateOrReservedIpv4Bytes(b[12]!, b[13]!, b[14]!, b[15]!);
  }

  // 6to4 (2002::/16) encapsulates IPv4 in bytes 2..5
  if (b[0] === 0x20 && b[1] === 0x02) {
    if (isPrivateOrReservedIpv4Bytes(b[2]!, b[3]!, b[4]!, b[5]!)) return true;
  }

  // Teredo tunneling (2001::/32)
  if (b[0] === 0x20 && b[1] === 0x01 && b[2] === 0x00 && b[3] === 0x00) {
    return true;
  }

  // Documentation (2001:db8::/32)
  if (b[0] === 0x20 && b[1] === 0x01 && b[2] === 0x0d && b[3] === 0xb8) {
    return true;
  }

  // Benchmarking (2001:2::/48)
  if (b[0] === 0x20 && b[1] === 0x01 && b[2] === 0x00 && b[3] === 0x02 && b[4] === 0 && b[5] === 0) {
    return true;
  }

  // Discard-only 100::/64
  if (b[0] === 0x01 && b[1] === 0x00 && b[2] === 0 && b[3] === 0 && b[4] === 0 && b[5] === 0 && b[6] === 0 && b[7] === 0) {
    return true;
  }

  // ULA fc00::/7
  if ((b[0]! & 0xfe) === 0xfc) return true;

  // Link-local fe80::/10
  if (b[0] === 0xfe && (b[1]! & 0xc0) === 0x80) return true;

  // Deprecated site-local fec0::/10
  if (b[0] === 0xfe && (b[1]! & 0xc0) === 0xc0) return true;

  // Multicast ff00::/8
  if (b[0] === 0xff) return true;

  return false;
}

export function isBlockedIpAddress(rawIp: string): boolean {
  const ip = rawIp.trim().toLowerCase().replace(/^\[|\]$/g, "").split("%")[0] ?? "";
  const version = net.isIP(ip);
  if (version === 4) {
    return isPrivateOrReservedIpv4(ip);
  }
  if (version === 6) {
    const bytes = parseIpv6Bytes(ip);
    if (!bytes) return true;
    return isBlockedIpv6Bytes(bytes);
  }
  return true;
}

export interface SsrfValidationResult {
  allowed: boolean;
  reason?: string;
  normalizedUrl?: string;
  resolvedAddresses?: string[];
}

export async function validateOutboundHttpsUrl(
  rawUrl: string,
  allowedHosts?: ReadonlySet<string>,
  resolver: (hostname: string) => Promise<Array<{ address: string }>> = (h) =>
    dns.lookup(h, { all: true })
): Promise<SsrfValidationResult> {
  const trimmed = rawUrl.trim();
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return { allowed: false, reason: "Malformed URL syntax." };
  }

  if (parsed.protocol !== "https:") {
    return { allowed: false, reason: "Only HTTPS outbound requests are permitted." };
  }
  if (parsed.username || parsed.password || trimmed.includes("@")) {
    return { allowed: false, reason: "Embedded credentials in URLs are prohibited." };
  }
  if (parsed.port !== "" && parsed.port !== "443") {
    return { allowed: false, reason: "Only standard HTTPS port 443 is permitted." };
  }

  const hostname = parsed.hostname.trim().toLowerCase().replace(/\.+$/, "");
  if (
    !hostname ||
    BLOCKED_HOSTNAMES.has(hostname) ||
    hostname.endsWith(".internal") ||
    hostname.endsWith(".local") ||
    hostname.endsWith(".localhost") ||
    hostname.endsWith(".arpa")
  ) {
    return { allowed: false, reason: "Target hostname resolves to an internal or metadata domain." };
  }

  if (allowedHosts && allowedHosts.size > 0 && !allowedHosts.has(hostname)) {
    return { allowed: false, reason: `Host '${hostname}' is not in the outbound allowlist.` };
  }

  const resolvedAddresses: string[] = [];
  const ipHost = hostname.replace(/^\[|\]$/g, "");
  if (net.isIP(ipHost) !== 0) {
    if (isBlockedIpAddress(ipHost)) {
      return { allowed: false, reason: "Direct private, loopback, or link-local IP addresses are blocked." };
    }
    resolvedAddresses.push(ipHost);
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
        resolvedAddresses.push(rec.address);
      }
    } catch {
      return { allowed: false, reason: "DNS resolution failed." };
    }
  }

  return {
    allowed: true,
    normalizedUrl: parsed.toString(),
    resolvedAddresses,
  };
}

/**
 * Creates a fetch-compatible function bound strictly to previously validated IP address(es),
 * preserving TLS SNI (`servername`) for the validated hostname and rejecting any HTTP redirect.
 * Prevents DNS rebinding TOCTOU between validation and TCP socket connect.
 */
export function createPinnedFetch(pinnedAddresses: readonly string[]): typeof fetch {
  if (pinnedAddresses.length === 0) {
    throw new Error("Pinned fetch requires at least one validated IP address.");
  }
  for (const addr of pinnedAddresses) {
    if (isBlockedIpAddress(addr)) {
      throw new Error(`Refusing pinned fetch to blocked address: ${addr}`);
    }
  }

  return (async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const targetUrl = typeof input === "string" || input instanceof URL ? new URL(input.toString()) : new URL(input.url);
    if (targetUrl.protocol !== "https:") {
      throw new Error("Pinned fetch only supports HTTPS.");
    }
    if (targetUrl.port !== "" && targetUrl.port !== "443") {
      throw new Error("Pinned fetch only supports port 443.");
    }

    const pinnedIp = pinnedAddresses[0]!;
    const family = net.isIP(pinnedIp) === 6 ? 6 : 4;
    const headersRecord: Record<string, string> = {};
    if (init?.headers) {
      const h = new Headers(init.headers);
      h.forEach((v, k) => {
        headersRecord[k] = v;
      });
    }
    if (!headersRecord["host"]) {
      headersRecord["host"] = targetUrl.host;
    }

    const bodyStr =
      typeof init?.body === "string"
        ? init.body
        : init?.body instanceof Uint8Array
          ? Buffer.from(init.body)
          : undefined;

    return await new Promise<Response>((resolve, reject) => {
      const req = https.request(
        {
          protocol: "https:",
          hostname: targetUrl.hostname,
          port: 443,
          path: `${targetUrl.pathname}${targetUrl.search}`,
          method: init?.method ?? "GET",
          headers: headersRecord,
          servername: targetUrl.hostname,
          lookup: ((_host: string, opts: unknown, cb: (...args: unknown[]) => void) => {
            if (typeof opts === "object" && opts !== null && (opts as { all?: boolean }).all) {
              cb(
                null,
                pinnedAddresses.map((address) => ({
                  address,
                  family: net.isIP(address) === 6 ? 6 : 4,
                }))
              );
              return;
            }
            cb(null, pinnedIp, family);
          }) as never,
        },
        (res) => {
          const status = res.statusCode ?? 502;
          if (status >= 300 && status < 400) {
            res.destroy();
            reject(new Error(`Blocked outbound HTTP redirect (status ${status})`));
            return;
          }
          const chunks: Buffer[] = [];
          res.on("data", (chunk: Buffer) => chunks.push(chunk));
          res.on("end", () => {
            const outHeaders = new Headers();
            for (const [k, v] of Object.entries(res.headers)) {
              if (typeof v === "string") outHeaders.set(k, v);
              else if (Array.isArray(v)) outHeaders.set(k, v.join(", "));
            }
            resolve(
              new Response(Buffer.concat(chunks), {
                status,
                statusText: res.statusMessage ?? "",
                headers: outHeaders,
              })
            );
          });
          res.on("error", reject);
        }
      );

      if (init?.signal) {
        if (init.signal.aborted) {
          req.destroy(new Error("Request aborted"));
          return;
        }
        const onAbort = () => req.destroy(new Error("Request aborted"));
        init.signal.addEventListener("abort", onAbort, { once: true });
        req.on("close", () => init.signal?.removeEventListener("abort", onAbort));
      }

      req.on("error", reject);
      if (bodyStr !== undefined) {
        req.write(bodyStr);
      }
      req.end();
    });
  }) as typeof fetch;
}
