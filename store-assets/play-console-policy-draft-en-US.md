# Google Play Submission Draft (TcpTun)

Updated: 2026-08-04

This is a Play Console preparation aid based on the current repository and Android client code. It is not an automatic submission and is not legal advice. Recheck it against the final Release AAB and the actual publishing entity.

## 1. App access

- Login required: No
- Registration required: No
- Subscription required: No
- Review method: Provide a review-only test profile or QR code privately in Play Console. Do not put production nodes, UUIDs, passwords, or private subscription links in the public listing.

Suggested reviewer instructions:

> TcpTun does not require an account, sign-in, or subscription. Install the app, import the review-only test profile, tap the profile, and confirm Android’s VPN permission prompt. Then inspect the VPN status and Diagnostics page. The test profile is provided privately through the Play Console App access instructions.

## 2. Ads and content

- Contains ads: No
- In-app purchases or subscriptions: No, subject to the final Release build
- Target audience: General-purpose network tool; not directed to children

## 3. VPN declaration

- Uses `VpnService`: Yes
- Is VPN a core functionality: Yes
- Core purpose: Create an Android device-level VPN interface and forward traffic to a remote endpoint selected by the user.
- Does the developer operate VPN servers: No
- Does the app provide VPN nodes, accounts, subscriptions, or cloud configuration: No
- Is VPN data used for advertising, profiling, or analytics: No

Suggested declaration text:

> TcpTun is a VPN/transparent-proxy client, not a VPN service provider. The app does not operate server nodes; users provide or select their own remote endpoint and are responsible for trusting that endpoint and reviewing its logging practices. VPN traffic is used only to implement the user-selected forwarding configuration.

## 4. Data safety facts

### Start with Google Play’s definition

Google Play defines “collect” for the Data safety form as transmitting data off the user’s device, even when the recipient is not the developer. “There is no operator-owned backend” is therefore not enough by itself. Data processed only on the device generally does not need to be declared as collected, but traffic forwarded by a VPN to a remote endpoint must be evaluated against the actual transmitted data and encryption model.

### Operator-owned collection

The current code contains no account backend, cloud synchronization, advertising, analytics, Crashlytics, telemetry, or crash-reporting endpoint. The TcpTun operator does not receive profiles, VPN traffic, logs, traffic-analysis events, or QR images from the app.

### Local processing

The app uses the following on the device:

- profiles, credentials, TLS/transport parameters, and routing rules;
- runtime settings such as MTU, local proxy settings, log level, and traffic-analysis selection;
- app package names/labels and local network information for routing and diagnostics;
- user-enabled traffic-analysis events;
- clipboard content only when the user explicitly imports a profile;
- camera frames only while the user actively scans a QR code.

No code path was found that sends these items to a TcpTun operator server. Profiles and some settings are stored in app-private Android storage; runtime logs and traffic-analysis events are primarily held in memory.

### Network behavior that must be disclosed

When VPN is enabled, user traffic is sent to the remote endpoint configured by the user. That endpoint operator may process or retain connection information under its own policy; TcpTun does not control the endpoint.

While the VPN is running, the app may probe through the current outbound connection:

- `connectivitycheck.gstatic.com/generate_204`
- `cp.cloudflare.com/generate_204`

When the user starts TCPing, the app tests `google.com:443`, `github.com:443`, and `cloudflare.com:443`. These targets may see normal network-connection metadata.

Do not reduce this to “the network has no transmission.” Distinguish:

1. Whether TcpTun’s developer collects or shares data: no operator-owned collection endpoint was found in the current code;
2. Whether the app sends traffic to the user-selected endpoint and diagnostic targets: yes, as part of VPN and diagnostics;
3. Whether the remote endpoint retains logs: TcpTun cannot control this and must disclose the risk in the listing and privacy policy.

### Data question in the VPN declaration

The VPN declaration separately asks whether the `VpnService` collects or shares data. For a client that forwards device traffic to a remote endpoint, do not promise that the answer is “No” in advance. Open the final form and answer based on the protocols in the final Release, what the remote endpoint can see, and what is actually transmitted. If the endpoint can see visited destinations, `Web browsing history` is a category that must be evaluated carefully. Do not omit VPN transmission merely because the developer does not operate the endpoint.

If the transport genuinely meets Google Play’s definition of end-to-end encryption (unreadable to the developer and intermediaries, with only the sender and recipient able to read it), evaluate the relevant exception using the official form guidance. Do not call ordinary TLS or proxy traffic end-to-end encrypted when the endpoint can decrypt it.

## 5. Permission explanations

- `INTERNET` and network-state permissions: connect to remote endpoints, run network diagnostics, and inspect local network state;
- `CAMERA`: recognize a profile QR code after the user opens the scanner;
- `VpnService` and foreground service: provide the core VPN function and its status notification;
- Notification permission: display the VPN foreground-service notification, subject to Android settings.

The current manifest does not request location, contacts, SMS, call-log, or storage read/write permissions.

## 6. In-app prominent disclosure before VPN start

Google Play’s VPN rules require an in-app prominent disclosure when `VpnService` accesses or collects personal or sensitive data. The disclosure must appear in the normal usage flow, explain how the data is used/shared, and obtain affirmative user consent. A privacy policy or store description alone is not a substitute.

Implemented first-run disclosure (shown as a separate Material 3 dialog, not only in Settings):

> TcpTun uses Android VpnService to access and route your device’s network traffic, including connection destinations and traffic content. Traffic is sent through an encrypted tunnel to the remote endpoint you choose. TcpTun does not operate these endpoints or receive your traffic, but the endpoint operator may see or retain connection information under its own policy. TcpTun may also perform Google and Cloudflare connectivity checks through the current connection. Use only endpoints you trust.

Only after the user taps “Agree and continue” does the app save versioned consent, request Android VPN permission, and start the service. “Not now,” Back, and outside dismissal cancel the pending start. Keep a recording of this flow for the VPN declaration review video.

## 7. Privacy-policy URL

The public privacy-policy URL is `https://tcptun.com/privacy/`. Before submission, deploy the updated repository policy text there, enter the URL in Play Console under App content / Privacy policy, and verify that it requires no sign-in and matches the final Release build. The app exposes this URL from the disclosure dialog and Settings.

## 8. Final release checks

- The final Release dependencies contain no newly added advertising, analytics, crash-reporting, or cloud SDK;
- The review-only endpoint is reachable from the review environment and the import steps are reproducible;
- Traffic from the device to the VPN tunnel endpoint meets Google Play’s encryption requirement; if a “native” profile is unencrypted, do not use it as the default review path;
- An in-app prominent disclosure, affirmative consent, and a no-consent/no-start path are implemented before VPN permission is requested;
- Prepare a review video no longer than 90 seconds showing app launch, disclosure/consent, VPN permission, VPN start, and VPN stop;
- The developer identity, privacy contact mechanism, and HTTPS policy URL match the Play listing;
- Complete every Data safety field for the final AAB instead of copying only “no developer collection”;
- The VPN declaration clearly says that the app is a client tool and does not operate VPN servers.
