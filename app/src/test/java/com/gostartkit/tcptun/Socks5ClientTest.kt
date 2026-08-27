package com.tcptun.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Socks5ClientTest {
    @Test
    fun connectWithoutCredentialsNegotiatesNoAuthentication() {
        val input = ByteArrayInputStream(
            byteArrayOf(
                0x05, 0x00,
                0x05, 0x00, 0x00, 0x01,
                127, 0, 0, 1, 0x04, 0x38,
            ),
        )
        val output = ByteArrayOutputStream()

        Socks5Client.connect(input, output, "example.com", 443, "", "")

        assertArrayEquals(
            byteArrayOf(
                0x05, 0x01, 0x00,
                0x05, 0x01, 0x00, 0x03, 11,
                *"example.com".encodeToByteArray(),
                0x01, 0xbb.toByte(),
            ),
            output.toByteArray(),
        )
    }

    @Test
    fun connectWithCredentialsCompletesUsernamePasswordAuthentication() {
        val input = ByteArrayInputStream(
            byteArrayOf(
                0x05, 0x02,
                0x01, 0x00,
                0x05, 0x00, 0x00, 0x03,
                1, 'x'.code.toByte(), 0x04, 0x38,
            ),
        )
        val output = ByteArrayOutputStream()

        Socks5Client.connect(input, output, "x.test", 53, "user", "pass")

        assertArrayEquals(
            byteArrayOf(
                0x05, 0x01, 0x02,
                0x01, 4,
                *"user".encodeToByteArray(),
                4,
                *"pass".encodeToByteArray(),
                0x05, 0x01, 0x00, 0x03, 6,
                *"x.test".encodeToByteArray(),
                0x00, 53,
            ),
            output.toByteArray(),
        )
    }

    @Test
    fun connectRejectsTruncatedServerReply() {
        assertThrows(IllegalStateException::class.java) {
            Socks5Client.connect(
                input = ByteArrayInputStream(byteArrayOf(0x05)),
                output = ByteArrayOutputStream(),
                host = "example.com",
                port = 443,
                username = "",
                password = "",
            )
        }
    }

    @Test
    fun connectRejectsUnsupportedOrPrivateAuthenticationMethods() {
        listOf(0xff, 0x80).forEach { selectedMethod ->
            assertThrows(IllegalStateException::class.java) {
                Socks5Client.connect(
                    input = ByteArrayInputStream(byteArrayOf(0x05, selectedMethod.toByte())),
                    output = ByteArrayOutputStream(),
                    host = "example.com",
                    port = 443,
                    username = "user",
                    password = "pass",
                )
            }
        }
    }

    @Test
    fun connectRejectsFailedRfc1929Authentication() {
        assertThrows(IllegalArgumentException::class.java) {
            Socks5Client.connect(
                input = ByteArrayInputStream(byteArrayOf(0x05, 0x02, 0x01, 0x01)),
                output = ByteArrayOutputStream(),
                host = "example.com",
                port = 443,
                username = "user",
                password = "wrong",
            )
        }
    }

    @Test
    fun rfc1929CredentialsPreserveUnsignedByteBoundary() {
        val boundary = "u".repeat(MaxSocksCredentialUtf8Bytes)
        val input = ByteArrayInputStream(
            byteArrayOf(
                0x05, 0x02,
                0x01, 0x00,
                0x05, 0x00, 0x00, 0x01,
                127, 0, 0, 1, 0x04, 0x38,
            ),
        )

        Socks5Client.connect(input, ByteArrayOutputStream(), "example.com", 443, boundary, boundary)

        assertThrows(IllegalArgumentException::class.java) {
            Socks5Client.connect(
                input = ByteArrayInputStream(byteArrayOf(0x05, 0x02)),
                output = ByteArrayOutputStream(),
                host = "example.com",
                port = 443,
                username = "u".repeat(MaxSocksCredentialUtf8Bytes + 1),
                password = "pass",
            )
        }
    }
}
