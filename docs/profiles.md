# Profiles and persistence

Android stores and runs structured Native profiles only. A profile describes one controlled
remote endpoint (server, port, transport, token, TLS or REALITY, Mux/carrier options) and cannot
contain an arbitrary tcptun-go FileConfig.

## Running profiles

Configured profiles become tagged Native outbounds in one runtime plan. The plan also contains a
dynamic balance pool and a direct outbound. Active membership can change without rebuilding the
Android VPN interface. Managed route rules retain stable profile references.

Every configured remote outbound must prove tunnel confidentiality. TLS and REALITY are accepted;
missing security, explicit/effective `none`, and Android ECH profiles are invalid. The run-plan
validator, `VpnService` preflight, and Go Android TUN boundary independently enforce this rule.
Direct is local routing behavior and is not required to have tunnel security.

## Persistence and migration

- Storage version 3 separates public profile fields from AES-GCM encrypted tokens and REALITY
  key material.
- Profile IDs and active IDs are preserved for supported structured profiles.
- Historical arbitrary Core JSON, ECH, and unencrypted structured profiles are removed during
  migration. They are never executed and are never guessed or silently converted to TLS.
- Supported legacy TLS and REALITY profiles keep their original semantics.
- Mutation revisions protect concurrent UI/service updates; malformed storage fails closed.
- Credentials are excluded from diagnostics, ordinary logs, SavedState, and public preferences.

## Import and sharing

The app accepts Native URI, versioned App Link, and T2/T3 QR/profile payloads that decode to
structured fields. JSON clipboard/file import and Full Config editing/export do not exist.
Encoders emit only TLS or REALITY, and decoders reject missing or `none` security before saving.

## Local proxy accounts

Runtime settings keep one canonical local SOCKS5/mixed account list, limited to 256 entries and
stored in encrypted settings. Android-created inbounds emit `users[]`; an empty list preserves
loopback no-auth behavior, while listen-all requires an account.

Each account has independent A1 QR/copy/share actions. A1 carries one username/password pair and
is separate from tunnel profiles. It is a bearer secret: the UI masks it, marks clipboard content
sensitive where supported, warns before sharing, and never places it in logs or navigation state.

## Validation

```text
structured decode/editor validation
  -> run-plan confidentiality validation
  -> VpnService preflight
  -> generated internal Core JSON
  -> androidbridge Android-TUN confidentiality validation
```

Coverage lives in JVM validators plus Android bridge, migration, URI/deep-link, QR, storage, and
lifecycle contract tests.
