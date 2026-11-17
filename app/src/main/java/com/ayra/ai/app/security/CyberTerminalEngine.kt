package com.ayra.ai.app.security

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import com.ayra.ai.app.vpn.AyraVpnPrivacyController
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Immutable
data class TerminalOutput(
    val command: String,
    val output: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val id: String = java.util.UUID.randomUUID().toString()
)

object CyberTerminalEngine {

    val WELCOME_BANNER = """
    ╔══════════════════════════════════════════════════════════════════════╗
    ║      GHOST AI • DEFENSIVE CYBER SECURITY TERMINAL & LAB v3.1         ║
    ║      Real Sandbox Filesystem, Network Probes, CVE API & Crypto       ║
    ╚══════════════════════════════════════════════════════════════════════╝
    Type 'help' for real sandbox & network diagnostic commands, or 'labs' for ethical CTFs.
    """.trimIndent()

    private val defaultHttpClient: OkHttpClient
        get() = NetworkSecurityHelper.hardenedPooledClient

    private val ACTIVE_PROBE_COMMANDS = setOf(
        "ping", "traceroute", "tracert", "whois", "dig", "nslookup",
        "curl", "headers", "security-headers", "portscan", "nmap"
    )
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    private const val RATE_LIMIT_WINDOW_MS = 60_000L
    const val MAX_PROBES_PER_MINUTE = 15

    private val recentProbeTimestamps = ArrayDeque<Long>()

    fun isNetworkProbeCommand(rawInput: String): Boolean {
        val cmd = rawInput.trim().split(Regex("\\s+")).firstOrNull()?.lowercase(Locale.ROOT) ?: return false
        return cmd in ACTIVE_PROBE_COMMANDS
    }

