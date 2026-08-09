package com.tcptun.client

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** Minimal SOCKS5 CONNECT handshake used by the service health probes. */
internal object Socks5Client {
    private const val MAX_REPLY_LENGTH = 512

    fun connect(
        socket: Socket,
        host: String,
        port: Int,
        username: String,
        password: String,
    ) {
        connect(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            host = host,
            port = port,
            username = username,
            password = password,
        )
    }

    internal fun connect(
        input: InputStream,
        output: OutputStream,
        host: String,
        port: Int,
        username: String,
        password: String,
    ) {
        require(port in 1..65535) { "invalid SOCKS5 destination port" }
        val authEnabled = username.isNotEmpty() || password.isNotEmpty()
        output.write(if (authEnabled) byteArrayOf(0x05, 0x02, 0x00, 0x02) else byteArrayOf(0x05, 0x01, 0x00))
        output.flush()

        val methodReply = input.readExact(2)
        require(methodReply[0] == 0x05.toByte()) { "invalid SOCKS5 method reply" }
        when (methodReply[1].toInt() and 0xff) {
            0x00 -> Unit
            0x02 -> authenticate(input, output, username, password)
            else -> error("SOCKS5 method rejected")
        }

        val hostBytes = host.encodeToByteArray()
        require(hostBytes.size <= 255) { "host is too long" }
        val request = ByteArray(7 + hostBytes.size)
        request[0] = 0x05
        request[1] = 0x01
        request[2] = 0x00
        request[3] = 0x03
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, destinationOffset = 5)
        request[request.lastIndex - 1] = ((port ushr 8) and 0xff).toByte()
        request[request.lastIndex] = (port and 0xff).toByte()
        output.write(request)
        output.flush()

        val replyHead = input.readExact(4)
        require(replyHead[0] == 0x05.toByte()) { "invalid SOCKS5 reply" }
        require(replyHead[1] == 0x00.toByte()) {
            "SOCKS5 connect failed: ${replyHead[1].toInt() and 0xff}"
        }
        val addressLength = when (replyHead[3].toInt() and 0xff) {
            0x01 -> 4
            0x03 -> input.read()
            0x04 -> 16
            else -> error("invalid SOCKS5 address type")
        }
        require(addressLength >= 0) { "SOCKS5 reply ended early" }
        input.readExact(addressLength + 2)
    }

    private fun authenticate(
        input: InputStream,
        output: OutputStream,
        username: String,
        password: String,
    ) {
        val usernameBytes = username.encodeToByteArray()
        val passwordBytes = password.encodeToByteArray()
        require(usernameBytes.size <= 255) { "SOCKS5 username is too long" }
        require(passwordBytes.size <= 255) { "SOCKS5 password is too long" }
        val request = ByteArray(3 + usernameBytes.size + passwordBytes.size)
        request[0] = 0x01
        request[1] = usernameBytes.size.toByte()
        usernameBytes.copyInto(request, destinationOffset = 2)
        request[2 + usernameBytes.size] = passwordBytes.size.toByte()
        passwordBytes.copyInto(request, destinationOffset = 3 + usernameBytes.size)
        output.write(request)
        output.flush()

        val reply = input.readExact(2)
        require(reply[0] == 0x01.toByte() && reply[1] == 0x00.toByte()) {
            "SOCKS5 username/password auth failed"
        }
    }

    private fun InputStream.readExact(length: Int): ByteArray {
        require(length in 0..MAX_REPLY_LENGTH) { "invalid SOCKS5 reply length" }
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(data, offset, length - offset)
            if (read < 0) error("connection closed")
            if (read == 0) {
                val value = read()
                if (value < 0) error("connection closed")
                data[offset++] = value.toByte()
            } else {
                offset += read
            }
        }
        return data
    }
}
