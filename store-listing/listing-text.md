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
| Short description | 80 | 74 |
| Full description | 4000 | 3992 |
| Release notes (per language) | 500 | 493 |

---

## App name

```
Lock Nest
```

## Short description

*Shown under the title in search results — the most-read line after the name.*

```
Encrypted vault, 20 entries free. No account, no cloud, no data collected.
```

## Full description

```
Lock Nest keeps your passwords in a single encrypted file on your own phone. No account, no server, no sync. Your vault is never uploaded anywhere — no code in the app sends what you store, and there is no server for it to go to.

THE FREE VERSION

The free version stores up to 20 entries, and nothing else is limited or time-limited: custom fields, search, the generator, biometric unlock, encrypted backups and CSV import and export all work fully. At 20 the Add button stops until you delete one. Importing more than 20 keeps the first 20, and says beforehand how many are left out.

MORE ROOM, IF YOU NEED IT

Plus holds 100 entries, Pro 500, Ultimate 1,000, and Unlimited has no cap. These are one-time purchases, not subscriptions — nothing renews or expires.

An upgrade changes one thing: how many entries your vault holds. Encryption, biometric unlock, backups and the generator are identical on every tier, including free. If you ever drop to a smaller tier, what you already saved stays visible and editable; only adding new entries stops.

HOW YOUR DATA IS PROTECTED

• Sealed with AES-256-GCM authenticated encryption. Tampering is detected, not silently accepted.
• Your master password is stretched into a key with PBKDF2-HMAC-SHA256 at 600,000 iterations, and is never stored in any form.
• The vault lives in private app storage and is excluded from Android's cloud backup, so it is never copied off your device behind your back.

EVERYDAY USE

• Entries hold Title, Category, Username, Password, Website and Comments, plus as many custom fields as you need: PIN, account number, recovery email, port, anything.
• Search titles, categories, usernames, websites and custom fields.
• Copy any field with one tap. Copied passwords are flagged as sensitive, hiding them from the clipboard preview on Android 13 and later, and are cleared after a delay you set.
• Show or hide any password. Copying takes the real value, not the mask.
• Generate strong passwords with an adjustable length and an optional symbol set, from a cryptographically secure random source.

LOCKING

• Unlock with your master password, or optionally your fingerprint or face using Android's own biometric system. The app never sees your biometric data.
• The vault locks itself after an inactivity period you choose, and when you leave the app for longer.
• Screenshots and screen recording are blocked by default, so passwords cannot be captured by other software or left in the app switcher.

BACKUP AND MOVING PHONES

• Export an encrypted backup with its own separate passphrase, and restore it here or on a new phone.
• Import and export semicolon-delimited CSV to move between password managers. CSV is plain text by necessity; the app says so and asks you to confirm first.

WHAT THIS APP DOES NOT DO

No ads. No analytics. No trackers. No cloud. No account. Nothing you store leaves your device unless you export a file yourself.

The app does request internet access, because Google Play's purchase system requires it. It is used only for buying and restoring an upgrade — if you never open the Upgrade screen, nothing connects.

IF YOU FORGET YOUR MASTER PASSWORD

A password manager built correctly cannot show you your old master password, because it never stored it. Instead you answer five security questions to set a new one. Wrong answers are rate-limited, and your entries survive.

That is the only way back in. Your data is only ever on your phone, so nobody can recover it for you — not even the developer. Forget both your master password and your security answers, or uninstall without a backup, and it is gone. Export an encrypted backup and keep it safe.

No password manager can protect you from a compromised device, malware with enough privileges, a plaintext CSV you export and expose, a weak master password, or guessable security answers. The full security model and its limits are in the source repository.

Open source: https://github.com/ACESoftPH/my_offline_password_safe
```

---

## Release notes — 1.1.0 (versionCode 2)

*Field: "What's new in this release". 500 characters per language.*

**These are the notes for the current build.** It is the first one containing
in-app purchases, so it is also the first that must go out against the revised
listing text and privacy policy.

```
More room, if you need it.

The free vault still holds 20 entries. You can now buy more capacity as a one-time purchase — Plus 100, Pro 500, Ultimate 1,000, or Unlimited.

• One-time purchases, not subscriptions. Nothing renews.
• Every other feature is identical on every tier, including free.
• Your existing entries are never affected by a limit.
• Your vault stays encrypted on your device. Purchases are handled by Google Play.

Already bought one? Settings → Upgrade → Restore purchases.
```

## Release notes — 1.0.0 (versionCode 1, superseded)

Kept only for reference. This build had no in-app purchases and no internet
permission, and its notes say so. **Do not paste these against the 1.1.0
bundle** — they describe an app that behaves differently from the one being
uploaded.

If versionCode 1 was never published to any track, it simply never shipped, and
1.1.0 is the first public release. Say "First public release." in that case
rather than implying an upgrade from something nobody had.

```
First public release.

Lock Nest stores your passwords in a single encrypted file on your device. No account, no sync, nothing uploaded.

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
