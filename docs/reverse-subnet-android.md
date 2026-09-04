# Reverse Subnet and Android

Reverse Subnet remains a tcptun-go Core/CLI/server feature, but the Google Play Android product no
longer accepts arbitrary FileConfig JSON and its structured profile model does not expose Reverse
Subnet or P2P topology. Existing Android Full Config profiles are removed during migration and
cannot start a `VpnService` session.

Android has one platform route mode: dual-stack Full Tunnel. It installs `0.0.0.0/0`, `::/0`, and
the fixed VPN DNS server. A structured Native remote endpoint must use TLS or REALITY; structured
Direct rules can still bypass the encrypted proxy for selected traffic. Legacy Split Tunnel route
metadata collapses to Full Tunnel during migration.

Use tcptun-go clients and the examples from the locked Core checkout for Reverse Subnet, P2P, ACL,
connector, and relay/direct-fallback deployments. Do not import those JSON files into the Android
app. This Android product boundary does not remove those capabilities from tcptun-go.
