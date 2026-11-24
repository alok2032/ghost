package com.ayra.ai.app.security

import android.content.Context
import com.ayra.ai.app.data.AyraPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.CertificatePinner
import okhttp3.CipherSuite
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.cert.CertificateException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

data class UrlValidationResult(
    val allowed: Boolean,
    val reason: String? = null,
    val resolvedAddresses: List<String> = emptyList()
)

enum class NetworkFailureCategory(val label: String) {
    NONE("Healthy"),
    NO_NETWORK("Offline / No Network"),
    CAPTIVE_PORTAL("Captive Portal Login Required"),
    NO_VALIDATED_INTERNET("Connected (No Internet Validation)"),
    DNS_FAILURE("DNS Resolution Failed"),
    TLS_HANDSHAKE_FAILURE("TLS Handshake Failed"),
    SSL_CERTIFICATE_FAILURE("SSL Certificate Verification Failed"),
    TIMEOUT("Connection Timed Out"),
    CONNECTION_REFUSED("Connection Refused"),
    AUTH_FAILURE("Authentication / Key Rejected"),
    RATE_LIMITED("Rate Limited (HTTP 429)"),
    BACKEND_UNAVAILABLE("Backend Gateway Unavailable"),
    PROVIDER_OUTAGE("AI Provider Service Error"),
    SSRF_BLOCKED("Blocked by Security Policy"),
    CLIENT_ERROR("Invalid Request / Endpoint"),
    UNKNOWN("Network Error")
}

data class NetworkFailureClassification(
    val category: NetworkFailureCategory,
    val userMessage: String,
    val technicalDetail: String,
    val retryable: Boolean,
    val httpCode: Int? = null
)

data class EndpointProbeResult(
    val ok: Boolean,
    val latencyMs: Long,
    val httpCode: Int? = null,
    val dnsAddresses: List<String> = emptyList(),
    val tlsProtocol: String? = null,
    val category: NetworkFailureCategory = NetworkFailureCategory.NONE,
    val message: String = ""
)

class RetryableHttpException(val httpCode: Int, message: String) : IOException(message)

class NonRetryableHttpException(val httpCode: Int, message: String) : IOException(message)

/**
 * Thrown by [NetworkSecurityHelper] when a request is rejected by client-side SSRF,
 * DNS-rebinding, Fake-IP VPN, or Local-Only security policies. Extends [UnknownHostException]
 * so OkHttp's [Dns.lookup] contract is honored cleanly while remaining distinct from offline DNS errors.
 */
class BlockedByPolicyException(val reasonCode: String, message: String) : UnknownHostException(message)

/**
 * Network & Transport Hardening Engine.
 *
 * Security Guarantees:
 * - Enforces TLS 1.3 (and TLS 1.2 AEAD suites fallback) via OkHttp [ConnectionSpec].
 * - Provides SPKI Certificate Pinning for first-party AYRA backend.
 * - Fails CLOSED on DNS resolution failures, malformed URLs, embedded credentials, non-443 ports,
 *   IPv4/IPv6 private, loopback, link-local, NAT64, 6to4, Teredo, documentation, ULA, and metadata IPs.
 * - Eliminates validation-vs-connect DNS rebinding TOCTOU via [PublicHttpsDnsGuard] bound on OkHttp clients.
 * - Provides bounded response reading ([readBounded] / [readBoundedBytes]) and SSRF-validated redirect traversal.
 */
object NetworkSecurityHelper {

    const val DEFAULT_MAX_RESPONSE_BYTES: Long = 2L * 1024L * 1024L
    const val DEFAULT_MAX_MEDIA_BYTES: Long = 12L * 1024L * 1024L

    private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

    private val sharedDispatcher: Dispatcher by lazy {
        Dispatcher().apply {
            maxRequests = 96
            maxRequestsPerHost = 24
        }
    }

    private val sharedConnectionPool: ConnectionPool by lazy {
        ConnectionPool(24, 5, TimeUnit.MINUTES)
    }

