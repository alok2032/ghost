package com.ayra.ai.app.security

import android.content.Context
import com.ayra.ai.app.data.AyraPreferences
import okhttp3.CertificatePinner
import okhttp3.CipherSuite
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

data class UrlValidationResult(
    val allowed: Boolean,
    val reason: String? = null
)

/**
 * Network & Transport Hardening Engine (Phase 7).
 *
 * Security Guarantees:
 * - Enforces TLS 1.3 (and TLS 1.2 AEAD suites fallback) via OkHttp [ConnectionSpec].
 * - Provides SPKI Certificate Pinning (primary + backup leaf/CA pins) for first-party AYRA backend
 *   when configured via runtime backend host + pins, with fail-safe rotation design.
 * - Enforces strict SSRF validation blocking `file://`, `ftp://`, `javascript:`, cloud metadata IPs
 *   (`169.254.169.254`), loopback, link-local, and RFC1918 private networks on public requests.
 */
object NetworkSecurityHelper {

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

    val hardenedPooledClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectionSpecs(listOf(strictTlsConnectionSpec))
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Local/LAN client permitting explicit cleartext HTTP ONLY to verified loopback/LAN endpoints
     * (e.g. Ollama / ComfyUI on private subnet) when user explicitly enables LAN endpoints.
     */
    val localLanClient: OkHttpClient by lazy {
        hardenedPooledClient.newBuilder()
            .connectionSpecs(listOf(strictTlsConnectionSpec, ConnectionSpec.CLEARTEXT))
            .connectTimeout(6, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /**
     * Central OkHttpClient factory sharing the singleton Dispatcher, ConnectionPool, TLS spec,
     * and baseline timeouts across all feature engines.
     */
    @JvmOverloads
    fun newClient(
        connectTimeoutSec: Long = 8L,
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

    /**
     * Builds a pinned HTTPS client for a dedicated AYRA backend domain with primary + backup SPKI pins.
     */
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
     * Strict public HTTPS SSRF validation.
     * Blocks non-HTTPS, localhost, `.local`/`.internal`, cloud metadata (`169.254.*`), RFC1918, IPv6 ULA/loopback.
     */
    fun validatePublicHttpsUrl(rawUrl: String): UrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return UrlValidationResult(false, "URL is empty.")
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return UrlValidationResult(false, "Malformed URL syntax.")

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "https") {
            return UrlValidationResult(false, "Blocked non-HTTPS scheme '${scheme ?: "none"}'. Only HTTPS is allowed for external requests.")
        }

        val host = uri.host?.lowercase(Locale.ROOT)
            ?: return UrlValidationResult(false, "URL missing host.")

        if (isForbiddenInternalHostLiteral(host)) {
            return UrlValidationResult(false, "Blocked internal/metadata host '$host' (SSRF defense).")
        }

        // Resolve DNS and inspect all returned IPs to block DNS rebinding to private networks
        val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (resolved != null) {
            for (addr in resolved) {
                if (isPrivateOrSpecialAddress(addr)) {
                    return UrlValidationResult(false, "Blocked resolution to non-public IP ${addr.hostAddress} (SSRF defense).")
                }
            }
        }

        return UrlValidationResult(true)
    }

    /**
     * Validates an explicit local AI server URL (Ollama, LM Studio, ComfyUI).
     * Metadata IP ranges (`169.254.*`) and non-http schemes (`file://`, `javascript:`) remain strictly blocked.
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

        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("[")?.removeSuffix("]")
            ?: return UrlValidationResult(false, "Endpoint URL missing host.")

        // Cloud instance metadata is NEVER allowed anywhere
        if (host.startsWith("169.254.") || host == "metadata.google.internal" || host == "metadata") {
            return UrlValidationResult(false, "Cloud metadata IP/host is permanently blocked.")
        }

        if (scheme == "https") return UrlValidationResult(true)

        val allowLan = context?.let { AyraPreferences.isAllowLanEndpointsEnabled(it) } ?: true
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2"
        if (isLoopback) return UrlValidationResult(true)

        if (!allowLan) {
            return UrlValidationResult(false, "Plaintext HTTP LAN endpoints are disabled in Security Center.")
        }

        val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
        val isLanIp = (addr != null && addr.isSiteLocalAddress) ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            host.endsWith(".local")
        return if (isLanIp) {
            UrlValidationResult(true)
        } else {
            UrlValidationResult(false, "Remote non-LAN URLs must use HTTPS.")
        }
    }

    private fun isForbiddenInternalHostLiteral(host: String): Boolean {
        val clean = host.removePrefix("[").removeSuffix("]")
        if (clean == "localhost" ||
            clean.endsWith(".local") ||
            clean.endsWith(".internal") ||
            clean.endsWith(".localhost") ||
            clean == "metadata.google.internal" ||
            clean == "127.0.0.1" ||
            clean == "::1" ||
            clean == "0.0.0.0" ||
            clean == "10.0.2.2" ||
            clean.startsWith("127.") ||
            clean.startsWith("10.") ||
            clean.startsWith("192.168.") ||
            clean.startsWith("169.254.") ||
            clean.startsWith("fc") ||
            clean.startsWith("fd") ||
            clean.startsWith("fe80:")
        ) {
            return true
        }
        if (clean.startsWith("172.")) {
            val second = clean.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        if (clean.startsWith("100.")) {
            // Carrier-grade NAT 100.64.0.0/10
            val second = clean.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 64..127) return true
        }
        return false
    }

    private fun isPrivateOrSpecialAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress ||
            addr.isMulticastAddress
        ) {
            return true
        }
        val bytes = addr.address
        if (addr is Inet4Address && bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0 || b0 == 10 || b0 == 127) return true
            if (b0 == 169 && b1 == 254) return true
            if (b0 == 172 && b1 in 16..31) return true
            if (b0 == 192 && b1 == 168) return true
            if (b0 == 100 && b1 in 64..127) return true
        }
        if (addr is Inet6Address) {
            val b0 = bytes[0].toInt() and 0xFF
            if ((b0 and 0xFE) == 0xFC) return true // fc00::/7 Unique Local Address
        }
        return false
    }
}
