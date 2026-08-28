package com.tcptun.client

private const val RedactedValue = "<redacted>"

private val JsonSecretField = Regex(
    """("(?:token|password|passwd|secret|client_secret|credential|authorization|proxy_authorization|socks_password|uuid|private_key|api_key)"\s*:\s*")((?:\\.|[^"\\])*)(")""",
    RegexOption.IGNORE_CASE,
)

private val AuthorizationHeader = Regex(
    """\b(authorization|proxy-authorization)(\s*:\s*)(?:(?:bearer|basic)\s+)?[^\s,;]+""",
    RegexOption.IGNORE_CASE,
)

private val UriUserInfo = Regex(
    """\b([a-z][a-z0-9+.-]*://)([^/\s@]+)@""",
    RegexOption.IGNORE_CASE,
)

private val OpaqueProfileUri = Regex(
    """\b((?:vmess|ss|ssr)://)[a-z0-9_+/%=-]+""",
    RegexOption.IGNORE_CASE,
)

private val CompactSharePayload = Regex(
    """\b((?:T[23]|A1):)[0-9A-Z \x24%*+\-./:]{8,}""",
    RegexOption.IGNORE_CASE,
)

private val SecretKeyValue = Regex(
    """\b(token|password|passwd|secret|client_secret|credential|socks_password|uuid|private_key|api_key)(\s*[=:]\s*)(?:"[^"]*"|'[^']*'|[^\s,;&]+)""",
    RegexOption.IGNORE_CASE,
)

/** Removes profile credentials before text reaches UI state, in-app history, or logcat. */
internal fun redactSensitiveText(value: String): String {
    var redacted = AuthorizationHeader.replace(value) { match ->
        match.groupValues[1] + match.groupValues[2] + RedactedValue
    }
    redacted = JsonSecretField.replace(redacted) { match ->
        match.groupValues[1] + RedactedValue + match.groupValues[3]
    }
    redacted = UriUserInfo.replace(redacted) { match ->
        match.groupValues[1] + RedactedValue + "@"
    }
    redacted = OpaqueProfileUri.replace(redacted) { match ->
        match.groupValues[1] + RedactedValue
    }
    redacted = CompactSharePayload.replace(redacted) { match ->
        match.groupValues[1] + RedactedValue
    }
    return SecretKeyValue.replace(redacted) { match ->
        match.groupValues[1] + match.groupValues[2] + RedactedValue
    }
}
