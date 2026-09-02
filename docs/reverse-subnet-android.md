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
and IPv6 rendezvous arrays, optional STUN, `host_candidates`, and the v0.5.0
`strategy: balanced|aggressive` field. Go `ValidateConfig` and the Go compiler are authoritative
after Android size/nesting/deprecation safety checks.

## Platform routing

Android always runs Full Tunnel and installs the dual-stack default routes when the VPN is
established:

```text
0.0.0.0/0
::/0
DNS 10.77.0.1
```

There is no Android platform split-tunnel mode and no user-configurable home CIDR/DNS route plan.
Internet proxy/VPN traffic and Reverse Subnet traffic enter the same TUN. Go compiled routing and
the Edge topology decide whether each flow uses the normal Internet outbound, another configured
outbound, or a `reverse_subnet` outbound.

The tcptun-go Native `route_mode` field is independent of Android platform routing and remains
preserved in Full JSON. In particular, `route_mode=rules` may be required for an Edge Native
inbound to evaluate the compiled route rules for each flow.

Older Android releases could persist a Home-network Split Tunnel plan. New releases do not restore
that behavior: legacy route metadata collapses to Full Tunnel and is retired from runtime-settings
storage during migration.

DNS handling and fake-IP domain restoration remain in Go. Android no longer derives or installs
additional platform routes from `dns.fake_ip.ipv4_range` or `ipv6_range`; the default routes already
capture those destinations.

## P2P and privacy

P2P defaults to disabled. `host_candidates` also defaults to disabled; enabling it in Full JSON
may disclose selected private interface addresses to the authorized peer. Tokens, credentials,
session secrets, probe credentials, permits, private keys, complete payloads, and candidate
endpoints are excluded from ordinary logs and diagnostics.

`strategy` defaults to `balanced`. `aggressive` enables Core-owned bounded retry, evidence-gated
port prediction, and shorter background refresh intervals. Android preserves this field but does
not predict ports, schedule candidate checks, or infer Direct/Relay state from logs.

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
