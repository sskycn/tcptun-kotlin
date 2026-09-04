# Reverse Subnet lab scope

The Android app no longer exposes Full Config import, so the former Android Remote lab is not a
supported product workflow. Reverse Subnet, P2P, ACL, connector, and relay/direct-fallback
validation belongs to tcptun-go Core/CLI/server test and lab environments. Use the exact Core
commit recorded in `bridge.lock` and its example configurations; do not import them into Android.

Android device validation should instead cover:

- structured Native TLS and REALITY profiles;
- dual-stack Full Tunnel routes and fixed VPN DNS;
- structured Direct rules;
- TUN ownership and socket protection;
- Wi-Fi/cellular handover and lifecycle cleanup; and
- rejection of `security=none`, ECH, and arbitrary Full Config JSON.

Record `NOT RUN` for every unexecuted device or network case. Configuration parsing and AAR
validation do not constitute network evidence.
