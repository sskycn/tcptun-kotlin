# App routing

Android per-app VPN filtering is not used. The VPN captures the default IPv4/IPv6 routes,
then tcptun-go evaluates ordered route rules and chooses the outbound.

Managed Android app rules use the native flow identity provider on Android 10+:

1. The core reports a source/destination tuple.
2. Android resolves the tuple with `ConnectivityManager.getConnectionOwnerUid`.
3. Installed package names are returned as a local app identity.
4. The core evaluates the configured App matcher.

UID ownership lookup is skipped when flow analysis is disabled and no App route rule exists.
On Android 9 and older, app identity is unavailable and ordinary routing continues.

Shared UID packages are handled conservatively using the multi-valued package matcher. App
identity callbacks are cleared with the rest of the bridge callbacks and are protected by
the active session epoch.

App routing is separate from the single-app traffic analysis feature; see
[traffic-analysis.md](traffic-analysis.md).