    internal fun preferIpv4Order(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size <= 1) return addresses
        val (v4, v6) = addresses.partition { it is Inet4Address }
        return v4 + v6
    }

    val strictTlsConnectionSpec: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .cipherSuites(
            CipherSuite.TLS_AES_256_GCM_SHA384,
            CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
        )
        .build()

    /**
     * OkHttp [Dns] guard for public HTTPS clients: inspects every resolved InetAddress immediately
     * before socket creation and rejects private, loopback, link-local, metadata, NAT64, 6to4, or ULA IPs.
     */
    internal object PublicHttpsDnsGuard : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val clean = hostname.trim().lowercase(Locale.ROOT).trimEnd('.')
            if (clean.isEmpty() || isForbiddenInternalHostLiteral(clean)) {
                val code = if (isCloudMetadataHost(clean)) "METADATA_HOST" else "INTERNAL_HOST"
                throw BlockedByPolicyException(code, "Blocked internal/metadata hostname: $hostname")
            }
            val addresses = Dns.SYSTEM.lookup(clean)
            if (addresses.isEmpty()) {
                throw UnknownHostException("Empty DNS resolution for $hostname")
            }
            for (addr in addresses) {
                if (isPrivateOrSpecialAddress(addr)) {
                    if (isFakeIpOrCarrierCgnatAddress(addr)) {
                        throw BlockedByPolicyException(
                            "FAKE_IP_VPN",
                            "DNS returned VPN Fake-IP or Private DNS address ${addr.hostAddress} for $hostname; disable VPN Fake-IP mode or check Private DNS."
                        )
                    }
                    throw BlockedByPolicyException(
                        "NON_PUBLIC_IP",
                        "DNS rebinding guard blocked non-public address ${addr.hostAddress} for $hostname"
                    )
                }
            }
            return preferIpv4Order(addresses)
        }
    }

    /**
     * OkHttp [Dns] guard for explicit loopback/LAN HTTPS clients: still blocks cloud metadata IPs
     * (`169.254.0.0/16`, `metadata.google.internal`, `fd00:ec2::254`) and unspecified `0.0.0.0`/`::`.
     */
    internal object LanDnsGuard : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val clean = hostname.trim().lowercase(Locale.ROOT).trimEnd('.')
            if (isCloudMetadataHost(clean)) {
                throw BlockedByPolicyException("METADATA_HOST", "Blocked cloud metadata hostname: $hostname")
            }
            val addresses = Dns.SYSTEM.lookup(clean)
            for (addr in addresses) {
                if (isCloudMetadataOrUnspecifiedAddress(addr)) {
                    throw BlockedByPolicyException("METADATA_HOST", "Blocked metadata/unspecified IP ${addr.hostAddress}")
                }
            }
            return preferIpv4Order(addresses)
        }
    }

    val hardenedPooledClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(sharedDispatcher)
            .connectionPool(sharedConnectionPool)
            .dns(PublicHttpsDnsGuard)
            .connectionSpecs(listOf(strictTlsConnectionSpec))
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Local/LAN client permitting explicit cleartext HTTP ONLY to loopback hosts declared in
     * `network_security_config.xml` (`localhost`, `127.0.0.1`, `10.0.2.2`), plus HTTPS to LAN subnets.
     */
    val localLanClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(sharedDispatcher)
            .connectionPool(sharedConnectionPool)
            .dns(LanDnsGuard)
            .connectionSpecs(listOf(strictTlsConnectionSpec, ConnectionSpec.CLEARTEXT))
            .connectTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    @JvmOverloads
    fun newClient(
        connectTimeoutSec: Long = 15L,
        readTimeoutSec: Long = 35L,
        writeTimeoutSec: Long = 15L,
        callTimeoutSec: Long = 60L,
        allowLanCleartext: Boolean = false
    ): OkHttpClient {
        val base = if (allowLanCleartext) localLanClient else hardenedPooledClient
        return base.newBuilder()
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSec, TimeUnit.SECONDS)
            .build()
    }

    fun buildPinnedClient(hostname: String, primaryPinSha256: String, backupPinSha256: String): OkHttpClient {
        val pinnerBuilder = CertificatePinner.Builder()
        if (primaryPinSha256.startsWith("sha256/")) {
            pinnerBuilder.add(hostname, primaryPinSha256)
        }
        if (backupPinSha256.startsWith("sha256/")) {
            pinnerBuilder.add(hostname, backupPinSha256)
        }
        return hardenedPooledClient.newBuilder()
            .certificatePinner(pinnerBuilder.build())
            .build()
    }

    /**
     * Reads up to [maxBytes] from an OkHttp [Response] body to prevent OOM crashes from oversized
     * or malicious responses. Fails closed with [IOException] on overflow unless [truncateOnOverflow]
     * is explicitly requested (e.g., HTML web snippet previews).
     */
    @JvmOverloads
    fun Response.readBounded(
        maxBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
        truncateOnOverflow: Boolean = false
    ): String {
        val bytes = readBoundedBytes(maxBytes = maxBytes, truncateOnOverflow = truncateOnOverflow)
        if (bytes.isEmpty()) return ""
        val charset = body?.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
        return String(bytes, charset)
    }

    @JvmOverloads
    fun Response.readBoundedBytes(
        maxBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
        truncateOnOverflow: Boolean = false
    ): ByteArray {
        val respBody = body ?: return ByteArray(0)
        val contentLength = respBody.contentLength()
        if (contentLength > maxBytes && !truncateOnOverflow) {
            throw IOException("Response Content-Length ($contentLength bytes) exceeds $maxBytes byte limit.")
        }
        respBody.byteStream().use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0L
            while (true) {
                val remaining = maxBytes - total
                if (remaining <= 0L) {
                    if (truncateOnOverflow) break
                    val extra = input.read()
                    if (extra == -1) break
                    throw IOException("Response body exceeded $maxBytes byte safety limit.")
                }
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val read = input.read(buf, 0, toRead)
                if (read <= 0) break
                out.write(buf, 0, read)
                total += read
            }
            return out.toByteArray()
        }
    }

    /**
     * Streams a [Response] body directly to [destFile] while enforcing a strict [maxBytes] ceiling.
     * Deletes any partial destination file and throws [IOException] if the limit is exceeded.
     */
    @JvmOverloads
    fun copyBoundedToFile(
        response: Response,
        destFile: File,
        maxBytes: Long = DEFAULT_MAX_MEDIA_BYTES
    ) {
        val respBody = response.body ?: throw IOException("Empty response body.")
        val contentLength = respBody.contentLength()
        if (contentLength > maxBytes) {
            throw IOException("Download Content-Length ($contentLength bytes) exceeds $maxBytes byte limit.")
        }
        try {
            respBody.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val remaining = maxBytes - total
                        if (remaining <= 0L) {
                            if (input.read() == -1) break
                            throw IOException("Downloaded stream exceeded $maxBytes byte safety limit.")
                        }
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = input.read(buf, 0, toRead)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        total += read
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { destFile.delete() }
            throw t
        }
    }

    /**
     * Executes an HTTPS request with manual redirect handling (up to [maxRedirects] hops),
     * re-validating every `Location` target via [urlValidator] (default: [validatePublicHttpsUrl])
     * and stripping sensitive credentials on cross-origin redirects.
     */
    @JvmOverloads
    fun executePublicHttpsWithValidatedRedirects(
        client: OkHttpClient,
        initialRequest: Request,
        maxRedirects: Int = 5,
        urlValidator: (String) -> UrlValidationResult = { validatePublicHttpsUrl(it) }
    ): Response {
        val nonRedirectingClient = if (client.followRedirects || client.followSslRedirects) {
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        } else {
            client
        }

        var currentRequest = initialRequest
        var currentUrl = initialRequest.url.toString()
        val initialCheck = urlValidator(currentUrl)
        if (!initialCheck.allowed) {
            throw SecurityException(initialCheck.reason ?: "URL blocked by SSRF policy.")
        }

        var hops = 0
        while (true) {
            val response = nonRedirectingClient.newCall(currentRequest).execute()
            if (response.code !in REDIRECT_STATUS_CODES) {
                return response
            }
            val code = response.code
            val location = response.header("Location")?.trim().orEmpty()
            response.close()

            if (location.isEmpty()) {
                throw IOException("HTTP $code redirect missing Location header.")
            }
            if (hops >= maxRedirects) {
                throw IOException("Exceeded maximum redirect limit ($maxRedirects).")
            }
            hops++

            val currentUri = runCatching { URI(currentUrl) }.getOrNull()
                ?: throw IOException("Malformed current URI during redirect.")
            val nextUri = runCatching { currentUri.resolve(location) }.getOrNull()
                ?: throw SecurityException("Malformed redirect Location '$location'.")
            val nextUrl = nextUri.toString()

            val redirectCheck = urlValidator(nextUrl)
            if (!redirectCheck.allowed) {
                throw SecurityException("Blocked redirect target '$nextUrl': ${redirectCheck.reason}")
            }

            val crossOrigin = !currentUri.scheme.equals(nextUri.scheme, ignoreCase = true) ||
                !currentUri.host.equals(nextUri.host, ignoreCase = true) ||
                currentUri.port != nextUri.port

            val nextBuilder = currentRequest.newBuilder().url(nextUrl)
            if (crossOrigin) {
                nextBuilder.removeHeader("Authorization")
                nextBuilder.removeHeader("x-api-key")
                nextBuilder.removeHeader("x-goog-api-key")
                nextBuilder.removeHeader("Cookie")
            }
            val method = currentRequest.method
            if (code == 303 || ((code == 301 || code == 302) && method != "GET" && method != "HEAD")) {
                nextBuilder.method("GET", null)
            }
            currentRequest = nextBuilder.build()
            currentUrl = nextUrl
        }
    }

    /**
     * Strict public HTTPS SSRF validation (FAILS CLOSED on DNS resolution failure).
     */
    @JvmOverloads
    fun validatePublicHttpsUrl(
        rawUrl: String,
        dnsResolver: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) }
    ): UrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return UrlValidationResult(false, "URL is empty.")
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return UrlValidationResult(false, "Malformed URL syntax.")

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https") {
            return UrlValidationResult(false, "Blocked non-HTTPS scheme '${scheme ?: "none"}'. Only HTTPS is allowed for external requests.")
        }
        if (!uri.rawUserInfo.isNullOrEmpty() || uri.rawAuthority?.contains("@") == true) {
            return UrlValidationResult(false, "Embedded credentials in URLs are prohibited.")
        }
        if (uri.port !in setOf(-1, 443, 8443)) {
            return UrlValidationResult(false, "Blocked HTTPS port ${uri.port}; only ports 443 and 8443 are permitted.")
        }

        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            ?: return UrlValidationResult(false, "URL missing host.")

        if (isForbiddenInternalHostLiteral(host)) {
            return UrlValidationResult(false, "Blocked internal/metadata host '$host' (SSRF defense).")
        }

        val resolved = runCatching { dnsResolver(host) }.getOrNull()
        if (resolved == null || resolved.isEmpty()) {
            return UrlValidationResult(false, "DNS resolution failed or returned empty records for '$host' (fail-closed SSRF guard).")
        }

        val verifiedIps = mutableListOf<String>()
        for (addr in resolved) {
            if (isPrivateOrSpecialAddress(addr)) {
                val hint = if (isFakeIpOrCarrierCgnatAddress(addr)) {
                    "VPN Fake-IP or CGNAT address"
                } else {
                    "non-public IP"
                }
                return UrlValidationResult(false, "Blocked resolution to $hint ${addr.hostAddress} (SSRF defense).")
            }
            addr.hostAddress?.let { verifiedIps.add(it) }
        }

        return UrlValidationResult(true, resolvedAddresses = verifiedIps)
    }

    /**
     * Validates an explicit local AI server URL (Ollama, LM Studio, ComfyUI).
     * Consistent with `res/xml/network_security_config.xml`:
     * - Cleartext `http://` is permitted ONLY for loopback hosts (`localhost`, `127.0.0.1`, `::1`, `10.0.2.2`).
     * - Non-loopback RFC1918 LAN endpoints (`192.168.*`, `10.*`, `.local`, etc.) MUST use `https://`
     *   (e.g., Tailscale HTTPS, Caddy, or mkcert) AND require Allow-LAN to be enabled in Security Center.
     * - Fails CLOSED on null context for non-loopback LAN endpoints or when Allow-LAN is disabled.
     */
    fun validateLocalAiEndpointUrl(context: Context?, rawUrl: String): UrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return UrlValidationResult(false, "Endpoint URL is empty.")
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return UrlValidationResult(false, "Malformed endpoint URL.")

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https" && scheme != "http") {
            return UrlValidationResult(false, "Unsupported URL scheme '$scheme'. Use http:// or https://.")
        }
        if (!uri.rawUserInfo.isNullOrEmpty()) {
            return UrlValidationResult(false, "Embedded credentials in endpoint URLs are prohibited.")
        }

        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("[")?.removeSuffix("]")?.trimEnd('.')
            ?: return UrlValidationResult(false, "Endpoint URL missing host.")

        if (isCloudMetadataHost(host)) {
            return UrlValidationResult(false, "Cloud metadata IP/host is permanently blocked.")
        }

        if (scheme == "https") {
            return if (isPrivateHostOrLanLiteral(host)) {
                if (isLoopbackHost(host)) return UrlValidationResult(true)
                val allowLan = context?.let { AyraPreferences.isAllowLanEndpointsEnabled(it) } ?: false
                if (allowLan) UrlValidationResult(true)
                else UrlValidationResult(false, "LAN HTTPS endpoints are disabled in Security Center.")
            } else {
                validatePublicHttpsUrl(trimmed)
            }
        }

        // Cleartext HTTP: strictly restricted to loopback hosts permitted by network_security_config.xml
        if (isLoopbackHost(host)) {
            return UrlValidationResult(true)
        }

        return UrlValidationResult(
            false,
            "Cleartext HTTP is restricted to loopback (localhost / 127.0.0.1 / 10.0.2.2) per network_security_config.xml. Use HTTPS (e.g. Tailscale HTTPS, Caddy, or mkcert) for LAN endpoints."
        )
    }

    private fun isLoopbackHost(cleanHost: String): Boolean =
        cleanHost == "localhost" || cleanHost == "127.0.0.1" || cleanHost == "::1" || cleanHost == "10.0.2.2"

    private fun isCloudMetadataHost(cleanHost: String): Boolean =
        cleanHost.startsWith("169.254.") ||
            cleanHost == "metadata.google.internal" ||
            cleanHost == "metadata" ||
            cleanHost == "metadata.goog" ||
            cleanHost == "100.100.100.200" ||
            cleanHost == "fd00:ec2::254"

    private fun isPrivateHostOrLanLiteral(clean: String): Boolean =
        isForbiddenInternalHostLiteral(clean)

    private fun isValidIpv4Literal(cleanHost: String): Boolean {
        val parts = cleanHost.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            val n = part.toIntOrNull()
            n != null && n in 0..255
        }
    }

    private fun isFakeIpOrCarrierCgnatAddress(addr: InetAddress): Boolean {
        val b = addr.address
        if (addr is Inet4Address && b.size == 4) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
            if (b0 == 100 && b1 in 64..127) return true
        }
        return false
    }

    internal fun isForbiddenInternalHostLiteral(host: String): Boolean {
        val clean = host.trim().lowercase(Locale.ROOT).removePrefix("[").removeSuffix("]").trimEnd('.')
        if (clean.isEmpty()) return true
        if (clean == "localhost" ||
            clean.endsWith(".local") ||
            clean.endsWith(".internal") ||
            clean.endsWith(".localhost") ||
            clean.endsWith(".arpa") ||
            isCloudMetadataHost(clean) ||
            clean == "::1" ||
            clean == "::" ||
            clean == "0.0.0.0"
        ) {
            return true
        }
        if (clean.contains(':')) {
            // Apply IPv6 prefix checks strictly to colon-containing IPv6 literals, never domain names like fd-example.com or ffmpeg.org.
            if (clean.startsWith("fc") ||
                clean.startsWith("fd") ||
                clean.startsWith("fe80:") ||
                clean.startsWith("fec0:") ||
                clean.startsWith("64:ff9b:") ||
                clean.startsWith("2001:db8:") ||
                clean.startsWith("ff")
            ) {
                return true
            }
            val parsedV6 = runCatching {
                if (clean.all { it.isDigit() || it in 'a'..'f' || it == ':' || it == '.' }) {
                    InetAddress.getByName(clean)
                } else null
            }.getOrNull()
            if (parsedV6 != null && isPrivateOrSpecialAddress(parsedV6)) return true
            return false
        }
        if (isValidIpv4Literal(clean)) {
            val octets = clean.split('.').map { it.toInt() }
            return isPrivateOrSpecialIpv4Bytes(octets[0], octets[1], octets[2])
        }
        return false
    }

    private fun isCloudMetadataOrUnspecifiedAddress(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress) return true
        val bytes = addr.address
        if (addr is Inet4Address && bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0 || (b0 == 169 && b1 == 254)) return true
        }
        return false
    }

    private fun isPrivateOrSpecialIpv4Bytes(b0: Int, b1: Int, b2: Int): Boolean {
        if (b0 == 0 || b0 == 10 || b0 == 127) return true
        if (b0 == 169 && b1 == 254) return true
        if (b0 == 172 && b1 in 16..31) return true
        if (b0 == 192 && b1 == 168) return true
        if (b0 == 100 && b1 in 64..127) return true
        if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) return true
        if (b0 == 198 && (b1 == 18 || b1 == 19)) return true
        if (b0 == 198 && b1 == 51 && b2 == 100) return true
        if (b0 == 203 && b1 == 0 && b2 == 113) return true
        if (b0 >= 224) return true
        return false
    }

    internal fun isPrivateOrSpecialAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val b = addr.address
        if (addr is Inet4Address && b.size == 4) {
            return isPrivateOrSpecialIpv4Bytes(
                b[0].toInt() and 0xFF,
                b[1].toInt() and 0xFF,
                b[2].toInt() and 0xFF
            )
        }
        if (addr is Inet6Address && b.size == 16) {
            val bytes = IntArray(16) { b[it].toInt() and 0xFF }
            // IPv4-compatible ::/96 or IPv4-mapped ::ffff:0:0/96
            val zero10 = (0 until 10).all { bytes[it] == 0 }
            if (zero10 && ((bytes[10] == 0 && bytes[11] == 0) || (bytes[10] == 0xFF && bytes[11] == 0xFF))) {
                return isPrivateOrSpecialIpv4Bytes(bytes[12], bytes[13], bytes[14])
            }
            // NAT64 64:ff9b::/96 and 64:ff9b:1::/48
            if (bytes[0] == 0x00 && bytes[1] == 0x64 && bytes[2] == 0xFF && bytes[3] == 0x9B) {
                if (bytes[4] == 0x00 && bytes[5] == 0x01) return true
                return isPrivateOrSpecialIpv4Bytes(bytes[12], bytes[13], bytes[14])
            }
            // 6to4 2002::/16 (bytes 2..5 contain embedded IPv4)
            if (bytes[0] == 0x20 && bytes[1] == 0x02) {
                return isPrivateOrSpecialIpv4Bytes(bytes[2], bytes[3], bytes[4])
            }
            // Teredo 2001::/32
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x00 && bytes[3] == 0x00) return true
            // Documentation 2001:db8::/32
            if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0D && bytes[3] == 0xB8) return true
            // Discard 100::/64
            if (bytes[0] == 0x01 && (1..7).all { bytes[it] == 0 }) return true
            // ULA fc00::/7
            if ((bytes[0] and 0xFE) == 0xFC) return true
            // Link-local fe80::/10 & deprecated site-local fec0::/10
            if (bytes[0] == 0xFE && ((bytes[1] and 0xC0) == 0x80 || (bytes[1] and 0xC0) == 0xC0)) return true
            // Multicast ff00::/8
            if (bytes[0] == 0xFF) return true
        }
        return false
    }

    /**
     * Normalizes an OpenAI-compatible custom endpoint or proxy URL to its `/v1/models` health path
     * without duplicating `/v1/v1/models`.
     */
    @JvmStatic
    fun normalizeOpenAiModelsUrl(rawEndpoint: String): String {
        val clean = rawEndpoint.trim()
            .removeSuffix("/chat/completions")
            .trimEnd('/')
            .removeSuffix("/v1")
        return "$clean/v1/models"
    }

    /**
     * Validates HTTPS URL syntax and forbids internal host literals without performing DNS I/O.
     * Intended strictly for OkHttp clients already hardened with [PublicHttpsDnsGuard].
     */
    @JvmStatic
    fun validatePublicHttpsSyntaxOnly(rawUrl: String): UrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return UrlValidationResult(false, "URL is empty.")
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return UrlValidationResult(false, "Malformed URL syntax.")

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https") {
            return UrlValidationResult(false, "Blocked non-HTTPS scheme '${scheme ?: "none"}'. Only HTTPS is allowed for external requests.")
        }
        if (!uri.rawUserInfo.isNullOrEmpty() || uri.rawAuthority?.contains("@") == true) {
            return UrlValidationResult(false, "Embedded credentials in URLs are prohibited.")
        }
        if (uri.port !in setOf(-1, 443, 8443)) {
            return UrlValidationResult(false, "Blocked HTTPS port ${uri.port}; only ports 443 and 8443 are permitted.")
        }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            ?: return UrlValidationResult(false, "URL missing host.")
        if (isForbiddenInternalHostLiteral(host)) {
            return UrlValidationResult(false, "Blocked internal/metadata host '$host' (SSRF defense).")
        }
        return UrlValidationResult(true)
    }

    /**
     * Classifies transport, TLS, DNS, security-policy, and HTTP status failures into actionable diagnostics.
     */
    @JvmStatic
    @JvmOverloads
    fun classifyNetworkFailure(
        error: Throwable? = null,
        httpCode: Int? = null,
        responseSnippet: String? = null
    ): NetworkFailureClassification {
        val extractedCode = httpCode
            ?: (error as? RetryableHttpException)?.httpCode
            ?: (error as? NonRetryableHttpException)?.httpCode
        val rawDetail = SecurityAuditLogger.redactSensitiveText(
            error?.message ?: error?.javaClass?.simpleName ?: responseSnippet.orEmpty()
        )
        val lower = rawDetail.lowercase(Locale.ROOT)

        if (error is BlockedByPolicyException ||
            error is SecurityException ||
            lower.contains("ssrf") ||
            lower.contains("dns rebinding guard") ||
            lower.contains("blocked internal/metadata") ||
            lower.contains("blocked cloud metadata") ||
            lower.contains("fake-ip") ||
            lower.contains("local-only privacy mode")
        ) {
            val policyMsg = if ((error as? BlockedByPolicyException)?.reasonCode == "FAKE_IP_VPN" || lower.contains("fake-ip")) {
                "DNS returned a VPN Fake-IP or Private DNS address. Disable VPN Fake-IP mode or check DNS."
            } else {
                "Blocked by network security policy."
            }
            return NetworkFailureClassification(
                category = NetworkFailureCategory.SSRF_BLOCKED,
                userMessage = policyMsg,
                technicalDetail = rawDetail.ifBlank { "Security policy restriction" },
                retryable = false,
                httpCode = extractedCode
            )
        }

        if (error is SSLPeerUnverifiedException ||
            error is CertificateException ||
            error?.cause is CertificateException
        ) {
            return NetworkFailureClassification(
                category = NetworkFailureCategory.SSL_CERTIFICATE_FAILURE,
                userMessage = "SSL certificate verification failed. Connection refused for safety.",
                technicalDetail = rawDetail.ifBlank { "Certificate validation failure" },
                retryable = false,
                httpCode = extractedCode
            )
        }

        if (error is SSLHandshakeException || error is SSLException) {
            return NetworkFailureClassification(
                category = NetworkFailureCategory.TLS_HANDSHAKE_FAILURE,
                userMessage = "Secure TLS 1.2/1.3 handshake failed.",
                technicalDetail = rawDetail.ifBlank { "TLS handshake failure" },
                retryable = false,
                httpCode = extractedCode
            )
        }

        if (error is UnknownHostException || lower.contains("unable to resolve host")) {
            return NetworkFailureClassification(
                category = NetworkFailureCategory.DNS_FAILURE,
                userMessage = "DNS lookup failed. Check internet connection or Private DNS settings.",
                technicalDetail = rawDetail.ifBlank { "UnknownHostException" },
                retryable = true,
                httpCode = extractedCode
            )
        }

        if (error is SocketTimeoutException ||
            error is kotlinx.coroutines.TimeoutCancellationException ||
            lower.contains("timed out") ||
            lower.contains("timeout")
        ) {
            return NetworkFailureClassification(
                category = NetworkFailureCategory.TIMEOUT,
                userMessage = "Network request timed out waiting for server response.",
                technicalDetail = rawDetail.ifBlank { "SocketTimeoutException" },
                retryable = true,
                httpCode = extractedCode
            )
        }

        if (error is ConnectException || lower.contains("failed to connect") || lower.contains("connection refused")) {
            return NetworkFailureClassification(
                category = NetworkFailureCategory.CONNECTION_REFUSED,
                userMessage = "Remote host refused the connection or is unreachable.",
                technicalDetail = rawDetail.ifBlank { "ConnectException" },
                retryable = true,
                httpCode = extractedCode
            )
        }

        if (extractedCode != null) {
            return when (extractedCode) {
                401, 403 -> NetworkFailureClassification(
                    category = NetworkFailureCategory.AUTH_FAILURE,
                    userMessage = "Authentication or API key was rejected (HTTP $extractedCode).",
                    technicalDetail = rawDetail.ifBlank { "HTTP $extractedCode" },
                    retryable = false,
                    httpCode = extractedCode
                )
                429 -> NetworkFailureClassification(
                    category = NetworkFailureCategory.RATE_LIMITED,
                    userMessage = "Rate limit exceeded (HTTP 429). Retry after a brief pause.",
                    technicalDetail = rawDetail.ifBlank { "HTTP 429 Too Many Requests" },
                    retryable = true,
                    httpCode = extractedCode
                )
                400, 404, 410, 422 -> NetworkFailureClassification(
                    category = NetworkFailureCategory.CLIENT_ERROR,
                    userMessage = "Endpoint or model rejected request (HTTP $extractedCode).",
                    technicalDetail = rawDetail.ifBlank { "HTTP $extractedCode" },
                    retryable = false,
                    httpCode = extractedCode
                )
                502, 503, 504 -> NetworkFailureClassification(
                    category = NetworkFailureCategory.BACKEND_UNAVAILABLE,
                    userMessage = "Service temporarily unavailable (HTTP $extractedCode).",
                    technicalDetail = rawDetail.ifBlank { "HTTP $extractedCode" },
                    retryable = true,
                    httpCode = extractedCode
                )
                in 500..599 -> NetworkFailureClassification(
                    category = NetworkFailureCategory.PROVIDER_OUTAGE,
                    userMessage = "Upstream server error (HTTP $extractedCode).",
                    technicalDetail = rawDetail.ifBlank { "HTTP $extractedCode" },
                    retryable = true,
                    httpCode = extractedCode
                )
                else -> NetworkFailureClassification(
                    category = NetworkFailureCategory.UNKNOWN,
                    userMessage = "Unexpected HTTP $extractedCode response.",
                    technicalDetail = rawDetail.ifBlank { "HTTP $extractedCode" },
                    retryable = false,
                    httpCode = extractedCode
                )
            }
        }

        return NetworkFailureClassification(
            category = NetworkFailureCategory.UNKNOWN,
            userMessage = rawDetail.ifBlank { "Network connection failed." },
            technicalDetail = rawDetail,
            retryable = error is IOException,
            httpCode = null
        )
    }

    @JvmStatic
    fun isRetryableFailure(error: Throwable): Boolean {
        if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) return false
        if (error is BlockedByPolicyException || error is SecurityException) return false
        if (error is NonRetryableHttpException) return false
        if (error is IllegalArgumentException) return false
        if (error is SSLHandshakeException ||
            error is SSLPeerUnverifiedException ||
            error is CertificateException ||
            error is SSLException
        ) {
            return false
        }
        if (error is RetryableHttpException) {
            return error.httpCode == 429 || error.httpCode == 502 || error.httpCode == 503 || error.httpCode == 504
        }
        val msg = (error.message ?: "").lowercase(Locale.ROOT)
        if (msg.contains("ssrf") ||
            msg.contains("dns rebinding guard") ||
            msg.contains("blocked internal/metadata") ||
            msg.contains("blocked cloud metadata") ||
            msg.contains("fake-ip") ||
            msg.contains("local-only privacy mode")
        ) {
            return false
        }
        return error is SocketTimeoutException ||
            error is ConnectException ||
            error is UnknownHostException ||
            error is kotlinx.coroutines.TimeoutCancellationException ||
            error is IOException
    }

    /**
     * Executes [block] with bounded exponential backoff and jitter strictly for transient network
     * errors or retryable HTTP status codes (`429`, `502`, `503`, `504`).
     * Never retries 4xx non-429 errors, SSL/certificate failures, SecurityExceptions, or CancellationExceptions.
     */
    suspend fun <T> executeWithSafeRetry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 200L,
        maxDelayMs: Long = 2_000L,
        jitterFactor: Double = 0.25,
        sleeper: suspend (Long) -> Unit = { delay(it) },
        randomFactor: () -> Double = { Math.random() },
        block: suspend (attempt: Int) -> T
    ): T {
        val boundedAttempts = maxAttempts.coerceIn(1, 5)
        val safeInitial = initialDelayMs.coerceIn(0L, 5_000L)
        val safeMax = maxDelayMs.coerceAtLeast(safeInitial).coerceAtMost(10_000L)
        val clampedJitter = jitterFactor.coerceIn(0.0, 0.5)

        var lastError: Throwable? = null
        for (attempt in 1..boundedAttempts) {
            try {
                return block(attempt)
            } catch (e: Throwable) {
                if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) {
                    throw e
                }
                lastError = e
                if (attempt >= boundedAttempts || !isRetryableFailure(e)) {
                    throw e
                }
                val shift = (attempt - 1).coerceIn(0, 6)
                val baseDelay = (safeInitial * (1L shl shift)).coerceAtMost(safeMax)
                val jitterMultiplier = 1.0 + ((randomFactor().coerceIn(0.0, 1.0) * 2.0 - 1.0) * clampedJitter)
                val finalDelay = (baseDelay * jitterMultiplier).toLong().coerceIn(0L, safeMax)
                if (finalDelay > 0L) {
                    sleeper(finalDelay)
                }
            }
        }
        throw lastError ?: IOException("Retry budget exhausted.")
    }

    /**
     * Performs a real bounded DNS + TLS/HTTPS probe against [url].
     */
    @JvmOverloads
    suspend fun probeHttpsEndpoint(
        url: String,
        client: OkHttpClient = hardenedPooledClient,
        maxAttempts: Int = 2,
        validatePublicUrl: Boolean = true
    ): EndpointProbeResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val uri = runCatching { URI(url.trim()) }.getOrNull()
            ?: return@withContext EndpointProbeResult(
                ok = false,
                latencyMs = 0L,
                category = NetworkFailureCategory.CLIENT_ERROR,
                message = "Malformed probe URL."
            )

        val resolvedIps = if (validatePublicUrl) {
            val validation = validatePublicHttpsUrl(url)
            if (!validation.allowed) {
                val cls = classifyNetworkFailure(
                    error = if (validation.reason?.contains("DNS", ignoreCase = true) == true) {
                        UnknownHostException(validation.reason)
                    } else {
                        SecurityException(validation.reason ?: "Blocked probe URL")
                    }
                )
                return@withContext EndpointProbeResult(
                    ok = false,
                    latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                    category = cls.category,
                    message = cls.userMessage
                )
            }
            validation.resolvedAddresses
        } else {
            uri.host?.takeIf { it.isNotBlank() }?.let { host ->
                runCatching {
                    preferIpv4Order(InetAddress.getAllByName(host).toList()).mapNotNull { it.hostAddress }
                }.getOrDefault(emptyList())
            }.orEmpty()
        }

        try {
            val (code, tlsProto) = executeWithSafeRetry(maxAttempts = maxAttempts) {
                val req = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    resp.readBounded(maxBytes = 64 * 1024L, truncateOnOverflow = true)
                    val proto = resp.handshake?.tlsVersion?.javaName
                    when {
                        resp.isSuccessful -> resp.code to proto
                        resp.code == 429 || resp.code in 502..504 ->
                            throw RetryableHttpException(resp.code, "HTTP ${resp.code}")
                        else ->
                            throw NonRetryableHttpException(resp.code, "HTTP ${resp.code}")
                    }
                }
            }
            EndpointProbeResult(
                ok = true,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                httpCode = code,
                dnsAddresses = resolvedIps,
                tlsProtocol = tlsProto,
                category = NetworkFailureCategory.NONE,
                message = buildString {
                    append("HTTP $code")
                    if (!tlsProto.isNullOrBlank()) append(" • $tlsProto")
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val cls = classifyNetworkFailure(error = e)
            EndpointProbeResult(
                ok = false,
                latencyMs = (System.currentTimeMillis() - start).coerceAtLeast(1L),
                httpCode = cls.httpCode,
                dnsAddresses = resolvedIps,
                category = cls.category,
                message = cls.userMessage
            )
        }
    }
}
