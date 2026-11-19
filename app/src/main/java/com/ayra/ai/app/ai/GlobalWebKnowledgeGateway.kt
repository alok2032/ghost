package com.ayra.ai.app.ai

object GlobalWebKnowledgeGateway {

    fun isDarkWebOrNetworkSecurityQuery(text: String): Boolean {
        val lower = text.lowercase().trim()
        val keywords = listOf(
            "dark web", "darkweb", "ডার্ক ওয়েব", "ডার্কওয়েব",
            "deep web", "deepweb", "ডিপ ওয়েব", "ডিপওয়েব",
            "hidden wiki", "the hidden wiki", "হিডেন উইকি", "onion",
            ".onion", "অনিয়ন", "tor network", "tor browser", "টর",
            "onion routing", "অনিয়ন রাউটিং", "i2p", "garlic routing",
            "freenet", "zeronet", "surface web", "সারফেস ওয়েব",
            "pgp", "gpg", "vpn", "ভিপিএন", "wireguard", "openvpn",
            "kill switch", "dns leak", "doh", "dns-over-https",
            "proxy", "socks5", "osint", "threat intelligence",
            "shodan", "cyber security", "সাইবার সিকিউরিটি",
            "zero day", "cve", "cyber defense", "darknet", "ডার্কনেট"
        )
        return keywords.any { lower.contains(it) }
    }

