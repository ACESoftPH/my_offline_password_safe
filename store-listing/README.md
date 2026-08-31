# Google Play listing assets

Everything here is generated from the app itself — the icon is rendered from the
same `ic_launcher_foreground.xml` the app ships, and the screenshots are real
captures of the release APK running on an emulator (Android 16, 1080×2400). No
mockups.

Regenerate with `store-listing/generate.py` (see "Regenerating" below).

## Files

| File | Play field | Spec |
|------|-----------|------|
| `icon-512.png` | App icon | 512×512, 32-bit PNG |
| `feature-graphic-1024x500.png` | Feature graphic | 1024×500, 24-bit PNG, no alpha |
| `screenshots-phone/*.png` | Phone screenshots | 1080×1920 (9:16), 24-bit PNG, no alpha |
| `screenshots-raw/*.png` | — | Unmodified 1080×2400 device captures |

**Upload `screenshots-phone/`.** Play documents an accepted aspect-ratio window
of 16:9 to 9:16; a raw modern-phone capture is 20:9, i.e. taller than the
documented maximum. The `screenshots-phone` versions are the same images scaled
to fit a 1080×1920 frame and centred on the brand gradient — nothing is cropped,
and the ratio is unambiguously within spec. `screenshots-raw/` is kept in case
you prefer to upload the native captures.

### Screenshot order

1. **Locked vault** — the lock screen; nothing is revealed before authentication
2. **Vault list** — entries with search
3. **Entry detail** — password masked with `#`, per-field copy, SHOW toggle
4. **Password generator** — length slider, restricted symbol set
5. **Security settings** — biometric unlock, auto-lock timeout
6. **Encrypted backup** — export protected by its own passphrase
7. **Custom fields** — add/edit entry, password strength meter
8. **Reset master password** — the five-question flow

## Suggested listing text

**App name** (30 char limit — 23 used)

```
Offline Password Wallet
```

**Short description** (80 char limit — 70 used)

```
Encrypted password vault. No account, no cloud, no internet permission.
```

**Full description** (4000 char limit)

```
Offline Password Wallet keeps your passwords in a single encrypted file on your
phone. There is no server, no account to create, and no sync. The app does not
request the INTERNET permission at all, so it cannot send your data anywhere —
that is enforced by Android, not just promised in a description.

ENCRYPTION
• Your vault is sealed with AES-256-GCM authenticated encryption.
• Your master password is turned into a key with PBKDF2-HMAC-SHA256 at 600,000
  iterations, and is never stored anywhere, in any form.
• Every save uses a fresh random nonce, and tampering with the vault file is
  detected rather than silently accepted.

WHAT YOU CAN DO
• Store entries with Title, Category, Username, Password, Website and Comments.
• Add as many custom fields as you like — PIN, account number, recovery email,
  server, port, anything.
• Search by title, category, username, website and custom fields.
• Copy any field with one tap. The clipboard is flagged sensitive and cleared
  automatically after a delay you choose.
• Generate strong passwords: adjustable length, optional symbols from a fixed
  safe set, every character from a cryptographically secure random source.
• Show or hide passwords; copying always copies the real value, never the mask.

LOCKING
• Unlock with your master password, or optionally with your fingerprint or face
  using Android's own biometric system — the app never sees your biometric data.
• The vault locks itself after a period of inactivity that you set, and also
  when you leave the app for longer than that.
• Screenshots and screen recording are blocked by default.

IF YOU FORGET YOUR MASTER PASSWORD
A correctly built password manager cannot show you your old master password,
because it never stores it. Instead you answer five security questions to
authorise setting a NEW master password. Your entries are preserved, and wrong
answers are rate-limited.

BACKUP AND TRANSFER
• Export an encrypted backup file protected by its own passphrase, and restore
  it on this phone or a new one.
• Import and export semicolon-delimited CSV for moving between password
  managers. CSV is plain text by design, and the app says so clearly and asks
  you to confirm before it writes one.

WHAT THIS APP DOES NOT DO
No ads. No analytics. No trackers. No cloud backup. No account. Nothing leaves
your device unless you explicitly export a file yourself.

HONEST LIMITATIONS
No password manager can protect you if your device itself is compromised, if
malware has enough privileges, if you export a plaintext CSV and then expose it,
if your master password is weak, or if someone guesses your security answers.
Because your data never leaves your phone, there is also no way for anyone —
including the developer — to recover it for you. Take an encrypted backup.

Open source: https://github.com/aldinson/my_offline_password_safe
```

## Data safety form

The answer to every collection question is **no**. The app collects nothing,
shares nothing, and has no network permission to do so with. The merged release
manifest declares only `USE_BIOMETRIC` and `USE_FINGERPRINT`, both from
`androidx.biometric`, and biometric matching is performed by Android — the app
never receives biometric data.

You will still need a **privacy policy URL**; Play requires one even for apps
that collect nothing.

## Regenerating

`generate.py` rebuilds the icon and feature graphic from the shipped vector. The
screenshots were captured from the release build running on an emulator with the
in-app "Block screenshots" setting temporarily turned off (the app sets
`FLAG_SECURE` by default, which otherwise makes every capture come out black).
