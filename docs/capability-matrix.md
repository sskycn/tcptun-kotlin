# tcptun-go / Android capability matrix

Baseline audited on 2026-09-02:

- tcptun-kotlin starting commit: `008a470ac758ca5429e73e50a4d69b72781f2362`
- tcptun-go target: `v0.5.0` commit `c4959ca9edf4ecfcdd6370eb058615c8ad7c7ab6`
- previous core pin: `5fea14bb57f997303044d1bbd7826a3eca2a2620`
- gomobile contract: unchanged; Bridge API remains `3`

The Go `FileConfig`, compiler, runtime, examples, tests, and Android Bridge are the
authority. “Raw” below means a bounded full JSON profile passed to Go validation;
it does not mean that Kotlin implements the feature.

| Go capability | Android applicability | Before | Gap / implementation | Evidence / test |
| --- | --- | --- | --- | --- |
| Native inbound/outbound | Yes | Structured + raw | Keep Core-owned | profile and AAR contract tests |
| direct outbound | Yes | Raw + managed bypass | Keep Core-owned | raw fixture validation |
| SOCKS5 inbound/outbound | Yes | Local inbound + raw | Preserve multi-user/auth mode | local proxy and raw tests |
| mixed inbound/outbound | Yes | Local inbound + raw | Preserve HTTP/SOCKS auth | local proxy and raw tests |
| blackhole outbound | Yes | Raw | Preserve without Kotlin schema | raw round trip |
| balance outbound | Yes | Dynamic profile pool + raw | No regression | `ProfileRunPlanTest` A/A+B/B |
| chain / `via` | Yes | Raw | Preserve current Go shape | raw round trip |
| multiple outbound addresses | Yes | Raw; structured single address | Preserve arrays | Go example fixtures |
| failover / carrier fallback | Yes | Core-owned | No Kotlin selection logic | AAR/core tests |
| inbound/outbound network capability | Yes | TCP/UDP arrays | Raw preparation preserves endpoint capability | Keep Core validation authoritative | raw preservation tests |
| TCP / UDP | Yes | Both | Captured by Full Tunnel; Core routes each logical flow | route-plan tests + device test |
| IPv4 / IPv6 | Yes | Full-tunnel dual stack | Always install dual-stack default routes | route-plan tests + device test |
| raw / WebSocket / H2 / H3 transport | Yes | Structured and raw | Keep Go validation authoritative | profile + raw tests |
| none / TLS / REALITY | Yes | Structured and raw | Keep crypto in Go | profile/AAR tests |
| ECH ClientHello | Yes | Structured and raw | No regression | codec/config tests |
| SNI, Reality keys, short IDs | Yes | Structured and encrypted storage | No regression or logging | storage/redaction tests |
| TCP / QUIC / auto carrier | Yes | Structured and raw | Core owns selection | profile + contract tests |
| carrier preference / UDP mode | Yes | Structured and raw | No regression | profile tests |
| stream/connection receive windows | Yes | Structured and raw | Preserve current/new fields | config tests |
| reconnect and carrier fallback | Yes | Core-owned, Android observes | No duplicate Kotlin state machine | lifecycle tests |
| MUX enable, sessions, streams, warm spare | Yes | Structured and raw | No regression | config tests |
| MUX resume, timeout, buffer | Yes | Structured and raw | No regression | config tests |
| resource budgets | Yes | Raw | Must survive Android preparation | raw preservation tests |
| multiple inbound users | Yes | Local accounts + raw | Preserve principal fields in raw | raw/security tests |
| Native principal | Server-authorized; Android consumes result | Raw | Never derive principal from token | principal fixture test |
| SOCKS / mixed HTTP authentication | Yes | Local accounts + raw | Core owns authentication/crypto | AAR contract tests |
| `auth_mode` secure/standard/auto | Yes | Raw | Preserve verbatim | raw preservation test |
| route inbound matcher | Yes | Raw + managed | Preserve | route tests |
| route principal matcher | Yes, mainly server topology | Missing on old Core; raw parser permissive | Upgrade Core and preserve | reverse fixture tests |
| route network matcher | Yes | Raw + managed | Preserve | route tests |
| domain/regex/suffix matcher | Yes | Raw; subset managed | Preserve full JSON | route tests |
| IP/CIDR/range matcher | Yes | Raw; subset managed | Preserve; Android platform route table remains Full Tunnel | route + plan tests |
| app IDs/prefix/platform/attributes | Android app IDs are relevant | Managed subset + raw | Preserve attributes | app-routing tests |
| DNS servers / strategy / outbound | Yes | Raw; structured defaults | Keep DNS policy Core-owned; Android installs fixed VPN DNS | route-plan tests |
| DNS TCP / UDP | Yes | Go-owned | Keep Core-owned | AAR/device tests |
| fake-IP IPv4/IPv6, TTL, capacity | Yes | Go-owned raw/default | No Android-specific platform routes needed under Full Tunnel | plan tests |
| fake-IP domain restore | Yes | Core-owned | No Kotlin implementation | Go tests/AAR validation |
| publish / expose reverse services | Yes for full topology | Raw | Preserve | raw fixture tests |
| reverse carrier/service | Yes | Raw | Preserve | AAR validation |
| Reverse Subnet IPv4/IPv6 TCP/UDP | Android Remote: yes | Old AAR cannot compile new schema | Upgrade AAR; use Full Tunnel plus Core/Edge routing | AAR + device/E2E |
| Edge ACL / Home export ACL | Server/Home; Android must obey | Core-owned | Never copy ACL to Kotlin | Go tests + E2E deny cases |
| CIDR/network/port policies | Yes through Core | Raw | Preserve `subnets`, `export_subnets`, `ports` | fixture/AAR tests |
| principals / connector principals | Server/Home | Raw | Preserve; never log/derive | fixture/security tests |
| native `route_mode=rules` | Yes | Raw | Preserve; independent of Android platform routing | fixture round trip |
| multiple sites / overlap + principal routing | Yes through Core | Raw | Preserve; Core compiles | Go tests + AAR fixture |
| special-address rejection / ACL intersection | Yes, security critical | Core-owned | No Kotlin policy copy | Go/E2E deny tests |
| relay mode | Yes | Core-owned | Default remains relay-only | AAR/E2E |
| P2P enabled / rendezvous IPv4+IPv6 | Yes | Raw was preserved but old Core rejected | Upgrade AAR; show safe state | fixtures/AAR test |
| host candidates | Yes, privacy-sensitive | No Android UI | Default off; explicit warning | settings/privacy test |
| STUN / edge-reflexive / peer-reflexive | Yes | Core-owned | Preserve config only | Go tests/AAR fixture |
| P2P strategy `balanced|aggressive` | Yes | Raw Full JSON | Preserve verbatim; Core validates and owns behavior | AAR fixture |
| bounded retry/prediction/background readiness | Yes | Core-owned | No Kotlin state machine | Go tests + network qualification |
| P2P control v2 + v1 fallback | Yes | Core-owned | No Kotlin protocol | Go tests/E2E |
| authenticated direct QUIC / permits | Yes | Core-owned | Existing `SocketProtector` for every socket | Go bridge + device test |
| relay fallback | Yes | Core-owned | Do not restart VPN or bypass auth | E2E |
| network handover | Yes | Underlying network coordinator exists | New flows re-gather/fallback; no migration promise | stress test |
| P2P resource cleanup | Yes | Bridge lifecycle exists | Verify stop/handover | lifecycle/device tests |
| Android TUN IPv4/IPv6 | Yes | Fixed addresses, default routes | Keep immutable Full Tunnel route plan | JVM/device tests |
| VpnService lifecycle / TUN ownership | Yes | Implemented | Preserve actor/single-writer ownership | lifecycle tests |
| SocketProtector | Yes | Implemented | Keep installed before start | AAR/device tests |
| AppIdentityProvider / app routing | Yes | Implemented | No regression | JVM/instrumentation |
| flow analysis | Yes | Implemented | No regression | instrumentation |
| full tunnel | Yes | Only platform mode | Always install IPv4/IPv6 default routes | route-plan test |
| split tunnel | No | Previously Android-only | Removed; routing policy belongs to Core/Edge | migration + route-plan tests |
| DNS/fake-IP platform routes | Fixed VPN DNS only | Fixed DNS/default routes | Default routes already capture fake-IP ranges | JVM/device tests |
| process recreation / foreground service | Yes | Implemented | No route-plan state to restore; rebuild Full Tunnel | recreation tests |
| Native URI / T2 / T3 / QR | Yes | Implemented | Do not change wire | codec/AAR tests |
| A1 local account | Yes | Implemented | Do not change wire | codec/AAR tests |
| App Link | Yes | Implemented | No regression | URI tests |
| full FileConfig import/share | Import/run: yes; public sharing is secret-sensitive | Encrypted raw storage | Preserve schema; do not put full JSON in T3 | storage/config tests |
| dynamic outbound start/stop | Yes | Implemented | No regression | A/A+B/B tests |
| runtime status / health / TCPing | Yes | Implemented | Extend only with stable facts | diagnostics tests |
| core version/build ID | Yes | Implemented | Align lock/AAR/docs | Bridge lock tests |
| bridge API/version | Yes | API 3 | Keep 3 because ABI unchanged | ABI contract test |
| log levels | Yes | Implemented | No secret endpoints/permits | redaction tests |
| P2P direct/relay stable status | Relevant | Go Bridge exposes no stable status field | Do not parse logs; show configured enabled only | documented gap |
| ICMP, Ethernet/L2, ARP/NDP, broadcast, multicast, mDNS | No | Unsupported | N/A: tcptun is logical TCP/UDP proxy | documentation |
| Windows/Wintun and daemon-only operations | No | Not exposed | N/A on Android | documentation |

## Implementation boundary

Android owns `VpnService`, fixed Full Tunnel route/DNS installation, encrypted
persistence, lifecycle, UI, and diagnostics. Go remains the only implementation
of protocol, crypto, authentication, MUX, routing compilation, DNS fake-IP,
reverse services, Reverse Subnet ACL, P2P candidate gathering/STUN/control/
permits/direct QUIC, and relay fallback.
