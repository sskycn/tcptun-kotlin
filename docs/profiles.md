# Profiles and persistence

Profiles are saved locally through the existing `ProfileStore`. Structured profiles are
editable Android models; complete strict tcptun-go JSON profiles are preserved as raw JSON.
The app validates both the Android model and a non-listening core runtime construction before
persisting an imported profile.

## Running profiles

Active structured profiles become tagged native outbounds in one runtime plan. The plan also
contains a dynamic balance pool and a direct outbound. Row actions hot-start or hot-stop one
outbound without rebuilding the Android VPN interface.

Managed route rules retain their stable profile references. A stopped profile remains a valid
rule target and becomes usable again when that profile is started.

## Persistence rules

- Profile IDs and active IDs are persisted by the existing store.
- Storage version 3 keeps public profile JSON separate from encrypted secret payloads. Tokens,
  raw core JSON, and Reality/ECH secret-like values never appear in public preferences.
- Legacy plaintext is replaced only after the new AES-GCM payload is written and read back.
  A failed migration leaves the old authoritative data intact for retry.
- Mutation revisions protect concurrent UI/service updates.
- Import and delete operations are bounded and recoverable.
- Credentials are not included in diagnostics or ordinary logs.
- Existing migrations, raw JSON fields, and compatibility behavior remain unchanged.

## Local proxy accounts

Runtime settings keep one canonical list of local proxy accounts, limited to 256 entries. The
encrypted schema stores the complete list; the previous encrypted `{username,password}` payload
and older preference fields are read as zero or one account and upgraded only after a verified
encrypted write. Passwords are excluded from ordinary preferences and saved UI state.

Android-created `socks5` and `mixed` inbounds emit `users[]`, never legacy top-level credentials.
An empty list omits `users` and preserves loopback no-auth behavior. Listen-all still requires an
account and generates a 192-bit password when enabled from an empty list. Duplicate usernames and
the Go 255-byte SOCKS credential limit are checked before save; tcptun-go remains authoritative.

Full JSON preserves `users[]` for mixed, socks5, native, VLESS (including per-user `flow`), VMess,
and Trojan inbounds unless the inbound is the reserved Android listener being replaced. Tunnel
users are never imported into local settings, and outbounds remain a single client identity.

Each local proxy account has independent A1 QR/copy/share actions. One `A1:` payload contains one
username/password pair only; there is no multi-account bundle. Scanning A1 opens a password-masked
preview. A new username is appended, an identical account is reused, and a conflicting username
requires explicit confirmation before its password is updated. Import reuses the same encrypted
runtime-settings CAS write and apply path; A1 text, QR bytes, and credentials are not placed in
SavedState, navigation arguments, logs, or diagnostics. A1 is Base45-encoded, not encrypted.

## Validation

Use the existing validators before save:

```text
Android profile validation -> bridge JSON construction -> tcptun-go validation
```

Profile persistence coverage lives in JVM store/codec tests and Android contract/UI tests.
