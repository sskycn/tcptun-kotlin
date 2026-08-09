# Traffic analysis

Traffic analysis can select one installed app on Android 10+. The service configures the
selected package and its flow callback on the running engine without restarting the VPN.
Clearing or changing the package resets the in-memory flow list and callback cursor.

Events are bounded, session/sequence ordered, and held only in memory. The Material 3 page
shows the restored domain or literal IP, port, outbound tag, route reason, and dropped-event
count. Events are not persisted and disappear when the process exits.

The feature is intentionally opt-in. When it is disabled, the provider avoids Android UID
ownership lookups. Callback payloads are sanitized before entering `TcptunState`; tokens,
passwords, UUIDs, private keys, and profile JSON are never part of the UI diagnostics model.

Coverage includes flow parsing, callback filtering, drop bounds, app-selection changes, and
the Android contract/UI tests.
