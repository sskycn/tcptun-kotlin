# TcpTun Google Play Store Listing

## Basic information

- App name: `TcpTun`
- App type: App
- Package name: `com.tcptun.client`
- Suggested category: Tools
- Contains ads: No (no advertising SDK is present in the current project; confirm that no advertising dependency is added to the Release build)

## Short description

VPN client with profile import, routing rules, and connection diagnostics.

## Full description

TcpTun is an Android VPN and transparent proxy client.

The app creates a device-level VPN interface through Android VpnService and forwards traffic to the remote endpoint configured by the user. The user provides and manages the server address, port, protocol, and credentials. Use an encrypted transport configuration appropriate for the server you control or trust.

TcpTun does not operate VPN nodes, proxy servers, subscriptions, or cloud services. It does not require sign-in or registration and has no advertising, behavioral analytics, or crash-reporting feature. Profiles, routing rules, and runtime state are primarily processed on the device.

Features:

• Manage multiple connection profiles and start or stop them individually
• Import profiles from the clipboard, QR codes, system links, or the manual form
• Support for VLESS, VMess, Trojan, and native tcptun-go profiles
• IPv4/IPv6 VPN traffic and transparent proxy support
• Ordered routing rules, a default outbound, and a dynamic connection pool
• Local SOCKS5 or mixed proxy listener
• VPN, underlying-network, connection, and Go-core diagnostics
• In-app runtime logs and optional traffic-destination analysis

TcpTun does not start an imported profile automatically. The user must select a profile and confirm Android's VPN permission prompt before a connection starts.

When VPN mode is used, device traffic is sent to the remote service selected in the profile. Use only servers you trust, and review the server operator's privacy policy, logging practices, and transport configuration.

While running, the app may use the current outbound connection for lightweight connectivity probes to Google and Cloudflare. When the user starts TCPing diagnostics, it tests port 443 on Google, GitHub, and Cloudflare. These requests are for connectivity diagnostics and are not sent to the TcpTun operator, but the selected endpoint and target sites may process connection metadata under their own policies.

## What's new in v0.2.51

Initial release of TcpTun:

• Manage profiles with manual, clipboard, QR, and system-link import, plus QR sharing
• Support native tcptun-go, VLESS, VMess, and Trojan profiles
• Android VPN transparent proxy for TCP/UDP, IPv4/IPv6, DNS, and fake-IP
• Local SOCKS5/mixed proxy and routing by domains, IPs, CIDR, ranges, and apps
• Dynamic outbounds, runtime switching, traffic analysis, rule generation, TCPing, health diagnostics, logs, recovery, and Chinese/English UI

## App access / reviewer instructions

The app does not require an account, sign-in, registration, or subscription. Use the dedicated test profile or QR code supplied privately in the Play Console review instructions:

1. Install and open TcpTun.
2. Import the review-only test profile from a QR code or the clipboard.
3. Tap the profile and confirm Android's VPN permission prompt.
4. Verify the VPN status, local proxy address, and basic connectivity.
5. Open Diagnostics from the top-right menu to inspect VPN, network, and Go-core status.

Do not put production server addresses, accounts, UUIDs, passwords, or private nodes in the public listing. Provide test credentials only through Play Console's private review instructions.

## VPN declaration draft

- Is VPN the core functionality: Yes
- Purpose: Create an Android device-level VPN interface and forward user-selected traffic to a user-configured remote service
- Other non-VPN core functionality: None
- Is VPN use documented in the store listing: Yes
- Review demonstration: Import a dedicated test profile, start the VPN, inspect status, and stop the VPN

## Data safety checklist before submission

This is a repository-based submission aid, not a replacement for the Play Console form or a legal declaration:

- Operator-owned backend: no account, cloud-sync, advertising, analytics, Crashlytics, telemetry, or crash-reporting endpoint was found. The TcpTun operator does not operate the remote VPN/proxy nodes.
- Local processing: profiles, credentials, routing rules, some runtime settings, app package names/labels, diagnostic state, logs, and optional traffic-analysis events are used locally; QR frames are used for on-device recognition.
- Do not turn “the app does not collect data for the developer” into “the network never transmits anything.” When VPN is enabled, user traffic is forwarded to the remote endpoint configured by the user. Connectivity probes also connect to Google and Cloudflare, and user-triggered TCPing connects to Google, GitHub, and Cloudflare.
- Complete the Play Console collection/sharing fields for the exact Release artifact. Where the form offers third-party processing, ephemeral processing, or relevant network-data options, describe the remote-endpoint and diagnostic behavior accurately instead of selecting a blanket “no data processing” answer.
- Camera access is used only for a user-initiated QR scan; the current Android code contains no path that sends camera frames to a TcpTun operator server.
- If a future release adds an operator backend, subscriptions, statistics, ads, crash reporting, or remote profile downloads, update this policy, the Data safety form, and the store listing together.

## Privacy policy URL

After publishing the formal policy based on `privacy-policy-draft-en-US.md`, record the final URL here:

`https://<your-domain>/privacy/tcptun-en-US`
