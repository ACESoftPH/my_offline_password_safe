# Lock Nest

A **local-only, offline** password manager for Android. No server, no account, no
cloud, no SQL database. Everything lives in a single encrypted file in the app's
private storage, and nothing a user stores is ever transmitted. The only network
access in the app is Google Play's billing client, used solely to sell and
restore the optional capacity upgrades.

- Kotlin · Jetpack Compose · Material 3 · single Activity · manual DI
- `minSdk 26`, `targetSdk 35`, `compileSdk 35`
- AES-256-GCM authenticated encryption + PBKDF2-HMAC-SHA256 key derivation
- Android Keystore-backed, biometric-gated unlock (optional)
- Five-question master-password **reset** (never "recovery of the old password")
- Portable **encrypted backup** (`.opwbackup`) + restore, incl. onto a new phone
- Semicolon-delimited CSV import/export via the Storage Access Framework
- Inactivity auto-lock + lock-on-background
- Unit + Robolectric + Compose UI tests

---

## 1. Building & testing

```bash
# Debug APK
./gradlew assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk

# Release APK, for sideloading (R8 + resource shrinking, lint-vital)
./gradlew assembleRelease          # -> app/build/outputs/apk/release/app-release.apk

# Release App Bundle, for Google Play
./gradlew bundleRelease            # -> app/build/outputs/bundle/release/app-release.aab

# JVM + Robolectric unit tests (178 tests)
./gradlew testDebugUnitTest

# Android lint
./gradlew lintDebug

# Instrumented UI tests (needs a connected device / emulator)
./gradlew connectedDebugAndroidTest
```

No `INTERNET` (or any network / storage / location) permission is present in the
merged manifest — only `USE_BIOMETRIC` / `USE_FINGERPRINT` (pulled in by
`androidx.biometric`, guarded by `uses-feature ... required=false`).

### Release signing

Signing is optional and driven by a git-ignored `keystore.properties` in the
project root:

```properties
storeFile=keystore/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Generate a key with:

```bash
keytool -genkeypair -v -keystore keystore/release.jks -storetype PKCS12 \
  -alias <alias> -keyalg RSA -keysize 4096 -validity 10000
