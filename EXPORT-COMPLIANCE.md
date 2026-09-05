# US export compliance — encryption

**Product:** Lock Nest (`com.acesoftph.offlinepasswordwallet`)
**Assessed against:** 1.1.0 (versionCode 2), commit `68935ce`
**Last verified:** 2026-09-05

This document records why Lock Nest's use of encryption is believed to qualify
for mass-market treatment under the US Export Administration Regulations (EAR),
what evidence supports that, and which obligations are **not** discharged by
writing code. It exists so that the person ticking an export-compliance box in
Google Play Console — or filing a report next February — does not have to
re-derive any of it.

It is a technical record, not legal advice. The classification decision and any
filing are the publisher's, and are worth confirming with counsel.

---

## 1. Classification

| | |
|---|---|
| Starting classification | **ECCN 5D002** — "information security" software using symmetric crypto above 56 bits |
| Claimed classification | **ECCN 5D992.c** — mass market, via Note 3 to Category 5, Part 2 |
| Basis | Note 3 (the "Cryptography Note") |
| Licence requirement | No licence required (NLR) to non-embargoed destinations |
| CCATS obtained | None. Self-classified. |

### Why 5D002 is the starting point, not an exemption

Lock Nest encrypts with AES-256. That is far above the 56-bit symmetric
threshold in Category 5, Part 2, so **the software is controlled to begin
with.** Nothing here is decontrolled for being cryptographically weak, and any
statement to the contrary would be wrong.

Mass-market treatment is what moves it to 5D992.c — not weakness, and not
absence of encryption.

### Why Note 3 applies

Note 3 to Category 5, Part 2 releases items from 5A002/5D002 when they are
generally available to the public and meet each of its criteria:

- **Sold from stock at retail selling points without restriction** — distributed
  through Google Play to the general public; no vetting of purchasers, no
  negotiated terms.
- **Cryptographic functionality cannot be easily changed by the user** — the
  algorithms, key sizes, KDF iteration counts and modes are compile-time
  constants in `CryptoConstants.kt`. There is no setting, no configuration file
  and no plug-in mechanism that lets a user substitute an algorithm or supply
  their own. KDF parameters read from a *file* are range-checked and rejected
  outside `100_000..2_000_000` iterations and 256-bit key length, precisely so a
  crafted file cannot alter the effective cryptography.
- **Designed for installation by the user without further substantial supplier
  support** — installed from Play like any other app; there is no provisioning
  step, licence server, or support contract.

### Why Note 4 does *not* apply

Note 4 ("ancillary cryptography") covers items whose **primary function is
something other than information security**, where crypto merely supports that
primary function.

**Lock Nest cannot rely on Note 4.** A password vault's primary function *is*
information security. This is recorded explicitly because Note 4 is the note
most often cited in error by small publishers, and citing it here would
misdescribe the product. Note 3 is the correct and sufficient basis.

---

## 2. Cryptographic inventory

Every cryptographic operation in the app, as of the commit above.

| Purpose | Algorithm | Parameters | Published standard |
|---|---|---|---|
| Vault payload | AES-256-GCM | 128-bit tag, 96-bit IV; bound to `(formatVersion, vaultId, revision)` as AAD from format v2 | FIPS 197, NIST SP 800-38D |
| Data encryption key (DEK) | 256-bit random | `SecureRandom` | platform CSPRNG |
| Master-password KEK | PBKDF2-HMAC-SHA256 | 600,000 iterations, 256-bit output, 16-byte random salt | RFC 8018, NIST SP 800-132 |
| Recovery KEK (5 security answers) | PBKDF2-HMAC-SHA256 | 600,000 iterations, 256-bit output | RFC 8018, NIST SP 800-132 |
| Biometric KEK | AES-256-GCM | Android Keystore, `setUserAuthenticationRequired(true)`, randomized encryption required | FIPS 197, NIST SP 800-38D |
| Encrypted backup (`.opwbackup`) | AES-256-GCM under PBKDF2-HMAC-SHA256 | 600,000 iterations, fresh 16-byte salt, fresh 96-bit IV | as above |
| Entitlement cache integrity | HMAC-SHA256 | key held in Android Keystore | FIPS 198-1, FIPS 180-4 |
| Random salts, IVs, generated passwords | `java.security.SecureRandom` | no `setSeed`; rejection sampling to avoid modulo bias | NIST SP 800-90 (platform) |

All parameters are centralised in
`app/src/main/java/com/acesoftph/offlinepasswordwallet/crypto/CryptoConstants.kt`.

### No cryptographic implementation is shipped

This is the strongest fact supporting the certification, and stronger than
"we chose standard algorithms":

**The APK/AAB contains no cryptographic implementation whatsoever.** Every
operation is a JCA lookup resolved by the Android platform's own providers
(AndroidOpenSSL / Conscrypt, AndroidKeyStore):

```
Cipher.getInstance("AES/GCM/NoPadding")
SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
Mac.getInstance("HmacSHA256")
KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
KeyStore.getInstance("AndroidKeyStore")
```

The algorithms are not merely publicly available — they are the operating
system's, unmodified.

### Negative assertions

