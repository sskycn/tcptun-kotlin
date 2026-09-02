# Reverse Subnet Android E2E lab

This lab records real evidence; it is not replaced by JVM or AAR validation. Use three independently
reachable nodes and the exact locked Core commit from `bridge.lock`:

```text
Android Remote → Go Edge → Go Home Connector → IPv4/IPv6 LAN targets
                        ↘ authenticated direct QUIC ↗
```

## Preparation

1. Build `tcptun-go` at the locked commit with `make build`, and install the same binary on Edge
   and Home.
2. Start from `examples/reverse-subnet-p2p-edge.json`, `-home.json`, and `-remote.json` in that
   checkout. Replace placeholders through a protected secret mechanism; never commit rendered
   configs or paste them into the report.
3. Import the Remote config as Full JSON on Android. Keep `host_candidates: false` for the first
   pass. Configure the Android Split Tunnel with the same IPv4/IPv6 home CIDRs and unicast DNS.
4. Run TCP echo/HTTP and UDP echo targets on both LAN families. Run a DNS server reachable over
   UDP and TCP 53.

## Relay and ACL matrix

Disable P2P on Remote and Home, then record PASS/FAIL with packet captures on Edge/Home:

| Case | Expected |
| --- | --- |
| IPv4 TCP, IPv6 TCP | allowed flows reach the LAN target |
| IPv4 UDP, IPv6 UDP | allowed fixed-destination flows reach the LAN target |
| DNS UDP 53, DNS TCP 53 | allowed queries return through relay |
| wrong principal | denied before target dial |
| denied CIDR | denied |
| denied TCP port / UDP port | denied |
| loopback, multicast, special address, default-route attempt | denied |

Test Edge policy and Home export policy independently, then their intersection. A denial must not
be converted into a successful relay fallback.

## Direct and fallback matrix

Enable P2P on Remote and Home and configure Edge IPv4/IPv6 rendezvous. Confirm an allowed TCP flow
and fixed-destination UDP flow use authenticated direct QUIC using Edge/Home evidence, not parsed
Android log text. Then separately block rendezvous UDP, STUN, and candidate connectivity. Each
direct failure must retain a working authorized relay path. Authorization, permit, and Home ACL
failures must remain failures.

## Handover and cleanup

With a direct session active, perform Wi-Fi → cellular → Wi-Fi and open a new flow after each
transition. Direct may be re-established or relay may be selected. Record Android Diagnostics,
service/TUN ownership, Edge/Home connection state, and FD counts. Stop during direct activity and
during reconnect; verify no stale TUN, native socket, Engine, callback, foreground notification,
or deferred restart remains.

## Evidence template

```text
tcptun-go commit:
Android build/core build ID:
device/API/ABI:
Edge/Home/LAN topology:
relay IPv4 TCP/UDP:
relay IPv6 TCP/UDP:
LAN DNS UDP/TCP:
principal deny:
CIDR/port/special-address deny:
P2P direct TCP/fixed UDP:
relay fallback:
authorization failure cannot fallback:
Wi-Fi/cellular handover:
final resource state:
```

Use `NOT RUN` for every unexecuted row. Never infer a network PASS from config parsing, AAR
`ValidateConfig`, or a lifecycle-only direct fixture.