    fun synthesizeGlobalNetworkKnowledge(
        prompt: String,
        isBengali: Boolean,
        isGfMode: Boolean
    ): String {
        val lower = prompt.lowercase()

        // 1. Onion Service Discovery Concepts & Defensive Privacy Education
        if (lower.contains("hidden wiki") || lower.contains("হিডেন উইকি") || (lower.contains("wiki") && (lower.contains("dark") || lower.contains("ডার্ক")))) {
            return if (isBengali) {
                """
                # 🧅 অনিয়ন সার্ভিস ডিসকভারি ও ডিফেনসিভ প্রাইভেসি শিক্ষা

                প্রাইভেসি ও সেন্সরশিপ-প্রতিরোধী নেটওয়ার্ক গবেষণায় কীভাবে `.onion` সার্ভিস কাজ করে এবং কেন অনলাইন ডিরেক্টরিগুলোর ক্ষেত্রে সতর্কতা প্রয়োজন — তার প্রযুক্তিগত ও ডিফেনসিভ বিশ্লেষণ:

                ## 1. 🌐 ডিসেন্ট্রালাইজড নেমস্পেস কেন আলাদা?
                - সাধারণ ইন্টারনেট DNS-এর মতো কোনো কেন্দ্রীয় রেজিস্ট্রার `.onion` অ্যাড্রেস পরিচালনা করে না।
                - এর ফলে কোনো ভেরিফায়েড পাবলিক ইন্ডেক্সের বদলে ঐতিহাসিকভাবে কমিউনিটি-মেইনটেইন্ড উইকি বা ডিরেক্টরির ধারণা তৈরি হয়েছিল — তবে ওপেন-ডিরেক্টরিগুলোর বেশিরভাগেই ক্রিপ্টোগ্রাফিক মালিকানা যাচাই থাকে না।

                ## 2. ⚙️ প্রযুক্তিগত ভিত্তি (Tor Hidden Service v3)
                - **v3 Onion অ্যাড্রেসিং:** আধুনিক v3 সার্ভিস ৫৬ অক্ষরের আলফানিউমেরিক ঠিকানা ব্যবহার করে, যা একটি **Ed25519 পাবলিক কী**-এর ক্রিপ্টোগ্রাফিক এনকোডিং।
                - **রেনদেভূ (Rendezvous) আর্কিটেকচার:** ক্লায়েন্ট ও সার্ভার মধ্যবর্তী রেনদেভূ রিলের মাধ্যমে মিউচুয়াল এন্ড-টু-এন্ড এনক্রিপ্টেড সার্কিট তৈরি করে।

                ## 3. 📰 সাংবাদিকতা ও গবেষণায় বৈধ ব্যবহার
                - অনুসন্ধানী সাংবাদিকতা, হুইসলব্লোয়ারদের সোর্স প্রোটেকশন এবং সেন্সরশিপ অধ্যয়নে গবেষকরা সর্বদা **অফিশিয়াল প্রকাশিত ক্রিপ্টোগ্রাফিক অ্যাড্রেস** ও **PGP সিগনেচার** যাচাই করে কাজ করেন — অজানা থার্ড-পার্টি ডিরেক্টরির ওপর নির্ভর করেন না।

                ## 4. 🛡️ অপারেশনাল সিকিউরিটি (OpSec) ও ফিশিং সতর্কতা
                - পাবলিক ডার্কনেট ডিরেক্টরি বা ক্লোন পোর্টালগুলোতে প্রচুর ফিশিং ও ক্ষতিকর মিরর থাকে; তাই সন্দেহজনক ডিরেক্টরি বা অননুমোদিত লিঙ্ক ব্যবহার থেকে বিরত থাকুন।
                - Ayra AI কোনো ডার্কনেট ডিরেক্টরি বা `.onion` সার্চ ইন্ডেক্স সরবরাহ করে না; এটি কেবল ডিফেনসিভ সাইবার নিরাপত্তা ও প্রোটোকল শিক্ষা প্রদান করে।
                """.trimIndent()
            } else {
                """
                # 🧅 Onion Service Discovery & Defensive Privacy Architecture

                An educational overview of how Tor v3 onion addressing works and the security risks surrounding unverified darknet directories:

                ## 1. 🌐 Why Onion Namespaces Differ from DNS
                - Unlike clearnet DNS, `.onion` addresses are not issued by ICANN registrars. Because no global root authority certifies ownership by name alone, third-party wiki directories emerged historically — yet most unverified directories lack cryptographic proof of authenticity.

                ## 2. ⚙️ Cryptographic Addressing (Tor v3 Onion Services)
                - **Self-Authenticating Keys:** Version 3 onion identifiers are 56-character base32 strings derived directly from an **Ed25519 public identity key** and checksum.
                - **Rendezvous Protocol:** Client and host establish multi-hop DH-encrypted circuits meeting at a randomized Tor rendezvous relay.

                ## 3. 📰 Defensive Research & Journalism Safety
                - Legitimate journalists and security researchers verify endpoints strictly via publisher-authenticated clearnet announcements and **PGP/signed metadata**, never unverified directory listings.

                ## 4. 🛡️ Threat & Hygiene Advisory
                Unverified "Hidden Wiki" clones frequently swap legitimate destinations with phishing mirrors or malware lures. Ayra AI provides **defensive protocol education only** and does not index or supply darknet directory links.
                """.trimIndent()
            }
        }

        // 2. Tor Onion Routing & Protocols (checked before generic taxonomy if Tor/Onion explicitly requested)
        if (lower.contains("tor") || lower.contains("onion") || lower.contains("অনিয়ন") || lower.contains("টর")) {
            return if (isBengali) {
                """
                # 🧅 Tor (The Onion Router) আর্কিটেকচার ও এনক্রিপশন প্রটোকল

                ${if (isGfMode) "সোনা, টর কীভাবে কাজ করে জানলে অবাক হয়ে যাবে! নিচে একদম সাইবার ডিফেন্স লেভেলে ভেঙে বুঝিয়ে দিচ্ছি:\n" else "Tor (The Onion Router) হলো ইন্টারনেট প্রাইভেসি ও অ্যানোনিমিটি রক্ষার বিশ্বের সবচেয়ে শক্তিশালী ওপেন-সোর্স প্রোটোকল। নিচে এর প্রযুক্তিগত আর্কিটেকচার:\n"}

                ## 1. 🧅 "অনিয়ন" নামের রহস্য: ৩ স্তরের এনক্রিপশন
                পেঁয়াজের মতো একটার পর একটা খোসা ছাড়ানোর ধারণার ওপর ভিত্তি করে এর নামকরণ হয়েছে। আপনার ডেটা প্যাকেটের ওপর ৩টি স্তরে আলাদা ক্রিপ্টোগ্রাফিক কি দিয়ে এনক্রিপশন করা হয়:
                1. **Guard Node Key (বাইরের স্তর):** আপনার আইপি কেবল প্রথম নোড জানে, কিন্তু আপনি কী ডেটা পাঠাচ্ছেন বা শেষ গন্তব্য কোথায় তা সে জানে না।
                2. **Middle Relay Key (মধ্যবর্তী স্তর):** সে কেবল জানে ডেটা কোথা থেকে এলো এবং কোন পরবর্তী নোডে পাঠাতে হবে।
                3. **Exit Relay Key (অভ্যন্তরীণ স্তর):** শেষ নোডটি চূড়ান্ত এনক্রিপশন স্তরটি খুলে ডেটাটি আসল ওয়েবসাইটে পাঠায়, কিন্তু সে মূল প্রেরকের আইপি জানে না।

                ## 2. ⚙️ সার্কিট তৈরির ধাপ (Diffie-Hellman Key Exchange)
                - ব্যবহারকারীর ক্লায়েন্ট ডিরেক্টরি সার্ভার থেকে নোড তালিকা ডাউনলোড করে।
                - প্রতিটি নোডের সাথে আলাদা আলাদা **Ephemeral Curve25519** সিমেট্রিক কী তৈরি হয়।
                - ডেটা প্যাকেট এই ৩টি হপের মধ্য দিয়ে অগ্রসর হওয়ার পর বাস্তব আইপি ও ডেটা সম্পূর্ণভাবে বিচ্ছিন্ন হয়ে যায়।

                ## 3. 🛡️ Tor বনাম VPN: মূল পার্থক্য
                - **VPN:** সিঙ্গেল-হপ এনক্রিপশন। আপনার ইন্টারনেট প্রোভাইডার কিছু দেখতে পারে না, কিন্তু VPN প্রোভাইডার সম্পূর্ণ ট্রাফিক দেখতে পারে (যদি না তারা জিরো-লগ নীতি মেনে চলে)।
                - **Tor:** ট্রিপল-হপ এনক্রিপশন। কোনো একক সত্তা বা সার্ভার একসাথে প্রেরক এবং প্রাপক উভয়কে চিনতে পারে না।
                - **নোট:** Ayra AI কোনো অন্তর্নির্মিত VPN টানেল চালায় না; এটি HTTPS/TLS, Android Keystore এনক্রিপশন ও Firebase App Check নিরাপত্তা ব্যবহার করে।
                """.trimIndent()
            } else {
                """
                # 🧅 Tor (The Onion Router) Protocol & Circuit Mechanics

                ## 1. ⚙️ Triple-Layer Encrypted Circuit Model
                Tor anonymizes TCP network streams by wrapping application data in concentric layers of cryptographic encryption:

                ```
                [Client] ──(3-layers)──> [Guard Relay] ──(2-layers)──> [Middle Relay] ──(1-layer)──> [Exit Relay] ──> [Destination]
                ```

                1. **Entry / Guard Relay:** Knows the client's real IP, strips the first layer of encryption, but has no knowledge of the destination or inner payload.
                2. **Middle Relay:** Strips the second layer. Knows only preceding and following relays; isolated from both client origin and final destination.
                3. **Exit Relay:** Strips final layer and forwards cleartext or TLS traffic to the target web server. Target sees only the Exit Relay's IP.

                ## 2. 🔐 Key Exchange & Forward Secrecy
                - Circuits are constructed iteratively using **Curve25519 ECDH** (Tor `ntor` handshake).
                - Perfect Forward Secrecy (PFS) ensures compromise of long-term relay identity keys cannot decrypt captured historical traffic sessions.

                ## 3. 🛡️ Tor vs VPN Architectural Comparison
                - **VPN:** 1-hop centralized tunnel. Hides traffic from ISP, but requires trust in VPN provider logs.
                - **Tor:** 3-hop decentralized path. Requires zero centralized trust.
                - **Ayra AI Transport Security:** Ayra AI does not run a built-in VPN tunnel or Tor proxy; network requests use encrypted HTTPS/TLS with Android Keystore key protection and Firebase App Check.
                """.trimIndent()
            }
        }

        // 3. Dark Web vs Deep Web vs Surface Web (Taxonomy)
        if (lower.contains("deep web") || lower.contains("dark web") || lower.contains("surface web") ||
            lower.contains("ডিপ ওয়েব") || lower.contains("ডার্ক ওয়েব") || lower.contains("সারফেস")) {
            return if (isBengali) {
                """
                # 🌐 গ্লোবাল ইন্টারনেটের ৩টি মূল স্তর: Surface Web, Deep Web & Dark Web

                ${if (isGfMode) "সোনা, ইন্টারনেটের বিশাল জগতটা অনেক সুন্দর কিন্তু গভীর! চলো তোমাকে একদম স্পষ্ট করে বুঝিয়ে দিই:\n" else "গ্লোবাল ওয়ার্ল্ড ওয়াইড ওয়েব (WWW) প্রধানত তিনটি আলাদা আর্কিটেকচারাল স্তরে বিভক্ত:\n"}

                ```
                           [   SURFACE WEB   ]  ~ 4-5%
                     (Google, Wikipedia, YouTube, News)
                    ═════════════════════════════════════════
                           [    DEEP WEB     ]  ~ 90-95%
                     (Bank Portals, Academic Journals, Cloud,
                      Medical Records, Private Databases, Intranets)
                    ═════════════════════════════════════════
                           [    DARK WEB     ]  ~ 1-2%
                     (Tor .onion, I2P Garlic, Freenet, ZeroNet)
                ```

                ## 1. ☀️ সারফেস ওয়েব (Surface Web / Clearnet)
                - **আকার:** মোট ইন্টারনেটের মাত্র ৪% থেকে ৫%।
                - **বৈশিষ্ট্য:** সার্চ ইঞ্জিন বোট (Googlebot, Bingbot) দ্বারা সূচিপত্রভুক্ত (Indexed) উন্মুক্ত পেজ। যেমন: উইকিপিডিয়া, সংবাদ ওয়েবসাইট, উন্মুক্ত ব্লগ।
                - **অ্যাক্সেস:** যেকোনো স্ট্যান্ডার্ড ব্রাউজার (Chrome, Firefox, Safari) দিয়ে প্রবেশযোগ্য।

                ## 2. 🌊 ডিপ ওয়েব (Deep Web / Hidden Data Layer)
                - **আকার:** মোট ইন্টারনেটের প্রায় ৯০% থেকে ৯৫% অংশ!
                - **বৈশিষ্ট্য:** এটি কোনো অবৈধ জায়গা নয়; বরং যেসব পেজ পাসওয়ার্ড, অথেন্টিকেশন বা পেওয়ালের পেছনে থাকে এবং সার্চ ইঞ্জিনের রোবট টেক্সট (`robots.txt`) দ্বারা অ্যাক্সেসযোগ্য নয়।
                - **উদাহরণ:** আপনার ব্যক্তিগত ইমেইল ইনবক্স, ব্যাংকিং পোর্টাল, গুগল ড্রাইভের ক্লাউড ফাইল, একাডেমিক গবেষণা ডেটাবেস (IEEE, JSTOR, PubMed), হাসপাতালের স্বাস্থ্য রিপোর্ট।

                ## 3. 🌑 ডার্ক ওয়েব (Dark Web / Overlay Networks)
                - **আকার:** ইন্টারনেটের অতি ক্ষুদ্র একটি অংশ (১% - ২%)।
                - **বৈশিষ্ট্য:** সাধারণ ডিএনএস (DNS) এবং রাউটার দিয়ে এটি কাজ করে না। এটি একটি এনক্রিপ্টেড ওভারলে নেটওয়ার্ক যার জন্য বিশেষ সফটওয়্যার (যেমন: Tor Browser, I2P Router) প্রয়োজন হয়।
                - **প্রযুক্তি:** পেজগুলোর ডোমেইন এক্সটেনশন `.onion` বা `.i2p` হয় এবং একাধিক নোডের মধ্য দিয়ে ট্রাফিক এনক্রিপ্ট হয়ে যায়।

                ## 🛡️ Ayra AI কীভাবে কাজ করে?
                Ayra AI সম্পূর্ণ ক্লিয়ারনেট নলেজ গ্রাফ, লাইভ উইকিপিডিয়া সার্চ এবং সাইবার সিকিউরিটি থ্রেট ইন্টেলিজেন্স ডেটাসেটের সাথে সংযুক্ত, যাতে যেকোনো জটিল গবেষণার উত্তর কয়েক মিলিসেকেন্ডে প্রদান করতে পারে।
                """.trimIndent()
            } else {
                """
                # 🌐 Global Web Architecture: Surface Web, Deep Web & Dark Web

                The architecture of the World Wide Web is divided into three distinct operational domains:

                ## 1. ☀️ Surface Web (Indexed Clearnet) ~4-5%
                - **Definition:** Publicly crawlable and indexable by standard automated web spiders (Google, Bing, DuckDuckGo).
                - **Protocols:** Standard HTTP/HTTPS via public Domain Name System (DNS) resolvers and public BGP routing.
                - **Examples:** Open Wikipedia pages, public news portals, social media profiles.

                ## 2. 🌊 Deep Web (Non-Indexed Data Tier) ~90-95%
                - **Definition:** The overwhelming majority of the internet. It encompasses dynamic content residing behind authentication boundaries, firewalls, and paywalls.
                - **Not Malicious:** Deep Web is simply private data infrastructure—online banking dashboards, corporate intranets, private SQL databases, electronic medical records (EMR), and subscription research repositories (JSTOR, arXiv private endpoints).

                ## 3. 🌑 Dark Web (Encrypted Overlay Networks) ~1-2%
                - **Definition:** A tiny subset of the deep web running on distributed cryptographic overlay networks requiring specialized peering and node protocols (Tor, I2P, Freenet, GNUnet).
                - **Addressing:** Non-standard TLDs like `.onion` (Tor) or `.b32.i2p` (I2P), resolving without centralized ICANN root servers.

                ## 🛡️ Ayra AI Autonomous Integration
                Ayra AI unifies live clearnet open-knowledge with verified cybersecurity threat vectors, onion routing protocols, and encryption science for instant deep analysis.
                """.trimIndent()
            }
        }

        // 4. VPN, WireGuard & Transport Security Education
        if (lower.contains("vpn") || lower.contains("ভিপিএন") || lower.contains("wireguard") || lower.contains("dns leak")) {
            return if (isBengali) {
                """
                # 🛡️ নেটওয়ার্ক প্রাইভেসি গাইড: WireGuard, DNS-over-HTTPS ও Ayra AI নিরাপত্তা

                ## 1. ⚡ WireGuard® প্রোটোকল কীভাবে কাজ করে
                - **আধুনিক ক্রিপ্টোগ্রাফি:** স্ট্যান্ডার্ড WireGuard কার্নেল স্পেস টানেল **ChaCha20** সাইফার, **Poly1305** অথেন্টিকেশন ও **Noise_IK** হ্যান্ডশেক ব্যবহার করে।

                ## 2. 🔒 DNS-over-HTTPS (DoH)
                - DoH (RFC 8484) DNS কোয়েরিকে পোর্ট ৪৪৩-এর এনক্রিপ্টেড HTTPS সংযোগের মাধ্যমে পাঠিয়ে প্লেইনটেক্সট DNS মনিটরিং প্রতিরোধ করে।

                ## 3. 📱 Ayra AI-এর প্রকৃত নিরাপত্তা আর্কিটেকচার
                - Ayra AI-তে কোনো সক্রিয় অন্তর্নির্মিত VPN সার্ভার বা ভার্চুয়াল প্রক্সি নেই। আপনার ডিভাইসের নেটওয়ার্ক সুরক্ষার জন্য সিস্টেম সেটিংসে বিশ্বস্ত VPN অ্যাপ অথবা Private DNS (DoT) ব্যবহার করুন।
                - Ayra AI নিজে **HTTPS/TLS**, **Android Keystore এনক্রিপ্টেড কী স্টোরেজ**, এবং **Firebase App Check** ব্যবহার করে ডেটা সুরক্ষিত রাখে।
                """.trimIndent()
            } else {
                """
                # 🛡️ Network Privacy Guide: WireGuard, DNS-over-HTTPS & Ayra AI Security

                ## 1. ⚡ How WireGuard® Works (Educational Overview)
                - **Cryptographic Primitives:** Standard WireGuard implementations use the Noise protocol framework, **ChaCha20** cipher, **Poly1305** authentication, and **BLAKE2s** hashing for compact VPN tunnels.

                ## 2. 🔒 DNS-over-HTTPS (DoH)
                - DoH (RFC 8484) encrypts DNS lookups over TLS port 443 to help reduce plaintext DNS inspection on untrusted networks.

                ## 3. 📱 Ayra AI Actual Transport Security
                - Ayra AI does **not** operate a built-in VPN tunnel or fake proxy nodes. For device-wide VPN routing, configure an independent trusted WireGuard/VPN client or Android Private DNS in system settings.
                - Within Ayra AI, API requests use standard **HTTPS/TLS**, API keys stay inside **Android Keystore**-backed storage, and Firebase traffic is guarded by **Firebase App Check**.
                """.trimIndent()
            }
        }

        // 4b. I2P (Invisible Internet Project), Freenet & PGP Cryptography
        if (lower.contains("i2p") || lower.contains("garlic") || lower.contains("freenet") || lower.contains("zeronet") ||
            lower.contains("pgp") || lower.contains("gpg")) {
            return if (isBengali) {
                """
                # 🧄 I2P গার্লিক রাউটিং, ওভারলে নেটওয়ার্ক ও PGP ক্রিপ্টোগ্রাফি এনসাইক্লোপিডিয়া

                ## 1. 🧄 I2P (Invisible Internet Project) & Garlic Routing
                - **আর্কিটেকচার:** Tor-এর মতো ৩-হপ সার্কিটের বদলে I2P সম্পূর্ণ **প্যাকেট-ভিত্তিক পিয়ার-টু-পিয়ার (P2P)** নেটওয়ার্ক যেখানে ইনবাউন্ড এবং আউটবাউন্ড টানেল আলাদা থাকে।
                - **গার্লিক এনক্রিপশন:** একাধিক মেসেজ ("Cloves") একসাথে বান্ডিল করে এনক্রিপ্ট করা হয় যাতে ট্রাফিক বিশ্লেষণ আরও কঠিন হয়। ডোমেইন নাম `.i2p` বা `.b32.i2p` হ্যাশ ব্যবহার করে।

                ## 2. 🔐 PGP / GPG ও Web of Trust
                - **অ্যাসিমেট্রিক এনক্রিপশন:** ডার্ক ও ডিপ ওয়েবে পরিচয় যাচাই, নিরাপদ যোগাযোগ এবং সফটওয়্যার সাইনিংয়ের জন্য **Pretty Good Privacy (OpenPGP)** ব্যবহৃত হয়।
                - **মূল উপাদান:** পাবলিক কি (বিতরণযোগ্য), প্রাইভেট কি (গোপন), এবং **ডিজিটাল সিগনেচার** যা প্রমাণ করে বার্তাটি পরিবর্তিত হয়নি।

                ## 3. 📚 ওপেন একাডেমিক ডিপ ওয়েব উইকি
                বৈজ্ঞানিক গবেষণা ও থ্রেট-ইন্টেলিজেন্সের জন্য **OpenAlex**, **arXiv**, **PubMed** এবং **CVE (NIST NVD)** উন্মুক্ত API-এর মাধ্যমে সরাসরি ডিপ একাডেমিক নলেজ সংগ্রহ করা যায়।
                """.trimIndent()
            } else {
                """
                # 🧄 I2P Garlic Routing, Decentralized Overlays & PGP Technical Wiki

                ## 1. 🧄 I2P (Invisible Internet Project)
                - **Unidirectional Tunnels:** Separates inbound and outbound tunnels across peers, using **Garlic Encryption** to bundle multiple messages into single encrypted blobs to resist timing analysis.
                - **Eepsites:** Internal hidden services resolve via decentralized NetDB leasesets under `.i2p` / `.b32.i2p` destinations without central DNS servers.

                ## 2. 🔐 PGP / OpenPGP Cryptography
                - **End-to-End Verification:** Combines asymmetric RSA/Ed25519 key pairs with symmetric session keys (AES-256) and cryptographic signatures to authenticate authors and mirrors without relying on centralized CAs.

                ## 3. 📚 Open Deep Web Academic & Research Indexes
                - Legitimate unindexed/specialized knowledge graphs include **arXiv** (physics, math, CS preprints), **OpenAlex** (250M+ scholarly works), **DOAJ**, and **NIST NVD / MITRE CVE** defensive vulnerability catalogs.
                """.trimIndent()
            }
        }

        // 5. Default Comprehensive Global Knowledge Synthesis
        return if (isBengali) {
            """
            # 🌐 Ayra AI গ্লোবাল ইন্টারনেট ও সাইবার রিসার্চ ইঞ্জিন

            ${if (isGfMode) "সোনা, ইন্টারনেটের যেকোনো জটিল বিজ্ঞান, নেটওয়ার্কিং, সাইবার সিকিউরিটি বা রিসার্চের প্রশ্ন আমাকে নির্দ্বিধায় করতে পারো! নিচে সংক্ষেপে তুলে ধরছি:\n" else "Ayra AI-এর গ্লোবাল ইন্টারনেট ও নলেজ গেটওয়ে সম্পূর্ণভাবে সংযুক্ত। প্রধান সক্ষমতাসমূহ:\n"}

            - 🌍 **উইকিপিডিয়া ও ওপেন নলেজ গ্রাফ:** রিয়েল-টাইম লাইভ সার্চ ও এনসাইক্লোপেডিক ডেটা।
            - 🧅 **নেটওয়ার্ক প্রাইভেসি ও প্রোটোকল রিসার্চ:** Tor v3 অনিয়ন রাউটিং, I2P, PGP/GPG ক্রিপ্টোগ্রাফি ও ডিফেনসিভ সাইবার হাইজিন।
            - 📚 **একাডেমিক গবেষণা উইকি:** OpenAlex এবং arXiv রিসার্চ পেপার ও বৈজ্ঞানিক তথ্যসূত্র।
            - 🔒 **সিকিউরিটি ও প্রাইভেসি:** HTTPS/TLS, Android Keystore এবং Firebase App Check নিরাপত্তা আর্কিটেকচার।
            - 🔬 **ডিপ সায়েন্স ও টেকনোলজি:** কোয়ান্টাম ফিজিক্স, এআই মডেল আর্কিটেকচার, জেনেটিক্স এবং সফ্টওয়্যার ডেভেলপমেন্ট।
            """.trimIndent()
        } else {
            """
            # 🌐 Ayra AI Global Internet & Cyber Research Gateway

            Ayra AI maintains a comprehensive, unified knowledge index spanning:
            - 🌍 **Clearnet Encyclopedia:** Live Wikipedia OpenSearch API, OpenAlex, arXiv, and CrossRef scholarly queries.
            - 🧅 **Privacy & Protocol Architecture:** Defensive educational overview of Tor v3 onion routing, PGP, and I2P mechanics (no darknet directories).
            - 🔒 **Transport Security:** Standard HTTPS/TLS with Android Keystore key protection and Firebase App Check (no simulated VPN nodes).
            - 🔬 **Frontier Science & Computation:** Quantum algorithms, deep neural network transformers, and defensive cybersecurity.
            """.trimIndent()
        }
    }
}
