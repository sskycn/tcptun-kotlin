package com.tcptun.client

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyAccountA1BridgeTest {
    @Test
    fun asciiAndUtf8GoldenVectorsRoundTripThroughBridge() {
        val alice = LocalProxyUser("alice", "secret-a")
        val utf8 = LocalProxyUser("用户", "密码123")

        assertEquals("A1:RU0XVDKPC331Z CZKE-UE72", LocalProxyAccountCodec.encode(alice))
        assertEquals("A1:C*0VZIL6TC6NT1TK2H4DK*9661", LocalProxyAccountCodec.encode(utf8))
        assertEquals(alice, LocalProxyAccountCodec.decode(LocalProxyAccountCodec.encode(alice)))
        assertEquals(utf8, LocalProxyAccountCodec.decode(LocalProxyAccountCodec.encode(utf8)))
    }

    @Test
    fun eachAccountProducesOnlyItsOwnA1Payload() {
        val alice = LocalProxyUser("alice", "secret-a")
        val bob = LocalProxyUser("bob", "secret-b")
        val alicePayload = LocalProxyAccountCodec.encode(alice)
        val bobPayload = LocalProxyAccountCodec.encode(bob)

        assertNotEquals(alicePayload, bobPayload)
        assertEquals(alice, LocalProxyAccountCodec.decode(alicePayload))
        assertEquals(bob, LocalProxyAccountCodec.decode(bobPayload))
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47),
            LocalProxyAccountCodec.encodeQrCode(alice).copyOf(4),
        )
    }

    @Test
    fun malformedA1AndCrossFormatDecodersAreRejected() {
        for (value in listOf("", "A1:", "A1:_0", "A2:00", "T3:00")) {
            assertTrue(runCatching { LocalProxyAccountCodec.decode(value) }.isFailure)
        }
        val a1 = LocalProxyAccountCodec.encode(LocalProxyUser("alice", "secret-a"))
        assertTrue(ProfileUriCodec.decode(a1).isFailure)
    }

    @Test
    fun scannerDispatchKeepsA1SeparateFromT2AndT3Profiles() {
        val a1 = LocalProxyAccountCodec.encode(LocalProxyUser("alice", "secret-a"))
        val a1Result = decodeScannedPayload(a1)
        assertTrue(a1Result is ScannedPayload.ProxyAccount)

        val profile = AppConfig(
            name = "scanner",
            serverHost = "192.0.2.10",
            serverPort = "9443",
            protocol = "native",
            token = "scanner-secret",
        )
        val t3 = requireNotNull(ProfileUriCodec.encodeForQr(profile))
        assertTrue(t3.startsWith("T3:"))
        assertTrue(decodeScannedPayload(t3) is ScannedPayload.Profile)

        val t2 = "T2:+1REESK007MO4UU1V2TDWL%53+UKDNS6ONP18VCRZCB\$CBECP9EXYC/:52%E1/DXTDJPC -DB\$CBECP9ERZCUPCAZDA8GLB0  C93D:"
        assertTrue(decodeScannedPayload(t2) is ScannedPayload.Profile)
    }

    @Test
    fun systemShareContainsOnlyTheSelectedA1Text() {
        val payload = LocalProxyAccountCodec.encode(LocalProxyUser("alice", "secret-a"))
        val intent = createProxyAccountShareIntent(payload)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(payload, intent.getStringExtra(Intent.EXTRA_TEXT))
    }
}
