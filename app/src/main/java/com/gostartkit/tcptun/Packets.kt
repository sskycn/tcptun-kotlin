package com.tcptun.client

import java.util.concurrent.atomic.AtomicInteger

private val packetId = AtomicInteger(1)

data class Ipv4Packet(
    val source: Int,
    val destination: Int,
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
    val ack: Boolean get() = flags and TCP_ACK != 0
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

fun parseIpv4Packet(buffer: ByteArray, length: Int): Ipv4Packet? {
    if (length < 20) return null
    val version = (buffer[0].toInt() ushr 4) and 0x0f
    if (version != 4) return null
    val ihl = (buffer[0].toInt() and 0x0f) * 4
    if (ihl < 20 || length < ihl) return null
    val total = readU16(buffer, 2)
    if (total < ihl || total > length) return null
    return Ipv4Packet(
        source = readI32(buffer, 12),
        destination = readI32(buffer, 16),
        protocol = buffer[9].toInt() and 0xff,
        headerLength = ihl,
        totalLength = total,
    )
}

fun parseTcpPacket(buffer: ByteArray, ip: Ipv4Packet): TcpPacket? {
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

fun parseUdpPacket(buffer: ByteArray, ip: Ipv4Packet): UdpPacket? {
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
    sourceIp: Int,
    destinationIp: Int,
    sourcePort: Int,
    destinationPort: Int,
    sequence: Long,
    acknowledgment: Long,
    flags: Int,
    payload: ByteArray = ByteArray(0),
): ByteArray {
    val ipHeaderLength = 20
    val tcpHeaderLength = 20
    val totalLength = ipHeaderLength + tcpHeaderLength + payload.size
    val packet = ByteArray(totalLength)
    writeIpv4Header(packet, totalLength, IPPROTO_TCP, sourceIp, destinationIp)
    val tcp = ipHeaderLength
    writeU16(packet, tcp, sourcePort)
    writeU16(packet, tcp + 2, destinationPort)
    writeU32(packet, tcp + 4, sequence)
    writeU32(packet, tcp + 8, acknowledgment)
    packet[tcp + 12] = (5 shl 4).toByte()
    packet[tcp + 13] = flags.toByte()
    writeU16(packet, tcp + 14, 65535)
    payload.copyInto(packet, tcp + tcpHeaderLength)
    writeU16(packet, tcp + 16, tcpChecksum(packet, tcp, tcpHeaderLength + payload.size, sourceIp, destinationIp))
    return packet
}

fun buildUdpPacket(
    sourceIp: Int,
    destinationIp: Int,
    sourcePort: Int,
    destinationPort: Int,
    payload: ByteArray,
): ByteArray {
    val ipHeaderLength = 20
    val udpHeaderLength = 8
    val udpLength = udpHeaderLength + payload.size
    val totalLength = ipHeaderLength + udpLength
    val packet = ByteArray(totalLength)
    writeIpv4Header(packet, totalLength, IPPROTO_UDP, sourceIp, destinationIp)
    val udp = ipHeaderLength
    writeU16(packet, udp, sourcePort)
    writeU16(packet, udp + 2, destinationPort)
    writeU16(packet, udp + 4, udpLength)
    payload.copyInto(packet, udp + udpHeaderLength)
    writeU16(packet, udp + 6, udpChecksum(packet, udp, udpLength, sourceIp, destinationIp))
    return packet
}

fun ipToString(value: Int): String {
    return listOf(24, 16, 8, 0).joinToString(".") { shift ->
        ((value ushr shift) and 0xff).toString()
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

private fun tcpChecksum(buffer: ByteArray, offset: Int, length: Int, sourceIp: Int, destinationIp: Int): Int {
    return transportChecksum(buffer, offset, length, sourceIp, destinationIp, IPPROTO_TCP)
}

private fun udpChecksum(buffer: ByteArray, offset: Int, length: Int, sourceIp: Int, destinationIp: Int): Int {
    val sum = transportChecksum(buffer, offset, length, sourceIp, destinationIp, IPPROTO_UDP)
    return if (sum == 0) 0xffff else sum
}

private fun transportChecksum(
    buffer: ByteArray,
    offset: Int,
    length: Int,
    sourceIp: Int,
    destinationIp: Int,
    protocol: Int,
): Int {
    var sum = 0
    sum = addIpToSum(sum, sourceIp)
    sum = addIpToSum(sum, destinationIp)
    sum += protocol
    sum += length
    return checksum(buffer, offset, length, sum)
}

private fun addIpToSum(sumIn: Int, ip: Int): Int {
    var sum = sumIn
    sum += (ip ushr 16) and 0xffff
    sum += ip and 0xffff
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
