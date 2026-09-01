# Store listing text

Single source for every piece of text in the Play listing.

**The blocks below are paste-ready.** Paragraphs are deliberately *not*
hard-wrapped: Play preserves the line breaks you paste, so a description wrapped
at 78 columns re-wraps again on a phone and comes out ragged. That makes the
lines here long — copy the whole block, don't reformat it.

Verify lengths after any edit: `python store-listing/check-lengths.py`

| Field | Limit | Used |
|---|---|---|
| App name | 30 | 9 |
| Short description | 80 | 79 |
| Full description | 4000 | 3918 |
| Release notes (per language) | 500 | 478 |

---

## App name

```
Lock Nest
```

## Short description

*Shown under the title in search results — the most-read line after the name.*

```
Encrypted vault, 20 entries free. No account, no cloud, no internet permission.
```

## Full description

```
Lock Nest keeps your passwords in a single encrypted file on your own phone. There is no account to create, no server, and no sync. The app does not request Android's internet permission at all, so it cannot send your data anywhere — you can verify that yourself in the Permissions section of this listing.

THE FREE VERSION

The free version stores up to 20 entries. Everything else is unlimited and nothing is time-limited: custom fields, searching, the password generator, biometric unlock, encrypted backups and CSV import and export all work fully. When you reach 20 entries the Add button stops until you delete one. Restoring a backup or importing a CSV that holds more than 20 keeps the first 20 and tells you beforehand how many will be left out.


HOW YOUR DATA IS PROTECTED

• Your vault is sealed with AES-256-GCM authenticated encryption.
• Your master password is stretched into a key using PBKDF2-HMAC-SHA256 at 600,000 iterations, and is never stored anywhere in any form.
• Every save uses a fresh random nonce, and any tampering with the vault file is detected rather than silently accepted.
• The vault lives in the app's private storage and is excluded from Android's cloud backup, so it is never copied off your device behind your back.

EVERYDAY USE

• Store up to 20 entries, each with Title, Category, Username, Password, Website and Comments.
• Add as many custom fields as you need — PIN, account number, recovery email, server, port, anything.
• Search across titles, categories, usernames, websites and custom fields.
• Copy any field with one tap. Copied passwords are flagged as sensitive, which hides them from the system clipboard preview on Android 13 and later, and are cleared automatically after a delay you choose.
• Show or hide any password. Copying always copies the real value, never the mask.
• Generate strong passwords with an adjustable length and an optional set of symbols, drawn from a cryptographically secure random source.

LOCKING

• Unlock with your master password, or optionally with your fingerprint or face using Android's own biometric system. The app never sees your biometric data.
• The vault locks itself after a period of inactivity that you choose, and also when you leave the app for longer than that.
• Screenshots and screen recording are blocked by default, so your passwords cannot be captured by other software or left visible in the app switcher.

IF YOU FORGET YOUR MASTER PASSWORD

A correctly built password manager cannot show you your old master password, because it never stored it. Instead, you answer five security questions to authorise setting a new one. Your entries are preserved, and wrong answers are rate-limited to slow down guessing.

BACKUP AND MOVING PHONES

• Export an encrypted backup protected by its own separate passphrase, and restore it on this phone or a new one.
• Import and export semicolon-delimited CSV to move between password managers. CSV is plain text by necessity, and the app says so clearly and asks you to confirm before writing one.

WHAT THIS APP DOES NOT DO

No ads. No analytics. No trackers. No cloud. No account. Nothing leaves your device unless you explicitly export a file yourself.

WORTH KNOWING BEFORE YOU START

Because your data is only ever on your phone, nobody can recover it for you — not even the developer. If you forget both your master password and your security answers, or uninstall without a backup, the data is gone. Export an encrypted backup and keep it somewhere safe.

No password manager can protect you if your device itself is compromised, if malware is running with enough privileges, if you export a plaintext CSV and then expose it, if your master password is weak, or if someone guesses your security answers. The full security model, including its limitations, is documented in the source repository.

Open source: https://github.com/ACESoftPH/my_offline_password_safe
```

---

## Release notes — production 1.0.0

*Field: "What's new in this release". 500 characters per language.*

```
First public release.

Lock Nest stores your passwords in a single encrypted file on your device. No account, no sync, no internet permission.

• Up to 20 entries, each with custom fields, search and per-field copy
• Strong password generator
• Optional fingerprint or face unlock
• Auto-lock after inactivity
• Encrypted backup you can restore on another phone
• CSV import and export

Please export an encrypted backup before uninstalling — data cannot be recovered otherwise.
```

### Template for later releases

Say what changed, not how excited you are about it.

```
• <what it now does for the user>
• <a fix, phrased as the symptom that no longer happens>

<One closing line only if the user must do or know something.>
```

Two rules that matter for this app specifically:

- **Never describe a security fix in enough detail to weaponise it** against
  people who have not updated yet. "Hardened the vault file format" is enough;
  specifics belong in the repository's history once users have moved on.
- **Repeat the backup reminder** in any release that changes the vault format or
  the recovery flow.

---

## Play metadata policy notes

The text above is written to stay inside Play's Store Listing and Promotion
policy. If you edit it, keep to these:

- **No superlatives you cannot support.** No "most secure", "unhackable",
  "military-grade". The description states mechanisms (AES-256-GCM, PBKDF2 at
  600,000 iterations) rather than marketing adjectives — both honest and
  policy-safe.
- **No competitor names or trademarks**, and no comparisons to named apps.
- **No keyword stuffing.** Repeating "password manager password vault secure
  password" is a demotion risk and reads badly.
- **No fake urgency, testimonials, invented ratings, or "#1" claims.**
- **No emoji in the title**; there is none in the description either.
- **Every claim must match the shipped build.** The internet-permission claim in
  particular is checkable by any reviewer against the Permissions section of the
  listing — which is exactly why it is worth making.
