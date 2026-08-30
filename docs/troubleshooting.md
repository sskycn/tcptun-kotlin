# Troubleshooting

## AAR missing or Engine method unavailable

Build the bridge from the neighboring Go checkout:

```bash
./scripts/build-androidbridge.sh
```

Check that `app/libs/androidbridge.aar` contains the expected Android ABIs. The app reports
missing AAR and missing Engine methods separately.

## VPN remains in cleanup pending

This means native shutdown has not been confirmed. Keep the app process alive long enough for
the bounded retry to finish. The client intentionally retains the TUN FD and callback
proxies rather than risking reuse while Go may still own its duplicate.

## Running state is degraded

Open Diagnostics and refresh once. Health checks are event/pull driven with a five-minute local listener safety check.
The safety timer performs only a local SOCKS handshake/request; it does not poll Internet targets. Inspect the underlying network, core state, outbound health, and the
latest redacted error.

## Import rejected

Confirm the URI scheme is supported, the HTTPS envelope is exactly `/v1`, the payload is
canonical and within size limits, and the profile can pass both Android and core validation.

## Device-only issues

OEM foreground-service, VPN permission, battery, and network handover behavior varies. Follow
[device-testing.md](device-testing.md) and record device model, Android version, ABI, and
whether the failure reproduces after a clean install.

## VPN works but local SOCKS/mixed clients stall

Capture evidence before manual recovery; see
[the local proxy investigation](investigations/local-proxy-stall.md) for the call chain,
confirmed accept-backoff failure, layer A–E diagnostics, tests, and remaining risks.
`local_proxy` logs distinguish TCP and protocol failures, and mark device-interface checks
as insufficient to prove external LAN/hotspot access. `bridge_control` phases distinguish
waiting for the shared Java lock from a native call that has not returned.
