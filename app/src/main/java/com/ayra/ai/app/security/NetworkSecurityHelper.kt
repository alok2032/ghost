package com.ayra.ai.app.security

import android.content.Context
import com.ayra.ai.app.data.AyraPreferences
import okhttp3.CertificatePinner
import okhttp3.CipherSuite
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class UrlValidationResult(
    val allowed: Boolean,
    val reason: String? = null,
    val resolvedAddresses: List<String> = emptyList()
)

/**
 * Network & Transport Hardening Engine.
 *
 * Security Guarantees:
 * - Enforces TLS 1.3 (and TLS 1.2 AEAD suites fallback) via OkHttp [ConnectionSpec].
 * - Provides SPKI Certificate Pinning for first-party AYRA backend.
 * - Fails CLOSED on DNS resolution failures, malformed URLs, embedded credentials, non-443 ports,
 *   IPv4/IPv6 private, loopback, link-local, NAT64, 6to4, Teredo, documentation, ULA, and metadata IPs.
 * - Eliminates validation-vs-connect DNS rebinding TOCTOU via [PublicHttpsDnsGuard] bound on OkHttp clients.
 */
object NetworkSecurityHelper {

    private val sharedDispatcher: Dispatcher by lazy {
        Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
    }

    private val sharedConnectionPool: ConnectionPool by lazy {
        ConnectionPool(16, 5, TimeUnit.MINUTES)
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
                throw UnknownHostException("Blocked internal/metadata hostname: $hostname")
            }
            val addresses = Dns.SYSTEM.lookup(clean)
            if (addresses.isEmpty()) {
                throw UnknownHostException("Empty DNS resolution for $hostname")
            }
            for (addr in addresses) {
                if (isPrivateOrSpecialAddress(addr)) {
                    throw UnknownHostException(
                        "DNS rebinding guard blocked non-public address ${addr.hostAddress} for $hostname"
                    )
                }
            }
            return addresses
        }
    }

    /**
     * OkHttp [Dns] guard for explicit LAN/localhost clients: still blocks cloud metadata IPs
     * (`169.254.0.0/16`, `metadata.google.internal`, `fd00:ec2::254`) and unspecified `0.0.0.0`/`::`.
     */
    internal object LanDnsGuard : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val clean = hostname.trim().lowercase(Locale.ROOT).trimEnd('.')
            if (isCloudMetadataHost(clean)) {
                throw UnknownHostException("Blocked cloud metadata hostname: $hostname")
            }
            val addresses = Dns.SYSTEM.lookup(clean)
            for (addr in addresses) {
                if (isCloudMetadataOrUnspecifiedAddress(addr)) {
                    throw UnknownHostException("Blocked metadata/unspecified IP ${addr.hostAddress}")
                }
            }
            return addresses
        }
    }

    val hardenedPooledClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(sharedDispatcher)
            .connectionPool(sharedConnectionPool)
            .dns(PublicHttpsDnsGuard)
            .connectionSpecs(listOf(strictTlsConnectionSpec))
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Local/LAN client permitting explicit cleartext HTTP ONLY to verified loopback/LAN endpoints
     * (e.g. Ollama / ComfyUI on private subnet) when user explicitly enables LAN endpoints.
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
        if (uri.port != -1 && uri.port != 443) {
            return UrlValidationResult(false, "Only standard HTTPS port 443 is permitted.")
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
                return UrlValidationResult(false, "Blocked resolution to non-public IP ${addr.hostAddress} (SSRF defense).")
            }
            addr.hostAddress?.let { verifiedIps.add(it) }
        }

        return UrlValidationResult(true, resolvedAddresses = verifiedIps)
    }

    /**
     * Validates an explicit local AI server URL (Ollama, LM Studio, ComfyUI).
     * Fails CLOSED when [context] is null for non-loopback LAN endpoints or when Allow-LAN setting is disabled.
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
                val allowLan = context?.let { AyraPreferences.isAllowLanEndpointsEnabled(it) } ?: false
                if (allowLan || isLoopbackHost(host)) UrlValidationResult(true)
                else UrlValidationResult(false, "LAN endpoints are disabled in Security Center.")
            } else {
                validatePublicHttpsUrl(trimmed)
            }
        }

        if (isLoopbackHost(host)) return UrlValidationResult(true)

        val allowLan = context?.let { AyraPreferences.isAllowLanEndpointsEnabled(it) } ?: false
        if (!allowLan) {
            return UrlValidationResult(false, "Plaintext HTTP LAN endpoints are disabled in Security Center.")
        }

        val resolved = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        if (resolved == null || resolved.isEmpty()) {
            return UrlValidationResult(false, "DNS resolution failed for LAN endpoint '$host'.")
        }
        for (addr in resolved) {
            if (isCloudMetadataOrUnspecifiedAddress(addr)) {
                return UrlValidationResult(false, "Blocked metadata/unspecified IP ${addr.hostAddress}.")
            }
        }
        val isLanIp = resolved.all { it.isSiteLocalAddress } ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            host.endsWith(".local")
        return if (isLanIp) {
            UrlValidationResult(true)
        } else {
            UrlValidationResult(false, "Remote non-LAN URLs must use HTTPS.")
        }
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

    private fun isForbiddenInternalHostLiteral(host: String): Boolean {
        val clean = host.removePrefix("[").removeSuffix("]").trimEnd('.')
        if (clean == "localhost" ||
            clean.endsWith(".local") ||
            clean.endsWith(".internal") ||
            clean.endsWith(".localhost") ||
            clean.endsWith(".arpa") ||
            isCloudMetadataHost(clean) ||
            clean == "127.0.0.1" ||
            clean == "::1" ||
            clean == "::" ||
            clean == "0.0.0.0" ||
            clean == "10.0.2.2" ||
            clean.startsWith("127.") ||
            clean.startsWith("10.") ||
            clean.startsWith("192.168.") ||
            clean.startsWith("fc") ||
            clean.startsWith("fd") ||
            clean.startsWith("fe80:") ||
            clean.startsWith("64:ff9b:") ||
            clean.startsWith("2001:db8:") ||
            clean.startsWith("ff")
        ) {
            return true
        }
        if (clean.startsWith("172.")) {
            val second = clean.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        if (clean.startsWith("100.")) {
            val second = clean.split(".").getOrNull(1)?.toIntOrNull()
            if (second != null && second in 64..127) return true
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
}