```

Both `keystore.properties` and `keystore/` are git-ignored, so a fresh clone
builds without anyone's private key — it just produces an unsigned release
artifact. **Keep the keystore and its passwords backed up**: on Google Play this
is the *upload key*, and losing it means asking Google to reset it before you can
publish another update.

### Play requirements this build already satisfies

- `applicationId` is `com.acesoftph.offlinepasswordwallet` (Play rejects
  `com.example.*`).
- `targetSdk 35`, meeting Play's current target-API requirement.
- **16 KB page size**: the two bundled native libraries
  (`libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`) have
  `p_align = 0x4000` LOAD segments and the APK passes
  `zipalign -c -P 16 4`, which Play requires for apps targeting Android 15+.
- The R8 mapping file is embedded in the bundle
  (`BUNDLE-METADATA/…/proguard.map`), so Play deobfuscates crash reports without
  a separate upload.

---

## 2. Application architecture

```
com.acesoftph.offlinepasswordwallet
├── crypto/            VaultCrypto, SecureRandomProvider, SecurityAnswers,
│                      CryptoConstants, EncryptedBlob, Base64Util, errors
├── data/
│   ├── model/         VaultDocument / VaultEntry / VaultField (decrypted, in-memory)
│   │                  EncryptedVaultFile / *Dto (on-disk envelope), ImportMode
│   ├── storage/       VaultCodec (seal/unseal + wrap/unwrap), VaultFileStore (atomic I/O)
│   ├── backup/        BackupCodec (portable encrypted-backup format), BackupManager
│   └── repository/    VaultRepository (single source of decrypted state + writes)
├── security/          MasterPasswordManager, RecoveryManager, RecoveryRateLimiter,
│                      KeyManager (Keystore), BiometricAuthenticator, AppLockManager
├── password/          PasswordGenerator, PasswordStrength
├── importexport/      Csv (parser/writer), CsvImporter, CsvExporter
├── entitlement/       SubscriptionTier, ProductCatalog, EntitlementManager,
│                      EntitlementStore (HMAC-tagged cache), BillingRepository
├── settings/          SettingsRepository (DataStore), AppSettings
├── ui/                MainActivity, WalletRoot (nav), screens/, components/, theme/
└── di/                ServiceLocator
```

Cryptographic logic is fully separated from UI. `VaultCrypto` and `VaultCodec`
are pure, do no I/O, and are unit-tested on a plain JVM (no Android, no
Robolectric).

### State model

`VaultRepository.state` is a `StateFlow<VaultState>`:

- `Uninitialized` — no vault file exists (first run).
- `Locked` — a vault file exists but nothing is decrypted in memory.
- `Unlocked(entries)` — decrypted; a snapshot of entries is exposed.

`WalletRoot` observes this and, whenever it flips to `Locked` / `Uninitialized`,
wipes the navigation back stack and routes to an auth screen. The decrypted vault
can never sit behind the lock screen.

---

## 3. Storage architecture

```
<app private files>/vault/
├── vault.json           encrypted vault envelope (see §4)
├── integrity.json       last written vaultId + revision, for rollback detection (no secrets)
├── biometric.json       DEK wrapped by an Android Keystore key (only if biometrics on)
└── recovery_state.json  recovery failed-attempt counter + lockout deadline (no secrets)
```

- Location is **internal app storage** (`Context.filesDir`). It is not
  world-readable and not reachable by ordinary file browsing or `adb pull` on a
  non-rooted device.
- **OS backup / device-transfer is fully disabled**: `android:allowBackup="false"`
  plus `res/xml/backup_rules.xml` (API ≤ 30) and
  `res/xml/data_extraction_rules.xml` (API ≥ 31) that exclude every domain. The
  vault is intentionally **not** portable through Android backup.
- Writes are **atomic and crash-safe** (`VaultFileStore.write`):
  1. serialize → write to `vault.json.tmp`
  2. `flush()` + `FileDescriptor.sync()` the file, then fsync the directory
  3. re-read and re-parse the temp file to prove it loads (and the `vaultId` matches)
  4. atomically `renameTo` the real file (with a copy-then-delete fallback)

  The only existing good file is replaced solely in step 4, so power loss at any
  point leaves either the old file or the fully-written new file — never a
  truncated vault.
- The vault file is **never silently recreated**. If it fails to parse you get a
  `VaultFormatException`; if it fails to decrypt/authenticate you get the exact
  message *"Unable to unlock the password vault. The vault file may be corrupted
  or the password may be incorrect."* The app does not overwrite it with an empty
  vault.

---

## 4. Encryption architecture

> US export control: every algorithm below is a published standard resolved by
> the Android platform's own JCA providers — the app bundles no cryptographic
> implementation. See [EXPORT-COMPLIANCE.md](EXPORT-COMPLIANCE.md) for the ECCN
> classification, the evidence, and the obligations that code cannot discharge.

### The envelope (`vault.json`)

```jsonc
{
  "formatVersion": 2,
  "kdf": {
    "algorithm": "PBKDF2WithHmacSHA256",
    "masterIterations": 600000,
    "recoveryIterations": 600000,
    "keyLengthBits": 256
  },
  "masterSaltB64":   "…16 random bytes…",
  "recoverySaltB64": "…16 random bytes…",
  "wrappedKeyMaster":   { "ivB64": "…12 bytes…", "ciphertextB64": "…DEK + GCM tag…" },
  "wrappedKeyRecovery": { "ivB64": "…12 bytes…", "ciphertextB64": "…DEK + GCM tag…" },
  "payload":            { "ivB64": "…12 bytes…", "ciphertextB64": "…vault JSON + GCM tag…" },
  "securityQuestions": [ "…the five fixed question texts (not answers)…" ],
  "vaultId": "uuid",
  "revision": 7,                  // monotonic write counter, part of the payload AAD
  "createdAtEpochMillis": 0,
  "modifiedAtEpochMillis": 0
}
```

**Header parameters are validated before use.** `formatVersion`, the KDF
algorithm, both iteration counts, the key length and both salts come out of a
file that an attacker may control. They are range-checked on every read
(iterations must be 100,000–2,000,000) *before* anything reaches the KDF, so a
hostile file can neither pin the CPU for hours (a denial of service that locks
the owner out) nor silently drop the KDF below the documented floor. Out-of-range
values are a `VaultFormatException`, never an honoured parameter. The same check
guards `.opwbackup` files.

### Payload binding and rollback detection (format v2)

The payload's AES-GCM is authenticated over associated data
`opw-vault-payload|v<version>|<vaultId>|<revision>`, and `revision` increments on
every persisted write. Because all wrappers protect the *same, stable* DEK, an
attacker with one-time write access could otherwise paste a `payload` blob
captured from an earlier version of the same file back over the current one — it
would still decrypt cleanly and silently revert the vault (reinstating a deleted
credential, undoing a post-breach rotation). With the AAD in place that splice is
a hard authentication failure. The AAD deliberately excludes the salts and
wrapped keys, so changing the master password or the security answers does not
invalidate the payload.

`VaultFileStore` additionally records the last written `vaultId` + `revision` in
`vault/integrity.json` and refuses to open a vault file that is behind that mark
(`VaultRollbackException`). **Honest limit:** an attacker who can write
`vault.json` can usually also write `integrity.json`, so this catches stale
copies, sync conflicts, partial writes and casual tampering — not a privileged
attacker. Real anti-rollback needs a hardware-backed monotonic counter.

v1 files (no AAD, no revision) are still readable and are upgraded to v2 on the
next write.

### Keys

| Key | How it is obtained | What it protects |
|-----|--------------------|------------------|
| **DEK** (Data Encryption Key), 256-bit | `SecureRandom` at vault creation; never stored in the clear | AES-256-GCM seal of the serialized vault JSON (`payload`) |
| **Master KEK** | `PBKDF2(masterPassword, masterSalt, 600 000)` | AES-256-GCM wrap of the DEK → `wrappedKeyMaster` |
| **Recovery KEK** | `PBKDF2(join(normalized 5 answers), recoverySalt, 600 000)` | AES-256-GCM wrap of the DEK → `wrappedKeyRecovery` |
| **Biometric KEK** | AES-256-GCM key generated **inside Android Keystore**, `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)` | AES-256-GCM wrap of the DEK → `biometric.json` |

Every wrapper protects the **same DEK**. Consequences:

- Unlocking = derive/obtain a KEK → GCM-unwrap the DEK → GCM-decrypt the payload.
  A wrong password/answer fails the GCM tag check on the *wrap*, so it is rejected
  before the payload is touched.
- **Changing the master password or the answers only re-wraps the DEK** (new salt,
  new IV). The large `payload` ciphertext is not re-encrypted, and the operation
  is a single atomic file write.
- Knowing `vault.json` reveals nothing without a KEK: the payload and both
  wrapped DEKs are AES-256-GCM ciphertext with random IVs.

### AES-GCM rules enforced in code

- One **fresh random 96-bit IV per encryption**, generated inside
  `VaultCrypto.encrypt` — callers cannot pass or reuse an IV.
- 128-bit GCM tag; authentication failure throws `AeadDecryptionException`
  (never a silent fallback).
- No ECB, no unauthenticated mode, no home-made primitives. Only
  `javax.crypto` / Android Keystore.
- `SecureRandom` (AndroidOpenSSL CSPRNG) for all salts, IVs, the DEK, and
  generated passwords. `java.util.Random` / `Math.random()` are never used for
  anything security-relevant.

### Why PBKDF2 and not Argon2id?

The spec allows PBKDF2-HMAC-SHA256 "with a strong iteration count" if Argon2id is
"impractical because of dependency/security concerns". Argon2 on Android/JVM means
bundling a native library. For an offline, security-sensitive app whose main
selling point is being *small and auditable*, minimising the native dependency
surface was judged the better trade-off. Mitigations:

- 600 000 iterations (OWASP 2023 floor for PBKDF2-HMAC-SHA256).
- The KDF parameters live in the vault header, so a future `formatVersion` can
  raise the count (or switch to Argon2id) and migrate existing vaults on unlock.

---

## 5. Master-password handling

- Taken from the UI as text, converted to a `CharArray` at the call boundary, and
  zeroed by the caller after use. It is **never** written to disk, SharedPreferences,
  DataStore, logs, `Bundle`, `Intent` extras, or `SavedStateHandle`.
- Used only to derive the Master KEK. The app has no code path that stores or
  returns the master password.
- Policy (`PasswordStrength.masterPolicyError`): **≥ 10 characters** and **≥ 3
  character classes**. No artificial maximum length. A live strength meter
  (rough Shannon-style bit estimate) is shown but never blocks beyond the policy.
- First run requires: create password → confirm → configure all five security
  answers → **only then** is the encrypted vault created.

### Change master password (Settings → Security)

1. Verify the current password by attempting a real unwrap (fails →
   `AeadDecryptionException`).
2. Derive a new Master KEK from the new password + a new random salt.
3. Re-wrap the DEK; atomic file write.
4. The old password no longer unwraps anything.

---

## 6. Security questions & master-password **reset**

The five questions are fixed (exactly these, no more, no fewer):

1. What was the name of your first school?
2. What was the name of your favorite pet?
3. What is your mother's maiden name?
4. What is your father's middle name?
5. In what year did you graduate from college?

### Answer normalization (deliberate, documented, stable)

`SecurityAnswers.normalize`:

1. Unicode **NFKC** normalization.
2. Trim leading/trailing whitespace.
3. Collapse internal whitespace runs to a single ASCII space.
4. **Lowercase** with `Locale.ROOT` — **case is ignored**.

The five normalized answers are joined with the ASCII Unit Separator (`0x1F`,
which cannot occur in normalized text) and that string is the PBKDF2 passphrase.

### Why the original master password is never shown

A correctly built password manager **cannot** show your old master password
because it never stores it — only a KDF-derived key that wraps the DEK. The app
therefore uses the words **"Reset your master password"**, never "recover".

### Reset flow (`RecoveryManager`)

```
five answers ─▶ normalize ─▶ PBKDF2 ─▶ Recovery KEK
             ─▶ GCM-unwrap wrappedKeyRecovery  ── tag fails ▶ "answers incorrect" (vault untouched)
             ─▶ DEK recovered  ─▶ vault unlocked via recovery
             ─▶ user picks a NEW master password
             ─▶ DEK re-wrapped under the new Master KEK (new salt) ─▶ atomic write
             ─▶ continue using Lock Nest
