package com.tcptun.client

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

private val packetId = AtomicInteger(1)

class IpAddress(bytes: ByteArray) {
    val bytes: ByteArray = bytes.copyOf()
    val version: Int
        get() = when (bytes.size) {
            4 -> 4
            16 -> 6
            else -> 0
        }

    init {
        require(bytes.size == 4 || bytes.size == 16) { "IP address must be IPv4 or IPv6" }
    }

    fun toIpv4Int(): Int {
        require(bytes.size == 4) { "not an IPv4 address" }
        return readI32(bytes, 0)
    }

    override fun equals(other: Any?): Boolean {
        return other is IpAddress && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }

    override fun toString(): String {
        return InetAddress.getByAddress(bytes).hostAddress ?: bytes.joinToString(":")
    }

    companion object {
        fun fromIpv4(value: Int): IpAddress {
            val bytes = ByteArray(4)
            writeI32(bytes, 0, value)
            return IpAddress(bytes)
        }
    }
}

data class IpPacket(
    val source: IpAddress,
    val destination: IpAddress,
    val protocol: Int,
    val headerLength: Int,
    val totalLength: Int,
)

data class TcpPacket(
    val sourcePort: Int,
    val destinationPort: Int,
    val sequence: Long,
    val acknowledgment: Long,
    val flags: Int,
    val window: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    val syn: Boolean get() = flags and TCP_SYN != 0
    val fin: Boolean get() = flags and TCP_FIN != 0
    val rst: Boolean get() = flags and TCP_RST != 0
}

data class UdpPacket(
    val sourcePort: Int,
    val destinationPort: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
)

const val IPPROTO_TCP = 6
const val IPPROTO_UDP = 17
const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10

private val IPV6_EXTENSION_HEADERS = setOf(0, 43, 44, 51, 60)

fun parseIpPacket(buffer: ByteArray, length: Int): IpPacket? {
    if (length < 1) return null
    return when ((buffer[0].toInt() ushr 4) and 0x0f) {
        4 -> parseIpv4Packet(buffer, length)
        6 -> parseIpv6Packet(buffer, length)
        else -> null
    }
}

fun parseIpv4Packet(buffer: ByteArray, length: Int): IpPacket? {
    if (length < 20) return null
    val version = (buffer[0].toInt() ushr 4) and 0x0f
    if (version != 4) return null
    val ihl = (buffer[0].toInt() and 0x0f) * 4
    if (ihl < 20 || length < ihl) return null
    val total = readU16(buffer, 2)
    if (total < ihl || total > length) return null
    return IpPacket(
        source = IpAddress(buffer.copyOfRange(12, 16)),
        destination = IpAddress(buffer.copyOfRange(16, 20)),
        protocol = buffer[9].toInt() and 0xff,
        headerLength = ihl,
        totalLength = total,
    )
}

fun parseIpv6Packet(buffer: ByteArray, length: Int): IpPacket? {
    if (length < 40) return null
    val payloadLength = readU16(buffer, 4)
    val totalLength = 40 + payloadLength
    if (totalLength > length) return null
    var protocol = buffer[6].toInt() and 0xff
    var transportOffset = 40
    while (protocol in IPV6_EXTENSION_HEADERS) {
        if (transportOffset + 8 > totalLength) return null
        val nextHeader = buffer[transportOffset].toInt() and 0xff
        val headerLength = when (protocol) {
            44 -> 8
            51 -> ((buffer[transportOffset + 1].toInt() and 0xff) + 2) * 4
            else -> ((buffer[transportOffset + 1].toInt() and 0xff) + 1) * 8
        }
        if (transportOffset + headerLength > totalLength) return null
        if (protocol == 44 && (readU16(buffer, transportOffset + 2) and 0xfff8) != 0) return null
        transportOffset += headerLength
        protocol = nextHeader
    }
    if (protocol != IPPROTO_TCP && protocol != IPPROTO_UDP) return null
    return IpPacket(
        source = IpAddress(buffer.copyOfRange(8, 24)),
        destination = IpAddress(buffer.copyOfRange(24, 40)),
        protocol = protocol,
        headerLength = transportOffset,
        totalLength = totalLength,
    )
}

