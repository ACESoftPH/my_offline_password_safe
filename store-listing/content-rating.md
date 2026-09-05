# Content rating questionnaire — answers

Play Console → **Policy → App content → Content rating**.

The questionnaire is filled in by the developer and the ratings (IARC) are issued
automatically from the answers. Answer honestly: a rating obtained from wrong
answers can be invalidated and the app removed.

For this app almost everything is **No**, because it is a single-user utility
with no content and no communication features. Its only network access is Google
Play billing, which carries no user content and is not a communication channel
between users.

---

## Step 1 — Contact details and category

| Field | Answer |
|---|---|
| Email address | `walletp85@gmail.com` |
| Category | **Utility, Productivity, Communication, or Other** |

> Pick the *Utility / Productivity / Other* category, **not** "Reference, News, or
> Educational". Utility gives the short questionnaire that matches this app.

---

## Step 2 — Questionnaire

### Violence

| Question | Answer |
|---|---|
| Does the app contain violence? | **No** |
| Realistic violence? | **No** |
| Violence towards, or the killing of, characters that are human or resemble humans? | **No** |
| Depictions of blood or gore? | **No** |
| Violence towards animals? | **No** |

### Sexuality

| Question | Answer |
|---|---|
| Does the app contain sexual material or nudity? | **No** |
| Sexually suggestive content? | **No** |
| Depictions of sexual violence? | **No** |

### Language

| Question | Answer |
|---|---|
| Does the app contain profanity or crude humour? | **No** |

### Controlled substances

| Question | Answer |
|---|---|
| Does the app reference or depict illegal drugs, alcohol or tobacco? | **No** |

### Gambling

| Question | Answer |
|---|---|
| Does the app allow real-money gambling, or simulate gambling? | **No** |
| Does the app contain a loot box or similar randomised paid mechanic? | **No** |
| Does the app allow users to purchase items? | **Yes** — optional one-time purchases of extra vault capacity. No loot boxes, no randomised mechanic, no real-money gambling. |

### Miscellaneous

| Question | Answer |
|---|---|
| Does the app share the user's current location with other users? | **No** |
| Does the app allow users to interact or exchange content with each other? | **No** |
| Does the app allow users to purchase digital goods? | **Yes** — one-time upgrades that raise the entry limit. Not subscriptions. |
| Does the app contain any content, features or functionality not covered above that may be inappropriate for children? | **No** |
| Is the app a "news" app? | **No** |
| Does the app natively facilitate the buying or selling of anything? | **No** — the app sells its own upgrades through Google Play billing, but provides no marketplace or trading between users. |
| Does the app contain user-generated content that is shared with others? | **No** — users create entries, but the data never leaves their own device |

> The last one is the only question that deserves a second's thought. The app
> does let a person type text in, but nothing is ever transmitted, published, or
> visible to any other user. There is no sharing mechanism, and the one network
> path that exists — Google Play billing — carries no user content. So the answer
> is **No**.

---

## Step 3 — Ads

| Question | Answer |
|---|---|
| Does your app contain ads? | **No** |

Declare the same in **App content → Ads**: the app contains no advertising SDK
and no ads of any kind.

---

## Expected ratings

Answering as above should yield the lowest rating in every region:

| Board | Rating |
|---|---|
| IARC generic | 3+ |
| ESRB (Americas) | Everyone |
| PEGI (Europe) | PEGI 3 |
| USK (Germany) | USK 0 |
| ClassInd (Brazil) | L (Livre) |
| ACB (Australia) | G |
| GRAC (South Korea) | All |

---

## Related declarations elsewhere in App content

These sit outside the rating questionnaire but are asked in the same section, and
the answers follow from the same facts:

| Declaration | Answer |
|---|---|
| **Target audience** | 18+ (or 13+). See the note below. |
| **Data safety** | No data collected, no data shared. Purchases are processed by Google Play, which is not app-collected data. See `README.md`. |
| **Ads** | No ads |
| **News app** | No |
| **COVID-19 contact tracing / status app** | No |
| **Data safety: encryption in transit** | Not applicable — the app transmits no user data. Purchase traffic is Google Play's own, over its own encrypted channel. |
| **Government app** | No |
| **Financial features** | **None.** The app stores credentials; it does not provide banking, lending, payments, or crypto functionality. Buying an upgrade is a Google Play transaction, not a financial feature of the app. |
| **Health apps** | No |
| **Privacy policy URL** | `https://acesoftph.github.io/my_offline_password_safe/` |

### In-app purchases — retake required

The purchase answers above **changed** when billing shipped. Play's questionnaire
must be retaken, not edited: an existing rating was granted against "no in-app
purchases", and leaving it stale is a misrepresentation even though the rating
itself is very unlikely to move (one-time non-randomised purchases of extra
capacity carry no gambling or loot-box implication).

Also set, outside the questionnaire:

- **Store listing → in-app purchases**: Yes. Play derives the "In-app purchases"
  badge and the price range shown on the listing from the products themselves,
  so create the products before submitting.
- **Data safety**: still no data collected and none shared. A Play purchase is
  handled by Google, not by the app, and is not declared as app-collected data.
  The app stores exactly one purchase-derived value, on-device: which tier the
  user owns.

### On target audience

Choose an adult-only age band (**18 and over**, or 13+ at the youngest).

The app is not designed for or directed at children, and choosing an adult band
keeps it out of the **Families** programme and its extra requirements. Do **not**
tick any age band under 13: that puts the app in Play's Families policy scope,
which brings design and ads requirements that make no sense for a password
manager. When asked "could your app unintentionally appeal to children?", answer
**No** — a password manager has no child-appealing themes, characters, or
animation.

---

## After you submit

The rating is issued immediately. If you later add any feature that changes an
answer — user-to-user sharing, cloud sync, in-app purchases — you must retake the
questionnaire before that release.
