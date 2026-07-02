package com.tcptun.client

import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class TunSocksForwarder(
    private val tun: ParcelFileDescriptor,
    private val socksHost: String,
    private val socksPort: Int,
    private val enableUdp: Boolean,
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagramSocket: (DatagramSocket) -> Boolean,
    private val log: (String) -> Unit,
) : Closeable {
    private val input = FileInputStream(tun.fileDescriptor)
    private val output = FileOutputStream(tun.fileDescriptor)
    private val writeLock = Any()
    private val workers: ExecutorService = Executors.newFixedThreadPool(4)
    private val tcpFlows = ConcurrentHashMap<TcpKey, TcpFlow>()
    private val udpFlows = ConcurrentHashMap<UdpKey, UdpFlow>()
    @Volatile private var running = false
    private var readerThread: Thread? = null

    fun start() {
        running = true
        readerThread = Thread(::readLoop, "tcptun-tun-reader").also { it.start() }
        log("vpn tun forwarding started; socks=$socksHost:$socksPort udp=$enableUdp")
    }

    private fun readLoop() {
        val buffer = ByteArray(65535)
        while (running) {
            val length = try {
                input.read(buffer)
            } catch (_: Exception) {
                if (running) log("vpn tun read stopped")
                break
            }
            if (length <= 0) continue
            val packet = buffer.copyOf(length)
            workers.execute { handlePacket(packet, length) }
        }
    }

    private fun handlePacket(packet: ByteArray, length: Int) {
        val ip = parseIpPacket(packet, length) ?: return
        when (ip.protocol) {
            IPPROTO_TCP -> handleTcp(packet, ip)
            IPPROTO_UDP -> handleUdp(packet, ip)
        }
    }

    private fun handleTcp(packet: ByteArray, ip: IpPacket) {
        val tcp = parseTcpPacket(packet, ip) ?: return
        val key = TcpKey(ip.source, tcp.sourcePort, ip.destination, tcp.destinationPort)
        if (tcp.rst) {
            tcpFlows.remove(key)?.close()
            return
        }
        if (tcp.syn) {
            val flow = tcpFlows.computeIfAbsent(key) {
                TcpFlow(key, sequence = 0x4d3c2b1aL)
            }
            synchronized(flow) {
                flow.clientNext = nextClientSequence(tcp)
                sendTcp(flow, TCP_SYN or TCP_ACK)
            }
            return
        }
        val flow = tcpFlows[key] ?: return
        if (tcp.fin) {
            synchronized(flow) {
                flow.clientNext = nextClientSequence(tcp)
                sendTcp(flow, TCP_ACK or TCP_FIN)
                flow.serverNext = (flow.serverNext + 1) and 0xffffffffL
            }
            tcpFlows.remove(key)?.close()
            return
        }
        if (tcp.payloadLength <= 0) return
        synchronized(flow) {
            try {
                val delta = sequenceDelta(tcp.sequence, flow.clientNext)
                if (delta > 0) {
                    sendTcp(flow, TCP_ACK)
                    return
                }
                var payloadOffset = tcp.payloadOffset
                var payloadLength = tcp.payloadLength
                if (delta < 0) {
                    val alreadySeen = (-delta).toInt()
                    if (alreadySeen >= payloadLength) {
                        sendTcp(flow, TCP_ACK)
                        return
                    }
                    payloadOffset += alreadySeen
                    payloadLength -= alreadySeen
                }
                val socket = ensureTcpConnected(flow)
                socket.getOutputStream().write(packet, payloadOffset, payloadLength)
                socket.getOutputStream().flush()
                flow.clientNext = (flow.clientNext + payloadLength) and 0xffffffffL
                sendTcp(flow, TCP_ACK)
            } catch (err: Exception) {
                log("tcp ${ipToString(key.destinationIp)}:${key.destinationPort} failed: ${err.message}")
                sendTcp(flow, TCP_RST or TCP_ACK)
                tcpFlows.remove(key)?.close()
            }
        }
    }

    private fun ensureTcpConnected(flow: TcpFlow): Socket {
        flow.socket?.let { return it }
        val socket = openSocksTcp(flow.key.destinationIp, flow.key.destinationPort)
        socket.tcpNoDelay = true
        flow.socket = socket
        Thread({ readTcpRemote(flow, socket) }, "tcptun-tcp-${flow.key.sourcePort}").start()
        return socket
    }

    private fun readTcpRemote(flow: TcpFlow, socket: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            val input = socket.getInputStream()
            while (running && !socket.isClosed) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                var offset = 0
                while (offset < n) {
                    val chunkLength = min(1400, n - offset)
                    val chunk = buffer.copyOfRange(offset, offset + chunkLength)
                    synchronized(flow) {
                        sendTcp(flow, TCP_ACK or TCP_PSH, chunk)
                        flow.serverNext = (flow.serverNext + chunk.size) and 0xffffffffL
                    }
                    offset += chunkLength
                }
            }
            synchronized(flow) {
                sendTcp(flow, TCP_ACK or TCP_FIN)
                flow.serverNext = (flow.serverNext + 1) and 0xffffffffL
            }
        } catch (_: Exception) {
            synchronized(flow) {
                sendTcp(flow, TCP_RST or TCP_ACK)
            }
        } finally {
            tcpFlows.remove(flow.key)?.close()
        }
    }

    private fun sendTcp(flow: TcpFlow, flags: Int, payload: ByteArray = ByteArray(0)) {
        val key = flow.key
        val packet = buildTcpPacket(
            sourceIp = key.destinationIp,
            destinationIp = key.sourceIp,
            sourcePort = key.destinationPort,
            destinationPort = key.sourcePort,
            sequence = flow.serverNext,
            acknowledgment = flow.clientNext,
            flags = flags,
            payload = payload,
        )
        writeTun(packet)
    }

    private fun handleUdp(packet: ByteArray, ip: IpPacket) {
        val udp = parseUdpPacket(packet, ip) ?: return
        if (!enableUdp) return
        val key = UdpKey(ip.source, udp.sourcePort, ip.destination, udp.destinationPort)
        val payload = packet.copyOfRange(udp.payloadOffset, udp.payloadOffset + udp.payloadLength)
        try {
            val flow = udpFlows.computeIfAbsent(key) { createUdpFlow(key) }
            flow.send(payload)
        } catch (err: Exception) {
            log("udp ${ipToString(key.destinationIp)}:${key.destinationPort} failed: ${err.message}")
            udpFlows.remove(key)?.close()
        }
    }

    private fun createUdpFlow(key: UdpKey): UdpFlow {
        val control = Socket()
        protectOrFail(control)
        control.connect(InetSocketAddress(socksHost, socksPort), 5000)
        control.soTimeout = 5000
        socksGreeting(control)
        val relay = socksUdpAssociate(control)
        val socket = DatagramSocket()
        protectOrFail(socket)
        val target = if (relay.address.isAnyLocalAddress) {
            InetSocketAddress(InetAddress.getByName(socksHost), relay.port)
        } else {
            relay
        }
        val flow = UdpFlow(key, control, socket, target)
        Thread({ readUdpRemote(flow) }, "tcptun-udp-${key.sourcePort}").start()
        return flow
    }

    private fun readUdpRemote(flow: UdpFlow) {
        val buffer = ByteArray(65535)
        while (running && !flow.socket.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                flow.socket.receive(packet)
                val decoded = parseSocksUdp(buffer, packet.length) ?: continue
                val out = buildUdpPacket(
                    sourceIp = decoded.hostIp,
                    destinationIp = flow.key.sourceIp,
                    sourcePort = decoded.port,
                    destinationPort = flow.key.sourcePort,
                    payload = decoded.payload,
                )
                writeTun(out)
            } catch (_: Exception) {
                break
            }
        }
        udpFlows.remove(flow.key)?.close()
    }

    private fun writeTun(packet: ByteArray) {
        synchronized(writeLock) {
            output.write(packet)
            output.flush()
        }
    }

    override fun close() {
        running = false
        tcpFlows.values.forEach { it.close() }
        udpFlows.values.forEach { it.close() }
        tcpFlows.clear()
        udpFlows.clear()
        workers.shutdownNow()
        runCatching { tun.close() }
        log("vpn tun forwarding stopped")
    }

    private fun nextClientSequence(tcp: TcpPacket): Long {
        var next = (tcp.sequence + tcp.payloadLength) and 0xffffffffL
        if (tcp.syn || tcp.fin) next = (next + 1) and 0xffffffffL
        return next
    }

    private fun sequenceDelta(left: Long, right: Long): Long {
        return ((left - right + 0x80000000L) and 0xffffffffL) - 0x80000000L
    }

    private fun openSocksTcp(destinationIp: IpAddress, destinationPort: Int): Socket {
        val socket = Socket()
        protectOrFail(socket)
        socket.connect(InetSocketAddress(socksHost, socksPort), 5000)
        socket.soTimeout = 0
        socksGreeting(socket)
        val out = socket.getOutputStream()
        val addressBytes = destinationIp.bytes
        val req = ByteArray(6 + addressBytes.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = socksAddressType(destinationIp).toByte()
        addressBytes.copyInto(req, 4)
        req[4 + addressBytes.size] = ((destinationPort ushr 8) and 0xff).toByte()
        req[5 + addressBytes.size] = (destinationPort and 0xff).toByte()
        out.write(req)
        out.flush()
        readSocksReply(socket)
        return socket
    }

    private fun socksGreeting(socket: Socket) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(0x05, 0x01, 0x00))
        out.flush()
        val reply = input.readNBytesCompat(2)
        if (reply[0].toInt() != 0x05 || reply[1].toInt() != 0x00) {
            throw IllegalStateException("SOCKS5 no-auth was rejected")
        }
    }

    private fun socksUdpAssociate(socket: Socket): InetSocketAddress {
        val out = socket.getOutputStream()
        out.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
        return readSocksReply(socket)
    }

    private fun readSocksReply(socket: Socket): InetSocketAddress {
        val input = socket.getInputStream()
        val header = input.readNBytesCompat(4)
        if (header[0].toInt() != 0x05 || header[1].toInt() != 0x00) {
            throw IllegalStateException("SOCKS5 reply code ${header[1].toInt() and 0xff}")
        }
        val host = when (header[3].toInt() and 0xff) {
            0x01 -> InetAddress.getByAddress(input.readNBytesCompat(4))
            0x03 -> {
                val len = input.read()
                if (len < 0) throw EOFException("SOCKS5 domain length missing")
                InetAddress.getByName(String(input.readNBytesCompat(len)))
            }
            0x04 -> InetAddress.getByAddress(input.readNBytesCompat(16))
            else -> throw IllegalStateException("SOCKS5 unsupported address type")
        }
        val portBytes = input.readNBytesCompat(2)
        val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
        return InetSocketAddress(host, port)
    }

    private fun UdpFlow.send(payload: ByteArray) {
        val dgram = buildSocksUdp(key.destinationIp, key.destinationPort, payload)
        socket.send(DatagramPacket(dgram, dgram.size, relay))
    }

    private fun buildSocksUdp(destinationIp: IpAddress, destinationPort: Int, payload: ByteArray): ByteArray {
        val addressBytes = destinationIp.bytes
        val headerLength = 4 + addressBytes.size + 2
        val packet = ByteArray(headerLength + payload.size)
        packet[3] = socksAddressType(destinationIp).toByte()
        addressBytes.copyInto(packet, 4)
        packet[4 + addressBytes.size] = ((destinationPort ushr 8) and 0xff).toByte()
        packet[5 + addressBytes.size] = (destinationPort and 0xff).toByte()
        payload.copyInto(packet, headerLength)
        return packet
    }

    private fun parseSocksUdp(buffer: ByteArray, length: Int): SocksUdp? {
        if (length < 10 || buffer[2].toInt() != 0) return null
        var offset = 3
        val hostIp = when (buffer[offset++].toInt() and 0xff) {
            0x01 -> {
                if (length < offset + 4 + 2) return null
                val ip = IpAddress(buffer.copyOfRange(offset, offset + 4))
                offset += 4
                ip
            }
            0x04 -> {
                if (length < offset + 16 + 2) return null
                val ip = IpAddress(buffer.copyOfRange(offset, offset + 16))
                offset += 16
                ip
            }
            else -> return null
        }
        val port = readU16(buffer, offset)
        offset += 2
        return SocksUdp(hostIp, port, buffer.copyOfRange(offset, length))
    }

    private fun socksAddressType(ip: IpAddress): Int {
        return when (ip.version) {
            4 -> 0x01
            6 -> 0x04
            else -> error("unsupported IP version")
        }
    }

    private fun protectOrFail(socket: Socket) {
        if (!protectSocket(socket)) {
            throw IllegalStateException("protect TCP socket failed")
        }
    }

    private fun protectOrFail(socket: DatagramSocket) {
        if (!protectDatagramSocket(socket)) {
            throw IllegalStateException("protect UDP socket failed")
        }
    }

    private fun java.io.InputStream.readNBytesCompat(length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = read(out, offset, length - offset)
            if (n < 0) throw EOFException("unexpected EOF")
            offset += n
        }
        return out
    }
}

data class TcpKey(
    val sourceIp: IpAddress,
    val sourcePort: Int,
    val destinationIp: IpAddress,
    val destinationPort: Int,
)

private data class UdpKey(
    val sourceIp: IpAddress,
    val sourcePort: Int,
    val destinationIp: IpAddress,
    val destinationPort: Int,
)

private class TcpFlow(
    val key: TcpKey,
    sequence: Long,
) : Closeable {
    var clientNext: Long = 0
    var serverNext: Long = sequence
    var socket: Socket? = null

    override fun close() {
        runCatching { socket?.close() }
    }
}

private class UdpFlow(
    val key: UdpKey,
    val control: Socket,
    val socket: DatagramSocket,
    val relay: InetSocketAddress,
) : Closeable {
    override fun close() {
        runCatching { socket.close() }
        runCatching { control.close() }
    }
}

private data class SocksUdp(
    val hostIp: IpAddress,
    val port: Int,
    val payload: ByteArray,
)