```

- Wrong answers **never touch the vault file**, so a failed attempt cannot
  destroy data.
- **Rate limiting** (`RecoveryRateLimiter`, persisted to `recovery_state.json` so
  an app restart can't reset it): 4 free attempts, then escalating lockouts
  30 s → 2 m → 10 m → 30 m → 1 h. A successful verification clears the counter.

  The answers are the lowest-entropy way into the vault, so the throttle **fails
  closed**:
  - a state file that exists but is **corrupt or truncated is treated as maximum
    penalty**, not as a clean slate — wiping the counter is never a win for the
    attacker;
  - an **in-memory tally** runs alongside the file, so making the file read-only
    (silently failing every write) cannot disable the throttle;
  - the deadline is enforced against **both** the wall clock (survives reboot)
    and `SystemClock.elapsedRealtime` (immune to clock changes), taking whichever
    is longer, and a wall clock that has moved *backwards* since the lockout was
    armed re-arms the full penalty instead of releasing.
- The vault is unlocked by the recovery key only for the duration of the reset
  screen. If the user leaves before choosing a new master password, the screen
  re-locks the vault on dispose, so a half-finished reset can never leave a
  decrypted vault behind an auth screen.
- The old master password is never derived, displayed, or logged.

### Change security answers (Settings → Security)

Requires the current master password (verified by a real unwrap), then re-derives
the Recovery KEK from the new answers + a new salt and re-wraps the DEK
atomically. Old answers stop working; the master password is unaffected.

**Warning shown during setup:** answers based on personal facts are far lower
entropy than a random recovery key, so this path is the weakest link — it is
rate-limited and you should treat the answers as sensitive as the password.

---

## 7. CSV format

- **Delimiter is `;` (semicolon)**, not comma — this is the primary/default
  format, matching the supplied `PasswordSafe_template.csv`.
- Template header / default fields:
  `Title;Category;Username;Password;Website;Comments`
- The field count is **not** hard-coded to six. Row 1 of an imported CSV is
  always treated as the field definition; extra columns (e.g. `PIN`,
  `Account Number`) automatically become **custom fields**.

### Parser (`Csv`, `importexport/`)

A hand-written RFC-4180-style state machine (not `split(";")`), unit-tested for:
semicolon delimiters, quoted fields, semicolons inside quotes, doubled quotes
inside fields, newlines inside quoted fields, LF / CR / CRLF line endings, empty
values, a leading UTF-8 BOM, and arbitrary Unicode. Blank header cells become
`Column N`; duplicate header names are disambiguated (`Title`, `Title (2)`, …) so
one entry never carries two identically-named fields.

Per RFC 4180 a quote only opens a quoted section at the **start of a field**;
anywhere else it is ordinary data. That matters for real-world imports: a value
like `pa"ss` from a manager that doesn't quote its output would otherwise put the
parser into quoted mode and swallow every following delimiter and newline,
silently collapsing the rest of the file into one field.

