# Claude Code Prompt — Offline Password Wallet Android App

You are an expert Android security engineer and Kotlin developer.

Build a complete Android application called **Offline Password Wallet**.

The application is a **local-only, offline password manager**. It must not require a server, cloud database, SQL database, user account, internet connection, or external authentication service.

The primary goals are:

1. Strong local encryption.
2. Complete offline operation.
3. Simple password-management UI.
4. CSV import/export compatibility.
5. Secure master-password authentication.
6. Optional Android biometric authentication.
7. Strong password generation.
8. Automatic locking after inactivity.
9. Recovery through five predefined security questions.
10. Support for custom fields.

---

# 1. IMPORTANT SECURITY REQUIREMENT

Treat this application as a security-sensitive application.

Do NOT implement the vault as plaintext CSV or plaintext JSON.

The vault data must always be encrypted while stored on the device.

Do NOT store:

- Master password in plaintext.
- Master password in SharedPreferences.
- Master password in ordinary application files.
- Security-question answers in plaintext.
- Password entries in plaintext outside the decrypted in-memory vault.
- Encryption keys in plaintext files.
- Generated passwords in logs.
- Passwords in Android Logcat.
- Passwords in crash reports.
- Passwords in analytics.
- Passwords in clipboard longer than necessary.

Use established Android cryptographic APIs rather than implementing cryptography manually.

Preferred cryptographic architecture:

- Kotlin.
- Android Keystore.
- AES-256-GCM for authenticated encryption.
- A strong password-based KDF such as Argon2id if an appropriate maintained Android/JVM implementation can be safely included.
- If Argon2id is impractical because of dependency/security concerns, use PBKDF2-HMAC-SHA256 with a strong iteration count and clearly document the rationale.
- Every vault encryption operation must use a unique random nonce/IV.
- Generate cryptographically secure random values using Android's SecureRandom.
- Never use java.util.Random for security-sensitive operations.

The master password must be used to derive or unlock the vault encryption key. The architecture must ensure that knowing the encrypted vault file does not reveal the contents.

The encryption format must include sufficient metadata to decrypt the vault, such as:

- format/version
- KDF parameters
- salt
- encryption nonce/IV
- authentication tag as applicable
- ciphertext

Do not hard-code encryption keys.

---

# 2. MASTER PASSWORD

When the application is launched for the first time:

1. Display a first-run setup screen.
2. Ask the user to create a master password.
3. Require confirmation of the master password.
4. Enforce a strong password policy.
5. Do NOT impose an unnecessarily restrictive maximum password length.
6. Display a password-strength indicator.
7. Explain that the master password cannot normally be recovered.
8. Then configure the five security questions described below.
9. Only after all required setup is successfully completed should the encrypted vault be created.

The master password must never be stored directly.

The application should instead use the master password to derive/unlock the cryptographic key protecting the vault.

---

# 3. SECURITY QUESTIONS / MASTER PASSWORD RECOVERY

During initial setup, require answers to exactly these five questions:

1. What was the name of your first school?
2. What was the name of your favorite pet?
3. What is your mother's maiden name?
4. What is your father's middle name?
5. In what year did you graduate from college?

The user must provide answers to all five.

Important:

The security-question answers must NEVER be stored in plaintext.

Normalize answers consistently before processing them, for example:

- Trim leading/trailing whitespace.
- Apply a consistent Unicode normalization.
- Decide on case handling and document it clearly.

Use a cryptographic KDF/derived key to protect the recovery material.

IMPORTANT SECURITY DESIGN:

Do NOT attempt to retrieve or display the original master password.

A correctly designed password manager cannot recover the original master password because it should never store it.

Instead, if the master password is forgotten:

1. User selects "Forgot Master Password".
2. Require answers to all five security questions.
3. Verify the answers securely.
4. Apply rate limiting/temporary lockout to prevent unlimited guessing.
5. If verification succeeds, allow the user to create a NEW master password.
6. Re-encrypt/re-key the vault under the new master password.
7. Do not expose the old master password.

The recovery mechanism must not destroy the existing vault if the answers are incorrect.

If the cryptographic design makes direct recovery-key implementation difficult, design the vault so that the encryption key is securely wrapped in a manner that allows authorized recovery and re-wrapping without exposing the underlying vault key.

Document the exact cryptographic design in the project documentation.

Also clearly warn the user during setup that security-question recovery is weaker than a high-entropy recovery key because the answers are based on personal information.

