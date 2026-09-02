# Reverse Subnet on Android

Android is a Remote client. The application owns `VpnService`, the platform route table,
persistence, lifecycle, UI, and diagnostics. tcptun-go remains the only implementation of Native
authentication, compiled routing, Reverse Subnet ACL, principal authorization, MUX, P2P control,
STUN, permits, direct QUIC, and relay fallback.

```text
Android app → TUN → Go compiled router → remote-edge Native outbound
            → Edge reverse_subnet → Home Connector → LAN target
```

## Configuration boundary

Use encrypted Full tcptun-go JSON for the Remote topology. Reverse Subnet and P2P are not added to
T2, T3, A1, Native URI, or profile QR. The Android client stores only the outbound token; the Edge
maps that credential to a server-side principal. Android never sends, derives, or authorizes a
principal.

Current full configs preserve `route_mode`, `principals`, `subnets`, `export_subnets`, destination
`ports`, `reverse_subnet`, resource budgets, root P2P listen configuration, outbound `p2p`, IPv4
and IPv6 rendezvous arrays, optional STUN, and `host_candidates`. Go `ValidateConfig` and the Go
compiler are authoritative after Android size/nesting/deprecation safety checks.

## Platform route modes

Full Tunnel is the default for clean installs and every upgrade without Android route metadata:

```text
0.0.0.0/0
::/0
DNS 10.77.0.1
```

Home-network Split Tunnel is explicit Android-only metadata. CIDRs are parsed as numeric prefixes,
canonicalized, deduplicated, bounded, and prohibited from containing an implicit `/0`. Only those
routes enter the VPN. Optional unicast home DNS addresses must be numeric and covered by one of the
configured prefixes.

The app reads enabled `dns.fake_ip.ipv4_range` and `ipv6_range` from the already prepared Core
config once during VPN establishment and adds those prefixes to a Split Tunnel. It does not
implement a DNS or fake-IP server. DNS handling and domain restoration remain in Go.

## P2P and privacy

P2P defaults to disabled. `host_candidates` also defaults to disabled; enabling it in Full JSON
may disclose selected private interface addresses to the authorized peer. Tokens, credentials,
session secrets, probe credentials, permits, private keys, complete payloads, and candidate
endpoints are excluded from ordinary logs and diagnostics.

All Go-created TCP/UDP/IPv4/IPv6 sockets continue through the existing `SocketProtector` callback
and `VpnService.protect(fd)`. Android does not open punching sockets or choose direct versus relay.
After network handover an established direct QUIC session may end. A later flow gathers fresh
candidates or safely falls back to relay; seamless direct-session migration is not promised.

## Supported traffic and limits

IPv4/IPv6 TCP and fixed-destination UDP work through the logical proxy. This covers SSH, HTTP(S),
SMB's TCP path, Home Assistant, and unicast LAN DNS over UDP/TCP 53 when ACLs allow them. tcptun is
not an L2 VPN: ICMP/ping, Ethernet, ARP/NDP transport, broadcast, multicast, mDNS, SSDP, and
Wake-on-LAN broadcast are unsupported.

See [device testing](device-testing.md) and the [repeatable lab](reverse-subnet-lab.md).
