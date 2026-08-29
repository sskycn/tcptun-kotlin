# URI, QR, and App Link import

Supported direct schemes:

- `native://`

VLESS, VMess, and Trojan were removed from tcptun-go v0.4.0. Pasting or scanning one of those
legacy URIs returns a protocol-specific unsupported error; its credential is never reinterpreted
as a Native token. A legacy Native URI may contain `fp=` for compatibility, but the value is
ignored and is absent from every new URI and T3 export.

Versioned HTTPS links use exactly:

```text
https://x.tcptun.com/v1#p=<unpadded-base64url(profile-uri)>
```

The fragment keeps the profile payload out of ordinary HTTP requests. The Android Manifest
and codec both restrict the host and path to `/v1`; non-canonical payloads, queries, ports,
unknown paths, oversized inputs, and unsupported schemes are rejected.

Tunnel profile QR export continues to use the tcptun-go Native compact `T2:`/`T3:` codec.
Profiles whose fields cannot be represented compactly, including resumable mux or ECH settings,
are not silently lossy-encoded.

Local proxy credentials use the independent `A1:<Base45>` format. One A1 always contains exactly
one username/password pair and never contains a profile, listener address, port, protocol, or
merge policy. It uses the same binary-to-Base45 QR-alphanumeric outer strategy as T3, but a
separate frozen wire schema owned by tcptun-go. Kotlin calls the Android Bridge and never
implements the A1 binary or Base45 codec.

Scanner dispatch is prefix-separated: A1 enters a proxy-account preview, while T2/T3 and the
supported URI/App Link forms remain profile imports. An A1 is never passed to `ProfileUriCodec`
or converted into `AppConfig`. New usernames append to encrypted `RuntimeSettings`; identical
accounts are not duplicated; password conflicts require an explicit update choice. A new account
is blocked at the 256-account limit, while explicitly updating an existing username remains
allowed.

A1 is not encrypted. Its text and QR image are bearer secrets. The UI hides the password by
default, marks copied A1 text sensitive on supported Android versions, warns before system share,
and does not put A1/password data in logs, diagnostics, SavedState, or navigation arguments.

Every imported payload passes URI decoding, Android validation, and core non-listening
validation before persistence. Import never starts the VPN automatically. Existing equivalent
profiles are reused instead of duplicated.

The production App Link association must be published at:

```text
https://x.tcptun.com/.well-known/assetlinks.json
```

Coverage is in `ProfileDeepLinkTest`, `ProfileQrCodeUiTest`, `ProxyAccountA1BridgeTest`, and import
UI/unit tests.