    fun extractProbeTarget(rawInput: String): String? {
        val tokens = rawInput.trim().split(Regex("\\s+"))
        if (tokens.size < 2) return null
        val candidate = tokens.drop(1).lastOrNull { !it.startsWith("-") } ?: return null
        return candidate
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .substringBefore(":")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    fun requiresAuthorizationConfirmation(rawInput: String): Boolean {
        if (!isNetworkProbeCommand(rawInput)) return false
        val host = extractProbeTarget(rawInput)?.lowercase(Locale.ROOT) ?: return false
        return host !in LOOPBACK_HOSTS
    }

    @Synchronized
    internal fun checkAndRecordProbeRateLimit(nowMs: Long = System.currentTimeMillis()): Boolean {
        while (recentProbeTimestamps.isNotEmpty() && nowMs - recentProbeTimestamps.first() > RATE_LIMIT_WINDOW_MS) {
            recentProbeTimestamps.removeFirst()
        }
        if (recentProbeTimestamps.size >= MAX_PROBES_PER_MINUTE) {
            return false
        }
        recentProbeTimestamps.addLast(nowMs)
        return true
    }

    @Synchronized
    internal fun resetRateLimiterForTesting() {
        recentProbeTimestamps.clear()
    }

    @JvmOverloads
    fun executeCommand(
        rawInput: String,
        context: Context? = null,
        httpClientOverride: OkHttpClient? = null,
        cveApiBaseUrl: String = "https://cve.circl.lu/api/cve/",
        userAuthorizedTarget: Boolean = (context == null),
        enforceRateLimit: Boolean = (context != null)
    ): TerminalOutput {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return TerminalOutput("", "")

        val client = httpClientOverride ?: defaultHttpClient
        val tokens = trimmed.split(Regex("\\s+"))
        val cmd = tokens[0].lowercase(Locale.ROOT)
        val args = tokens.drop(1)

        val needsAuth = requiresAuthorizationConfirmation(trimmed)
        if (needsAuth && !userAuthorizedTarget) {
            val host = extractProbeTarget(trimmed) ?: "target"
            if (context != null) {
                SecurityAuditLogger.log(
                    context = context,
                    category = SecurityAuditCategory.NETWORK,
                    severity = SecurityAuditSeverity.WARN,
                    message = "CyberTerminal blocked unconfirmed probe '$cmd' -> $host"
                )
            }
            return TerminalOutput(
                command = rawInput,
                output = "Error: Target ownership/authorization confirmation required before running '$cmd' against '$host'. Confirm \"I am the owner / authorized to test this target\" in the terminal prompt.",
                isError = true
            )
        }

        if (enforceRateLimit && isNetworkProbeCommand(trimmed)) {
            if (!checkAndRecordProbeRateLimit()) {
                if (context != null) {
                    SecurityAuditLogger.log(
                        context = context,
                        category = SecurityAuditCategory.NETWORK,
                        severity = SecurityAuditSeverity.WARN,
                        message = "CyberTerminal rate limit hit for '$cmd' (>${MAX_PROBES_PER_MINUTE}/min)"
                    )
                }
                return TerminalOutput(
                    command = rawInput,
                    output = "Error: Rate limit exceeded (max $MAX_PROBES_PER_MINUTE network probes per minute). Wait briefly before retrying.",
                    isError = true
                )
            }
        }

        val output = when (cmd) {
            "help", "man", "?" -> handleHelp()
            "clear", "cls" -> "CLEAR_TERMINAL"
            "ls", "dir" -> handleLs(args, context)
            "pwd" -> getWorkspaceDir(context).absolutePath
            "whoami" -> System.getProperty("user.name")?.takeIf { it.isNotBlank() }
                ?: "uid-${runCatching { Process.myUid() }.getOrDefault(0)}"
            "id" -> {
                val uid = runCatching { Process.myUid() }.getOrDefault(0)
                val pid = runCatching { Process.myPid() }.getOrDefault(0)
                "uid=$uid(app_sandbox) pid=$pid package=com.ayra.ai.app"
            }
            "uname" -> {
                val arch = System.getProperty("os.arch") ?: "aarch64"
                val osName = System.getProperty("os.name") ?: "Linux"
                val osVer = System.getProperty("os.version") ?: (Build.VERSION.RELEASE ?: "unknown")
                val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: arch
                "$osName android-${Build.VERSION.SDK_INT} $osVer $arch ($abi)"
            }
            "date" -> Date().toString()
            "uptime" -> {
                val sec = runCatching { SystemClock.elapsedRealtime() / 1000L }.getOrDefault(0L)
                val hrs = sec / 3600L
                val mins = (sec % 3600L) / 60L
                val remSec = sec % 60L
                String.format(Locale.US, "up %02d:%02d:%02d since device boot", hrs, mins, remSec)
            }
            "echo" -> args.joinToString(" ")
            "cat" -> handleCat(args, context)
            "write", "touch" -> handleWrite(args, context)
            "mkdir" -> handleMkdir(args, context)
            "rm" -> handleRm(args, context)
            "ps" -> handlePs()
            "top", "htop" -> handleTop()
            "df" -> handleDf(context)
            "free" -> handleFree()
            "ifconfig", "ip" -> handleIfconfig()
            "netstat", "ss" -> handleNetstat()
            "ping" -> handleRealPing(args)
            "traceroute", "tracert" -> handleTraceroute(args)
            "whois" -> handleWhois(args, client)
            "dig", "nslookup" -> handleRealDig(args)
            "curl" -> handleRealCurl(args, client)
            "hash" -> handleHash(args)
            "base64" -> handleBase64(args)
            "cve" -> handleCve(args, client, cveApiBaseUrl)
            "owasp" -> handleOwasp(args)
            "vpn", "shield" -> handleVpnStatus()
            "labs", "practice", "ctf" -> handleLabs()
            "portscan", "nmap" -> handleNmapAudit(args)
            "headers", "security-headers" -> handleRealHeaders(args, client)
            "exit" -> "CLOSE_TERMINAL"
            else -> {
                when {
                    cmd.startsWith("cve-") -> handleCve(listOf(cmd) + args, client, cveApiBaseUrl)
                    cmd in listOf("sqli", "xss", "csrf", "idor", "ssrf") -> handleOwasp(listOf(cmd) + args)
                    else -> "bash: command not found: $cmd\nType 'help' to see available diagnostic & lab commands."
                }
            }
        }

        if (context != null && isNetworkProbeCommand(trimmed)) {
            val host = extractProbeTarget(trimmed) ?: "unspecified"
            SecurityAuditLogger.log(
                context = context,
                category = SecurityAuditCategory.NETWORK,
                severity = SecurityAuditSeverity.INFO,
                message = "CyberTerminal authorized probe '$cmd' -> $host"
            )
        }

        return TerminalOutput(
            command = rawInput,
            output = output,
            isError = output.startsWith("bash: command not found") || output.startsWith("Error:")
        )
    }

    internal fun getWorkspaceDir(context: Context?): File {
        val base = context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "ayra_cyber_workspace")
        val ws = File(base, "lab_workspace")
        if (!ws.exists()) {
            ws.mkdirs()
        }
        seedStarterWorkspaceFilesIfEmpty(ws)
        return ws
    }

