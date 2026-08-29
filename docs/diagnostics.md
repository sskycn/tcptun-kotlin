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

On Android 11 and newer, process startup also records the most recent system-reported exit
category, sampled PSS/RSS, and the bounded system description in the runtime log. This
distinguishes Java/native crashes, ANRs, low-memory kills, and OEM actions such as Xiaomi
`OneKeyClean` without collecting a crash trace.

If a bug report needs the raw profile or credentials, collect those separately through an
explicit user-controlled channel; never add them to automatic diagnostics.

Core outbound status may include the forward-compatible fields `carrier_mode` and
`carrier_preference`. Android's parser accepts and bounds both fields as well as unknown future
fields; it does not treat a new status property as a failure of the complete status document.