fun parseTcpPacket(buffer: ByteArray, ip: IpPacket): TcpPacket? {
    val offset = ip.headerLength
    if (ip.totalLength < offset + 20) return null
    val dataOffset = ((buffer[offset + 12].toInt() ushr 4) and 0x0f) * 4
    if (dataOffset < 20 || ip.totalLength < offset + dataOffset) return null
    return TcpPacket(
        sourcePort = readU16(buffer, offset),
        destinationPort = readU16(buffer, offset + 2),
        sequence = readU32(buffer, offset + 4),
        acknowledgment = readU32(buffer, offset + 8),
        flags = buffer[offset + 13].toInt() and 0xff,
        window = readU16(buffer, offset + 14),
        payloadOffset = offset + dataOffset,
        payloadLength = ip.totalLength - offset - dataOffset,
    )
}

fun parseUdpPacket(buffer: ByteArray, ip: IpPacket): UdpPacket? {
    val offset = ip.headerLength
    if (ip.totalLength < offset + 8) return null
    val udpLength = readU16(buffer, offset + 4)
    if (udpLength < 8 || offset + udpLength > ip.totalLength) return null
    return UdpPacket(
        sourcePort = readU16(buffer, offset),
        destinationPort = readU16(buffer, offset + 2),
        payloadOffset = offset + 8,
        payloadLength = udpLength - 8,
    )
}

fun buildTcpPacket(
    sourceIp: IpAddress,
    destinationIp: IpAddress,
    sourcePort: Int,
    destinationPort: Int,
    sequence: Long,
    acknowledgment: Long,
    flags: Int,
    payload: ByteArray = ByteArray(0),
): ByteArray {
    require(sourceIp.version == destinationIp.version) { "source and destination IP versions differ" }
    val ipHeaderLength = if (sourceIp.version == 4) 20 else 40
    val tcpHeaderLength = 20
    val totalLength = ipHeaderLength + tcpHeaderLength + payload.size
    val packet = ByteArray(totalLength)
    writeIpHeader(packet, totalLength, IPPROTO_TCP, sourceIp, destinationIp)
    val tcp = ipHeaderLength
    writeU16(packet, tcp, sourcePort)
    writeU16(packet, tcp + 2, destinationPort)
    writeU32(packet, tcp + 4, sequence)
    writeU32(packet, tcp + 8, acknowledgment)
    packet[tcp + 12] = (5 shl 4).toByte()
    packet[tcp + 13] = flags.toByte()
    writeU16(packet, tcp + 14, 65535)
    payload.copyInto(packet, tcp + tcpHeaderLength)
    writeU16(
        packet,
        tcp + 16,
        transportChecksum(packet, tcp, tcpHeaderLength + payload.size, sourceIp, destinationIp, IPPROTO_TCP),
    )
    return packet
}

fun buildUdpPacket(
    sourceIp: IpAddress,
    destinationIp: IpAddress,
    sourcePort: Int,
    destinationPort: Int,
    payload: ByteArray,
): ByteArray {
    require(sourceIp.version == destinationIp.version) { "source and destination IP versions differ" }
    val ipHeaderLength = if (sourceIp.version == 4) 20 else 40
    val udpHeaderLength = 8
    val udpLength = udpHeaderLength + payload.size
    val totalLength = ipHeaderLength + udpLength
    val packet = ByteArray(totalLength)
    writeIpHeader(packet, totalLength, IPPROTO_UDP, sourceIp, destinationIp)
    val udp = ipHeaderLength
    writeU16(packet, udp, sourcePort)
    writeU16(packet, udp + 2, destinationPort)
    writeU16(packet, udp + 4, udpLength)
    payload.copyInto(packet, udp + udpHeaderLength)
    val sum = transportChecksum(packet, udp, udpLength, sourceIp, destinationIp, IPPROTO_UDP)
    writeU16(packet, udp + 6, if (sum == 0) 0xffff else sum)
    return packet
}

fun ipToString(value: IpAddress): String = value.toString()

fun ipToString(value: Int): String = ipToString(IpAddress.fromIpv4(value))

private fun writeIpHeader(packet: ByteArray, totalLength: Int, protocol: Int, sourceIp: IpAddress, destinationIp: IpAddress) {
    if (sourceIp.version == 4) {
        writeIpv4Header(packet, totalLength, protocol, sourceIp.toIpv4Int(), destinationIp.toIpv4Int())
    } else {
        writeIpv6Header(packet, totalLength, protocol, sourceIp, destinationIp)
    }
}

