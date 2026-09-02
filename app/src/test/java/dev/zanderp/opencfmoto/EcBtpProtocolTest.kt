package dev.zanderp.opencfmoto

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EcBtpProtocolTest {
    @Test
    fun syncTime_littleEndianEpoch() {
        val reply = EcBtpProtocol.syncTimeReply(nowMillis = 0x0102030405060708L, rawOffsetMillis = 0)
        assertEquals(13, reply.size)
        assertEquals(0x24.toByte(), reply[0])
        assertEquals(0x01.toByte(), reply[1])
        assertEquals(0x0C.toByte(), reply[2])
        assertArrayEquals(
            byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01),
            reply.copyOfRange(3, 11),
        )
        assertEquals(0x0A.toByte(), reply[12])
    }

    @Test
    fun syncTime_addsRawOffset() {
        val twoHours = 2 * 60 * 60 * 1000
        val parsed = EcBtpProtocol.parse(EcBtpProtocol.syncTimeReply(0L, twoHours))
        assertNotNull(parsed)
        var value = 0L
        for (index in 7 downTo 0) {
            value = (value shl 8) or (parsed!!.payload[index].toLong() and 0xFF)
        }
        assertEquals(twoHours.toLong(), value)
    }

    @Test
    fun queryTime_zoneNameNotMillis() {
        val zone = TimeZone.getTimeZone("Europe/Rome")
        val parsed = EcBtpProtocol.parse(
            EcBtpProtocol.queryTimeReply(Date(1_786_272_219_244L), zone, Locale.UK),
        )
        assertNotNull(parsed)
        assertEquals(EcBtpProtocol.CMD_QUERY_TIME, parsed!!.command)
        assertEquals("09.08.2026 12:43:39:CEST", String(parsed.payload, Charsets.UTF_8))
    }

    @Test
    fun parse_rejectsJunk() {
        val good = EcBtpProtocol.build(EcBtpProtocol.CMD_SYNC_TIME, byteArrayOf(1, 2, 3, 4))
        assertNull(EcBtpProtocol.parse(byteArrayOf(0x24, 0x01, 0x05)))
        assertNull(EcBtpProtocol.parse(good.copyOf().also { it[0] = 0x23 }))
        assertNull(EcBtpProtocol.parse("OK+CONN".toByteArray()))
    }
}