**Spreadsheet formula injection.** A CSV value beginning with `=`, `+`, `-`, `@`,
TAB or CR is executed as a formula by Excel / LibreOffice / Sheets. Export
prefixes such values with an apostrophe (the standard "this is text" marker) and
import strips it back off, so a Lock Nest export → import round
trip is lossless while the exported file is safe to open in a spreadsheet. An
apostrophe that isn't followed by one of those characters is left alone.

### Import (`Menu → Import / Export → Import CSV`)

- File chosen via Storage Access Framework (`ACTION_OPEN_DOCUMENT`). No storage
  permission.
- The file is read **fully into memory**, parsed, and shown as
  *"Found N entries and M fields. Import?"* with the field list.
- Two modes: **Add to vault** (default, append) or **Replace entire vault**
  (behind a second explicit confirmation dialog). Existing entries are never
  overwritten without confirmation.
- On commit the entries are added to the in-memory vault and **immediately
  re-encrypted** (atomic write). No plaintext copy is kept.

### Export (`Menu → Import / Export → Export CSV`)

- Requires ticking *"I understand the exported CSV is plaintext"* first.
- Columns = union of all field names (the six template fields first, then custom
  fields in first-seen order); missing values export as empty.
- Written straight to a user-chosen SAF `CREATE_DOCUMENT` URI. The app keeps no
  copy and never transmits it.

**The CSV export is intentionally plaintext** — CSV interoperability requires it.
The app says so plainly and never pretends otherwise. See §10.

---

## 7b. Encrypted backup & restore

For moving to a new phone, or recovering after the app/device is lost or
compromised, Lock Nest can export a **portable encrypted backup** and restore
from it. This is separate from CSV — the backup is never plaintext.

Menu: `Import / Export → Export encrypted backup` and
`Import / Export → Restore from encrypted backup` (also in `Settings → Data`).
"Restore" is additionally offered on the **Unlock** screen and the **first-run
Setup** screen, so a fresh install can be restored without any prior vault.

### File format (`*.opwbackup`)

A small JSON envelope (magic `OPW-ENCRYPTED-BACKUP`, `formatVersion` 1):

```jsonc
{
  "magic": "OPW-ENCRYPTED-BACKUP",
  "formatVersion": 1,
  "kdf": { "algorithm": "PBKDF2WithHmacSHA256", "iterations": 600000, "keyLengthBits": 256 },
  "saltB64": "…16 random bytes…",
  "payload": { "ivB64": "…12 bytes…", "ciphertextB64": "…VaultDocument JSON + GCM tag…" },
  "entryCount": 42,               // non-sensitive, shown on the restore screen
  "createdAtEpochMillis": 0,
  "appVersionName": "1.0.0"
}
```

- `payload` is **AES-256-GCM** ciphertext of the serialized `VaultDocument`
  (entries + fields), under a key = `PBKDF2-HMAC-SHA256(backupPassphrase,
  saltB64, 600 000)`. Fresh random salt per export; fresh random 96-bit IV per
  export (generated inside `VaultCrypto.encrypt`).
- The backup passphrase is **independent** of the master password, the security
  answers, and any Android Keystore key. Nothing device-bound is in the file, so
  it restores on any device.
- The backup contains **only** the vault document. It does **not** contain the
  master password, the security-question answers, or the biometric key.
- Wrong passphrase / tampering → `BackupDecryptionException`; a non-backup file →
  `BackupFormatException`. Neither ever touches the existing vault.

### Export (`BackupManager.exportBytes`)

Requires the vault unlocked. Prompts for a backup passphrase + confirmation
(subject to the same ≥10-char / ≥3-class policy as the master password) with a
strength meter, then writes the file to a user-chosen SAF `CREATE_DOCUMENT` URI
(`lock-nest-YYYY-MM-DD.opwbackup`). The app keeps no copy and never
transmits it.

### Restore (`BackupManager.previewAndDecrypt` → `restoreAsNewVault` / `mergeIntoUnlockedVault`)

1. Pick the file (SAF `OPEN_DOCUMENT`, read into memory only) + enter the backup
   passphrase → decrypt → preview *"N entries · created … · app …"*.
