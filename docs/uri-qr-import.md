# URI, QR, and App Link import

Supported direct schemes:

- `native://`
- `vless://`
- `vmess://`
- `trojan://`

Versioned HTTPS links use exactly:

```text
https://x.tcptun.com/v1#p=<unpadded-base64url(profile-uri)>
```

The fragment keeps the profile payload out of ordinary HTTP requests. The Android Manifest
and codec both restrict the host and path to `/v1`; non-canonical payloads, queries, ports,
unknown paths, oversized inputs, and unsupported schemes are rejected.

QR export continues to use the existing tcptun-go compact `T2:`/`T3:` codec. Profiles whose
fields cannot be represented compactly, including resumable mux or ECH settings, are not
silently lossy-encoded.

Every imported payload passes URI decoding, Android validation, and core non-listening
validation before persistence. Import never starts the VPN automatically. Existing equivalent
profiles are reused instead of duplicated.

The production App Link association must be published at:

```text
https://x.tcptun.com/.well-known/assetlinks.json
```

Coverage is in `ProfileDeepLinkTest`, `ProfileQrCodeUiTest`, and import UI tests.
