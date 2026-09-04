# TcpTun Privacy Policy

Effective date: 2026-09-04

This policy describes the TcpTun Android client only. Remote endpoints configured by users are not provided or operated by the TcpTun project.

## 1. Operator and contact

The Android app is published under the developer identity shown on its Google Play listing and maintained by the TcpTun open-source project.

Privacy inquiries: [TcpTun GitHub issues](https://github.com/sskycn/tcptun/issues). Do not include passwords, tokens, private profiles, or other secrets in a public issue.

## 2. What the app is

TcpTun is an Android VPN and transparent proxy tool that runs locally on the user's device. It uses Android `VpnService` to create a VPN interface and connects to remote endpoints provided or selected by the user.

The TcpTun operator does not provide, sell, rent, or manage VPN nodes, proxy servers, subscription services, or cloud configuration services. The app has no operator-owned backend for accounts, synchronization, analytics, advertising, or crash reporting.

## 3. What we do not collect

Based on the current Android client code, the app does not upload the following information to a TcpTun operator server. The app also has no account system and no advertising, analytics, or crash-reporting SDK:

- names, email addresses, telephone numbers, accounts, or sign-in credentials;
- contacts, SMS, call logs, location, or the device address book;
- usage analytics, advertising identifiers, or crash reports;
- profiles, VPN traffic, traffic-analysis events, runtime logs, or QR-code images.

In this policy, “we do not collect” means that the TcpTun operator does not receive or retain these data from the app. It does not mean that a remote endpoint configured by the user, or a connectivity-test target, cannot observe network connection information. See Section 5.

## 4. Local processing and storage

To provide its features, the app may process the following data only on the Android device:

- User-imported or manually entered profile data, including server address, port, protocol, authentication data, TLS/transport parameters, and other settings;
- Routing rules, including domains, IP/CIDR ranges, app-package matching rules, and outbound selection;
- Runtime settings, such as MTU, local SOCKS/mixed proxy port, routing options, log level, and traffic-analysis selection;
- Installed app package names and labels, and local network-interface/IP information, for app routing, traffic analysis, and diagnostics;
- VPN, proxy, and connection diagnostic state;
- When the user explicitly enables traffic analysis, destination domain/IP, port, protocol, route reason, app package name, and timestamps for the selected app;
- Profile text or profile links read when the user explicitly taps “Import from clipboard.” After a successful import, the app attempts to clear clipboard text that still matches the imported value;
- Camera preview frames while the user opens the QR scanner. The frames are used to recognize a profile QR code on the device; the current app code contains no path that uploads camera frames to a TcpTun operator server.

Non-secret profile fields, routing rules, and some runtime settings are stored in app-private Android `SharedPreferences`. Profile credentials are stored separately with AES-256-GCM encryption using a key protected by Android Keystore. Users should still protect their device and exported or shared profile data. Runtime logs and traffic-analysis events are primarily held in app memory. Before logs are shown in the app or written to visible Android Logcat while the UI is visible, sensitive fields are redacted. The app has no feature that uploads these items to an operator server.

## 5. VPN traffic, diagnostic connections, and third-party endpoints

### 5.1 User-configured remote endpoints

When VPN mode is enabled, device traffic is sent to the remote endpoint selected in the profile. The server address, port, protocol, and authentication data needed to establish the connection are used according to that network protocol.

The TcpTun operator does not own or operate these endpoints and does not receive endpoint traffic, account credentials, or server logs through TcpTun. A remote endpoint operator may see or retain connection time, source IP, destination information, traffic metadata, and content that is not protected by end-to-end encryption. What is visible, whether it is logged, and how long it is retained depend on the endpoint, the transport configuration, and the endpoint operator. Use only endpoints you trust and review their separate privacy policies.

TcpTun does not guarantee that a user-provided profile or endpoint is secure. Version 0.5.0 requires an encrypted TLS or REALITY tunnel for Android VPN profiles; overall security still depends on the profile parameters, device, and remote endpoint configuration.

### 5.2 Connectivity checks performed by the app

While a VPN is running, the app may perform lightweight HTTPS `204` connectivity probes through the currently selected outbound connection to:

- `connectivitycheck.gstatic.com/generate_204`;
- `cp.cloudflare.com/generate_204`.

When the user taps the bottom status area to run TCPing diagnostics, the app tests port 443 through the current outbound connection on:

- `google.com`;
- `github.com`;
- `cloudflare.com`.

These requests are for connectivity diagnostics and are not sent to the TcpTun operator. Google, Cloudflare, or GitHub may process network-connection metadata under their own policies and logging practices. TCPing is user initiated. Upstream health probes are triggered by VPN and connection-diagnostic logic, not by an account or behavioral analytics system.

## 6. Sharing

The TcpTun operator does not sell, rent, or share app data with advertisers, data brokers, or analytics providers. The app has no account, synchronization, advertising, or telemetry endpoint that sends app data to the developer.

However, when the user enables the VPN or local proxy, the remote endpoint selected by the user receives network connections forwarded according to the profile. When the app runs the connectivity probes above or the user starts TCPing, the corresponding target sites receive network connections. This is part of the network functionality, not collection by the TcpTun operator, and is governed separately by the relevant endpoint or site operator.

## 7. Retention and deletion

- Profiles, routing rules, and runtime settings stored on the device remain until the user deletes or changes them in the app, uses Android’s “Clear app data,” or uninstalls the app;
- In-app runtime logs and traffic-analysis events are held in memory and can be cleared in the app. Traffic-analysis state is cleared when the related feature is stopped or the analysis target changes; other in-memory state disappears when the app process ends;
- The TcpTun operator has no cloud copy of these data. Whether a remote endpoint or Google, Cloudflare, or GitHub retains network logs is outside TcpTun’s control; deletion or access requests must be directed to the relevant operator.

## 8. Permissions and third-party components

The app requests network-state, network-access, camera, foreground-service, and notification-related permissions. Camera access is used only when the user opens the QR scanner and grants permission. VPN and foreground-service permissions are used for the core VPN function and its status notification.

The current project uses CameraX, Google ML Kit Barcode Scanning, an Android bridge, and the tcptun-go network core. No advertising, Firebase Analytics, Crashlytics, or standalone telemetry SDK was found in the current project dependencies and Android code. Third-party endpoints and websites are not operated by the TcpTun operator and are governed by their own policies.

## 9. Children’s privacy

TcpTun is a general-purpose network tool and is not directed to children. We do not knowingly collect children’s personal information. If a parent or guardian believes that a child provided personal information to us, please contact us using Section 1. Because the app has no operator backend, we do not receive such information from the app.

## 10. Security statement

The app uses Android app-private storage for local settings and Android Keystore-backed AES-256-GCM encryption for stored profile credentials. Users should use trusted devices, endpoints, and transport settings, and should not share private profile data. No network tool can guarantee absolute security if the device, endpoint, or transport configuration is compromised.

## 11. Changes to this policy

We will update this page and its effective date when this policy changes. If a change materially affects data processing, we will provide notice in an app update or through another reasonable channel.

## 12. Contact

For questions about this policy or TcpTun’s data practices, use the contact mechanism in Section 1. Do not post secrets in a public issue.