    private fun seedStarterWorkspaceFilesIfEmpty(ws: File) {
        val marker = File(ws, ".seeded")
        if (marker.exists()) return
        runCatching {
            File(ws, "README_LAB.txt").writeText(
                "Ghost AI Sandbox Workspace (${ws.absolutePath})\nUse 'ls', 'cat <file>', 'write <file> <text>', 'mkdir <dir>', 'rm <file>' to work with real files on disk.\n"
            )
            File(ws, "defense.txt").writeText(
                """
                GHOST AI DEFENSIVE SECURITY PRINCIPLES:
                1. Zero Trust Architecture (Never trust, always verify every request).
                2. Least-Privilege Access Controls across all microservices and APIs.
                3. End-to-End Cryptography with TLS 1.3 and Android Keystore AES-256-GCM.
                4. Real-time Threat Intelligence and immutable audit logging.
                """.trimIndent()
            )
            File(ws, "owasp_top10.md").writeText(
                """
                # OWASP Top 10 Quick Mitigation Cheat Sheet
                - SQLi: Parameterized queries & Room DAOs.
                - XSS: Contextual HTML encoding & strict CSP.
                - SSRF: Strict HTTPS & non-private IP verification.
                - Broken Auth: Keystore tokens + MFA.
                """.trimIndent()
            )
            File(ws, "zero_trust_policy.json").writeText(
                """
                {
                  "policy_name": "GhostAiZeroTrustEgress_v3",
                  "enforce_tls": true,
                  "minimum_tls_version": "1.3",
                  "audit_logging": "ENABLED"
                }
                """.trimIndent()
            )
            marker.writeText("1")
        }
    }

    private fun resolveWorkspaceFile(ws: File, rawPath: String): File? {
        val cleaned = rawPath.trim().trimStart('/')
        if (cleaned.isEmpty()) return ws
        val candidate = File(ws, cleaned).canonicalFile
        val rootCanonical = ws.canonicalFile
        return if (candidate.path == rootCanonical.path || candidate.path.startsWith(rootCanonical.path + File.separator)) {
            candidate
        } else {
            null
        }
    }

    private fun handleHelp(): String {
        return """
        GHOST AI DEFENSIVE CYBER & LINUX SHELL TOOLKIT:
        ----------------------------------------------------------------------
        • ping <host>              : Real ICMP/socket latency test against target
        • dig / nslookup <domain>  : Real DNS resolution & IP record discovery
        • headers <url>            : Real HTTP security header audit (HSTS, CSP, etc.)
        • curl -I <url>            : Fetch real HTTP response status and server info
        • cve <cve-id>             : Live CVE JSON lookup (circl.lu / NVD reference)
        • ifconfig / netstat       : Real device NetworkInterface & sandbox socket info
        • ls / cat / write / rm    : Real file I/O inside local app lab_workspace
        • hash <sha256|md5> <txt>  : Compute genuine cryptographic message digests
        • base64 <encode|decode>   : Encode or decode Base64 strings
        • owasp [top10|sqli|xss]   : Review OWASP security vulnerabilities & fixes
        • vpn / shield             : Inspect active WireGuard profile status
        • ps / top / df / free     : Live JVM/Android sandbox process & memory metrics
        • nmap <target>            : [Educational Reference Only — Active Scanning Disabled]
        • labs                     : Certified ethical platforms (HTB, THM, OverTheWire)
        • clear                    : Clear the terminal console
        ----------------------------------------------------------------------
        """.trimIndent()
    }

    private fun handleLs(args: List<String>, context: Context?): String {
        val ws = getWorkspaceDir(context)
        val target = if (args.isEmpty()) ws else resolveWorkspaceFile(ws, args[0])
            ?: return "Error: Path escapes sandbox workspace."
        if (!target.exists()) return "ls: cannot access '${args.firstOrNull() ?: "."}': No such file or directory"
        if (target.isFile) return "${target.length()} bytes  ${target.name}"

        val entries = target.listFiles()?.filter { !it.name.startsWith(".") }?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        if (entries.isEmpty()) return "(empty workspace: ${target.name})"
        return entries.joinToString("\n") { f ->
            val kind = if (f.isDirectory) "drwxr-xr-x" else "-rw-r--r--"
            val size = if (f.isDirectory) 4096L else f.length()
            val mod = Date(f.lastModified()).toString().take(16)
            String.format(Locale.US, "%s %8d  %s  %s%s", kind, size, mod, f.name, if (f.isDirectory) "/" else "")
        }
    }

    private fun handleCat(args: List<String>, context: Context?): String {
        if (args.isEmpty()) return "Usage: cat <filename> (e.g. cat defense.txt, cat README_LAB.txt)"
        val ws = getWorkspaceDir(context)
        val target = resolveWorkspaceFile(ws, args[0])
            ?: return "Error: Path '${args[0]}' is outside sandbox workspace."
        if (!target.exists() || target.isDirectory) {
            return "cat: ${args[0]}: No such file in ${ws.name}"
        }
        return runCatching { target.readText().take(16_000) }
            .getOrElse { "Error: Unable to read '${args[0]}': ${it.message}" }
    }

