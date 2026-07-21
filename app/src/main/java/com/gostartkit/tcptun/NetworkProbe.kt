package com.tcptun.client

import java.io.InputStream
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal fun completeTlsHandshake(socket: Socket, host: String, port: Int, timeoutMs: Int) {
    val sslSocket = createTlsSocket(socket, host, port)
    sslSocket.soTimeout = timeoutMs
    sslSocket.sslParameters = sslSocket.sslParameters.apply {
        endpointIdentificationAlgorithm = "HTTPS"
    }
    sslSocket.use { it.startHandshake() }
}

internal fun fetchHttpsStatus(socket: Socket, host: String, port: Int, path: String, timeoutMs: Int): Int {
    require(path.startsWith('/') && '\r' !in path && '\n' !in path && path.length <= MAX_HTTP_PROBE_PATH_LENGTH) {
        "invalid HTTP probe path"
    }
    val sslSocket = createTlsSocket(socket, host, port)
    sslSocket.soTimeout = timeoutMs
    sslSocket.sslParameters = sslSocket.sslParameters.apply {
        endpointIdentificationAlgorithm = "HTTPS"
    }
    sslSocket.use { tls ->
        tls.startHandshake()
        val request = buildString {
            append("GET ")
            append(path)
            append(" HTTP/1.1\r\nHost: ")
            append(host)
            append("\r\nConnection: close\r\nUser-Agent: tcptun-android-health\r\n\r\n")
        }
        tls.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        val statusLine = tls.getInputStream().readBoundedAsciiLine(MAX_HTTP_STATUS_LINE_LENGTH)
            ?: error("empty HTTP response")
        val parts = statusLine.split(" ", limit = 3)
        require(parts.size >= 2 && parts[0].startsWith("HTTP/")) { "invalid HTTP response: $statusLine" }
        return parts[1].toIntOrNull() ?: error("invalid HTTP status: $statusLine")
    }
}

private fun createTlsSocket(socket: Socket, host: String, port: Int): SSLSocket {
    require(host.isNotBlank() && '\r' !in host && '\n' !in host) { "invalid TLS host" }
    require(port in 1..65_535) { "invalid TLS port" }
    val factory = SSLSocketFactory.getDefault()
    require(factory is SSLSocketFactory) { "default SSL socket factory is unavailable" }
    val wrapped = factory.createSocket(socket, host, port, true)
    if (wrapped !is SSLSocket) {
        runRecoverableCatching { wrapped.close() }
        throw IllegalStateException("default SSL socket factory returned a non-TLS socket")
    }
    return wrapped
}

internal fun InputStream.readBoundedAsciiLine(maxLength: Int): String? {
    require(maxLength > 0) { "maximum line length must be positive" }
    val bytes = ByteArray(maxLength)
    var size = 0
    while (true) {
        val value = read()
        if (value < 0) return if (size == 0) null else bytes.copyOf(size).toString(Charsets.US_ASCII)
        if (value == '\n'.code) {
            val contentSize = if (size > 0 && bytes[size - 1] == '\r'.code.toByte()) size - 1 else size
            return bytes.copyOf(contentSize).toString(Charsets.US_ASCII)
        }
        if (size >= maxLength) throw IllegalStateException("HTTP status line is too long")
        bytes[size++] = value.toByte()
    }
}

private const val MAX_HTTP_PROBE_PATH_LENGTH = 2_048
private const val MAX_HTTP_STATUS_LINE_LENGTH = 4_096
