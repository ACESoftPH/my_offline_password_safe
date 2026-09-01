# Google Play listing assets

Everything here is generated from the app itself — the icon is rendered from the
same `ic_launcher_foreground.xml` the app ships, and the screenshots are real
captures of the release APK running on an emulator (Android 16, 1080×2400). No
mockups.

Screenshots are taken in **dark mode**, which is where the black/gray/orange
palette reads most clearly. The app follows the system setting and has a fully
worked light theme too.

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
3. **Entry detail** — password masked with `*`, per-field copy, reveal toggle
4. **Password generator** — length slider, restricted symbol set
5. **Security settings** — biometric unlock, auto-lock timeout
6. **Encrypted backup** — export protected by its own passphrase
7. **Custom fields** — add/edit entry, password strength meter
8. **Reset master password** — the five-question flow

## Listing text

App name, short description, full description and release notes live in
**[listing-text.md](listing-text.md)** — paste-ready, with the Play character
limits verified by `check-lengths.py`. They are kept in one place on purpose:
this file previously carried a second copy, and the two drifted the moment the
password mask changed.

## Content rating

See **[content-rating.md](content-rating.md)** for every questionnaire answer,
the expected ratings, and the target-audience choice.

## Closed testing

See **[closed-testing.md](closed-testing.md)** for the Play Console steps, the
message to send testers, and the release notes. `testers.csv` is the email list
to upload; `tester-tracking.csv` is your own record of who actually opted in,
which Play does not show you per-person.

## Data safety form

The answer to every collection question is **no**. The app collects nothing and
shares nothing: no analytics, no crash reporting, no telemetry, and no code that
transmits what a user stores.

The merged release manifest declares `USE_BIOMETRIC` and `USE_FINGERPRINT` from
`androidx.biometric` — biometric matching is performed by Android, and the app
never receives biometric data — plus `INTERNET`, `ACCESS_NETWORK_STATE` and
`com.android.vending.BILLING`, all three from Google Play's billing library and
used only to sell and restore the capacity upgrades. A Play purchase is processed
by Google, not by the app, so it is not declared as app-collected data.

## URLs and contact for the Play Console

| Play Console field | Value |
|---|---|
| Privacy policy | `https://acesoftph.github.io/my_offline_password_safe/` |
| Support website (optional) | `https://acesoftph.github.io/my_offline_password_safe/support.html` |
| Support email (required, shown publicly) | `walletp85@gmail.com` |

Both pages live in `docs/` and are served by GitHub Pages from `main` / `docs`.
They are self-contained — no fonts, CDNs, scripts or trackers — so the privacy
policy does not contradict its own contents.

> `walletp85@gmail.com` is registered and in use. It appears in
> `docs/support.html`, `docs/index.html`, `content-rating.md`,
> `closed-testing.md` and this file — change all five together if it ever moves.

## Regenerating

`generate.py` rebuilds the icon and feature graphic from the shipped vector. The
screenshots were captured from the release build running on an emulator with the
in-app "Block screenshots" setting temporarily turned off (the app sets
`FLAG_SECURE` by default, which otherwise makes every capture come out black).
