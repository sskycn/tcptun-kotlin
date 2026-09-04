# tcptun-go / Android capability matrix

Android is a controlled structured-profile client. tcptun-go remains a general Core/CLI/server;
features omitted from Android are not removed from the Core.

| Capability | Android product | tcptun-go Core |
| --- | --- | --- |
| Native remote outbound | Yes | Yes |
| raw / WebSocket / H2 / H3 transport | Yes | Yes |
| TLS | Required option | Yes |
| REALITY | Required option | Yes |
| `security=none` remote tunnel | No | Yes |
| ECH profile | No | Yes |
| Arbitrary FileConfig JSON import/editor | No | CLI/server configuration remains supported |
| URI / App Link / T2 / T3 / QR | TLS/REALITY structured profiles only | Codec supported |
| Mux and TCP/QUIC/auto carrier | Yes | Yes; Core owns selection and fallback |
| Direct outbound/rules | Yes | Yes |
| Full Tunnel | Yes; only Android platform route mode | Platform-specific |
| Split Tunnel | No | Platform-specific |
| Dynamic profile pool/start-stop | Yes | Runtime-owned |
| Local SOCKS5/mixed proxy and A1 accounts | Yes | Yes |
| Reverse Subnet / P2P topology | Not exposed | Yes |
| DNS/fake-IP, app routing, diagnostics | Yes | Core and Android share responsibilities |

## Confidentiality boundary

Android enforces TLS or REALITY during editor/import validation, run-plan normalization,
`VpnService` startup, and the Go Android TUN start boundary. The Go bridge examines remote Native
outbounds only; Direct is allowed and cannot make an unencrypted Native outbound legal.

The JSON passed to `androidbridge.Engine.Configure` is internal serialization generated from
structured fields. It is not a user-editable or importable Full Config feature.

Android owns `VpnService`, fixed dual-stack Full Tunnel routes and DNS installation, encrypted
persistence, lifecycle, UI, and diagnostics. Go owns protocol, crypto, authentication, Mux,
routing compilation, DNS/fake-IP, Reverse Subnet, and P2P behavior.