private fun writeIpv4Header(packet: ByteArray, totalLength: Int, protocol: Int, sourceIp: Int, destinationIp: Int) {
    packet[0] = 0x45
    writeU16(packet, 2, totalLength)
    writeU16(packet, 4, packetId.getAndIncrement() and 0xffff)
    packet[8] = 64
    packet[9] = protocol.toByte()
    writeI32(packet, 12, sourceIp)
    writeI32(packet, 16, destinationIp)
    writeU16(packet, 10, checksum(packet, 0, 20, 0))
}

private fun writeIpv6Header(packet: ByteArray, totalLength: Int, protocol: Int, sourceIp: IpAddress, destinationIp: IpAddress) {
    packet[0] = 0x60
    writeU16(packet, 4, totalLength - 40)
    packet[6] = protocol.toByte()
    packet[7] = 64
    sourceIp.bytes.copyInto(packet, 8)
    destinationIp.bytes.copyInto(packet, 24)
}

fun readU16(buffer: ByteArray, offset: Int): Int {
    return ((buffer[offset].toInt() and 0xff) shl 8) or (buffer[offset + 1].toInt() and 0xff)
}

private fun readI32(buffer: ByteArray, offset: Int): Int {
    return ((buffer[offset].toInt() and 0xff) shl 24) or
        ((buffer[offset + 1].toInt() and 0xff) shl 16) or
        ((buffer[offset + 2].toInt() and 0xff) shl 8) or
        (buffer[offset + 3].toInt() and 0xff)
}

private fun readU32(buffer: ByteArray, offset: Int): Long {
    return readI32(buffer, offset).toLong() and 0xffffffffL
}

private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = ((value ushr 8) and 0xff).toByte()
    buffer[offset + 1] = (value and 0xff).toByte()
}

private fun writeI32(buffer: ByteArray, offset: Int, value: Int) {
    buffer[offset] = ((value ushr 24) and 0xff).toByte()
    buffer[offset + 1] = ((value ushr 16) and 0xff).toByte()
    buffer[offset + 2] = ((value ushr 8) and 0xff).toByte()
    buffer[offset + 3] = (value and 0xff).toByte()
}

private fun writeU32(buffer: ByteArray, offset: Int, value: Long) {
    writeI32(buffer, offset, (value and 0xffffffffL).toInt())
}

private fun transportChecksum(
    buffer: ByteArray,
    offset: Int,
    length: Int,
    sourceIp: IpAddress,
    destinationIp: IpAddress,
    protocol: Int,
): Int {
    val initial = if (sourceIp.version == 4) {
        ipv4PseudoHeaderSum(sourceIp.toIpv4Int(), destinationIp.toIpv4Int(), length, protocol)
    } else {
        ipv6PseudoHeaderSum(sourceIp, destinationIp, length, protocol)
    }
    return checksum(buffer, offset, length, initial)
}

private fun ipv4PseudoHeaderSum(sourceIp: Int, destinationIp: Int, length: Int, protocol: Int): Int {
    var sum = 0
    sum = addIpToSum(sum, sourceIp)
    sum = addIpToSum(sum, destinationIp)
    sum += protocol
    sum += length
    return sum
}

private fun ipv6PseudoHeaderSum(sourceIp: IpAddress, destinationIp: IpAddress, length: Int, protocol: Int): Int {
    var sum = 0
    sum = addBytesToSum(sum, sourceIp.bytes)
    sum = addBytesToSum(sum, destinationIp.bytes)
    sum += (length ushr 16) and 0xffff
    sum += length and 0xffff
    sum += protocol
    return sum
}

private fun addIpToSum(sumIn: Int, ip: Int): Int {
    var sum = sumIn
    sum += (ip ushr 16) and 0xffff
    sum += ip and 0xffff
    return sum
}

private fun addBytesToSum(sumIn: Int, bytes: ByteArray): Int {
    var sum = sumIn
    var i = 0
    while (i + 1 < bytes.size) {
        sum += readU16(bytes, i)
        i += 2
    }
    return sum
}

private fun checksum(buffer: ByteArray, offset: Int, length: Int, initial: Int): Int {
    var sum = initial
    var i = offset
    val end = offset + length
    while (i + 1 < end) {
        sum += readU16(buffer, i)
        i += 2
    }
    if (i < end) {
        sum += (buffer[i].toInt() and 0xff) shl 8
    }
    while (sum ushr 16 != 0) {
        sum = (sum and 0xffff) + (sum ushr 16)
    }
    return sum.inv() and 0xffff
}
