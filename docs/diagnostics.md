# Diagnostics and redaction

`TcptunDiagnosticsSnapshot` is the support-facing model. It contains:

- Application version and build.
- Core version and build ID.
- VPN state and session ID.
- Active network type and availability.
- TUN MTU and TCP/UDP state fields.
- Outbound tag, health, latency, and last error.
- Typed, timestamped user-safe errors.

The snapshot is built from the immutable runtime state and immediately redacted. It does not
accept or serialize a profile JSON document. `safeText()` is intentionally a small support
format, not a persistence format.

Redaction covers credentials commonly found in URI, JSON, headers, and key/value errors,
including tokens, passwords, UUIDs, authorization headers, private keys, and compact profile
payloads. Redaction is applied before state/log storage as well as before support text.

Tests:

- `DiagnosticsSnapshotTest` checks required sections and secret removal.
- `SensitiveTextTest` checks state/log redaction.
- `AndroidBridgeContractTest` verifies that core status data remains credential-free.

If a bug report needs the raw profile or credentials, collect those separately through an
explicit user-controlled channel; never add them to automatic diagnostics.