Do NOT add additional recovery questions. The required number is exactly five.

---

# 4. VAULT STORAGE

Do NOT use SQLite, Room, PostgreSQL, MySQL, Firebase, Supabase, or any other database.

The wallet should use a local encrypted file.

Preferred logical structure:

Encrypted file
    ↓
JSON vault document
    ↓
Vault entries
    ↓
Fields

The JSON itself does not need to be encrypted separately if the entire serialized vault is encrypted using authenticated encryption.

A possible logical JSON structure is:

{
  "formatVersion": 1,
  "entries": [
    {
      "id": "...",
      "fields": [
        {
          "name": "Title",
          "value": "..."
        },
        {
          "name": "Category",
          "value": "..."
        }
      ]
    }
  ]
}

You may improve this structure if there is a better secure design.

Every entry should have a stable unique identifier.

Custom fields must be supported.

The encrypted vault should be stored in the application's private storage area.

The user must never be able to accidentally expose the unencrypted vault through ordinary file browsing.

---

# 5. DEFAULT FIELDS

The attached CSV template is:

PasswordSafe_template.csv

Its first row contains:

Title;Category;Username;Password;Website;Comments

Therefore the initial/default fields must be:

- Title
- Category
- Username
- Password
- Website
- Comments

IMPORTANT:

The CSV uses **semicolon (`;`) as the delimiter**, not comma.

The application should support this template format.

Design the CSV import/export system so that semicolon-delimited CSV is the primary/default format.

---

# 6. CUSTOM FIELDS

Each password entry must have a button such as:

"+ Add Custom Field"

When pressed:

1. Create a new field.
2. Ask the user for the field name.
3. Allow the user to enter the field value.
4. Save the custom field as part of that entry.

Examples:

- PIN
- Account Number
- Security Code
- Recovery Email
- Server
- Port
- Notes
- Customer ID
- WiFi Password

The user can create as many custom fields as practical.

The user must also be able to:

- Edit a custom field name.
- Edit its value.
- Delete a custom field.

Avoid duplicate field names within a single entry, or clearly handle them if duplicates are allowed.

---

# 7. PASSWORD ENTRY UI

Create a clean password-wallet interface.

The main screen should display saved entries.

Each entry should show an appropriate identifying value, primarily the Title.

The user must be able to:

- Add entry.
- Edit entry.
- Delete entry.
- View entry.
- Search entries.
- Copy individual field values.
- Show/hide password.
- Generate a password.
- Manually enter a password.
- Add custom fields.

When viewing an entry, every field should have a Copy button.

For example:

Title       [Facebook Account]       [COPY]

Username    [myusername]             [COPY]