2. Then:
   - **Fresh install / locked vault**: choose a **new master password** and
     **new security answers**; the app builds a brand-new `vault.json` around the
     restored entries (`VaultCodec.createVault`) via an atomic write. If a vault
     already existed on the device, a strong confirmation dialog is required
     first, and biometric login is turned off (the restored vault has a new DEK,
     so the old device-bound biometric wrapping is stale).
   - **Vault already unlocked**: optionally just **add** the backup's entries to
     the current vault (re-encrypted immediately), or take the replace path
     above. Imported entries whose id already exists get a **fresh id**, so
     restoring a backup of the *current* vault cannot produce two entries sharing
     one id (which would make `delete` remove both and `edit` only ever reach the
     first).

Losing the backup passphrase makes that backup unrecoverable — by design.

---

## 8. Auto-lock & lifecycle

`AppLockManager` (time source: `SystemClock.elapsedRealtime`, monotonic, counts
device sleep):

- Timeout options (Settings → Security → Auto-Lock Timeout): 30 s, 1 m, 2 m,
  5 m (**default**), 10 m, 30 m, Never.
- `ServiceLocator.bindAutoLock` drives the manager from `VaultRepository.state`:
  the timer is armed on every `Locked → Unlocked` transition and disarmed on the
  way back. This bridge is the thing that makes auto-lock work at all — without
  it the manager's `unlocked` flag never flips, `onUserInteraction()` no-ops, the
  monitor exits immediately and a decrypted vault would sit in memory forever. It
  has a dedicated regression test (`AutoLockWiringTest`).
- `MainActivity.onUserInteraction()` feeds every touch / key / nav event to the
  manager, resetting the idle timer.
- A 1-second monitor coroutine locks the vault when
  `now - lastInteraction ≥ timeout`.
- `WalletApplication` observes `ProcessLifecycleOwner`: on background it records
  the time; on foreground, if the elapsed background time ≥ timeout, it locks
  immediately.
- "Locking" means `VaultRepository.lock()`: drop the `VaultDocument`, the DEK and
  the envelope references, best-effort zero the DEK bytes, publish `Locked`, and
  the UI falls back to the unlock screen. Sensitive screens are left; nothing
  decrypted is reachable until re-authentication.

**Honest limitation:** Android does not guarantee an app can terminate its own
process, and this app does **not** claim to. It clears references and returns to
authentication; it cannot guarantee every byte is gone from RAM (see §9).

`FLAG_SECURE` is applied **synchronously at the top of `onCreate`**, before
anything can be drawn, and only relaxed later if the "Block screenshots" setting
(default on) turns out to be off. Reading that preference is asynchronous, so
starting secure is what keeps the first frames — and the recents thumbnail — from
being capturable on a cold start.

All vault operations (`unlock*`, `createVault`, `changeMasterPassword`, every
entry mutation, backup export/restore) run on `Dispatchers.Default`, never on the
caller's dispatcher. Each performs a 600,000-iteration PBKDF2 and/or an fsync'd
write; on the Main thread that is hundreds of milliseconds to several seconds of
frozen UI — an ANR on mid-range devices.

---

## 9. Memory security & its limits

- Decrypted vault data exists only while `Unlocked`; it is dropped on lock.
- Password/answer inputs are handled as `CharArray` at call boundaries and zeroed
  after use; PBKDF2 `PBEKeySpec.clearPassword()` is called; derived key byte
  arrays are `fill(0)`-ed after use.
- **JVM `String` immutability**: values that reach a Compose `TextField`, JSON
  serialization, or the clipboard are `String`s. Kotlin/JVM strings are immutable
  and their backing `char[]` cannot be reliably wiped; the GC may keep copies
  until collected, and the OS may page memory. Memory clearing here **reduces
  the window**, it does not eliminate it. This is a fundamental JVM constraint,
  not a bug.
- No decrypted content is placed in `Bundle`, `Intent` extras, `SavedStateHandle`
  or `rememberSaveable`. Edit-screen fields use plain `remember`; the Activity
  sets `configChanges` so rotation does not recreate the screen, and after real
  process death the vault is locked and re-authentication is required.
- No logging of any kind in the codebase (`grep` for `Log.` / `println` returns
  nothing). Release builds additionally strip `android.util.Log` and
  `PrintStream` calls via R8 `-assumenosideeffects`.

---

## 10. Clipboard (§9 of the spec)

`ClipboardUtil` is the only copy path:

- API 33+ : the clip is flagged `EXTRA_IS_SENSITIVE`, so the OS clipboard preview
  does not display the value.
- After a configurable delay (default 30 s, Settings slider) the app clears the
  clipboard **if our value is still the current clip** — `clearPrimaryClip()` on
  API 28+, overwrite with a space on API 26–27. If someone else has replaced the
  clipboard in the meantime, we leave it alone.
- Once a clear has been promised, a following **non-sensitive** copy does not
  cancel it: whatever we put on the clipboard next is still cleared on schedule.
  (Previously, copying a username right after a password silently dropped the
  guarantee the snackbar had just shown the user.) A newer sensitive copy resets
  the window rather than inheriting the old deadline.
- Passwords are never logged.

**Limitation:** another app or a clipboard-manager may read the value before it
is cleared; Android gives apps no way to prevent that.

---

## 11. Password generator

`PasswordGenerator` (`SecureRandom` only):

