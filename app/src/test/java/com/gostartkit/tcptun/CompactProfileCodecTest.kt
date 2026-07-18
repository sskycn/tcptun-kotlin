package com.tcptun.client

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactProfileCodecTest {
    @Test
    fun binaryQrRoundTripsAndUsesLowerQrVersion() {
        val profile = AppConfig(
            name = "边缘 | TLS #1",
            serverHost = "192.0.2.1",
            serverPort = "443",
            protocol = "vless",
            transport = "ws",
            token = "14c1bdf2-9815-46ff-862e-50f459b84cbf",
            sni = "cdn.example.com",
            path = "/tunnel",
            tls = true,
            tlsInsecure = true,
            mux = true,
            muxMode = "group",
            muxMaxSessions = 6,
            muxMaxStreamsPerSession = 256,
            muxWarmSpare = 2,
            upstreamProtocol = "mixed",
        )
        val plain = requireNotNull(ProfileUriCodec.encode(profile))
        val compact = requireNotNull(ProfileUriCodec.encodeForQr(profile))

        assertTrue(compact.startsWith("T2:"))
        assertTrue(compact.endsWith(":"))
        assertTrue(compact.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:" })
        assertTrue(compact.length < plain.length)
        assertTrue(
            Encoder.encode(compact, ErrorCorrectionLevel.M).version.versionNumber <
                Encoder.encode(plain, ErrorCorrectionLevel.M).version.versionNumber,
        )

        val decoded = ProfileUriCodec.decode(compact).getOrThrow()
        assertEquals(profile.name, decoded.name)
        assertEquals(profile.serverHost, decoded.serverHost)
        assertEquals(profile.serverPort, decoded.serverPort)
        assertEquals(profile.protocol, decoded.protocol)
        assertEquals(profile.transport, decoded.transport)
        assertEquals(profile.token, decoded.token)
        assertEquals(profile.sni, decoded.sni)
        assertEquals(profile.path, decoded.path)
        assertEquals(profile.tls, decoded.tls)
        assertEquals(profile.tlsInsecure, decoded.tlsInsecure)
        assertEquals(profile.mux, decoded.mux)
        assertEquals(profile.muxMode, decoded.muxMode)
        assertEquals(profile.muxMaxSessions, decoded.muxMaxSessions)
        assertEquals(profile.muxMaxStreamsPerSession, decoded.muxMaxStreamsPerSession)
        assertEquals(profile.muxWarmSpare, decoded.muxWarmSpare)
        assertEquals(profile.upstreamProtocol, decoded.upstreamProtocol)
        assertNull(decoded.validate())
    }

    @Test
    fun legacyCompactTextIsNotAccepted() {
        assertFalse(ProfileUriCodec.decode("t1|v|token|example.com|443").isSuccess)
    }
}
