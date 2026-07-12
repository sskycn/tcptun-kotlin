package com.tcptun.client

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal fun completeTlsHandshake(socket: Socket, host: String, port: Int, timeoutMs: Int) {
    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
    val sslSocket = factory.createSocket(socket, host, port, true) as SSLSocket
    sslSocket.soTimeout = timeoutMs
    sslSocket.sslParameters = sslSocket.sslParameters.apply {
        endpointIdentificationAlgorithm = "HTTPS"
    }
    sslSocket.use { it.startHandshake() }
}

internal fun fetchHttpsStatus(socket: Socket, host: String, port: Int, path: String, timeoutMs: Int): Int {
    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
    val sslSocket = factory.createSocket(socket, host, port, true) as SSLSocket
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
        val statusLine = BufferedReader(InputStreamReader(tls.getInputStream(), Charsets.US_ASCII)).readLine()
            ?: error("empty HTTP response")
        val parts = statusLine.split(" ", limit = 3)
        require(parts.size >= 2 && parts[0].startsWith("HTTP/")) { "invalid HTTP response: $statusLine" }
        return parts[1].toIntOrNull() ?: error("invalid HTTP status: $statusLine")
    }
}