- Length slider **8–64**, default **20**; the current value is shown
  (`Password Length: 24`).
- Character sets: lowercase `a-z`, uppercase `A-Z`, digits `0-9`, and — only when
  "Use special characters" is ON — exactly `!@#$%^&*()_-+=<>.?{[}]~|` and nothing
  else.
- Guarantees per generated password: ≥ 1 lowercase, ≥ 1 uppercase, ≥ 1 digit, and
  ≥ 1 special **iff** specials are enabled; **only** characters from the enabled
  sets.
- The guaranteed characters are placed first, then the rest is filled from the
  full enabled pool, then the whole array is **Fisher–Yates shuffled with
  `SecureRandom`** so there is no positional pattern.
- The generator opens with a password already generated; **Generate Again**
  re-rolls. It is never mandatory — the Password field is always a normal,
  manually-editable text field.

Defaults (length, specials on/off) are configurable in Settings.

---

## 12. Biometric login

- Off by default. Toggle in Settings → Security → Biometric login.
- Uses AndroidX `BiometricPrompt` with `BIOMETRIC_STRONG` (class 3) only. **No
  custom fingerprint/face code exists**; the OS does all matching and never
  exposes raw biometric data to the app.
- Enabling: generate the Keystore key → `BiometricPrompt` authorizes an
  encrypt-mode `Cipher` → wrap the current DEK → store `biometric.json` (written
  through a temp file + fsync + atomic rename, so a crash mid-write cannot leave
  a blob that parses but fails to decrypt).
- Unlocking: `BiometricPrompt` authorizes a decrypt-mode `Cipher` (bound to the
  stored IV) → unwrap the DEK → unlock. The negative button is
  **"Use master password"**.
- Revocable: disabling deletes the Keystore key **and** the wrapped blob.
- **Nothing escapes the unlock path.** Generating a key under an existing alias
  replaces it, so every enable failure (prompt cancelled, error, write failure)
  turns the setting back off and destroys the key material — the toggle can never
  read "on" while no usable wrapped key exists. On the unlock side, a corrupt or
  unparseable `biometric.json`, a bad IV, or a GCM tag that no longer verifies are
  all reported as `BiometricKeyInvalidatedException` rather than escaping as an
  unhandled exception. That path is auto-launched on every app start, so an escape
  there would have crashed the app on launch, repeatedly, instead of falling back
  to the master password.
- Invalidation handled: new biometric enrollment, removed biometrics, lock-screen
  changes and Keystore key invalidation all cause a
  `BiometricKeyInvalidatedException`; the app then disables biometric login,
  deletes the key material, and falls back to master-password unlock, inviting
  you to re-enable.
- If biometrics are unavailable/none-enrolled, the option is simply not offered;
  master password always works.

---

## 13. Threat model

**In scope / mitigated**

| Threat | Mitigation |
|--------|-----------|
| Lost/stolen locked device, attacker reads `vault.json` | AES-256-GCM payload + wrapped DEKs; only a PBKDF2 KEK (600k iters) or a Keystore-gated key can unwrap. Offline guessing is bounded by master-password entropy. |
| Tampering with the vault file | GCM authentication fails → explicit corruption/incorrect-password error; no silent acceptance. |
| Splicing an old `payload` back over a current header (silent rollback) | Payload AAD binds it to `(formatVersion, vaultId, revision)`; the spliced blob fails the tag check. A revision high-water mark also refuses a stale whole file. Partial mitigation — see §14. |
| Hostile KDF parameters in a vault/backup file (CPU-pinning DoS, or a silently weakened KDF) | Iterations, algorithm, key length and salts are range-checked before reaching the KDF; out-of-range is a format error. |
| Bypassing the recovery throttle by wiping/locking its state file, or by moving the clock | Corrupt state = maximum penalty; in-memory tally floors the count; deadline enforced on both wall and monotonic clocks. |
| A CSV export detonating in a spreadsheet (formula injection) | Values starting with `= + - @` TAB CR are neutralized on export and restored on import. |
| Shoulder-surfing / screen capture | Passwords masked with `*` (fixed width, so the mask does not leak the real length); `FLAG_SECURE` on by default (blocks screenshots, screen recording, recents preview). |
| App left open | Inactivity auto-lock + lock-on-background; references cleared. |
| Clipboard scraping | Sensitive-flagged clips + timed auto-clear. |
| OS backup exfiltration | Backup and device-transfer fully disabled for all domains. |
| Network exfiltration | No `INTERNET` permission; nothing to exfiltrate over. |
| Stolen `.opwbackup` file | AES-256-GCM under a dedicated PBKDF2 (600k) backup passphrase, independent of (and typically stronger than) the security answers; contains no master password / answers / keystore material. |
| Brute-forcing recovery answers | Persistent, escalating rate limiting; answers never leave the device. |
| Weak/duplicate KDF outputs | Unique random salt per KEK; unique random IV per encryption (enforced in code). |

**Out of scope / NOT protected — see §14.**

---

## 14. Security Limitations

No password manager can protect your data if:

- **The device itself is compromised** — a rooted/jailbroken device, a malicious
  custom ROM, or a kernel-level exploit can read another app's memory and private
  files regardless of app-level encryption.
- **Malware has sufficient privileges** — an accessibility-service abuser, a
  screen-reader trojan, a keylogger, or a clipboard-scraping app running with the
  right permissions can capture your master password as you type it or entries as
  you view them.