    private fun handleWrite(args: List<String>, context: Context?): String {
        if (args.isEmpty()) return "Usage: write <filename> <text_content>"
        val ws = getWorkspaceDir(context)
        val target = resolveWorkspaceFile(ws, args[0])
            ?: return "Error: Path '${args[0]}' is outside sandbox workspace."
        val body = args.drop(1).joinToString(" ")
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(body)
            "Wrote ${body.toByteArray().size} bytes to ${target.name}"
        }.getOrElse { "Error: Failed to write ${args[0]}: ${it.message}" }
    }

    private fun handleMkdir(args: List<String>, context: Context?): String {
        if (args.isEmpty()) return "Usage: mkdir <dirname>"
        val ws = getWorkspaceDir(context)
        val target = resolveWorkspaceFile(ws, args[0])
            ?: return "Error: Path '${args[0]}' is outside sandbox workspace."
        if (target.exists()) return "mkdir: cannot create directory '${args[0]}': File exists"
        return if (target.mkdirs()) "Created directory ${target.name}/" else "Error: Could not create directory '${args[0]}'."
    }

    private fun handleRm(args: List<String>, context: Context?): String {
        if (args.isEmpty()) return "Usage: rm <filename>"
        val ws = getWorkspaceDir(context)
        val target = resolveWorkspaceFile(ws, args[0])
            ?: return "Error: Path '${args[0]}' is outside sandbox workspace."
        if (target.canonicalPath == ws.canonicalPath) return "Error: Cannot delete workspace root."
        if (!target.exists()) return "rm: cannot remove '${args[0]}': No such file or directory"
        return if (target.deleteRecursively()) "Removed ${args[0]}" else "Error: Failed to delete '${args[0]}'."
    }

    private fun handleRealPing(args: List<String>): String {
        if (args.isEmpty()) return "Error: Target host required. Usage: ping <hostname|ip>"
        val rawTarget = args[0].replace(Regex("^https?://"), "").substringBefore("/").substringBefore(":")
        val target = rawTarget.trim()
        if (target.isBlank()) return "Error: Invalid host."

        return try {
            val startResolve = System.currentTimeMillis()
            val address = InetAddress.getByName(target)
            val resolveMs = System.currentTimeMillis() - startResolve
            val ip = address.hostAddress ?: target

            val times = mutableListOf<Long>()
            var reached = 0
            for (seq in 1..4) {
                val pingStart = System.currentTimeMillis()
                val ok = address.isReachable(1500)
                val roundTrip = (System.currentTimeMillis() - pingStart).coerceAtLeast(1)
                if (ok) reached++
                times.add(roundTrip)
            }

            val avg = times.average().toInt()
            val min = times.minOrNull() ?: avg
            val max = times.maxOrNull() ?: avg
            val lossPct = ((4 - reached) * 100.0) / 4.0

            """
            PING $target ($ip): 56 data bytes
            64 bytes from $ip: probe_seq=1 time=${times[0]} ms
            64 bytes from $ip: probe_seq=2 time=${times[1]} ms
            64 bytes from $ip: probe_seq=3 time=${times[2]} ms
            64 bytes from $ip: probe_seq=4 time=${times[3]} ms

            --- $target ping statistics ---
            4 probes transmitted, $reached reachable, ${String.format(Locale.US, "%.1f", lossPct)}% packet loss (DNS resolve: ${resolveMs}ms)
            round-trip min/avg/max = $min/$avg/$max ms
            """.trimIndent()
        } catch (e: Exception) {
            "ping: cannot resolve $target: Unknown host or network unreachable (${e.message})"
        }
    }

    private fun handleRealDig(args: List<String>): String {
        if (args.isEmpty()) return "Error: Domain required. Usage: dig <domain.com> [A|AAAA]"
        val domain = args[0].lowercase(Locale.ROOT).replace(Regex("^https?://"), "").substringBefore("/").substringBefore(":")
        val recordType = if (args.size > 1) args[1].uppercase(Locale.ROOT) else "A"

        return try {
            val start = System.currentTimeMillis()
            val addresses = InetAddress.getAllByName(domain)
            val elapsed = System.currentTimeMillis() - start

            val records = addresses.joinToString("\n") { addr ->
                val ip = addr.hostAddress ?: ""
                val type = if (ip.contains(":")) "AAAA" else "A"
                String.format(Locale.US, "%-24s 300   IN      %-6s %s", domain, type, ip)
            }

            """
            ; <<>> DiG (Android System Resolver) <<>> $domain $recordType
            ;; Got answer: ${addresses.size} record(s)
            ;; QUESTION SECTION:
            ;$domain.                       IN      $recordType

            ;; ANSWER SECTION:
            $records

            ;; Query time: ${elapsed} msec
            ;; WHEN: ${Date()}
            """.trimIndent()
        } catch (e: Exception) {
            "dig: DNS resolution failed for $domain (${e.message ?: "NXDOMAIN"})"
        }
    }

    private fun handleRealHeaders(args: List<String>, client: OkHttpClient): String {
        if (args.isEmpty()) return "Error: Target URL required. Usage: headers <https://example.com>"
        var urlStr = args[0]
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            urlStr = "https://$urlStr"
        }
        if (client === defaultHttpClient) {
            val check = NetworkSecurityHelper.validatePublicHttpsUrl(urlStr)
            if (!check.allowed) {
                return "Error: ${check.reason ?: "Blocked unsafe URL (SSRF defense)."}"
            }
        }

        return try {
            val req = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "GhostAiSecurityAuditor/3.1")
                .head()
                .build()

            client.newCall(req).execute().use { resp ->
                val headers = resp.headers
                val headerLines = StringBuilder()
                for (i in 0 until headers.size) {
                    headerLines.append("${headers.name(i)}: ${headers.value(i)}\n")
                }

                val hasHsts = headers["Strict-Transport-Security"] != null
                val hasCsp = headers["Content-Security-Policy"] != null
                val hasXfo = headers["X-Frame-Options"] != null
                val hasXcto = headers["X-Content-Type-Options"] != null

                """
                === HTTP HEAD AUDIT: $urlStr ===
                HTTP/${resp.protocol} ${resp.code} ${resp.message}
                $headerLines
                === SECURITY POLICY EVALUATION ===
                • Strict-Transport-Security (HSTS) : ${if (hasHsts) "ENFORCED" else "MISSING"}
                • Content-Security-Policy (CSP)    : ${if (hasCsp) "ENFORCED" else "MISSING"}
                • X-Frame-Options (Clickjacking)   : ${if (hasXfo) "ENFORCED" else "MISSING"}
                • X-Content-Type-Options (MIME)    : ${if (hasXcto) "ENFORCED" else "MISSING"}
                """.trimIndent()
            }
        } catch (e: Exception) {
            "headers: connection error to $urlStr: ${e.message}"
        }
    }

    private fun handleRealCurl(args: List<String>, client: OkHttpClient): String {
        if (args.isEmpty()) return "Usage: curl -I <url> (or curl <url>)"
        val urlArg = args.lastOrNull { !it.startsWith("-") } ?: return "Error: URL required."
        var targetUrl = urlArg
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            targetUrl = "https://$targetUrl"
        }
        if (client === defaultHttpClient) {
            val check = NetworkSecurityHelper.validatePublicHttpsUrl(targetUrl)
            if (!check.allowed) {
                return "Error: ${check.reason ?: "Blocked unsafe URL (SSRF defense)."}"
            }
        }

        return try {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "GhostAi-HTTP-Client/3.1")
                .build()

            client.newCall(req).execute().use { resp ->
                """
                HTTP/${resp.protocol} ${resp.code} ${resp.message}
                server: ${resp.header("server", "unknown")}
                content-type: ${resp.header("content-type", "unknown")}
                content-length: ${resp.header("content-length", "unknown")}
                date: ${resp.header("date", Date().toString())}
                """.trimIndent()
            }
        } catch (e: Exception) {
            "curl: (6) Could not reach $urlArg (${e.message})"
        }
    }

    private fun handleHash(args: List<String>): String {
        if (args.size < 2) return "Usage: hash <sha256|sha512|md5> <text_to_hash>"
        val algorithm = when (args[0].lowercase(Locale.ROOT)) {
            "sha256", "sha-256" -> "SHA-256"
            "sha512", "sha-512" -> "SHA-512"
            "md5" -> "MD5"
            else -> "SHA-256"
        }
        val input = args.drop(1).joinToString(" ")
        val md = MessageDigest.getInstance(algorithm)
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }

        return """
        Input string : "$input"
        Algorithm    : $algorithm
        Digest (hex) : $hex
        Digest size  : ${digest.size * 8} bits
        """.trimIndent()
    }

    private fun handleBase64(args: List<String>): String {
        if (args.size < 2) return "Usage: base64 <encode|decode> <text>"
        val mode = args[0].lowercase(Locale.ROOT)
        val text = args.drop(1).joinToString(" ")

        return when (mode) {
            "encode", "enc", "e" -> {
                val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                "Base64 Encoded:\n$encoded"
            }
            "decode", "dec", "d" -> {
                try {
                    val decoded = String(Base64.getDecoder().decode(text), Charsets.UTF_8)
                    "Base64 Decoded:\n$decoded"
                } catch (_: Exception) {
                    "Error: Invalid Base64 input string."
                }
            }
            else -> "Usage: base64 <encode|decode> <text>"
        }
    }

    private fun handleCve(
        args: List<String>,
        client: OkHttpClient,
        cveApiBaseUrl: String
    ): String {
        if (args.isEmpty()) return "Usage: cve <CVE-ID> (e.g. cve CVE-2024-3094, cve CVE-2021-44228)"
        val cveId = args[0].uppercase(Locale.US).trim()
        if (!cveId.matches(Regex("^CVE-\\d{4}-\\d{4,}$"))) {
            return "Error: Invalid CVE identifier '$cveId'. Expected format: CVE-YYYY-NNNNN"
        }

        val endpoint = cveApiBaseUrl.trimEnd('/') + "/" + cveId
        if (client === defaultHttpClient && cveApiBaseUrl == "https://cve.circl.lu/api/cve/") {
            val check = NetworkSecurityHelper.validatePublicHttpsUrl(endpoint)
            if (!check.allowed) {
                return "Error: ${check.reason ?: "Blocked unsafe CVE endpoint."}"
            }
        }
        return try {
            val req = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("User-Agent", "GhostAi-CVE-Lookup/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank() || body == "null") {
                    return "[$cveId] Live CVE API returned HTTP ${resp.code} (not found or unavailable).\nReference: https://nvd.nist.gov/vuln/detail/$cveId"
                }
                val json = JSONObject(body)
                // Support both CIRCL schema (summary/cvss) and CVE 5.x schema (cveMetadata / containers.cna)
                val containersDesc = json.optJSONObject("containers")
                    ?.optJSONObject("cna")
                    ?.optJSONArray("descriptions")
                    ?.optJSONObject(0)
                    ?.optString("value", "")
                    .orEmpty()
                val summary = json.optString("summary", "")
                    .ifBlank { containersDesc }
                    .ifBlank { "No summary returned by upstream CVE feed." }

                val cvss = when {
                    json.has("cvss") && !json.isNull("cvss") -> json.optDouble("cvss", -1.0)
                    json.has("cvss3") && !json.isNull("cvss3") -> json.optDouble("cvss3", -1.0)
                    else -> -1.0
                }
                val cvssText = if (cvss >= 0.0) String.format(Locale.US, "%.1f", cvss) else "Unscored/See NVD"
                val published = json.optString("Published", json.optString("published", ""))
                    .ifBlank {
                        json.optJSONObject("cveMetadata")?.optString("datePublished", "").orEmpty()
                    }
                    .ifBlank { "Unknown" }

                val refs = buildList {
                    val arr = json.optJSONArray("references")
                    if (arr != null) {
                        for (i in 0 until minOf(arr.length(), 3)) {
                            val item = arr.opt(i)
                            val url = when (item) {
                                is String -> item
                                is JSONObject -> item.optString("url")
                                else -> ""
                            }
                            if (url.isNotBlank()) add(url)
                        }
                    }
                }

                buildString {
                    append("[$cveId] Live Vulnerability Record\n")
                    append("• CVSS Score   : $cvssText\n")
                    append("• Published    : $published\n")
                    append("• Summary      : ${summary.take(500)}\n")
                    if (refs.isNotEmpty()) {
                        append("• References   :\n")
                        refs.forEach { append("  - $it\n") }
                    }
                    append("• NVD Portal   : https://nvd.nist.gov/vuln/detail/$cveId")
                }.trimEnd()
            }
        } catch (e: Exception) {
            "[$cveId] Live CVE feed unreachable (${e.message ?: "offline"}).\nVerify internet access or view: https://nvd.nist.gov/vuln/detail/$cveId"
        }
    }

    private fun handleOwasp(args: List<String>): String {
        val topic = if (args.isNotEmpty()) args[0].lowercase(Locale.ROOT) else "top10"
        return when {
            topic.contains("sqli") -> """
            [OWASP A03:2021 - Injection] SQL Injection (SQLi)
            • Concept   : Untrusted user data concatenated directly into SQL queries.
            • Vulnerable: SELECT * FROM users WHERE user = '" + input + "'";
            • Defensive : Use Parameterized PreparedStatement / Room @Query bindings:
                          SELECT * FROM users WHERE user = :username;
            """.trimIndent()

            topic.contains("xss") -> """
            [OWASP A03:2021 - Injection] Cross-Site Scripting (XSS)
            • Concept   : Injecting malicious client scripts into trusted web pages.
            • Defensive : 1. Context-aware HTML entity encoding.
                          2. Strict Content-Security-Policy (CSP) with nonce-based execution.
            """.trimIndent()

            else -> """
            OWASP TOP 10 (SECURITY REFERENCE):
            1. A01: Broken Access Control (Enforce server-side authorization checks)
            2. A02: Cryptographic Failures (Enforce TLS 1.3, AES-256-GCM, Keystore)
            3. A03: Injection (SQLi, Command Injection, XSS)
            4. A04: Insecure Design (Threat modeling, secure architectural patterns)
            5. A05: Security Misconfiguration (Disable default credentials & debug endpoints)
            6. A06: Vulnerable and Outdated Components (Dependency auditing)
            7. A07: Identification and Authentication Failures (MFA, rate-limiting)
            8. A08: Software and Data Integrity Failures (Code signing & integrity verification)
            9. A09: Security Logging and Monitoring Failures (Audit logs & alerting)
            10. A10: Server-Side Request Forgery (SSRF) (Strict HTTPS & private-IP filtering)
            """.trimIndent()
        }
    }

    private fun handleNmapAudit(args: List<String>): String {
        val target = if (args.isNotEmpty()) args[0] else "target.example.com"
        return """
        [Educational Reference Only — Active Port Scanning Disabled on Sandbox]
        Android unprivileged apps do not include raw SYN/nmap scanning binaries.
        Reference Audit Commands (for authorized hosts on a workstation):
          nmap -sV -sC -Pn $target        (Service version & safe scripts)
          nmap -p 22,80,443,51820 $target (Check common transport ports)
        Inside Ghost AI Terminal, run real live checks instead:
          • headers https://$target
          • traceroute $target
          • dig $target
        """.trimIndent()
    }

    private fun handleTraceroute(args: List<String>): String {
        val target = if (args.isNotEmpty()) args[0].replace(Regex("^https?://"), "").substringBefore("/").trim() else "1.1.1.1"
        return try {
            val startDns = System.currentTimeMillis()
            val address = InetAddress.getByName(target)
            val dnsMs = System.currentTimeMillis() - startDns
            val ip = address.hostAddress ?: target

            val rttSamples = mutableListOf<String>()
            for (port in listOf(443, 80)) {
                val t0 = System.currentTimeMillis()
                try {
                    java.net.Socket().use { sock ->
                        sock.connect(java.net.InetSocketAddress(address, port), 1500)
                        val rtt = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                        rttSamples.add("TCP:$port -> ${rtt}ms (OPEN)")
                    }
                } catch (_: Exception) {
                    rttSamples.add("TCP:$port -> unreachable/filtered")
                }
            }

            """
            [Real DNS + TCP End-to-End Path Probe] target: $target ($ip)
             • DNS Lookup      : ${dnsMs} ms -> $ip
             • Endpoint Probes : ${rttSamples.joinToString(" | ")}
            Note: Unprivileged Android sandbox permits socket probes rather than raw ICMP TTL hop tracing.
            """.trimIndent()
        } catch (e: Exception) {
            "traceroute probe: cannot resolve $target (${e.message ?: "offline"})"
        }
    }

    private fun handleWhois(args: List<String>, client: OkHttpClient): String {
        val domain = if (args.isNotEmpty()) args[0].lowercase(Locale.ROOT).replace(Regex("^https?://"), "").substringBefore("/").trim() else "example.com"
        val rdapUrl = "https://rdap.org/domain/$domain"
        if (client === defaultHttpClient) {
            val check = NetworkSecurityHelper.validatePublicHttpsUrl(rdapUrl)
            if (!check.allowed) {
                return "Error: ${check.reason ?: "Blocked invalid RDAP lookup target."}"
            }
        }
        return try {
            val quickClient = client.newBuilder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(4, TimeUnit.SECONDS)
                .build()
            val rdapReq = Request.Builder()
                .url(rdapUrl)
                .header("Accept", "application/rdap+json")
                .build()
            quickClient.newCall(rdapReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val ldhName = json.optString("ldhName", domain.uppercase(Locale.US))
                    val statusArr = json.optJSONArray("status")
                    val statuses = buildList {
                        if (statusArr != null) {
                            for (i in 0 until statusArr.length()) add(statusArr.optString(i))
                        }
                    }.joinToString(", ").ifBlank { "registered" }
                    val handle = json.optString("handle", "RDAP Verified")
                    """
                    Domain Name : $ldhName
                    RDAP Handle : $handle
                    Status      : $statuses
                    Source      : Live RDAP Directory (https://rdap.org)
                    """.trimIndent()
                } else {
                    val ips = InetAddress.getAllByName(domain).joinToString(", ") { it.hostAddress ?: "" }
                    """
                    Domain Name : ${domain.uppercase(Locale.US)}
                    Resolved IPs: ${ips.ifBlank { "Unavailable" }}
                    RDAP Status : HTTP ${resp.code}
                    """.trimIndent()
                }
            }
        } catch (e: Exception) {
            """
            Domain Name : ${domain.uppercase(Locale.US)}
            Lookup Note : Live RDAP directory unreachable offline (${e.javaClass.simpleName}). Connect to internet for live registration records.
            """.trimIndent()
        }
    }

    private fun handleVpnStatus(): String {
        return AyraVpnPrivacyController.formatStatusSummary(isBengali = false)
    }

    private fun handleLabs(): String {
        return """
        APPROVED ETHICAL CYBERSECURITY TRAINING PLATFORMS:
        ----------------------------------------------------------------------
        1. Hack The Box (HTB)       : Real-world penetration testing & CTF labs
        2. TryHackMe (THM)          : Guided defensive, SOC, and offensive learning
        3. OverTheWire (Bandit)     : Linux command-line security mastery
        4. PortSwigger Web Security : Deep hands-on OWASP & web vulnerability labs
        5. PicoCTF                  : Foundational cryptography, reverse-engineering
        ----------------------------------------------------------------------
        """.trimIndent()
    }

    private fun handlePs(): String {
        val activeThreads = Thread.activeCount()
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        return """
        RUNTIME PROCESS TELEMETRY (JVM / Android App Sandbox):
        • Active Threads : $activeThreads
        • CPU Cores      : ${rt.availableProcessors()}
        • Heap In Use    : ${usedMb} MiB
        • Package Kernel : com.ayra.ai.app (Safe Sandbox)
        """.trimIndent()
    }

    private fun handleTop(): String {
        val rt = Runtime.getRuntime()
        val totalMb = rt.totalMemory() / (1024.0 * 1024.0)
        val freeMb = rt.freeMemory() / (1024.0 * 1024.0)
        val usedMb = totalMb - freeMb
        val maxMb = rt.maxMemory() / (1024.0 * 1024.0)
        return String.format(
            Locale.US,
            "top - %s | Threads: %d | CPU Cores: %d\nMiB JVM Heap: %.1f max, %.1f allocated, %.1f used, %.1f free",
            Date().toString(),
            Thread.activeCount(),
            rt.availableProcessors(),
            maxMb,
            totalMb,
            usedMb,
            freeMb
        )
    }

    private fun handleDf(context: Context?): String {
        val root = getWorkspaceDir(context)
        val totalKb = root.totalSpace / 1024L
        val availKb = root.usableSpace / 1024L
        val usedKb = (totalKb - availKb).coerceAtLeast(0L)
        val pct = if (totalKb > 0) ((usedKb * 100) / totalKb) else 0L
        return """
        Filesystem     1K-blocks      Used Available Use% Mounted on
        app_storage    $totalKb  $usedKb  $availKb  $pct% ${root.absolutePath}
        """.trimIndent()
    }

    private fun handleFree(): String {
        val rt = Runtime.getRuntime()
        val totalKb = rt.maxMemory() / 1024L
        val allocKb = rt.totalMemory() / 1024L
        val freeAllocKb = rt.freeMemory() / 1024L
        val usedKb = (allocKb - freeAllocKb).coerceAtLeast(0L)
        val availKb = (totalKb - usedKb).coerceAtLeast(0L)
        return String.format(
            Locale.US,
            "               total        used        free   available\nJVM Heap:  %9d   %9d   %9d   %9d (KiB)",
            totalKb,
            usedKb,
            freeAllocKb,
            availKb
        )
    }

    private fun handleIfconfig(): String {
        val sb = StringBuilder()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            if (interfaces.isEmpty()) {
                sb.append("(No network interfaces reported by OS)\n")
            } else {
                for (iface in interfaces) {
                    val addrs = iface.inetAddresses.toList()
                    val ipv4 = addrs.filterIsInstance<Inet4Address>().joinToString(", ") { it.hostAddress ?: "" }
                    val ipv6 = addrs.filter { it !is Inet4Address }.joinToString(", ") { it.hostAddress?.substringBefore("%") ?: "" }
                    val state = if (iface.isUp) "UP" else "DOWN"
                    val flags = buildList {
                        add(state)
                        if (iface.isLoopback) add("LOOPBACK")
                        if (iface.isPointToPoint) add("POINTOPOINT")
                        if (iface.isVirtual) add("VIRTUAL")
                    }.joinToString(",")
                    sb.append("${iface.name}: flags=<$flags> mtu ${runCatching { iface.mtu }.getOrDefault(1500)}\n")
                    if (ipv4.isNotBlank()) sb.append("        inet $ipv4\n")
                    if (ipv6.isNotBlank()) sb.append("        inet6 $ipv6\n")
                }
            }
        }.onFailure {
            sb.append("NetworkInterface query error: ${it.message}\n")
        }

        val status = AyraVpnPrivacyController.vpnStatus.value
        val wgState = if (status.isConnected) "ACTIVE" else "CONFIGURED/IDLE"
        sb.append("\nWireGuard Profile Status: $wgState")
        if (status.isConfigured) {
            sb.append(" | client=${status.config.clientAddress} | endpoint=${status.config.endpoint} | mtu=${status.config.mtu}")
        } else {
            sb.append(" | No WireGuard profile saved")
        }
        return sb.toString().trim()
    }

    private fun handleNetstat(): String {
        val localIps = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp }
                .flatMap { iface -> iface.inetAddresses.toList().mapNotNull { addr -> addr.hostAddress?.let { "${iface.name}: $it" } } }
        }.getOrDefault(emptyList())

        return buildString {
            append("JVM/Sandbox unprivileged — system-wide netstat requires root/adb shell.\n")
            append("Active Local Network Bindings visible to app sandbox:\n")
            if (localIps.isEmpty()) {
                append("  (none visible)")
            } else {
                localIps.forEach { append("  • $it\n") }
            }
        }.trimEnd()
    }
}
