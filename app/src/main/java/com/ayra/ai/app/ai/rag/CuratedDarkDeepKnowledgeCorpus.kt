package com.ayra.ai.app.ai.rag

/**
 * Curated, specification-grounded technical knowledge corpus for Dark Web protocols
 * and Deep Web academic/cybersecurity ecosystems.
 *
 * Every entry cites genuine public standards or documentation URLs and contains zero
 * fabricated telemetry, fake darknet links, or invented numbers.
 */
object CuratedDarkDeepKnowledgeCorpus {

    data class CuratedEntry(
        val sourceKey: String,
        val title: String,
        val layer: String,        // DARK_WEB, DEEP_WEB, SURFACE_WIKI
        val providerName: String,
        val sourceUrl: String,
        val keywordsCsv: String,
        val body: String
    )

    val entries: List<CuratedEntry> = listOf(
        CuratedEntry(
            sourceKey = "curated:dark_web:tor_onion_v3",
            title = "Tor (The Onion Router) & v3 Onion Service Protocol",
            layer = "DARK_WEB",
            providerName = "Tor Project Specification",
            sourceUrl = "https://spec.torproject.org/rend-spec-v3.html",
            keywordsCsv = "tor,onion,v3,relay,guard,middle,exit,curve25519,ed25519,rendezvous,ntor,dark web,টর,অনিয়ন",
            body = """
                Tor (The Onion Router) anonymizes TCP streams across a volunteer overlay network using three-hop encrypted circuits: Entry Guard Relay, Middle Relay, and Exit Relay. Each hop negotiates ephemeral symmetric keys via the ntor Curve25519 Elliptic-Curve Diffie-Hellman (ECDH) handshake, providing Perfect Forward Secrecy (PFS). The Guard knows the client's IP but not the destination; the Middle knows only its adjacent relays; the Exit sees the outbound clearnet request but not the client's origin IP.

                Tor Version 3 (.onion) Hidden Services replace deprecated 16-character v2 RSA-1024 addresses with 56-character base32 Ed25519 cryptographic identity keys plus checksum and version byte. Connections between client and onion service remain entirely inside the Tor network via Introduction Points and a randomly selected Rendezvous Point, without exiting onto the public clearnet or using centralized DNS. Directory consensus health is published publicly via Tor Metrics Onionoo.
            """.trimIndent()
        ),
        CuratedEntry(
            sourceKey = "curated:dark_web:hidden_wiki_directories",
            title = "The Hidden Wiki, Darknet Indexing & Defensive OpSec",
            layer = "DARK_WEB",
            providerName = "Ahmia / SecureDrop Documentation",
            sourceUrl = "https://ahmia.fi/documentation/",
            keywordsCsv = "hidden wiki,dark web,darknet,ahmia,securedrop,tails,whonix,opsec,.onion,হিডেন উইকি,ডার্ক ওয়েব",
            body = """
                Traditional clearnet search crawlers (Google, Bing) do not index the .onion namespace. Historical community directories such as The Hidden Wiki and specialized indexers like Ahmia catalog publicly announced onion services. Legitimate public-interest onion services include journalism whistleblower intake systems (SecureDrop), investigative outlets (ProPublica, BBC, NYT onion mirrors), Tor Project mirrors, and cryptographic key servers.

                Operational Security (OpSec) warning: Numerous impersonator Hidden Wiki clones publish phishing links and deceptive mirrors. Security researchers isolate onion browsing inside ephemeral sandboxes (Tails OS or Whonix gateway isolation), verify cryptographic PGP signatures and official v3 fingerprints before trusting mirrors, disable risky browser scripts, and never log personal accounts over untrusted circuits.
            """.trimIndent()
        ),
        CuratedEntry(
            sourceKey = "curated:dark_web:i2p_garlic_routing",
            title = "I2P (Invisible Internet Project) & Garlic Routing Architecture",
            layer = "DARK_WEB",
            providerName = "I2P Technical Specification",
            sourceUrl = "https://geti2p.net/en/docs/how/tech-intro",
            keywordsCsv = "i2p,garlic routing,eepsite,netdb,leaseset,b32.i2p,p2p,tunnel,গার্লিক,আইটুপি",
            body = """
                I2P (Invisible Internet Project) is a peer-to-peer distributed anonymous overlay network designed primarily for internal hidden services called Eepsites (.i2p and base32 .b32.i2p hashes). Unlike Tor's bidirectional three-hop circuits, I2P constructs separate unidirectional inbound and outbound tunnels so reply paths differ from request paths.

                Garlic Routing bundles multiple encrypted messages ("cloves") with independent delivery instructions into a single garlic message, helping resist packet counting and timing correlation attacks. Peer routing metadata (RouterInfo and LeaseSets) is stored across a decentralized Kademlia-style floodfill network database (NetDB) rather than central directory authorities.
            """.trimIndent()
        ),
        CuratedEntry(
            sourceKey = "curated:dark_web:openpgp_trust",
            title = "OpenPGP / GPG Asymmetric Signatures & Web of Trust",
            layer = "DARK_WEB",
            providerName = "OpenPGP Standard (RFC 4880 / RFC 9580)",
            sourceUrl = "https://www.openpgp.org/about/",
            keywordsCsv = "pgp,openpgp,gpg,signature,fingerprint,web of trust,rsa,ed25519,aes-256,পিজিপি,এনক্রিপশন",
            body = """
                OpenPGP provides end-to-end cryptographic confidentiality and message/artifact authentication without depending on commercial Certificate Authorities (CAs). Hybrid encryption pairs asymmetric algorithms (Ed25519 / ECDH / RSA) with symmetric session ciphers (AES-256) to protect emails, software tarballs, security advisories, and onion mirror manifests.

                Under the decentralized Web of Trust model, researchers verify detached cryptographic signatures against trusted key fingerprints out-of-band to detect tampered releases, counterfeit repositories, or man-in-the-middle substitution.
            """.trimIndent()
        ),
        CuratedEntry(
            sourceKey = "curated:dark_web:wireguard_doh_security",
            title = "WireGuard Noise Protocol, DNS-over-HTTPS (RFC 8484) & Transport Security",
            layer = "DARK_WEB",
            providerName = "WireGuard & IETF RFC 8484",
            sourceUrl = "https://www.wireguard.com/protocol/",
            keywordsCsv = "wireguard,vpn,chacha20,poly1305,blake2s,noise,doh,dns-over-https,dns leak,ভিপিএন",
            body = """
                WireGuard implements a minimal-attack-surface cryptographic tunnel built on the Noise_IKpsk2 handshake framework, using Curve25519 for key exchange, ChaCha20 for symmetric encryption, Poly1305 for authenticated integrity, and BLAKE2s for hashing. By binding peers purely to static public keys and Cryptokey Routing tables, it achieves rapid roaming and stateless rejection of unauthenticated packets.

                DNS-over-HTTPS (DoH, IETF RFC 8484) and DNS-over-TLS (DoT) prevent ISP-level plaintext inspection or spoofing of domain lookups. Note: Ayra AI communicates exclusively over authenticated HTTPS/TLS with Android Keystore protection and does not run a built-in VPN or Tor daemon.
            """.trimIndent()
        ),
        CuratedEntry(
            sourceKey = "curated:dark_web:cve_owasp_defense",
            title = "Threat Intelligence, MITRE CVE / NIST NVD & OWASP Defense Framework",
            layer = "DARK_WEB",
            providerName = "NIST NVD & OWASP Foundation",
            sourceUrl = "https://nvd.nist.gov/vuln",
            keywordsCsv = "cve,cvss,nvd,owasp,cybersecurity,ssrf,prompt injection,threat intelligence,zero day,সাইবার,ভালনারেবিলিটি",
            body = """
                Defensive threat intelligence relies on standardized vulnerability tracking through MITRE CVE identifiers and NIST National Vulnerability Database (NVD) CVSS severity vectors (Attack Vector, Complexity, Privileges Required, User Interaction, Confidentiality/Integrity/Availability impact).

                Core mobile and AI defensive engineering (OWASP MASVS / OWASP LLM Top 10) enforces hardware-backed Android Keystore key wrapping, SQLCipher encrypted databases, strict SSRF protections blocking loopback/RFC1918 targets, certificate-verified TLS, prompt-injection boundary wrappers separating untrusted retrieved documents from system prompts, and non-privileged execution boundaries.
            """.trimIndent()
        ),
        CuratedEntry(
            sourceKey = "curated:deep_web:web_layers_taxonomy",
            title = "Global Web Architecture: Surface Web, Deep Web & Dark Web",
            layer = "DEEP_WEB",
            providerName = "Ayra AI Curated Web Taxonomy",
            sourceUrl = "https://en.wikipedia.org/wiki/Deep_web",
            keywordsCsv = "surface web,deep web,dark web,robots.txt,crawler,intranet,academic,ডিপ ওয়েব,ডার্ক ওয়েব,সারফেস ওয়েব",
            body = """
                The World Wide Web comprises three distinct architectural tiers:
                1. Surface Web (~4-5%): Publicly crawlable pages indexed by search spiders (Googlebot, Bingbot), accessible via standard DNS and HTTP/HTTPS (Wikipedia, public blogs, news).
                2. Deep Web (~90-95%): Non-indexed resources sitting behind authentication gates, dynamic database queries, paywalls, or robots.txt exclusions. Examples include private email, cloud storage, banking dashboards, hospital electronic health records (EHR), corporate intranets, and specialized scholarly/scientific repositories. The Deep Web is overwhelmingly legitimate infrastructure.
                3. Dark Web (~1-2%): A small subset requiring cryptographic overlay routers (Tor .onion, I2P .i2p, Freenet) with decentralized addressing outside standard ICANN DNS.
            """.trimIndent()
        ),
        CuratedEntry(
            sourceKey = "curated:deep_web:scholarly_apis",
            title = "Open Deep Web Academic Indexes: OpenAlex, arXiv, CrossRef & NIH PubMed",
            layer = "DEEP_WEB",
            providerName = "OpenAlex / arXiv / CrossRef / NCBI",
            sourceUrl = "https://docs.openalex.org/",
            keywordsCsv = "openalex,arxiv,crossref,pubmed,doi,pmid,research,paper,citation,inverted index,গবেষণা,চিকিৎসা",
            body = """
                While proprietary journals sit behind paywalls in the Deep Web, open research infrastructure provides direct programmatic access to hundreds of millions of peer-reviewed works and preprints:
                • OpenAlex indexes 250M+ scholarly publications, authors, citations, and abstracts encoded compactly as inverted word-position indexes.
                • arXiv hosts open-access STEM preprints in Physics, Mathematics, Computer Science, Cryptography, and Quantitative Biology via its OAI/Atom API.
                • CrossRef resolves authoritative DOI bibliographic records, publication dates, and publisher metadata.
                • NIH NCBI PubMed (E-utilities: esearch + esummary) indexes biomedical literature, clinical trials, genomics, and medical citations worldwide.
            """.trimIndent()
        ),
        CuratedEntry(
            sourceKey = "curated:deep_web:transformer_attention_rag",
            title = "Transformer Self-Attention, KV Caching & Hybrid Vector RAG Architecture",
            layer = "DEEP_WEB",
            providerName = "arXiv:1706.03762 / Deep Research Corpus",
            sourceUrl = "https://arxiv.org/abs/1706.03762",
            keywordsCsv = "transformer,attention,llm,rag,vector,cosine,bm25,embedding,kv cache,rope,ai,neural",
            body = """
                Modern Large Language Models build on scaled dot-product multi-head self-attention (Vaswani et al., arXiv:1706.03762): Attention(Q,K,V) = softmax((Q K^T) / sqrt(d_k)) V. Contemporary production architectures incorporate Rotary Position Embeddings (RoPE), Grouped-Query Attention (GQA) for KV-cache efficiency, Mixture-of-Experts (MoE) sparse routing, and quantized inference (GGUF/AWQ).

                Hybrid Retrieval-Augmented Generation (RAG) pairs dense semantic vector embeddings (cosine similarity over L2-normalized vectors stored in local SQLite/Room BLOBs) with sparse lexical scoring (BM25/TF-IDF) to ground LLM responses in verified source passages and cut hallucination rates even offline.
            """.trimIndent()
        ),
        CuratedEntry(
            sourceKey = "curated:deep_web:post_quantum_zero_trust",
            title = "NIST Post-Quantum Cryptography (ML-KEM / ML-DSA) & Mobile Zero-Trust",
            layer = "DEEP_WEB",
            providerName = "NIST Computer Security Resource Center",
            sourceUrl = "https://csrc.nist.gov/projects/post-quantum-cryptography",
            keywordsCsv = "post-quantum,pqc,ml-kem,kyber,ml-dsa,dilithium,keystore,sqlcipher,zero trust,fips,কোয়ান্টাম",
            body = """
                To counter "Harvest Now, Decrypt Later" threats from future fault-tolerant quantum computers (Shor's algorithm breaking RSA/ECDH), NIST standardized lattice-based Post-Quantum Cryptography (PQC): FIPS 203 ML-KEM (derived from CRYSTALS-Kyber) for key encapsulation and FIPS 204 ML-DSA (derived from CRYSTALS-Dilithium) plus FIPS 205 SLH-DSA (SPHINCS+) for digital signatures.

                In mobile zero-trust vaults, sensitive keys are sealed via Android Hardware Keystore AES-256-GCM Key-Encryption-Keys (KEK) wrapping per-asset Data-Encryption-Keys (DEK) alongside SQLCipher full-database page encryption.
            """.trimIndent()
        )
    )
}
