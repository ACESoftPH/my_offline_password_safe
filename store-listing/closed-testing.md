# Closed testing

Google Play requires personal developer accounts created after 13 November 2023
to run a closed test before they can apply for production access: at least
**12 testers opted in continuously for at least 14 days**.

> Check your own requirement first. Play Console → **Publishing overview** or
> **Testing → Production → Apply for production access** shows the exact
> conditions for *your* account, including whether they apply at all and the live
> tester count. The rules change; that page is authoritative, this file is not.

---

## Files here

| File | Purpose |
|---|---|
| `testers.csv` | Upload to Play Console to create the tester email list |
| `tester-tracking.csv` | Your own private record of who actually opted in — Play does not show you this per-person |

---

## Before you start

- [x] `walletp85@gmail.com` registered (support page, privacy policy and listing all point at it)
- [ ] Store listing complete — icon, feature graphic, screenshots, descriptions (`README.md`)
- [ ] Content rating questionnaire submitted (`content-rating.md`)
- [ ] Data safety form submitted — no data collected, no data shared
- [ ] Privacy policy URL set to `https://aldinson.github.io/my_offline_password_safe/`
- [ ] Target audience set to an adult band (18+, or 13+ at youngest)
- [ ] `OfflinePasswordWallet-1.0.0.aab` built and signed
- [ ] **12–15 real people** who have agreed in advance to take part

That last item is the one that actually delays people. Recruit **15**, not 12:
some will never open the invite, and the count must not dip below 12 at any point
during the 14 days.

---

## 1. Create the tester email list

Play Console → **Testing → Closed testing → Testers** tab → **Create email list**.

- Name it something like `Offline Password Wallet — closed testers`.
- Upload `testers.csv`, or paste the addresses directly (comma- or newline-separated).
- Replace the placeholder addresses first. Every address must be a **Google
  account** — a Gmail address, or any other address that has been registered with
  Google. An address with no Google account attached silently cannot opt in.

## 2. Upload the build

Play Console → **Testing → Closed testing → Create new release**.

- Upload `OfflinePasswordWallet-1.0.0.aab`.
- Accept **Play App Signing** when offered. Google then holds the app signing key
  and your `keystore/release.jks` becomes the *upload key* — which means if you
  ever lose it, Google can reset it rather than you losing the listing forever.
- Release name: `1.0.0 (1)`.
- Release notes: see the template at the bottom of this file.
- Roll out to the closed track.

The build must pass review before testers can install. That usually takes hours
but can take days for a first submission — start the clock early.

## 3. Send the opt-in link

Once the release is live on the track, share:

```
https://play.google.com/apps/testing/com.aldinson.offlinepasswordwallet
```

Each tester must open it **with the Google account you listed**, press *Become a
tester*, and then install the app from Play. Opting in is the step that counts;
merely being on your list does not.

Record who has actually opted in as they confirm, in `tester-tracking.csv`. Play
does not give you a per-person list, only a count, so if the number is short you
will have no way to tell who is missing unless you track it yourself.

## 4. Wait 14 days

The requirement is **continuous**. Do not remove anyone from the list, and ask
testers not to opt out or uninstall until you tell them the test is finished.
Uploading new builds to the track during the period is fine and does not reset
the clock.

## 5. Apply for production access

Play Console → **Testing → Production → Apply for production access**.

You will be asked, in prose, about your testing. Answer concretely — vague
answers are a common cause of rejection. Draw on `tester-tracking.csv`:

- How you recruited testers (friends, colleagues, a community — say which)
- What feedback you received and what you changed as a result
- How you decided the app is ready

Review of this application typically takes a few days.

---

## Message to send your testers

> **Offline Password Wallet — closed test**
>
> Thanks for helping test my Android password manager. It stores passwords in an
> encrypted file on your phone — there's no account, no cloud, and the app has no
> internet permission at all.
>
> **To join:**
> 1. Open this link on your phone, signed in with the Google account you gave me:
>    https://play.google.com/apps/testing/com.aldinson.offlinepasswordwallet
> 2. Tap **Become a tester**, then install from Play.
> 3. Please **stay opted in for at least two weeks** — Google requires that
>    before the app can be published. I'll let you know when it's done.
>
> ⚠️ **Please don't store passwords you actually rely on during the test.** Use
> made-up ones. This is a first release, and if something goes wrong there is no
> cloud backup and no way for me to recover your data — that's by design.
>
> **What's useful to try** (anything you notice is worth telling me):
> - Set it up, add a few entries, search for them
> - Show/hide a password, copy a field
> - Generate a password — try the length slider and the symbols switch
> - Turn on fingerprint unlock, lock the app, unlock it again
> - Change the auto-lock timeout and check it locks when you expect
> - Export an encrypted backup, then restore it
> - "Forgot master password" → reset it with the five security answers
>
> Tell me anything confusing, ugly, slow, or broken — especially anything where
> you weren't sure what would happen. Reply here, or email
> walletp85@gmail.com.

---

## Release notes for the first build

```
First release.

An offline password manager. Your vault is encrypted with AES-256-GCM and stored
only on your device — there is no account, no sync, and no internet permission.

• Entries with custom fields, search, and per-field copy
• Strong password generator
• Optional fingerprint or face unlock
• Auto-lock after inactivity
• Encrypted backup you can restore on another phone
• CSV import and export

Please don't store passwords you actually rely on during this test, and take an
encrypted backup before uninstalling — data cannot be recovered otherwise.
```

---

## Things that commonly go wrong

| Problem | Cause / fix |
|---|---|
| Tester says "item not found" on Play | They opened the link with a different Google account, or the release is still in review. |
| Tester opted in but Play shows no app | Play can take a few hours to propagate. Have them clear the Play Store app's cache, or open the app's Play listing directly. |
| Count stuck below 12 | Someone on the list never opted in. This is exactly what `tester-tracking.csv` is for — chase the gaps. |
| Count dropped mid-period | Someone opted out or was removed from the list. Re-add and expect the clock to be affected. |
| Non-Google email address | Cannot opt in at all, and fails silently. Verify each address belongs to a Google account. |

**Do not** pad the list with fake accounts or paid click-throughs. Play checks for
this, and it puts the developer account itself at risk — a far worse outcome than
waiting a couple of extra weeks.