- **You export a plaintext CSV and expose it** — CSV export is deliberately
  unencrypted for interoperability. Anyone who obtains that file can read every
  password in it. Delete exports you don't need; never sync them to the cloud.
- **The master password is weak** — the whole scheme's strength ceiling is your
  master password's entropy. PBKDF2 slows guessing; it does not fix a guessable
  password.
- **The security-question answers are guessed** — they are based on personal
  facts (lower entropy, sometimes publicly discoverable). Someone who knows you,
  or who researches you, plus enough time against the rate limiter, can reset
  your master password. This is why setup warns that recovery is the weakest
  link.

Additional constraints:

- **JVM memory cannot be reliably scrubbed** (see §9). Clearing shortens the
  exposure window; it cannot guarantee erasure.
- **`SecureRandom` quality** depends on the device's OS CSPRNG.
- **Android Keystore** hardware-backing varies by device; on some devices the key
  is software-emulated, weakening the biometric path's tamper resistance (the
  master-password path is unaffected).
- **The clipboard** may be read by other apps before auto-clear.
- **Rollback protection is partial.** The revision high-water mark lives in
  `vault/integrity.json`, next to the vault. An attacker who can write one can
  usually write the other, so this reliably catches stale copies, sync conflicts,
  partial writes and casual tampering — not a privileged attacker. Genuine
  anti-rollback needs a hardware-backed monotonic counter, which Android does not
  expose to ordinary apps. The payload AAD is *not* partial: splicing an old
  payload into a current header always fails, regardless of attacker privilege.
- **The recovery throttle's state file can still be deleted outright** by an
  attacker who already has read/write access to app-private storage — that is
  read as a genuine first run. Such an attacker can also read the encrypted vault
  directly, so the throttle is not the binding constraint in that scenario.
- Using encryption **does not by itself make the app secure**. The security model
  is exactly what is described in this document — its strength is bounded by the
  master password, the device's integrity, and the choices above.

---

## 15. Backup considerations

- The app does **not** synchronize with any cloud service and requests no network
  access. It is entirely offline.
- Android Auto Backup / cloud backup / device-to-device transfer are disabled for
  every data domain, so the encrypted vault and Keystore-wrapped key material are
  not silently copied off the device.
- The supported way to move data between installs/devices is the **encrypted
  backup** (§7b): a `.opwbackup` file whose contents are AES-256-GCM encrypted
  under a dedicated backup passphrase (PBKDF2-HMAC-SHA256, 600 000 iterations).
  Store the file **and** its passphrase somewhere safe; the file is only as
  strong as that passphrase, and losing the passphrase makes it unrecoverable.
  The app writes it only where you choose and never uploads it.
- A **plaintext CSV export** (§7, §14) also exists for interoperability with other
  tools — that one is *not* encrypted and is clearly warned as such.

---

## 15b. Editions, entry limits & monetization

Lock Nest is free to use and monetized by **one-time** purchases of extra vault
capacity. No subscriptions, no ads, no analytics, no trackers, and no selling of
user data — a tier buys capacity and nothing else.

### Tiers

| Tier | Max entries | Planned price | Product ID |
|---|---:|---:|---|
| Free | 20 | — | `locknest_free` |
| Plus | 100 | PHP 299 | `locknest_plus` |
| Pro | 500 | PHP 399 | `locknest_pro` |
| Ultimate | 1,000 | PHP 599 | `locknest_ultimate` |
| Unlimited | no practical limit | PHP 799 | `locknest_unlimited` |

Capacities, product IDs and planning prices live **only** in `ProductCatalog`.
No screen and no domain class hard-codes a limit or a price; they ask
`EntitlementManager`. Product IDs are placeholders until the products exist in
Play Console, and renaming them is a change to that one file. Google Play is the
source of truth for the price a user actually sees, localized to their country —
the pesos above are planning values, and the Upgrade screen says so.

The limit counts **entries**, not fields. One entry with a username, password,
website, PIN and recovery email is one entry, not five. Custom fields per entry
are unlimited on every tier.

### Layering

```
UI  ->  EntitlementManager  ->  BillingRepository  ->  Google Play Billing
             |
             +-- EntitlementStore (integrity-protected offline cache)

VaultRepository -> EntryCapacityPolicy   (an Int and a message; knows nothing
                                          about tiers, products or billing)
```

The vault does not depend on the entitlement layer, and neither depends on
billing. `VaultRepository` takes an `EntryCapacityPolicy` that defaults to
unlimited, so the vault is complete and testable with no monetization present at
all. The entitlement layer holds no vault reference and works while the vault is
locked.

**A tier never affects cryptographic access.** Encryption, the master password,
recovery, biometrics and auto-lock are identical on every tier, and nothing on
the unlock path consults an entitlement. Forging a tier buys capacity, not
decryption, and cannot expose anyone else's data.

### Enforcement, and what happens at the limit

Capacity is enforced in `VaultRepository`, not in the "Add entry" button, because
entries also arrive by CSV import, by duplicating an entry, and by restoring a
backup. Reaching the limit blocks **creation only**:

- Adding a new entry and duplicating one are refused with
  `EntryCapacityReachedException`, and the UI shows an upgrade prompt.
- Imports and backup restores keep what fits and discard the rest, warning
  beforehand how many will be left out. They never fail wholesale — refusing a
  25-entry backup outright would leave a user unable to recover any of it.
- Reading, searching, editing, copying and deleting are never restricted.

### Downgrades never destroy data