| Assertion | How it is verified |
|---|---|
| No bundled crypto library (BouncyCastle, libsodium, Tink, OpenSSL, …) | `gradle/libs.versions.toml` contains no crypto dependency |
| No native code | no `jniLibs` directory; no `ndk`, `externalNativeBuild` or `cmake` block in `app/build.gradle.kts` |
| No proprietary or non-published algorithm | every algorithm string in `app/src/main/` is a JCA standard name (see verification below) |
| No hand-rolled cipher, mode or KDF | no bit-level crypto primitives in the source; the only composition is standard multi-KEK key wrapping of one DEK |
| No asymmetric cryptography, PKI or certificate handling in app code | no RSA/EC/`Signature`/`KeyPairGenerator` usage |
| No cryptanalytic function | no password cracking, key recovery, or brute-force capability |
| No key escrow, key recovery by the vendor, or backdoor | there is no server, no account, and no vendor-held key; a forgotten master password is recoverable only via the on-device security answers |
| No quantum cryptography, no crypto for military or law-enforcement-specific use | not present |
| Crypto functionality not user-modifiable | compile-time constants; file-supplied KDF parameters range-checked |

### Reproducing the verification

```bash
# Every algorithm string and JCA lookup in production code
grep -rnE '"(AES|RSA|EC|PBKDF2|Hmac|SHA|MD5)[^"]*"|getInstance\(' app/src/main/java --include=*.kt

# No native / bundled crypto
find app/src -type d -name jniLibs -o -name "*.so"
grep -nE "ndk|externalNativeBuild|cmake|jni" app/build.gradle.kts

# No networking implemented by this app
grep -rnE "HttpURLConnection|OkHttp|Retrofit|Socket|URL\(" app/src/main/java --include=*.kt
```

At the assessed commit, the second and third commands return nothing.

---

## 3. Network behaviour

The app implements **no networking of its own** — no HTTP client, no sockets, no
TLS stack. No vault data, master password, security answer or backup is ever
transmitted anywhere.

`app/src/main/AndroidManifest.xml` declares no permissions at all. The merged
release manifest gains exactly these, all from libraries:

| Permission | Source |
|---|---|
| `android.permission.INTERNET` | Google Play Billing |
| `android.permission.ACCESS_NETWORK_STATE` | Google Play Billing |
| `com.android.vending.BILLING` | Google Play Billing |
| `android.permission.USE_BIOMETRIC`, `USE_FINGERPRINT` | AndroidX Biometric |

Any TLS involved in a purchase is performed inside Google's billing client and
the platform, not by this app.

---

## 4. Obligations that code cannot discharge

Ticking a box in Play Console does not satisfy these. Google is the distribution
channel; the publisher is the exporter.

### 4.1 Annual self-classification report

Relying on mass-market self-classification as 5D992.c generally carries a
reporting duty to BIS: an annual self-classification report, **due 1 February**,
covering items exported or reexported in the preceding calendar year, submitted
to BIS and to the ENC Encryption Request Coordinator (NSA), in the format of
Supplement No. 8 to Part 742.

The governing text is **15 CFR §742.15(b)** together with **§740.17(e)(3)**.
The scope of this requirement has been amended more than once — *read the
current text before each filing rather than relying on this summary.*

**Status: not yet filed.** The first report would cover calendar year 2026 if
Lock Nest is published in 2026, and would be due 1 February 2027.

### 4.2 Destination control

Country selection in Play Console belongs to the publisher. Comprehensively
embargoed destinations must remain deselected regardless of ECCN — currently
**Cuba, Iran, North Korea, Syria**, and the Russian-occupied regions of Ukraine
(Crimea, and the so-called DNR/LNR). See 15 CFR Part 746. Denied- and
restricted-party screening obligations under Part 744 also continue to apply.

### 4.3 Play Console declaration

Google Play requires the publisher to affirm compliance with US export law for
apps containing encryption. Sections 1–3 of this document are the supporting
record for that affirmation.

---

## 5. Maintenance

Re-verify this document — and re-run the commands in §2 — whenever any of the
following changes, because each can move the classification:

- A cryptographic algorithm, key size, mode or KDF is added or changed.
- **Any crypto library or native code is added**, which would end the "no
  bundled implementation" assertion in §2.
- Cryptographic behaviour becomes user-configurable, which would defeat Note 3.
- The app gains networking of its own, a server component, or an account system.
- A key escrow, key recovery or vendor-held-key mechanism is introduced.
- Distribution moves outside retail public availability (enterprise, negotiated,
  or vetted-customer distribution), which would defeat Note 3.

---

## 6. References

- 15 CFR Part 774, Commerce Control List, **Category 5 Part 2** — ECCNs 5A002,
  5D002, 5A992, 5D992
- **Note 3 to Category 5, Part 2** (Cryptography Note) — mass market
- **Note 4 to Category 5, Part 2** (ancillary cryptography) — *not applicable
  here; see §1*
- 15 CFR **§740.17** — License Exception ENC
- 15 CFR **§742.15** — encryption items, self-classification reporting
- 15 CFR **Part 746** — embargoes and other special controls
- 15 CFR **Part 744** — end-user and end-use based restrictions
- **Supplement No. 8 to Part 742** — self-classification report format
- Internal: `README.md` §"Encryption architecture", `CryptoConstants.kt`