Password    [###########]            [SHOW] [COPY]

Website     [https://...]            [COPY]

Comments    [...]                    [COPY]

The COPY button for a password MUST copy the actual password value, never the censorship characters.

---

# 8. PASSWORD CENSORING

The password field must support show/hide functionality.

When hidden, use:

#

as the censor character.

For example:

Password:

`################`

When shown:

`MyActualPassword123!`

The number of `#` characters does not need to reveal the actual password length if doing so would improve security.

IMPORTANT:

Regardless of whether the password is currently hidden or visible, pressing COPY must copy the real password.

Never copy the `#` characters.

---

# 9. CLIPBOARD SECURITY

Because this is a password manager:

- Copy operations should use Android's ClipboardManager.
- Passwords should not be logged.
- Consider automatically clearing sensitive clipboard contents after a configurable short period where Android permits this.
- Do not retain copied passwords unnecessarily.
- Make the clipboard behavior compatible with modern Android versions.

Document any Android-version limitations.

---

# 10. PASSWORD GENERATOR

Implement a strong password generator.

The generator must automatically provide a generated password immediately when opened.

The user can press a button such as:

"Generate Again"

to generate another password.

Every generated password must be cryptographically random.

Use SecureRandom or an equivalent cryptographically secure random source.

Never use:

- Math.random()
- java.util.Random
- predictable sequences
- timestamps
- usernames
- dictionary words

unless specifically requested by the user.

---

# 11. PASSWORD CHARACTER SET

The generated password must contain:

- Lowercase letters: a-z
- Uppercase letters: A-Z
- Numbers: 0-9

Special characters are optional.

The ONLY allowed special characters are exactly:

!@#$%^&*()_-+=<>.?{[}]~|

Do not add other special characters.

For example, do NOT automatically include:

`
'
:
;
/
\
,
`
or other punctuation outside the specified set.

---

# 12. PASSWORD GENERATOR STRENGTH

The generator must ensure that generated passwords are strong.

When special characters are enabled, ensure that the generated password includes:

- at least one lowercase letter
- at least one uppercase letter
- at least one number
- at least one allowed special character

When special characters are disabled, ensure that the generated password includes:

- at least one lowercase letter
- at least one uppercase letter
- at least one number

Randomize the position of the required character classes instead of always placing them at fixed positions.

The remaining characters should be randomly selected from the enabled character sets.

Do not generate passwords using a predictable pattern.

---

# 13. PASSWORD LENGTH SLIDER

The password generator must have a slider allowing the user to select the password length.

Use a sensible secure range, for example:

8 to 64 characters

with a default such as:

20 characters.

You may choose a better minimum/default/maximum if security/usability considerations justify it, but document the choice.

Display the current selected length clearly.

Example:

Password Length: 24

[------●----------------]

---

# 14. SPECIAL CHARACTER OPTION

Provide a switch/checkbox:

Use Special Characters

ON/OFF

When ON:

Use only:

!@#$%^&*()_-+=<>.?{[}]~|

When OFF:

Use only:

A-Z
a-z
0-9

---

# 15. MANUAL PASSWORD ENTRY

The password generator must NOT be mandatory.

The user must be able to:

- Generate a password.
- Generate another password.
- Accept the generated password.
- Or ignore the generator completely and manually type their own password.

The Password field must therefore always support manual editing.

---

# 16. CSV IMPORT

The application must have:

Menu → Import / Export → Import CSV

The importer must:

1. Allow the user to select a CSV file using Android's Storage Access Framework.
2. Parse the header row.
3. Treat the header row as field names.
4. Create fields based on those headers.
5. Import all rows as password entries.
6. Preserve empty fields.
7. Preserve custom columns.
8. Handle the semicolon-delimited format used by the supplied template.

Example:

Title;Category;Username;Password;Website;Comments

Each row becomes one vault entry.

If a CSV contains additional headers, such as:

Title;Category;Username;Password;Website;Comments;PIN;Account Number

then PIN and Account Number must automatically become custom fields.

Before importing, show a confirmation screen such as:

"Found 42 entries and 8 fields. Import?"

Do not overwrite existing entries without explicit confirmation.

Prefer an import mode that lets the user choose:

- Add imported entries
- Replace existing vault

If replacement is implemented, require a strong confirmation.

---

# 17. CSV EXPORT

Menu:

Import / Export → Export CSV

Export all wallet contents to CSV.

The exported CSV must contain:

- All entries.
- All standard fields.
- All custom fields.

Because different entries may have different custom fields, create a union of all field names.

For example:

Title;Category;Username;Password;Website;Comments;PIN;Account Number

Entries that don't have a particular field should have an empty value for that column.

The exported CSV is intentionally plaintext because CSV interoperability requires plaintext.

IMPORTANT SECURITY WARNING:

Before exporting, clearly warn:

"CSV files are not encrypted. Anyone who obtains the exported file can read your passwords."

Require explicit confirmation before exporting.

Use Android's Storage Access Framework so the user chooses where to save the CSV.

Do not silently save plaintext CSV files into an arbitrary location.

---

# 18. IMPORT/EXPORT SECURITY

Imported CSV files are plaintext.

Therefore:

- Do not keep the imported plaintext file permanently.
- Read it into memory where practical.
- Convert it into vault entries.
- Encrypt the resulting vault immediately.
- Do not create unnecessary temporary plaintext copies.

For export:

- Warn the user that the exported CSV is plaintext.
- Never pretend that the CSV export is encrypted.
- Never silently upload or synchronize the CSV.

The app must never transmit imported/exported passwords over the network.

---

# 19. BIOMETRIC LOGIN

Add:

Settings → Security → Biometric Login

The user can enable or disable biometric login.

Use Android's official biometric authentication mechanism:

BiometricPrompt

Do not implement custom fingerprint/face recognition.

When enabled:

1. The user opens the application.
2. The application can request biometric authentication.
3. Successful biometric authentication unlocks the vault.

The biometric system must be backed by Android's secure authentication infrastructure.

Do not store biometric data in the application.

The app must never access raw fingerprint/face data.

If biometric authentication is unavailable, disabled, or fails, allow the user to authenticate with the master password.

Provide a clear setting to disable biometric login.

---

# 20. BIOMETRIC KEY DESIGN

Use Android Keystore appropriately.

Do not simply store the master password after biometric authentication.

Instead, use a cryptographic key-management design where Android Keystore protects a key/wrapped vault key that can be unlocked following successful biometric authentication.

The biometric-protected mechanism should be revocable by disabling biometric login.

Handle cases such as:

- New biometric enrollment.
- Biometric enrollment changes.
- Device lock-screen changes.
- Biometric hardware unavailable.
- User removes all enrolled biometrics.
- Android Keystore key invalidation.

When biometric-protected keys become invalid, fall back to master-password authentication and allow the user to re-enable biometric login.

---

# 21. TIMEOUT / AUTOMATIC LOCK

The application must automatically lock itself after inactivity.

Settings:

Security → Auto Lock Timeout

Provide configurable options such as:

- 30 seconds
- 1 minute
- 2 minutes
- 5 minutes
- 10 minutes
- 30 minutes
- Never

Default should be a reasonably secure value such as 5 minutes.

"Activity" should include meaningful user interaction such as:

- Touch interaction.
- Navigation.
- Editing.
- Viewing entries.

When the configured timeout expires:

1. Lock the vault.
2. Clear decrypted vault data from memory as far as practical.
3. Finish/close sensitive activities.
4. Return to the authentication screen.
5. Require master password or biometric authentication when reopening/unlocking.

IMPORTANT:

Android does not guarantee that an application can completely terminate its own process.

Therefore, do NOT falsely claim that the application can guarantee process termination.

Instead implement secure application locking:

- Clear in-memory references.
- Close sensitive screens.
- Return to authentication.
- Prevent access to decrypted data until authentication succeeds.

If Android allows the relevant activity/task to be finished, do so.

---

# 22. APP BACKGROUNDING

Also lock the wallet when appropriate if the user leaves the app for longer than the configured timeout.

For example:

1. User opens wallet.
2. User switches to another application.
3. Timeout expires.
4. Wallet locks.
5. When the user returns, authentication is required.

Do not allow the decrypted vault to remain accessible indefinitely in the background.

---

# 23. MEMORY SECURITY

Because this is a password manager:

- Keep decrypted vault data in memory only while unlocked.
- Clear sensitive objects/references when locking where practical.
- Avoid unnecessary copies of password strings.
- Avoid logging sensitive data.
- Do not place passwords into Bundle arguments unnecessarily.
- Do not put passwords into Intent extras.
- Do not expose passwords through debug screens.
- Disable verbose logging in release builds.
- Consider FLAG_SECURE for screens displaying sensitive information to prevent screenshots/screen recording where appropriate.

Document the limitations of JVM/Kotlin String immutability and explain what memory clearing can and cannot guarantee.

---

# 24. SEARCH

The main vault screen should include search.

Search should allow the user to search entries by useful non-sensitive metadata such as:

- Title
- Category
- Username
- Website
- Custom field names/values

However, be conscious that searching decrypted passwords requires the vault to already be unlocked.

Do not create an unencrypted search index containing passwords.

---

# 25. ENTRY MANAGEMENT

Implement:

### Add Entry

Creates a new password entry.

### Edit Entry

Allows all fields to be changed.

### Delete Entry

Requires confirmation before permanently deleting.

### Duplicate Entry

Optional but useful.

### Search

Search existing entries.

### Copy

Copy individual field values.

### Password Generator

Generate a password directly inside the password field.

---

# 26. USER INTERFACE

Use modern Android UI.

Preferred stack:

- Kotlin
- Jetpack Compose
- Material 3
- AndroidX
- Navigation Compose where appropriate

Make the UI simple and uncluttered.

Primary screens:

1. First Run / Setup
2. Master Password Unlock
3. Biometric Unlock
4. Vault Entry List
5. Entry Details
6. Add/Edit Entry
7. Password Generator
8. Settings
9. Security Questions / Recovery
10. Import CSV
11. Export CSV
12. Recovery / Reset Master Password

Use appropriate dialogs for destructive operations.

Passwords should be masked by default.

---

# 27. SETTINGS

Settings should include at minimum:

## Security

- Enable/Disable Biometric Login
- Auto-Lock Timeout
- Change Master Password
- Change Security Question Answers

## Data

- Import CSV
- Export CSV

## Password Generator

- Default password length
- Use special characters by default

You may add other useful settings if they improve usability without compromising security.

---

# 28. CHANGE MASTER PASSWORD

Allow:

Settings → Security → Change Master Password

Require:

1. Current master password, unless already authenticated through an appropriate secure recovery flow.
2. New master password.
3. Confirmation of new master password.

After successful verification:

1. Derive the new encryption key.
2. Re-encrypt/re-wrap the vault.
3. Replace the old cryptographic protection.
4. Ensure the operation is atomic.

Do not risk leaving the vault unusable if the device loses power during the operation.

Use a safe write strategy such as:

- write new encrypted vault to temporary file
- fsync/flush where appropriate
- verify it can be opened
- atomically replace the old vault

---

# 29. CHANGE SECURITY ANSWERS

Settings must allow the user to change the five security-question answers.

Require the current master password before changing them.

The application must:

1. Verify the master password.
2. Ask for new answers.
3. Securely replace the old recovery protection.
4. Never store the answers in plaintext.

The five questions themselves remain fixed unless you have a strong security/usability reason to make them configurable. The current specification requires exactly these five questions.

---

# 30. VAULT FILE CORRUPTION

Design the encrypted storage format so that corruption is detectable.

AES-GCM authentication must detect unauthorized modification/corruption.

If the vault cannot be decrypted or authenticated:

Display a clear error such as:

"Unable to unlock the password vault. The vault file may be corrupted or the password may be incorrect."

Do not silently create a new empty vault and overwrite the existing vault.

Provide safe recovery/error handling.

---

# 31. BACKUP CONSIDERATIONS

The application should NOT automatically synchronize with cloud services.

It should remain completely offline.

If implementing an encrypted backup feature later, design it so that the backup itself is encrypted.

For this initial implementation, CSV import/export is required.

Do not add Google Drive, Firebase, Dropbox, OneDrive, or any cloud service.

---

# 32. NETWORK SECURITY

The application is offline-only.

Prefer not to request INTERNET permission at all.

The final APK should not require:

android.permission.INTERNET

unless an unavoidable dependency requires it.

Audit the final manifest and remove unnecessary permissions.

---

# 33. ANDROID SECURITY

Use:

- Android Keystore
- BiometricPrompt
- SecureRandom
- Storage Access Framework
- Android private application storage
- Encrypted authenticated vault format

Do not implement your own encryption algorithm.

Do not use obsolete cryptographic algorithms.

Do not use ECB mode.

Do not use unauthenticated encryption.

Do not hard-code cryptographic secrets.

---

# 34. PROJECT STRUCTURE

Use a clean maintainable architecture.

A reasonable structure is:

data/
    model/
    repository/
    storage/
    crypto/

domain/
    model/
    usecase/

ui/
    screens/
    components/
    navigation/
    theme/

security/
    MasterPasswordManager
    BiometricManager
    KeyManager
    VaultCrypto

importexport/
    CsvImporter
    CsvExporter

password/
    PasswordGenerator

settings/

You may improve this architecture if appropriate.

Keep cryptographic logic separated from UI code.

---

# 35. TESTING REQUIREMENTS

Create automated tests for security-critical functionality.

At minimum test:

## Password Generator

- Minimum length.
- Maximum length.
- Correct length.
- Contains lowercase.
- Contains uppercase.
- Contains numbers.
- Contains special character when enabled.
- Does not contain unauthorized special characters.
- Generates different passwords.
- Uses cryptographically secure randomness.

## CSV

- Correct semicolon parsing.
- Header extraction.
- Empty fields.
- Custom fields.
- Multiple rows.
- Export/import round trip.
- Fields containing delimiter/quotes.
- Unicode content.

## Encryption

- Encrypt/decrypt round trip.
- Wrong password fails.
- Modified ciphertext fails.
- Modified authentication data fails.
- Unique nonce/IV per encryption.
- Empty vault.
- Large vault.

## Master Password

- Setup.
- Change password.
- Old password stops working after change.
- New password works.

## Recovery

- Correct five answers permit reset.
- Incorrect answers do not.
- Partial answers do not.
- Recovery does not reveal the old password.
- Recovery rate limiting works.

## Auto Lock

- Timeout occurs.
- Activity resets timeout.
- Locked vault cannot be accessed.
- Authentication is required after lock.

## Biometric

Mock/test the relevant success/failure paths where practical.

---

# 36. UI TESTS

Add UI tests for:

1. First-run setup.
2. Master password login.
3. Creating an entry.
4. Editing an entry.
5. Adding custom fields.
6. Showing/hiding password.
7. Copying password while censored.
8. Password generator.
9. Generate Again.
10. CSV import.
11. CSV export.
12. Settings.
13. Biometric enable/disable.
14. Auto-lock.
15. Forgot Master Password.
16. Master-password reset.

---

# 37. CSV EDGE CASES

The CSV parser must correctly handle:

- Semicolon delimiters.
- Quoted fields.
- Semicolons inside quoted fields.
- Quotes inside fields.
- Empty values.
- Unicode.
- Newlines inside quoted fields if practical.
- Different line endings.

Do not implement CSV parsing using a naive:

split(";")

approach.

Use a proper CSV parser or implement a correctly tested parser.

---

# 38. PASSWORD GENERATOR SECURITY TEST

Add a test ensuring that every generated password satisfies the selected policy.

For example, if special characters are enabled:

Generated password must satisfy:

- `[a-z]`
- `[A-Z]`
- `[0-9]`
- one character from:

`!@#$%^&*()_-+=<>.?{[}]~|`

and must contain no characters outside those sets.

---

# 39. ACCESSIBILITY

Support:

- Screen readers where practical.
- Proper content descriptions.
- Sufficient touch targets.
- Keyboard navigation where practical.
- Dark/light themes.
- Appropriate contrast.

Do not sacrifice password security for accessibility.

---

# 40. APP LOCK UX

When locked, display a simple screen:

Offline Password Wallet

[Unlock with Biometrics]

or

[Enter Master Password]

Do not display the contents of the vault on the lock screen.

Do not reveal entry titles or usernames before authentication.

---

# 41. APPLICATION LIFECYCLE

Carefully handle:

- Activity recreation.
- Rotation.
- Process death.
- Background/foreground transitions.
- Device reboot.
- Low-memory conditions.

Never assume that decrypted vault data will survive process death.

After process death, require authentication again.

Do not persist decrypted vault contents in SavedStateHandle, Bundle, or other persistent UI state.

---

# 42. FIRST-RUN INITIALIZATION

Detect whether a vault has already been initialized.

Do not rely only on a simple boolean preference.

The application should safely determine whether a valid vault exists.

Prevent accidental initialization from overwriting an existing vault.

---

# 43. ATOMIC VAULT OPERATIONS

Every modification should safely persist the encrypted vault.

For example:

1. Load/decrypt vault.
2. Modify in memory.
3. Serialize.
4. Encrypt.
5. Write temporary encrypted file.
6. Verify.
7. Atomically replace the existing vault.
8. Clear unnecessary plaintext data.

Do not truncate the only copy before successfully writing the replacement.

---

# 44. APP NAME AND IDENTITY

Use:

Application name:

**Offline Password Wallet**

Suggested package name:

`com.example.offlinepasswordwallet`

However, if this conflicts with project conventions, use an appropriate unique package name.

---

# 45. README / SECURITY DOCUMENTATION

Create a detailed README.md explaining:

- Application architecture.
- Storage architecture.
- Encryption architecture.
- Master-password handling.
- Key derivation.
- Android Keystore usage.
- Biometric authentication.
- Recovery mechanism.
- CSV format.
- Auto-lock.
- Threat model.
- Security limitations.
- Backup considerations.
- Why the master password cannot be retrieved.
- Why recovery resets the master password instead.
- Why CSV exports are dangerous because they are plaintext.

Include a section:

## Security Limitations

Explicitly explain that no password manager can protect data if:

- The device itself is compromised.
- Malware has sufficient privileges.
- The user intentionally exports plaintext CSV and exposes it.
- The master password is weak.
- The security-question answers are guessed.

---

# 46. DO NOT OVERENGINEER

This is intentionally a local/offline password wallet.

Do NOT add:

- Server backend.
- REST API.
- SQL database.
- Cloud synchronization.
- User registration.
- Email login.
- Social login.
- Advertising.
- Analytics.
- Tracking.
- Internet-dependent services.

Keep the architecture simple, auditable, and security-focused.

---

# 47. DEVELOPMENT PROCESS

Before writing the implementation:

1. Inspect the existing repository.
2. Determine whether this is a new project or an existing Android project.
3. Inspect all existing source code and configuration.
4. Do not overwrite existing work unnecessarily.
5. Create a clear implementation plan.
6. Identify dependencies.
7. Implement incrementally.
8. Run tests after each major subsystem.
9. Fix compilation errors.
10. Run static analysis/lint.
11. Perform a security review.

If the repository is empty, initialize a modern Android project using Kotlin and Jetpack Compose.

---

# 48. REQUIRED DELIVERABLE

At the end, provide a working Android project that can be built into an APK.

The project must:

- Compile successfully.
- Pass unit tests.
- Pass relevant UI tests.
- Have no unnecessary network permissions.
- Store vault contents encrypted.
- Support the supplied semicolon-delimited CSV format.
- Support custom fields.
- Support secure password generation.
- Support manual passwords.
- Support password show/hide.
- Support copying actual password values.
- Support biometric login.
- Support auto-lock.
- Support five-question master-password recovery/reset.
- Support changing security answers.
- Support changing the master password.
- Support encrypted local storage.
- Have clear security documentation.

---

# 49. FINAL SECURITY AUDIT

Before declaring the implementation complete, perform a dedicated security audit.

Search the entire source tree for:

- println
- Log.d
- Log.i
- Log.v
- Log.w
- Log.e
- password
- masterPassword
- securityAnswer
- secret
- token
- key
- vault

Review whether any sensitive value can accidentally be logged or persisted.

Also inspect:

- AndroidManifest.xml
- Gradle dependencies
- ProGuard/R8 configuration
- backup configuration
- exported activities
- content providers
- file providers
- debug configuration
- network permissions

Ensure Android backup behavior does not accidentally expose sensitive vault data.

If Android Auto Backup is enabled, configure it appropriately so the encrypted vault and sensitive key material are not unintentionally exposed or backed up in an insecure manner.

---

# 50. IMPORTANT DESIGN DECISION ABOUT RECOVERY

The following distinction is mandatory:

The application must NOT say:

"Recover your forgotten master password."

Instead, use language such as:

"Reset your master password"

because the original master password must never be stored.

The recovery flow is:

Five security answers
        ↓
Secure verification
        ↓
Authorized recovery
        ↓
Create new master password
        ↓
Re-key/re-encrypt vault
        ↓
Continue using wallet

Never:

Five answers
        ↓
Display original password

That would defeat the security architecture.

---

# 51. INITIAL CSV TEMPLATE

The supplied CSV template contains this header:

Title;Category;Username;Password;Website;Comments

Use these six fields as the initial/default fields.

Do not hard-code the number of fields to six, because future CSV files may contain custom columns.

The first row of an imported CSV should always be treated as the field/header definition.

---

# 52. IMPLEMENTATION PRIORITY

Implement in this order:

Phase 1:
- Project setup
- Data model
- Encrypted vault
- Master password
- Basic vault UI

Phase 2:
- Add/edit/delete entries
- Default fields
- Custom fields
- Search
- Copy/show/hide

Phase 3:
- Secure password generator
- Length slider
- Special-character setting

Phase 4:
- CSV import/export
- Storage Access Framework

Phase 5:
- Android biometric authentication
- Keystore integration

Phase 6:
- Auto-lock
- Lifecycle handling

Phase 7:
- Five-question recovery/reset
- Change security answers
- Change master password

Phase 8:
- Automated tests
- UI tests
- Security audit
- Documentation
- Release build verification

At each phase, keep the application buildable.

---

# 53. CLAUDE CODE BEHAVIOR

Do not merely describe how to build the application.

Actually inspect the repository and implement the application.

When you encounter an architectural/security decision, favor:

1. Security
2. Data integrity
3. Offline operation
4. Simplicity
5. Maintainability
6. User experience

Do not take shortcuts involving cryptography.

If a dependency is required for secure cryptographic functionality, evaluate whether it is reputable, maintained, and appropriate before adding it.

At completion, report:

- Files created/modified.
- Architecture.
- Encryption design.
- Key-management design.
- Recovery design.
- CSV design.
- Password-generator design.
- Biometric design.
- Auto-lock implementation.
- Tests performed.
- Build result.
- Any remaining security limitations.

Do not claim the application is secure merely because it uses encryption. Explain the actual security model and limitations.

Now begin by inspecting the repository and the supplied `PasswordSafe_template.csv`, then create the implementation plan and proceed with development.