If entitlement is lost while the vault holds more than the new tier allows — a
refund, a purchase made on another account, a reinstall before restoration — the
app **never** deletes, hides, truncates, exports or locks anything. Every entry
stays visible and editable, the UI explains the state, and deleting is the way
back under the limit. Verified by test with 150 entries against a 20-entry cap,
including across a lock/unlock cycle to prove nothing was truncated on disk.

### Purchase restoration and offline behaviour

One-time products are restored by re-querying the store, which happens at startup
and from **Restore purchases** on the Upgrade screen. Play is authoritative
whenever it answers — including when it answers "nothing owned", so a refund can
correct the cache downwards.

Play being *unreachable* is not an answer. An unavailable or failing store leaves
the cached entitlement untouched, so losing network never costs a paying user
their capacity. Any exception from the billing layer is contained and treated as
"no answer": billing cannot fail in a way that blocks the vault.

The cache is not a plaintext preference. The tier is stored with an HMAC-SHA256
tag computed over it, plus a per-install random id, using a key held in the
Android Keystore that cannot be extracted from the device. Editing the stored
tier invalidates the tag and the record is rejected. Every failure path — a
missing tag, a mismatched tag, a Keystore key invalidated by a device restore —
falls back to **Free** rather than throwing or failing open.

**Assumptions and limitations, stated plainly:**

- Client-side entitlement cannot be made tamper-proof. This raises forging a tier
  from "edit a text file" to "defeat a hardware-backed key or patch the APK", and
  no further. That is why Play remains the source of truth whenever it is
  reachable, and why this is only a cache.
- Failing closed to Free can temporarily understate what someone paid for, for
  example after a device restore invalidates the Keystore key. A single "Restore
  purchases" fixes it. The alternative — failing open — would hand every tier to
  anyone able to corrupt a file.
- The per-install id binds a cached record to this install, so it cannot be
  copied to another device; it also means the cache does not survive a reinstall.
  Restoration from Play is the intended path there, and now runs automatically at
  startup as well as from the Upgrade screen's **Restore purchases**.

### Google Play Billing

Billing is live, using **Play Billing Library 9.1.0**. All tiers are one-time,
non-consumed `INAPP` products; no subscription API is used anywhere (§46M).
`PlayBillingRepository` is the only file in the app that imports a billing type,
and swapping `NoBillingRepository` back in is still a one-line change in
`ServiceLocator` that disables every purchase path and changes nothing else.

**Upgrades cost full price.** Play offers proration and replacement for
subscriptions only, so moving from Plus to Pro means buying Pro and keeping Plus;
`highestOwnedTier` then takes the higher of the two. The Upgrade screen states
this before the buttons rather than leaving it to a receipt.

Only settled purchases grant capacity. A `PENDING` purchase — cash, a bank
transfer Play has not cleared — grants nothing until it settles, and is reported
as pending rather than as a failure. Purchases are acknowledged on every query,
because Play auto-refunds anything unacknowledged after three days.

#### Permissions this costs

Adding the library changed the merged manifest. The release build now declares:

| Permission | Source |
|---|---|
| `com.android.vending.BILLING` | Play Billing library |
| `android.permission.INTERNET` | Play Billing library (transitive) |
| `android.permission.ACCESS_NETWORK_STATE` | Play Billing library (transitive) |
| `android.permission.USE_BIOMETRIC` / `USE_FINGERPRINT` | the app itself |

The library also pulls in `play-services-base`, `play-services-basement`,
`play-services-tasks`, `play-services-location` and Google's `datatransport`
stack. No location permission ends up in the merged manifest, but the location
library is linked in. Release APK grew from 2.19 MB to 2.50 MB.

**The app still makes no network call of its own.** Nothing in the vault, the
crypto, the import/export or the settings touches a socket, and the only code
that can reach the network is the billing client — reached only from the Upgrade
screen, so a user who never opens it never causes a connection. But "cannot send
your data anywhere, verifiable in the Permissions section" is no longer literally
true and **must be corrected in the store listing, the privacy policy page and
the Data safety form** before this build is published.

#### No server-side receipt verification

Purchase signatures are not verified. Doing it properly needs a server to hold
the key and check the receipt, and this app deliberately has none. Verifying
in-process against an embedded public key is theatre — whoever can forge a
purchase can patch out the check.

The exposure is bounded and is not a security issue: a forged entitlement buys
vault **capacity** and nothing else. No key material, no decryption, no access to
anyone else's data (§46O). The cost of being wrong is revenue, not passwords.

### Debug tier override

Debug builds carry a **Settings → Debug: force a tier** picker for exercising the
higher capacities without billing. It lives in `src/debug`, so it is not compiled
into a release APK — enforced by build variant rather than a runtime
`BuildConfig.DEBUG` check, which would leave the code sitting in the shipped
binary. Verified by scanning the release APK's dex for `DebugEntitlementOverride`,
`entitlement_debug`, `debug_tier_override` and the settings row label: all four
are present in the debug APK and absent from release.

---

## 16. What is deliberately NOT here

No server, REST API, SQL/Room/SQLite, cloud sync, account/registration, email or
social login, ads, analytics, tracking, or any internet-dependent service. The
architecture is kept small and auditable on purpose.

Monetization adds none of that. Paid tiers are one-time purchases of vault
capacity through Google Play; there are no subscriptions, no advertising SDKs
and no behavioural analytics, and the vault itself never depends on billing, a
network or a server (see 15b).
